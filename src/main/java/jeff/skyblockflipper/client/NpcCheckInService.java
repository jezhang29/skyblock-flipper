package jeff.skyblockflipper.client;

import jeff.skyblockflipper.client.track.TrackerService;
import jeff.skyblockflipper.core.api.MarketData;
import jeff.skyblockflipper.core.config.FlipperConfig;
import jeff.skyblockflipper.core.strategy.NpcCheckIn;
import jeff.skyblockflipper.core.strategy.NpcReprice;
import jeff.skyblockflipper.core.text.Coins;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.sounds.SoundEvents;

import java.util.ArrayList;
import java.util.List;

/**
 * Says when the resting NPC basket wants a click.
 *
 * <p>The one place the mod speaks without being asked, and it exists because the measurement says
 * the difference between a worked basket and a forgotten one is the whole trade: 59.7M per
 * eight-hour cycle returning every 30 minutes against 11.5M posting once and walking away. A player
 * who has to remember on their own eventually does not.
 *
 * <p>{@link NpcCheckIn} decides whether there is anything worth saying; this owns the clock and the
 * chat line. Two rate limits, for two different failure modes:
 *
 * <ul>
 *   <li>The review runs at most once per bazaar revision, so a tick handler never re-ranks a book
 *       that has not changed. That is the same rule {@link CandidateFeed} caches on.
 *   <li>It speaks at most once per {@code npcCheckInMinutes}, so a book that keeps moving past your
 *       orders produces one line per check-in interval rather than one line per poll.
 * </ul>
 *
 * <p>Client thread only: {@link ClientTickEvents} runs there, which is what makes it safe to build
 * a chat component and hand it straight to the player.
 */
public final class NpcCheckInService {
	/** High enough to carry over Skyblock's own noise without being an alarm. */
	private static final float CHIME_PITCH = 1.5f;

	/**
	 * The toast's identity, so a second reminder replaces the first rather than stacking under it.
	 *
	 * <p>{@code SystemToastId} has a public constructor precisely so a mod can have one of its own;
	 * reusing a vanilla id would let an unrelated notification cancel this one and the other way
	 * round.
	 */
	private static final SystemToast.SystemToastId TOAST_ID = new SystemToast.SystemToastId();

	/** The book this last looked at, so an unchanged snapshot costs nothing. */
	private static long reviewedRevision = -1L;

	/**
	 * When the player was last told, or last asked. Zero means never, which lets the first eligible
	 * check speak: logging back in to a basket that was outbid overnight is exactly the case the
	 * reminder is for.
	 */
	private static long lastNoticeAt;

	private NpcCheckInService() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
	}

	/**
	 * Restarts the interval, because the player has just seen the answer.
	 *
	 * <p>Called by {@code /flip npc reprice} and {@code /flip npc plan}. Somebody who is working the
	 * basket right now does not need to be told to work the basket, and a reminder arriving on top
	 * of the list it is pointing at reads as a bug.
	 */
	public static void acknowledge() {
		lastNoticeAt = System.currentTimeMillis();
	}

	private static void tick() {
		FlipperConfig config = SkyblockFlipperClient.config();

		// Read through config() at use time, so /flip reload turns this on and off without a restart.
		if (!config.npcRepriceReminder || !TrackerService.enabled()) {
			return;
		}

		MarketData data = MarketDataService.data();
		long revision = data.bazaarRevision();

		if (!data.hasBazaar() || revision == reviewedRevision) {
			return;
		}

		long now = System.currentTimeMillis();

		// Checked before the review rather than after, so a basket inside its interval costs one
		// comparison per tick instead of a pass over every resting order.
		if (now - lastNoticeAt < config.npcCheckInMinutes * 60_000L) {
			return;
		}

		reviewedRevision = revision;
		// The review directly rather than through CandidateFeed.worklist(), which would allocate a
		// whole basket - a pass over every product on the book - on every poll for a player who has
		// not asked for one. It is the same NpcReprice.review the worklist runs on the same orders,
		// so the two cannot disagree about what needs a click.
		List<NpcReprice.Advice> advice = NpcReprice.review(TrackerService.restingBuyOrders(),
				CandidateFeed.context(), now);

		if (advice.isEmpty()) {
			if (TrackerService.hasUnpricedBuyOrders()) {
				lastNoticeAt = now;
				announceUnwatched();
			}

			return;
		}

		NpcCheckIn.due(advice, config.minProfitPerFlip).ifPresent(due -> {
			lastNoticeAt = now;
			announce(due);
		});
	}

	/**
	 * The reminder cannot do its job, and says so rather than staying quiet.
	 *
	 * <p>An order announced in chat carries its size and its total but never its price per unit, and
	 * the price is the whole input to a reprice - so an order the mod has only heard about is one it
	 * cannot advise on. The orders menu is where the price is, and until it has been drawn once this
	 * service has nothing to say about a book full of orders. Silence there is indistinguishable
	 * from a reminder that does not work, which is what it was mistaken for.
	 */
	private static void announceUnwatched() {
		MutableComponent message = Component.literal("[Flipper] ").withStyle(ChatFormatting.GOLD)
				.append(Component.literal("Open Bazaar -> Manage Orders once so I can watch your "
						+ "orders. Chat never says what price an order rests at, so until that menu "
						+ "has been on screen there is nothing to reprice against.")
						.withStyle(ChatFormatting.YELLOW));

		send(message);
		toast("Flipper cannot see your orders",
				"Open Bazaar -> Manage Orders once");
	}

	/**
	 * One line, clickable, with no detail in it.
	 *
	 * <p>The detail is {@code /flip npc reprice}, which the click runs. Printing the orders here
	 * would put a list in the chat log at a moment the player did not choose, and the list is only
	 * usable standing at the bazaar anyway.
	 */
	private static void announce(NpcCheckIn.Due due) {
		MutableComponent message = Component.literal("[Flipper] ").withStyle(ChatFormatting.GOLD)
				.append(Component.literal(summary(due)).withStyle(ChatFormatting.YELLOW))
				.append(Component.literal(" [reprice]")
						.withStyle(style -> style
								.withColor(ChatFormatting.AQUA)
								.withUnderlined(true)
								.withClickEvent(new ClickEvent.RunCommand("/flip npc reprice"))));

		send(message);
		toast(due.orders() + (due.orders() == 1 ? " NPC order needs a click"
				: " NPC orders need a click"), toastDetail(due));
		chime();
	}

	/**
	 * The same notice as a toast, because a chat line is not a notification.
	 *
	 * <p>Skyblock's chat runs at a speed that loses a line inside a few seconds, and the one message
	 * this mod sends unprompted was going out into that. A toast sits in the corner for its own
	 * duration whatever chat is doing, and {@code addOrUpdate} means a second reminder replaces the
	 * first rather than building a column of them.
	 *
	 * <p>Two lines and no numbers past the counts: a toast is what makes you look, and
	 * {@code /flip npc reprice} is what you look at.
	 */
	private static void toast(String title, String detail) {
		Minecraft client = Minecraft.getInstance();

		SystemToast.addOrUpdate(client.gui.toastManager(), TOAST_ID,
				Component.literal(title).withStyle(ChatFormatting.GOLD),
				Component.literal(detail));
	}

	private static String toastDetail(NpcCheckIn.Due due) {
		if (due.claimCount() > 0) {
			return Coins.format(due.claimable()) + " filled and uncollected";
		}

		return due.cancelCount() > 0
				? Coins.format(due.capitalToFree()) + " parked, /flip npc reprice"
				: Coins.format(due.profitAtStake()) + " at stake, /flip npc reprice";
	}

	/**
	 * A single note, because a line in chat is easy to miss.
	 *
	 * <p>Skyblock scrolls chat fast enough that the one message the mod sends unprompted can be gone
	 * before it is read, which defeats the point of sending it at all. Rate-limited by the same
	 * check-in interval as the line it accompanies, so there is exactly one per reminder.
	 *
	 * <p>Played as a UI sound, which puts it on the master volume: a player who has muted the game
	 * has said what they want and should not be an exception.
	 */
	private static void chime() {
		if (!SkyblockFlipperClient.config().npcRepriceSound) {
			return;
		}

		Minecraft.getInstance().getSoundManager()
				.play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING, CHIME_PITCH));
	}

	/**
	 * What is waiting, claims first.
	 *
	 * <p>A claim is coins already earned, so it leads whatever else is in the same trip: a reminder
	 * that opens with an outbid order buries the one line that is not a forecast.
	 */
	private static String summary(NpcCheckIn.Due due) {
		List<String> parts = new ArrayList<>();

		if (due.claimCount() > 0) {
			parts.add(due.claimCount() + " filled, " + Coins.format(due.claimable())
					+ " to collect");
		}

		if (due.cancelCount() > 0) {
			parts.add(due.cancelCount() + " to cancel, freeing "
					+ Coins.format(due.capitalToFree()));
		}

		if (due.repriceCount() > 0) {
			parts.add(due.repriceCount() + " outbid, worth "
					+ Coins.format(due.profitAtStake()));
		}

		return due.orders() + (due.orders() == 1 ? " NPC order needs" : " NPC orders need")
				+ " a click: " + String.join("; ", parts);
	}

	private static void send(Component message) {
		Minecraft client = Minecraft.getInstance();

		if (client.player != null) {
			client.player.sendSystemMessage(message);
		}
	}
}
