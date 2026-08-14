package jeff.skyblockflipper.client.track;

import jeff.skyblockflipper.SkyblockFlipper;
import jeff.skyblockflipper.client.CandidateFeed;
import jeff.skyblockflipper.client.LedgerService;
import jeff.skyblockflipper.client.MarketDataService;
import jeff.skyblockflipper.client.SkyblockFlipperClient;
import jeff.skyblockflipper.core.ledger.LedgerEntry;
import jeff.skyblockflipper.core.pricing.Fees;
import jeff.skyblockflipper.core.strategy.NpcReprice;
import jeff.skyblockflipper.core.text.Coins;
import jeff.skyblockflipper.core.track.CapturedChat;
import jeff.skyblockflipper.core.track.CapturedMenu;
import jeff.skyblockflipper.core.track.Settlement;
import jeff.skyblockflipper.core.track.TradeEvent;
import jeff.skyblockflipper.core.track.TrackedOrder;
import jeff.skyblockflipper.core.track.TradeTracker;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Turns the trades Hypixel reports into ledger entries while you play.
 *
 * <p>The same chat lines and menus {@link CaptureService} records, read live instead of written to
 * a file. {@link TradeTracker} does the understanding; this owns the instance, decides when a
 * settlement is new, and hands it to the ledger.
 *
 * <p>Off unless {@code autoTrackEnabled}, and independent of trade capture: capture writes a file
 * nothing reads, this writes the ledger, and either can run without the other.
 *
 * <p>Client thread only, like the ledger it writes to.
 */
public final class TrackerService {
	private static TradeTracker tracker = new TradeTracker("");

	/** Whose orders the tracker is reading, so an account switch does not inherit the last one's. */
	private static String player = "";

	/** How many of the tracker's settlements have reached the ledger. It only ever grows. */
	private static int booked;

	/** Set after the first ledger write failure, so a broken disk costs one log line, not one a trade. */
	private static boolean broken;

	/**
	 * Bumped whenever anything the tracker knows might have changed.
	 *
	 * <p>Exists so a cache keyed on the market can also notice the account. Placing an order changes
	 * what there is to do without changing the book, and a panel keyed only on the bazaar revision
	 * spends the next poll telling the player to place the orders they have just placed. Deliberately
	 * coarse - one bump per event accepted, not one per real change - because the cost of an
	 * unnecessary rebuild is one allocation and the cost of a missed one is a wrong instruction.
	 */
	private static long orderRevision;

	private TrackerService() {
	}

	public static boolean enabled() {
		return SkyblockFlipperClient.config().autoTrackEnabled && !broken;
	}

	/** A counter that moves whenever the tracker has been fed. See {@link #orderRevision}. */
	public static long orderRevision() {
		return orderRevision;
	}

	public static void accept(CapturedChat chat) {
		tracker().accept(chat);
		orderRevision++;
		drain();
	}

	public static void accept(CapturedMenu menu) {
		tracker().accept(menu);
		orderRevision++;
		drain();
	}

	/** Resting orders the tracker believes you have, for {@code /flip status} to report. */
	public static TradeTracker tracker() {
		String name = playerName();

		// A different account has different orders, and the co-op orders menu is filtered by name,
		// so carrying the old tracker over would either lose every order or adopt someone else's.
		if (!name.equals(player)) {
			player = name;
			tracker = new TradeTracker(name);
			booked = 0;
		}

		return tracker;
	}

	/**
	 * The resting buy orders as {@link NpcReprice} wants them, which is every one it could identify.
	 *
	 * <p>Lives here rather than at either caller because {@code /flip npc reprice} and the check-in
	 * reminder have to be looking at the same orders - a reminder about an order the command then
	 * says nothing about is worse than no reminder.
	 *
	 * <p>Three kinds of tracked order are dropped. Sell offers are the other leg of a spread flip.
	 * A price of zero means the order was seen announced in chat but never in a menu, and chat never
	 * names the price. An order whose name matches nothing in the item catalog cannot be looked up
	 * on the book at all.
	 *
	 * <p><b>An order with nothing left on the book is kept when it still holds uncollected units.</b>
	 * That is a completely filled order waiting to be claimed, which is the one state where there is
	 * money to collect and no order to reprice, and dropping it was hiding exactly the trades that
	 * had worked.
	 */
	public static List<NpcReprice.Order> restingBuyOrders() {
		TradeTracker tracker = tracker();
		List<NpcReprice.Order> orders = new ArrayList<>();

		for (TrackedOrder order : tracker.resting()) {
			if (order.side() != TradeEvent.Side.BUY || order.unitPrice() <= 0.0d
					|| (order.remaining() <= 0L && order.unclaimed() <= 0L)) {
				continue;
			}

			// Enchantment-book orders carry no item data at all, so the name index is the only route
			// from what the menu said to an id the book can be looked up by.
			String itemId = order.itemId().isEmpty()
					? tracker.names().idFor(order.displayName())
					: order.itemId();

			if (!itemId.isEmpty()) {
				// placedAt is when this session first saw the order, not when Hypixel accepted it.
				// The tracker starts empty every launch, so it is a lower bound on the real age and
				// the resting-window rule built on it fires late rather than wrongly. Which of the
				// two it is travels with it: a round's dwell rule waives it on an adopted order,
				// where the resting-window rule can only ever under-report.
				orders.add(new NpcReprice.Order(itemId, order.displayName(), order.unitPrice(),
						order.total(), order.remaining(), order.unclaimed(), order.placedAt(),
						order.adopted()));
			}
		}

		return orders;
	}

	/**
	 * Items whose buy orders were bought out since {@code at}, for the round to retire its rows with.
	 *
	 * <p>Beside {@link #restingBuyOrders()} because the two are read together and describe the same
	 * book from opposite ends: what is still on it, and what left it by filling.
	 */
	public static Set<String> filledSince(long at) {
		return tracker().filledSince(at);
	}

	/**
	 * When each item's buy orders were last cancelled since {@code at}, for the same rows.
	 *
	 * <p>The third reading of an empty book position, beside {@link #restingBuyOrders()} and
	 * {@link #filledSince}: pulled off by you rather than bought out. It cannot retire a row on its
	 * own - a reprice is a cancel too - so what the round does with it is start a clock.
	 */
	public static Map<String, Long> cancelledSince(long at) {
		return tracker().cancelledSince(at);
	}

	/**
	 * Whether there are buy orders the tracker has heard about but cannot price.
	 *
	 * <p>The state that looks exactly like a broken reminder. Chat announces a placement with its
	 * size and its total escrow and never with a price per unit, so an order that has only ever been
	 * announced cannot be compared to the book at all - {@link #restingBuyOrders()} drops it, every
	 * consumer sees an empty list, and the mod says nothing about a bazaar full of orders. Opening
	 * the orders menu once fixes it, and this exists so something can say so.
	 */
	public static boolean hasUnpricedBuyOrders() {
		return tracker().resting().stream()
				.anyMatch(order -> order.side() == TradeEvent.Side.BUY && order.unitPrice() <= 0.0d
						&& order.remaining() > 0L);
	}

	private static void drain() {
		List<Settlement> settlements = tracker.settlements();

		for (int i = booked; i < settlements.size(); i++) {
			book(settlements.get(i));
		}

		booked = settlements.size();
	}

	private static void book(Settlement settlement) {
		try {
			// Read through config() at use time, so /flip reload changes this without a restart.
			// The quotes are what let a basket line be recognised: nothing opens a position for one
			// by hand, so without them every NPC buy arrives unquoted and trackUnquotedTrades - off
			// by default, and rightly - drops it.
			LedgerService.ledger()
					.record(settlement, fees(), SkyblockFlipperClient.config().trackUnquotedTrades,
							CandidateFeed.quotes())
					.ifPresent(entry -> report(settlement, entry));
		} catch (IOException e) {
			broken = true;
			SkyblockFlipper.LOGGER.error("Auto-tracking stopped: could not write {}",
					LedgerService.file(), e);
		}
	}

	/**
	 * Says something only when a position finishes.
	 *
	 * <p>A line per settlement would be a second copy of Hypixel's own chat. A line per close is
	 * the one moment the mod knows something the player does not: what the trade actually made, and
	 * for a quoted position, how that compares with what was promised.
	 */
	private static void report(Settlement settlement, LedgerEntry entry) {
		if (entry.status() != LedgerEntry.Status.CLOSED) {
			return;
		}

		long realized = Math.round(entry.realizedTotal());
		Component message = Component.literal("[Flipper] ").withStyle(ChatFormatting.GOLD)
				.append(Component.literal(entry.displayName() + " closed: "
						+ Coins.format(realized) + " net on " + entry.unitsSold() + " units")
						.withStyle(realized < 0L ? ChatFormatting.RED : ChatFormatting.GREEN));

		if (entry.isQuoted() && entry.quotedOnFilled() != 0.0d) {
			message = message.copy().append(Component.literal(String.format(" (%.0f%% of quoted)",
					entry.realizedTotal() / entry.quotedOnFilled() * 100.0d))
					.withStyle(ChatFormatting.GRAY));
		}

		send(message);
	}

	/**
	 * Always deferred to the render thread, because the caller is not reliably on it.
	 *
	 * <p>Measured on 2026-08-04: a modpack alongside this one delivered four chat messages on the
	 * netty IO thread rather than the client thread, and two of them killed the connection with
	 * {@code Internal Exception: IllegalStateException: Rendersystem called from wrong thread}.
	 * Adding a message to the chat GUI splits it with the font, and an uncached glyph asserts it is
	 * on the render thread. This service is driven from a chat callback, so it inherits whatever
	 * thread the message arrived on and would eventually do the same thing to a fill notification.
	 */
	private static void send(Component message) {
		Minecraft client = Minecraft.getInstance();

		client.execute(() -> {
			if (client.player != null) {
				client.player.sendSystemMessage(message);
			}
		});
	}

	/**
	 * The fee model the quotes were computed with, so realized and quoted stay comparable. Derpy
	 * quadruples auction fees, so the mayor has to be read at close time rather than assumed.
	 */
	private static Fees fees() {
		return new Fees(SkyblockFlipperClient.config().bazaarFlipperLevel,
				MarketDataService.data().mayor().isDerpy());
	}

	private static String playerName() {
		return Optional.ofNullable(Minecraft.getInstance().getUser())
				.map(user -> user.getName())
				.orElse("");
	}
}
