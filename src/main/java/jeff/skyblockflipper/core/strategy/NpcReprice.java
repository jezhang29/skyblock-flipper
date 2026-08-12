package jeff.skyblockflipper.core.strategy;

import jeff.skyblockflipper.core.model.BazaarProduct;
import jeff.skyblockflipper.core.model.ItemCatalog;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * What to do with the buy orders already resting under an NPC price.
 *
 * <p>{@link NpcBasket} decides what to place; this is the other half of the same cycle, and it is
 * where most of the money is. Measured per 8-hour cycle on three days of tape: posting once and
 * walking away makes 11.5M, returning every 30 minutes to put the order back on top of the book
 * makes 59.7M. An order only collects flow while it is the best bid, so a basket that is never
 * repriced is quoting five times what it will make.
 *
 * <p><b>It says what to do and the player clicks.</b> No part of this places, edits or cancels an
 * order. Automating bazaar clicks is a macro and against Hypixel's rules.
 *
 * <p><b>The stop is the same 15% the plans were filtered on.</b> Chasing a rising book upward eats
 * the margin, and past {@code npc x (1 - npcMinMarginRatio)} the slot is worth more holding
 * something else. That is the one number that turns a reprice into a cancel, and it is the same
 * threshold {@link NpcFlipStrategy} used to admit the item in the first place, so an order is never
 * told to chase to a price its own plan would have refused.
 *
 * <p>Nothing here can lose coins by being wrong. The NPC price cannot move, so the worst outcome of
 * a missed reprice is an order that sits unfilled, and the worst outcome of a needless cancel is a
 * re-placed order.
 */
public final class NpcReprice {
	private NpcReprice() {
	}

	/**
	 * One resting buy order, as whatever read the orders menu understands it.
	 *
	 * <p>Deliberately not {@code TrackedOrder}: this needs a price, a size, how much of it has
	 * happened and when it started, and taking the tracker's type would tie the money math to the
	 * bookkeeping that happens to be feeding it today.
	 *
	 * @param unitPrice the price the order actually rests at, which is the whole input. A plan's
	 *                  quoted cost includes the chase it had not paid yet, so it is not this
	 * @param total     units the order was placed for, so a partial fill can be described as a share
	 *                  of something
	 * @param remaining units still on the book, i.e. what a reprice would be moving
	 * @param unclaimed units that have already filled and are sitting in the order waiting to be
	 *                  collected. Coins you own and cannot spend until you click Claim, and the one
	 *                  part of a partial fill that has a deadline of sorts: nothing else can be done
	 *                  with that item until it is out of the order
	 * @param placedAt  epoch millis the order started resting, or 0 when nothing knows. <b>A lower
	 *                  bound, never an upper one.</b> The tracker starts empty each session, so an
	 *                  order placed yesterday and first seen in today's orders menu is dated to
	 *                  today. Age therefore under-reports, which makes {@link Action#EXPIRED} fire
	 *                  late rather than wrongly
	 */
	public record Order(String itemId, String displayName, double unitPrice, long total,
			long remaining, long unclaimed, long placedAt) {
		/** A bare order with nothing known about its history, which is what most tests want. */
		public static Order of(String itemId, String displayName, double unitPrice, long remaining) {
			return new Order(itemId, displayName, unitPrice, remaining, remaining, 0L, 0L);
		}

		/** Units that have filled, whether or not they have been collected. */
		public long filled() {
			return Math.max(0L, total - remaining);
		}

		public boolean partlyFilled() {
			return filled() > 0L && remaining > 0L;
		}

		/** Hours the order is known to have been resting, or empty when nothing timed it. */
		public OptionalDouble restingHours(long now) {
			return placedAt <= 0L || now <= placedAt
					? OptionalDouble.empty()
					: OptionalDouble.of((now - placedAt) / 3_600_000.0d);
		}
	}

	public enum Action {
		/** Still the best bid. Nothing to do. */
		HOLD,

		/** Outbid, and getting back to the top still leaves the margin above the floor. */
		REPRICE,

		/** Outbid past the point where the trade is worth a slot. Take the order off the book. */
		CANCEL,

		/**
		 * Still priced fine and still not filled, past the window the coins were budgeted for.
		 *
		 * <p>The one piece of advice here that is not about the book. {@code npcRestingHours} means
		 * "how long capital may sit in this trade", and until now nothing enforced it: an order that
		 * held the top of the book and collected nothing for a day was reported as healthy every
		 * time, because on every axis the book knows about it was. Cancelling it is not a loss - the
		 * refund is the whole remaining stake - it is the slot and the coins going back to the
		 * basket, which is the only place they can earn anything.
		 */
		EXPIRED
	}

	/**
	 * One order and what to do with it.
	 *
	 * @param bestBid     the best bid on the book right now, which is your own price while you are
	 *                    at the top of it
	 * @param postPrice   where you would have to post to be the best bid again
	 * @param chaseStop   the highest price this item is worth chasing to
	 * @param marginRatio margin against the NPC price at the price this advice is about: yours on a
	 *                    hold, the new one on a reprice, what it would have to become on a cancel
	 */
	public record Advice(
			Order order,
			Action action,
			double npcPrice,
			double bestBid,
			double postPrice,
			double chaseStop,
			double marginRatio,
			String reason
	) {
		/** Coins the reprice adds to what the remaining units will cost. Zero unless repricing. */
		public double extraCost() {
			return action == Action.REPRICE
					? Math.max(0.0d, postPrice - order.unitPrice()) * order.remaining()
					: 0.0d;
		}

		/** Profit still on the table if the remaining units fill at the advised price. */
		public double profitAtStake() {
			return action == Action.CANCEL || action == Action.EXPIRED
					? 0.0d
					: (npcPrice - priceInQuestion()) * order.remaining();
		}

		/** Coins the units already filled are worth at the NPC, which is what claiming releases. */
		public double claimableProfit() {
			return Math.max(0.0d, npcPrice - order.unitPrice()) * order.unclaimed();
		}

		/** Whether there is a partial fill sitting in the order waiting to be collected. */
		public boolean hasUnclaimed() {
			return order.unclaimed() > 0L;
		}

		/** Coins tied up in the units still resting, at whatever they would end up costing. */
		public long capitalAtStake() {
			return Math.round(priceInQuestion() * order.remaining());
		}

		private double priceInQuestion() {
			return action == Action.REPRICE ? postPrice : order.unitPrice();
		}

		/** Whether this order wants a click on the book, ignoring anything waiting to be claimed. */
		public boolean needsAction() {
			return action != Action.HOLD;
		}

		/** Whether the player has anything at all to do here, claiming included. */
		public boolean needsAnything() {
			return needsAction() || hasUnclaimed();
		}

		/** Whether the advice is to take the order off the book, for whichever of the two reasons. */
		public boolean isCancel() {
			return action == Action.CANCEL || action == Action.EXPIRED;
		}
	}

	/**
	 * Reviews every order against the book as it is now.
	 *
	 * <p>Orders on items no NPC buys are dropped rather than reported: they are ordinary spread
	 * flips, and this has nothing to say about them. So are orders on a product the current bazaar
	 * snapshot does not carry, which means the book has not been fetched rather than that the order
	 * is wrong.
	 *
	 * <p>Sorted with the ones needing a click first and the largest of those at the top, because the
	 * list is read while standing at a bazaar menu with a limited amount of patience.
	 *
	 * @param now epoch millis, passed rather than read so the resting-window rule is testable. Core
	 *            owns no clock
	 */
	public static List<Advice> review(List<Order> orders, StrategyContext context, long now) {
		List<Advice> advice = new ArrayList<>();

		for (Order order : orders) {
			adviseOn(order, context, now).ifPresent(advice::add);
		}

		advice.sort(Comparator
				.comparing((Advice a) -> a.needsAnything() ? 0 : 1)
				.thenComparing(Comparator.comparingLong(Advice::capitalAtStake).reversed()));

		return advice;
	}

	private static Optional<Advice> adviseOn(Order order, StrategyContext context, long now) {
		ItemCatalog.Entry entry = context.catalog().get(order.itemId()).orElse(null);
		BazaarProduct product = context.bazaar().products().get(order.itemId());

		// An order with nothing on the book and nothing to collect is finished, not resting.
		if (entry == null || product == null
				|| (order.remaining() <= 0L && order.unclaimed() <= 0L)) {
			return Optional.empty();
		}

		Double npcPrice = entry.npcPrice().filter(price -> price > 0.0d).orElse(null);

		if (npcPrice == null) {
			return Optional.empty();
		}

		NpcContext npc = context.npc();
		double chaseStop = npc.maxChasePrice(npcPrice);
		OptionalDouble bestBid = product.instantSellPrice();

		// Filled to the last unit and still holding them. There is no order left to price, so every
		// branch below would be reasoning about a book position that no longer exists; the whole of
		// what is left to do is the claim, which the caller reads off unclaimed().
		if (order.remaining() <= 0L) {
			return Optional.of(new Advice(order, Action.HOLD, npcPrice, order.unitPrice(),
					order.unitPrice(), chaseStop, margin(npcPrice, order.unitPrice()),
					"Filled completely - all " + order.total() + " units are in the order waiting "
							+ "to be collected"));
		}

		// Before anything about the book, because it is not a fact about the book: the window is how
		// long these coins were ever meant to sit here, and an order that outlives it is holding a
		// slot the basket could refill whether or not it is still correctly priced. Placed first so
		// an outbid order past its window is told to come back rather than to chase.
		Optional<Advice> expired = expiry(order, npcPrice, chaseStop, npc, now);

		if (expired.isPresent()) {
			return expired;
		}

		// An empty buy side with an order resting in it means the snapshot predates the order. Say
		// so rather than computing a reprice off a book that does not contain your own bid.
		if (bestBid.isEmpty()) {
			return Optional.of(new Advice(order, Action.HOLD, npcPrice, order.unitPrice(),
					order.unitPrice(), chaseStop, margin(npcPrice, order.unitPrice()),
					"Nothing else is bidding on this book, so the order is the top of it"));
		}

		double bid = bestBid.getAsDouble();

		// Your own order is in the book, so being the best bid reads as a tie rather than as a lead.
		// A tie is behind whoever posted first, but outbidding yourself by an increment to find out
		// costs the increment on every unit for no measured gain.
		if (order.unitPrice() >= bid) {
			return Optional.of(new Advice(order, Action.HOLD, npcPrice, bid, order.unitPrice(),
					chaseStop, margin(npcPrice, order.unitPrice()),
					String.format("Top of the book at %.1f, %.0f%% under the %.1f the NPC pays",
							order.unitPrice(), margin(npcPrice, order.unitPrice()) * 100.0d,
							npcPrice)));
		}

		double postPrice = product.outbidBuyOrder().orElse(bid + BazaarProduct.PRICE_INCREMENT);

		if (postPrice >= npcPrice) {
			return Optional.of(new Advice(order, Action.CANCEL, npcPrice, bid, postPrice, chaseStop,
					margin(npcPrice, postPrice),
					String.format("The book has caught the NPC price: the top bid is %.1f against "
							+ "%.1f, so there is no trade here any more", bid, npcPrice)));
		}

		if (postPrice > chaseStop) {
			return Optional.of(new Advice(order, Action.CANCEL, npcPrice, bid, postPrice, chaseStop,
					margin(npcPrice, postPrice),
					String.format("Getting back on top costs %.1f, past the %.1f stop - that is a "
									+ "%.0f%% margin against the %.0f%% floor, and the slot is worth "
									+ "more elsewhere", postPrice, chaseStop,
							margin(npcPrice, postPrice) * 100.0d,
							npc.minMarginRatio() * 100.0d)));
		}

		return Optional.of(new Advice(order, Action.REPRICE, npcPrice, bid, postPrice, chaseStop,
				margin(npcPrice, postPrice),
				String.format("Outbid at %.1f. Move to %.1f and it is still a %.0f%% margin, with "
								+ "%.1f of room left before the stop", bid, postPrice,
						margin(npcPrice, postPrice) * 100.0d, chaseStop - postPrice)));
	}

	/**
	 * The order has been resting longer than the coins were budgeted for, if anything timed it.
	 *
	 * <p>Deliberately blunt: past the window, with units still on the book, the advice is to take
	 * them back. There is no partial credit for an order that is nearly done, because the refund is
	 * the whole remaining stake and the same coins in the next basket are worth a measured amount
	 * more. What stops this being noise is that {@link Order#placedAt} under-reports - it is when
	 * the mod first saw the order, not when Hypixel accepted it - so a long-resting order the
	 * tracker met five minutes ago is silently given another full window.
	 */
	private static Optional<Advice> expiry(Order order, double npcPrice, double chaseStop,
			NpcContext npc, long now) {
		OptionalDouble resting = order.restingHours(now);

		if (resting.isEmpty() || resting.getAsDouble() < npc.restingHours()) {
			return Optional.empty();
		}

		double hours = resting.getAsDouble();
		String filled = order.filled() > 0L
				? String.format("%d of %d units filled", order.filled(), order.total())
				: "nothing has filled";

		return Optional.of(new Advice(order, Action.EXPIRED, npcPrice, order.unitPrice(),
				order.unitPrice(), chaseStop, margin(npcPrice, order.unitPrice()),
				String.format("Resting %.1fh against a %.0fh window and %s. Cancel it: the refund is "
								+ "the whole remaining stake, and the slot is what the next basket is "
								+ "short of", hours, npc.restingHours(), filled)));
	}

	private static double margin(double npcPrice, double price) {
		return npcPrice <= 0.0d ? 0.0d : (npcPrice - price) / npcPrice;
	}
}
