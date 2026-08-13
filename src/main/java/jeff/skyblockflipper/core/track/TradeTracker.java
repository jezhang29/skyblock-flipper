package jeff.skyblockflipper.core.track;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Folds a trading session into what was bought and sold and what is still on the book.
 *
 * <p>Two streams arrive and neither is sufficient. Chat is an event stream: it says the instant
 * something happens, it is the only place a price per unit is ever printed, and it is blind to
 * everything that happens with the client closed and to every fill that stopped short - a partial
 * fill sends no notification at all. The orders menu is a snapshot: it is right about how much of
 * an order filled no matter who was watching, and it never says what anything sold for or when.
 *
 * <p>So chat drives the state machine and the menu overrules it. Concretely, in the session this
 * was written from: a 1,344 unit Slimeball offer filled 903 and announced nothing, the menu showed
 * {@code Filled: 903/1.3k}, a claim line then paid out 34,107 coins for 903 units at 38.2, and a
 * cancel refunded the other 441. Three of those four facts exist in exactly one of the two streams.
 *
 * <p>What comes out is {@link #settlements()} - coins that actually moved, which is what the ledger
 * can be filled from - and {@link #orders()}, the positions those settlements belong to. Nothing
 * here writes to the ledger yet.
 *
 * <p>Item ids are resolved through {@link ItemNameIndex} at read time rather than when an event
 * arrives, because the menu that names an item usually opens after the chat line that traded it.
 *
 * <p>Not thread-safe. Feed it from one thread, in the order the client saw things.
 */
public final class TradeTracker {
	/**
	 * How far a coin refund may sit from an order's escrow and still be taken as that order's.
	 *
	 * <p>Wide enough for the rounding between a fractional unit price and a whole-coin total, and
	 * narrow enough that two buy orders would have to rest within a tenth of a percent of the same
	 * coins to be confused.
	 */
	private static final double REFUND_TOLERANCE = 0.001d;

	private final String player;
	private final ItemNameIndex names = new ItemNameIndex();
	private final List<TrackedOrder> orders = new ArrayList<>();
	private final List<Settlement> settlements = new ArrayList<>();

	/**
	 * @param player the in-game name, needed because the co-op orders menu lists every member's
	 *               orders in the same rows. Blank means no menu order is ever taken as yours,
	 *               which loses the menu half rather than booking a co-op mate's position as your
	 *               own
	 */
	public TradeTracker(String player) {
		this.player = player == null ? "" : player;
	}

	/** Replays a whole recorded session. Equivalent to feeding {@link #accept} in file order. */
	public static TradeTracker replay(CaptureSession session, String player) {
		TradeTracker tracker = new TradeTracker(player);
		session.records().forEach(tracker::accept);
		return tracker;
	}

	public void accept(CaptureRecord record) {
		switch (record) {
			case CapturedChat chat -> accept(chat);
			case CapturedMenu menu -> accept(menu);
		}
	}

	public void accept(CapturedChat chat) {
		ChatParser.parse(chat.at(), chat.text()).ifPresent(this::apply);
	}

	public void accept(CapturedMenu menu) {
		// Every menu teaches names, not just the orders one: the auction view is where a BIN
		// purchase's id comes from, and nothing else in the session carries it.
		names.learn(menu);

		if (OrderMenuParser.isOrdersMenu(menu)) {
			reconcile(menu.at(), OrderMenuParser.ownedBy(OrderMenuParser.parse(menu), player));
		}
	}

	/** Coins that moved, oldest first, with ids resolved from every menu seen so far. */
	public List<Settlement> settlements() {
		return settlements.stream().map(s -> s.withItemId(names.idFor(s.displayName()))).toList();
	}

	/** Every order this saw, resting or finished, oldest first. */
	public List<TrackedOrder> orders() {
		return List.copyOf(orders);
	}

	/** Orders still believed to be on the book. */
	public List<TrackedOrder> resting() {
		return orders.stream().filter(TrackedOrder::isResting).toList();
	}

	/** Filled units nobody has collected yet, which is the state worth telling a player about. */
	public List<TrackedOrder> awaitingClaim() {
		return orders.stream().filter(o -> o.isResting() && o.unclaimed() > 0L).toList();
	}

	/**
	 * Items whose buy orders left the book by being bought out, since {@code at}.
	 *
	 * <p>The evidence a frozen {@code NpcRound} row needs to know it is finished rather than
	 * half-worked. Both states show the same thing in {@link #resting()} - nothing - and the round
	 * reads the absence as a reprice in progress, so without this a flip that filled and was claimed
	 * kept asking to be repriced until the interval ran out.
	 *
	 * <p>Item ids rather than orders, because a round row is per item and an item's units may have
	 * been spread over several orders that finished at different moments.
	 *
	 * @param at epoch millis to count from, which is the round's own open time. Orders that finished
	 *           before the round opened are not evidence about it: the round was frozen after them
	 */
	public Set<String> filledSince(long at) {
		Set<String> items = new LinkedHashSet<>();

		for (TrackedOrder order : orders) {
			if (order.side() == TradeEvent.Side.BUY && order.finishedByFilling()
					&& order.finishedAt() >= at) {
				String id = order.itemId().isEmpty() ? names.idFor(order.displayName()) : order.itemId();

				if (!id.isEmpty()) {
					items.add(id);
				}
			}
		}

		return items;
	}

	public ItemNameIndex names() {
		return names;
	}

	private void apply(TradeEvent event) {
		switch (event.kind()) {
			// The one order whose age is a fact: the placement itself was announced, so what is
			// recorded is when Hypixel accepted it rather than when this first heard of it.
			case ORDER_PLACED -> orders.add(new TrackedOrder(event.at(), false, event.side(),
					event.displayName(), names.idFor(event.displayName()), event.units(),
					event.coins(), placedUnitPrice(event)));

			// Announces a complete fill and nothing else; the money arrives at the claim.
			case ORDER_FILLED -> matchOnTotal(event).orElseGet(() -> adopt(event)).fill(event.units());

			case ORDER_CLAIMED -> {
				matchForClaim(event)
						.ifPresent(order -> order.claim(event.at(), event.units(), event.unitPrice()));
				settle(event, Settlement.Venue.BAZAAR_ORDER);
			}

			// A sell cancel names the item and the units; a buy cancel names only the coins, so the
			// two find their order by different evidence and cancel by different amounts.
			case ORDER_CANCELLED -> {
				if (event.displayName().isEmpty()) {
					matchForCoinRefund(event)
								.ifPresent(order -> order.cancel(event.at(), order.remaining()));
				} else {
					matchForCancel(event).ifPresent(order -> order.cancel(event.at(), event.units()));
				}
			}

			case INSTANT -> settle(event, Settlement.Venue.BAZAAR_INSTANT);

			// No order behind it and none to find: the counter takes the stack out of the inventory
			// and pays for it in one line. Which position it closes is the ledger's question.
			case NPC_SOLD -> settle(event, Settlement.Venue.NPC);

			case AUCTION_BOUGHT, AUCTION_SOLD -> settle(event, Settlement.Venue.AUCTION);

			// Carries no price: the only line that names one is the public broadcast every player's
			// listing also produces. A listing is not money moving anyway.
			case AUCTION_LISTED -> {
			}
		}
	}

	/**
	 * What a just-placed order rests at, out of the coins its setup line quoted.
	 *
	 * <p><b>Buys only.</b> A buy order escrows the gross, so the quoted coins over the units is the
	 * price per unit exactly; a sell offer quotes what it will pay out net of tax, which is about 1%
	 * under the price on the book and would read as an offer nobody posted.
	 *
	 * <p>Worth doing because the alternative was a hole rather than an approximation. Chat never
	 * names a price per unit, so an order was unpriced until the orders menu had been drawn - and
	 * {@code TrackerService.restingBuyOrders} drops an unpriced order, so {@code NpcBasket.Held}
	 * charged neither a slot nor the coins for it and the basket would rank the same item again.
	 * Placing a basket and re-opening it before visiting the menu therefore offered to buy some of it
	 * twice. The menu still overrules this the moment it is read.
	 */
	private static double placedUnitPrice(TradeEvent event) {
		return event.side() == TradeEvent.Side.BUY && event.units() > 0L
				? event.coins() / event.units()
				: 0.0d;
	}

	private void settle(TradeEvent event, Settlement.Venue venue) {
		double unitPrice = event.unitPrice() > 0.0d || event.units() == 0L
				? event.unitPrice()
				: event.coins() / event.units();

		settlements.add(new Settlement(event.at(), venue, event.side(), "", event.displayName(),
				event.units(), unitPrice, event.coins()));
	}

	/**
	 * The menu is the truth about what is on the book, so this is where an order the chat stream
	 * never saw gets adopted and where one it thinks is still resting gets buried.
	 */
	private void reconcile(long at, List<OrderSnapshot> mine) {
		List<TrackedOrder> matched = new ArrayList<>();

		for (OrderSnapshot snapshot : mine) {
			TrackedOrder order = orders.stream()
					.filter(TrackedOrder::isResting)
					.filter(o -> !matched.contains(o))
					.filter(o -> o.side() == snapshot.side()
							&& o.displayName().equals(snapshot.displayName())
							&& o.total() == snapshot.total())
					.findFirst()
					.orElseGet(() -> adopt(snapshot));

			order.applySnapshot(snapshot);
			matched.add(order);
		}

		for (TrackedOrder order : orders) {
			// Absence from a menu drawn after the order was placed is the only evidence there is
			// that an order left the book without saying so - a fill collected on another device,
			// or in a session nobody was capturing. The 793 unit Slimeball offer in the recorded
			// session is exactly that: placed, announced filled, never claimed in chat, and simply
			// not in the menu 37 minutes later.
			//
			// The rule is blunt on purpose. Hypixel repaints this menu on a timer, so a snapshot
			// taken seconds after a placement can predate the row appearing, and that order gets
			// buried early. It comes back the next time the menu draws it, because an unrecognised
			// row is adopted, so the cost of being wrong here is a duplicated order rather than a
			// lost one.
			if (order.isResting() && order.placedAt() < at && !matched.contains(order)) {
				order.vanish(at);
			}
		}
	}

	/**
	 * An order the menu drew that the chat stream never saw placed, dated to when it was seen.
	 *
	 * <p>Marked adopted, because that date is a lower bound and nothing more: the row may have been
	 * resting since yesterday. Every rule that reasons about age has to know which of the two it has.
	 */
	private TrackedOrder adopt(OrderSnapshot snapshot) {
		TrackedOrder order = new TrackedOrder(snapshot.at(), true, snapshot.side(),
				snapshot.displayName(), snapshot.itemId(), snapshot.total(), 0.0d,
				snapshot.unitPrice());
		orders.add(order);
		return order;
	}

	/** For an event about an order placed before capture started, or in an earlier session. */
	private TrackedOrder adopt(TradeEvent event) {
		TrackedOrder order = new TrackedOrder(event.at(), true, event.side(), event.displayName(),
				names.idFor(event.displayName()), event.units(), 0.0d, event.unitPrice());
		orders.add(order);
		return order;
	}

	private Optional<TrackedOrder> matchOnTotal(TradeEvent event) {
		return candidates(event).filter(o -> o.total() == event.units() && o.filled() < o.total())
				.findFirst();
	}

	/**
	 * Which order a claim belongs to, and the one place several orders on one item have to be told
	 * apart. The session ran three Slimeball offers at once and claimed 903, 395 and 366 units out
	 * of orders of 1,344, 395 and 366.
	 *
	 * <p>An exact match on uncollected units settles it when a fill has already been seen. Failing
	 * that the claim is itself the evidence of a fill, so the largest order that still has room for
	 * those units takes them - which is how the 903 lands on the 1,344 order and not on either of
	 * the small ones.
	 */
	private Optional<TrackedOrder> matchForClaim(TradeEvent event) {
		Optional<TrackedOrder> exact = candidates(event)
				.filter(o -> o.unclaimed() == event.units())
				.findFirst();

		if (exact.isPresent()) {
			return exact;
		}

		return candidates(event)
				.filter(o -> o.total() - o.claimed() >= event.units())
				.max(Comparator.comparingLong(o -> o.total() - o.claimed()));
	}

	/** A refund is in units left on the book, so the order it came off is the one that size fits. */
	private Optional<TrackedOrder> matchForCancel(TradeEvent event) {
		Optional<TrackedOrder> exact = candidates(event)
				.filter(o -> o.remaining() == event.units())
				.findFirst();

		return exact.isPresent()
				? exact
				: candidates(event).filter(o -> o.remaining() >= event.units()).findFirst();
	}

	/**
	 * Which order a coin refund cancelled, matched on the refund amount because it is the only
	 * thing the line carries.
	 *
	 * <p>The escrow held on a resting buy order is its uncommitted units at its resting price, and
	 * Hypixel refunds exactly that, so the amount identifies the order whenever two orders on the
	 * same side are not resting on the same coins. A tolerance rather than equality because the
	 * setup line's total is rounded to the coin while the unit price is not.
	 *
	 * <p>Nothing is cancelled when no order comes close. Cancelling the wrong order would take a
	 * live position off the book inside the tracker and leave its later fills homeless, which is a
	 * worse outcome than a stale order the next menu snapshot buries anyway.
	 */
	private Optional<TrackedOrder> matchForCoinRefund(TradeEvent event) {
		return orders.stream()
				.filter(TrackedOrder::isResting)
				.filter(o -> o.side() == event.side())
				.filter(o -> Math.abs(escrow(o) - event.coins()) <= event.coins() * REFUND_TOLERANCE)
				.min(Comparator.comparingDouble(o -> Math.abs(escrow(o) - event.coins())));
	}

	/** Coins an order still has tied up, from a menu price when there is one and the setup line otherwise. */
	private static double escrow(TrackedOrder order) {
		if (order.unitPrice() > 0.0d) {
			return order.remaining() * order.unitPrice();
		}

		return order.total() == 0L
				? 0.0d
				: order.setupCoins() * order.remaining() / order.total();
	}

	private Stream<TrackedOrder> candidates(TradeEvent event) {
		return orders.stream()
				.filter(TrackedOrder::isResting)
				.filter(o -> o.side() == event.side() && o.displayName().equals(event.displayName()));
	}
}
