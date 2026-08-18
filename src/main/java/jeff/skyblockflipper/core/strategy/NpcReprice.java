package jeff.skyblockflipper.core.strategy;

import jeff.skyblockflipper.core.model.BazaarProduct;
import jeff.skyblockflipper.core.model.ItemCatalog;
import jeff.skyblockflipper.core.pricing.FillModel;
import jeff.skyblockflipper.core.pricing.FillModel.FillEstimate;
import jeff.skyblockflipper.core.text.Coins;

import java.time.Duration;
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
	/**
	 * How far above your own price the book has to be before you are treated as outbid.
	 *
	 * <p>Half an increment, which is wide enough to absorb any rounding in where the resting price
	 * came from and narrower than the smallest real outbid there is. Nobody can post inside you by
	 * less than {@link BazaarProduct#PRICE_INCREMENT}, so a genuine displacement clears this by
	 * double, while a price derived from a setup line quoted to the coin cannot.
	 *
	 * <p>Without it a resting price a hundredth of a coin under the top bid read as outbid, and the
	 * advice was to move to a price above the one already held - outbidding yourself, on every unit,
	 * to end up where you started.
	 *
	 * <p>Package-visible because {@link NpcRound} compares a frozen post price to whatever the order
	 * rests at now, and it has to call the same two prices equal that this does. Two tolerances would
	 * let a round call a row unfinished on the strength of the rounding the review had forgiven.
	 */
	static final double OUTBID_TOLERANCE = BazaarProduct.PRICE_INCREMENT / 2.0d;

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
	 * @param adopted   true when {@code placedAt} is when the order was first seen rather than when
	 *                  it was placed, i.e. it was read out of an orders menu instead of watched being
	 *                  posted. The two are the same number and mean different things, and
	 *                  {@link NpcRound#eligible} is the rule that cannot be written without knowing
	 *                  which it has
	 */
	public record Order(String itemId, String displayName, double unitPrice, long total,
			long remaining, long unclaimed, long placedAt, boolean adopted) {
		/**
		 * An order whose placement was announced, which is the shape everything had before a dwell
		 * rule needed to tell the two apart.
		 */
		public Order(String itemId, String displayName, double unitPrice, long total, long remaining,
				long unclaimed, long placedAt) {
			this(itemId, displayName, unitPrice, total, remaining, unclaimed, placedAt, false);
		}

		/** A bare order with nothing known about its history, which is what most tests want. */
		public static Order of(String itemId, String displayName, double unitPrice, long remaining) {
			return new Order(itemId, displayName, unitPrice, remaining, remaining, 0L, 0L, false);
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
		/**
		 * Nothing to do. Leave it exactly where it is.
		 *
		 * <p>Two ways to get here and the reason line says which. The order is still the best bid,
		 * or it has been outbid by an amount that is not worth the click - see {@link RepriceValue}.
		 * Both are "leave it alone", and a reader that needs to tell them apart has
		 * {@link Advice#outbid()}.
		 */
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
	 * What moving this order back to the front of the book is expected to be worth.
	 *
	 * <p>The number that decides whether a reprice earns its row. Being outbid is not on its own a
	 * reason to click: on a contested book it is true again within seconds - the live
	 * {@code TRANSMISSION_TUNER} sample on 2026-08-11 had five bots a tenth of a coin apart - and
	 * advice that fires every time it is true is advice nobody can act on.
	 *
	 * <p>It is the same {@link FillModel} the placing side sizes with, over the same measured
	 * displacement, so the two halves of a cycle cannot disagree about what an order will take in.
	 * An item the book displaces you off in nine minutes is credited with nine minutes of flow,
	 * which on a thin margin is not worth walking to a menu for; one that holds for hours is
	 * credited with the rest of the interval.
	 *
	 * <p><b>The baseline is zero and that is deliberate.</b> An order collects flow only while it is
	 * the best bid, which is the whole 11.5M-against-59.7M measurement this strategy is built on, so
	 * an order that has been outbid is credited with nothing where it sits. Nothing measures when
	 * the orders inside it will clear, and inventing a figure for that would suppress reprices on
	 * the strength of a guess. Zero is the baseline most favourable to repricing, so the filter can
	 * only ever drop a row that is not worth a click even on the generous reading.
	 *
	 * @param unitsExpected  units expected to fill over the rest of the interval once the order is
	 *                       back on top, capped at what is still resting - a reprice cannot buy more
	 *                       than the order is for
	 * @param gain           coins those units are worth: {@code unitsExpected} times the margin at
	 *                       the price the reprice would post at. Zero unless the advice is a reprice
	 * @param outbidsPerHour measured displacement on this book, carried so a row can say why a gain
	 *                       is small rather than only that it is. Zero when unmeasured
	 * @param measured       false when this rests on the unmeasured fallback share rather than on
	 *                       recorded displacement. <b>An unmeasured gain never suppresses a
	 *                       reprice</b>: a fresh install has no tape, and filtering on a guess would
	 *                       mute the advice hardest on the accounts with the least history
	 */
	public record RepriceValue(double unitsExpected, double gain, double outbidsPerHour,
			boolean measured) {
		/** No estimate, which is what every advice that is not a reprice carries. */
		public static RepriceValue none() {
			return new RepriceValue(0.0d, 0.0d, 0.0d, false);
		}

		/**
		 * Average minutes before someone posts inside a fresh order here, where that was measured.
		 *
		 * <p>The mean wait of the Poisson process {@link FillModel} treats displacement as, so it is
		 * an average and not a deadline: half of all orders are outbid sooner than this.
		 */
		public OptionalDouble minutesToOutbid() {
			return measured && outbidsPerHour > 0.0d
					? OptionalDouble.of(60.0d / outbidsPerHour)
					: OptionalDouble.empty();
		}

		/** Whether this is a measurement that fell short of a floor, rather than an absent one. */
		public boolean measuredBelow(double floor) {
			return measured && gain < floor;
		}
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
	 * @param value       what the reprice is expected to be worth, and empty on every advice that is
	 *                    not one. Present on a hold that was outbid but not worth chasing, which is
	 *                    the one case where the estimate is the reason for the advice
	 */
	public record Advice(
			Order order,
			Action action,
			double npcPrice,
			double bestBid,
			double postPrice,
			double chaseStop,
			double marginRatio,
			RepriceValue value,
			String reason
	) {
		/**
		 * The shape before a reprice was valued, for the branches and callers that have no estimate
		 * to offer. A cancel and a full fill are decided by the book and the clock, not by throughput.
		 */
		public Advice(Order order, Action action, double npcPrice, double bestBid, double postPrice,
				double chaseStop, double marginRatio, String reason) {
			this(order, action, npcPrice, bestBid, postPrice, chaseStop, marginRatio,
					RepriceValue.none(), reason);
		}

		/**
		 * Whether the book has moved past this order, whatever the advice about it is.
		 *
		 * <p>Separate from {@link #needsAction()} because the two stopped meaning the same thing when
		 * a reprice had to earn its row: an order can be outbid and still be told to hold.
		 */
		public boolean outbid() {
			return order.remaining() > 0L && bestBid > order.unitPrice() + OUTBID_TOLERANCE;
		}

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
		return review(orders, context, now, context.npc().checkIn());
	}

	/**
	 * The same review, told how much of the check-in interval is left to collect anything in.
	 *
	 * <p>A reprice is only worth what it fills before the next trip, so the horizon the gain is
	 * measured over is the time to that trip and not a fixed hour. A caller working a round part way
	 * through passes what is left of it; the three-argument form passes a whole interval, which is
	 * the honest answer for a caller that is not tracking one.
	 *
	 * @param remainingInterval time to the next check-in. Null or non-positive is read as a full
	 *                          interval rather than as "no time left": an elapsed interval means the
	 *                          next trip is now, and valuing every reprice at zero at exactly the
	 *                          moment the player came back would mute the whole list
	 */
	public static List<Advice> review(List<Order> orders, StrategyContext context, long now,
			Duration remainingInterval) {
		Duration horizon = remainingInterval == null || remainingInterval.isZero()
				|| remainingInterval.isNegative()
				? context.npc().checkIn()
				: remainingInterval;

		List<Advice> advice = new ArrayList<>();

		for (Order order : orders) {
			adviseOn(order, context, now, horizon).ifPresent(advice::add);
		}

		advice.sort(Comparator
				.comparing((Advice a) -> a.needsAnything() ? 0 : 1)
				.thenComparing(Comparator.comparingLong(Advice::capitalAtStake).reversed()));

		return advice;
	}

	private static Optional<Advice> adviseOn(Order order, StrategyContext context, long now,
			Duration horizon) {
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
		// costs the increment on every unit for no measured gain. Held to within OUTBID_TOLERANCE,
		// so a price that arrived rounded is a tie rather than a reprice.
		if (order.unitPrice() >= bid - OUTBID_TOLERANCE) {
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

		RepriceValue value = valueOf(order, product, npcPrice, postPrice, context, horizon);

		// The click has to be worth the same coins any other candidate has to be worth. Below it the
		// order stays where it is: the units are still bid for at a better price than the reprice
		// would pay, and the trade is not over, so there is nothing to do but wait for the book.
		if (value.measuredBelow(context.minProfitPerFlip())) {
			return Optional.of(new Advice(order, Action.HOLD, npcPrice, bid, postPrice, chaseStop,
					margin(npcPrice, order.unitPrice()), value, notWorthChasing(value, context)));
		}

		return Optional.of(new Advice(order, Action.REPRICE, npcPrice, bid, postPrice, chaseStop,
				margin(npcPrice, postPrice), value,
				String.format("Outbid at %.1f. Move to %.1f and it is still a %.0f%% margin, with "
								+ "%.1f of room left before the stop", bid, postPrice,
						margin(npcPrice, postPrice) * 100.0d, chaseStop - postPrice)));
	}

	/**
	 * Expected units over the horizon at the top of the book, priced at the margin they would earn.
	 *
	 * <p>Capped at what is still resting, because a reprice moves an order rather than enlarging it:
	 * a book that would dump four thousand units into an order of five hundred is worth five hundred
	 * units of margin and no more.
	 */
	private static RepriceValue valueOf(Order order, BazaarProduct product, double npcPrice,
			double postPrice, StrategyContext context, Duration horizon) {
		double marginPerUnit = npcPrice - postPrice;
		double hours = horizon.toMillis() / 3_600_000.0d;

		if (marginPerUnit <= 0.0d || hours <= 0.0d || order.remaining() <= 0L) {
			return RepriceValue.none();
		}

		FillEstimate fill = FillModel.estimate(
				product,
				context.trends().fillStatsFor(order.itemId()).orElse(null),
				horizon,
				NpcFlipStrategy.UNMEASURED_FILL_SHARE);

		double units = Math.min(order.remaining(), fill.buyUnitsPerHour() * hours);

		return new RepriceValue(units, units * marginPerUnit, fill.outbidsPerHour(),
				fill.measured());
	}

	/**
	 * Why an outbid order is being left alone, in the terms that decided it.
	 *
	 * <p>Says the displacement rate rather than only the coins, because that is the part a player
	 * cannot see from the menu and the part that will not change by coming back sooner.
	 */
	private static String notWorthChasing(RepriceValue value, StrategyContext context) {
		String pace = value.minutesToOutbid().isPresent()
				? String.format(" - the book posts inside you about every %.0f minutes",
						value.minutesToOutbid().getAsDouble())
				: "";

		return String.format("Outbid, but chasing it back is worth about %s before the next "
						+ "check-in%s, under the %s a flip has to make. Leave it: the order is still "
						+ "bid at a better price than the move would pay",
				Coins.format(value.gain()), pace, Coins.format(context.minProfitPerFlip()));
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
