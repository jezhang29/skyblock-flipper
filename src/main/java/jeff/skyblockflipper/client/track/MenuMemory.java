package jeff.skyblockflipper.client.track;

import jeff.skyblockflipper.core.track.CapturedMenu;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

import java.util.Optional;

/**
 * The last menu that was open, kept so a command can describe it after it has closed.
 *
 * <p>Chat cannot be typed into while a menu is open, so there is no way to ask about the screen in
 * front of you: by the time {@code /flip menu} is typed, the menu is gone. This snapshots it on the
 * way out, which turns "what is this button called?" into one command rather than a capture session,
 * a trimmed fixture and a rebuild.
 *
 * <p>Held in memory only and overwritten by the next menu. It is a debugging aid for wording that
 * has changed under the slot detector, not a record of anything.
 */
public final class MenuMemory {
	private static CapturedMenu last;

	private MenuMemory() {
	}

	public static void register() {
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (!(screen instanceof AbstractContainerScreen<?> container)) {
				return;
			}

			// On the way out rather than on the way in: Hypixel sends a menu as a frame of filler
			// and fills the real items in over the following ticks, so the contents are only
			// complete some time after it opened.
			ScreenEvents.remove(screen).register(
					removed -> last = MenuReader.describe(container, System.currentTimeMillis()));
		});
	}

	public static Optional<CapturedMenu> last() {
		return Optional.ofNullable(last);
	}
}
