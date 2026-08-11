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
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

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
		List<NpcReprice.Order> orders = TrackerService.restingBuyOrders();

		if (orders.isEmpty()) {
			return;
		}

		NpcCheckIn.due(NpcReprice.review(orders, CandidateFeed.context()), config.minProfitPerFlip)
				.ifPresent(due -> {
					lastNoticeAt = now;
					announce(due);
				});
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
	}

	private static String summary(NpcCheckIn.Due due) {
		StringBuilder text = new StringBuilder()
				.append(due.orders())
				.append(due.orders() == 1 ? " NPC order needs a click: " : " NPC orders need a click: ");

		if (due.repriceCount() > 0) {
			text.append(due.repriceCount()).append(" outbid, worth ")
					.append(Coins.format(due.profitAtStake()));
		}

		if (due.repriceCount() > 0 && due.cancelCount() > 0) {
			text.append("; ");
		}

		if (due.cancelCount() > 0) {
			text.append(due.cancelCount()).append(" past the stop, holding ")
					.append(Coins.format(due.capitalToFree()));
		}

		return text.toString();
	}

	private static void send(Component message) {
		Minecraft client = Minecraft.getInstance();

		if (client.player != null) {
			client.player.sendSystemMessage(message);
		}
	}
}
