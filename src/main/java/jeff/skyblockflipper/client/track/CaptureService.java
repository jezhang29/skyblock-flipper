package jeff.skyblockflipper.client.track;

import jeff.skyblockflipper.SkyblockFlipper;
import jeff.skyblockflipper.client.SkyblockFlipperClient;
import jeff.skyblockflipper.core.track.CaptureFilter;
import jeff.skyblockflipper.core.track.CaptureLog;
import jeff.skyblockflipper.core.track.CapturedChat;
import jeff.skyblockflipper.core.track.CapturedMenu;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.file.Path;

/**
 * Records what Hypixel says while trading, so the tracker that will read it can be written against
 * real text.
 *
 * <p>Read-only in every direction: it listens to chat the client already received and to menus the
 * player already opened, and it never sends, clicks or types anything. Off unless
 * {@code tradeCaptureEnabled} is set, and the flag is read at use time rather than at registration,
 * so {@code /flip capture} takes effect on the next line without a restart.
 */
public final class CaptureService {
	/**
	 * Ticks a menu's contents must sit unchanged before it is worth recording.
	 *
	 * <p>Hypixel sends a menu as a frame of filler glass and then fills the real items in over the
	 * following ticks, so snapshotting on open would capture an empty menu, and snapshotting every
	 * tick would capture the same settled menu two hundred times a minute.
	 */
	private static final int SETTLE_TICKS = 5;

	private static final CaptureLog LOG = new CaptureLog(file());

	/** Set after the first write failure, so a full disk costs one log line rather than one a tick. */
	private static boolean broken;

	private CaptureService() {
	}

	public static Path file() {
		return FabricLoader.getInstance().getConfigDir()
				.resolve(SkyblockFlipper.MOD_ID)
				.resolve("chat-capture.jsonl");
	}

	public static CaptureLog log() {
		return LOG;
	}

	public static void register() {
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			// Action bar text repaints every tick and carries no trade wording worth having.
			if (overlay || !enabled()) {
				return;
			}

			String line = message.getString();

			if (CaptureFilter.keepChat(line)) {
				write(() -> LOG.append(new CapturedChat(System.currentTimeMillis(), line)));
			}
		});

		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (screen instanceof AbstractContainerScreen<?> container
					&& CaptureFilter.keepMenu(screen.getTitle().getString())) {
				MenuWatcher.attach(container);
			}
		});
	}

	private static boolean enabled() {
		return SkyblockFlipperClient.config().tradeCaptureEnabled && !LOG.isFull() && !broken;
	}

	private static void write(Write action) {
		try {
			action.run();

			if (LOG.isFull()) {
				SkyblockFlipper.LOGGER.warn("Trade capture stopped: {} reached its size cap", file());
			}
		} catch (IOException e) {
			broken = true;
			SkyblockFlipper.LOGGER.error("Trade capture failed writing {}", file(), e);
		}
	}

	@FunctionalInterface
	private interface Write {
		void run() throws IOException;
	}

	/**
	 * Watches one open menu and records it once its contents stop changing.
	 *
	 * <p>One instance per opened screen, holding the settle state. The per-tick check hashes only the
	 * cheap parts of each slot; the full snapshot, which stringifies every slot's NBT, is built once
	 * the contents have settled.
	 */
	private static final class MenuWatcher {
		private final AbstractContainerScreen<?> screen;

		private int lastSignature;
		private int stableTicks;
		private int writtenHash;
		private boolean everWritten;

		private MenuWatcher(AbstractContainerScreen<?> screen) {
			this.screen = screen;
			this.lastSignature = signature(screen);
		}

		/**
		 * AFTER_INIT fires again on every window resize, and a screen's tick event keeps whatever was
		 * registered on it, so attaching twice to one screen would double every record it writes.
		 * Held weakly, because a strong reference would keep a closed screen alive.
		 */
		private static WeakReference<Screen> attached = new WeakReference<>(null);

		static void attach(AbstractContainerScreen<?> screen) {
			if (attached.get() == screen) {
				return;
			}

			attached = new WeakReference<>(screen);
			MenuWatcher watcher = new MenuWatcher(screen);
			ScreenEvents.afterTick(screen).register(ignored -> watcher.tick());
		}

		private void tick() {
			if (!enabled()) {
				return;
			}

			int signature = signature(screen);

			if (signature != lastSignature) {
				lastSignature = signature;
				stableTicks = 0;
				return;
			}

			// Recorded exactly on the tick it settles: counting past the threshold would re-record
			// the same contents for as long as the menu stays open.
			if (++stableTicks != SETTLE_TICKS) {
				return;
			}

			CapturedMenu menu = MenuReader.read(screen, System.currentTimeMillis());

			if (menu.slots().isEmpty() || (everWritten && menu.contentsHash() == writtenHash)) {
				return;
			}

			writtenHash = menu.contentsHash();
			everWritten = true;
			write(() -> LOG.append(menu));
		}

		/**
		 * A cheap stand-in for "the contents changed", computed every tick.
		 *
		 * <p>Item count and name catch an order filling, a page turning and a menu populating, which
		 * is everything the settle check needs to notice. It does not need to be exact: a change it
		 * misses only means the snapshot is taken a few ticks later, and
		 * {@link CapturedMenu#contentsHash()} still decides whether the settled contents are new.
		 */
		private static int signature(AbstractContainerScreen<?> screen) {
			int hash = 1;

			for (Slot slot : screen.getMenu().slots) {
				if (slot.container instanceof Inventory) {
					continue;
				}

				ItemStack stack = slot.getItem();
				hash = hash * 31 + (stack.isEmpty()
						? 0
						: stack.getCount() * 31 + stack.getHoverName().getString().hashCode());
			}

			return hash;
		}
	}
}
