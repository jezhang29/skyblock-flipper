package jeff.skyblockflipper.core.strategy;

import jeff.skyblockflipper.core.model.BazaarProduct;
import jeff.skyblockflipper.core.model.ItemCatalog;
import jeff.skyblockflipper.core.model.OrderLevel;
import jeff.skyblockflipper.core.pricing.FillModel;
import jeff.skyblockflipper.core.pricing.FillModel.FillEstimate;
import jeff.skyblockflipper.core.text.Coins;
import jeff.skyblockflipper.core.valuation.PriceTrend;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Buy on the bazaar below the fixed price an NPC pays, then sell to the NPC.
 *
 * <p>NPC prices never move, so this is one-sided arbitrage against a constant: no spread to
 * capture, no counterparty to wait for on the sell leg, and no sales tax, because selling to an NPC
 * is not a bazaar transaction.
 *
 * <p><b>There are two ways to acquire the stock, and they are not close.</b>
 *
 * <ul>
 *   <li><b>Instant buy</b> - cross the spread and take the ask. Immediate, but you pay the ask, and
 *       the size is capped by how much of the book sits below the NPC price.</li>
 *   <li><b>Buy order</b> - post a bid and wait for someone to dump into it. You pay the bid instead
 *       of the ask, which on a wide book is most of the profit. Measured on
 *       {@code ENCHANTED_MELON_BLOCK} against the live bazaar: instant-buying at 50933.5 against
 *       an NPC price of 51200 nets 266 a unit, while a buy order at 49654.2 nets 1545 - 5.8x more
 *       per unit. What it costs is time, and only that: the NPC price cannot move away while you
 *       wait, so an unfilled order is a cancelled order, not a loss.</li>
 * </ul>
 *
 * <p>So both are evaluated and the better one by profit per hour is what gets recommended, with the
 * other reported alongside it. The strategy used to hard-code "do not post a buy order", which was
 * right about the mechanics - the ask-side edge does close in minutes - and wrong about the
 * conclusion, because it left the larger and more durable half of the trade on the table.
 *
 * <p><b>Both routes are sized from traded volume, not from the book.</b> Resting depth is a
 * snapshot; a plan measured in units per hour needs a flow. An item with 4000 units resting below
 * the NPC price but 30 units of weekly turnover is not a 4000-unit opportunity, it is a trap that
 * takes a week to exit, and sizing off the book is how a tool ends up recommending it.
 *
 * <p><b>Three separate ceilings apply, and which one binds is the whole ranking.</b> Carrying
 * capacity limits units per hour. {@link StrategyContext#npcCapRemaining()} limits gross NPC coins
 * per day, across every item at once. Capital limits both. Sizing against only the first is what
 * makes an expensive item look like a business: {@code ENCHANTED_DIAMOND_BLOCK} at 204,800 a unit
 * quotes 23M an hour and exhausts a 500M daily budget in about fifteen minutes, while
 * {@code FIG_LOG} at 8 a unit could never spend that budget in a week of grinding. Plans are
 * therefore sized over {@link StrategyContext#npcRestingHours()} rather than over an hour, because
 * an hourly figure cannot express a limit that is measured per day.
 */
public final class NpcFlipStrategy implements FlipStrategy {
	/** How deep into the ask side to walk before giving up. */
	private static final int MAX_LEVELS = 10;

	/** Usable inventory slots for one trip to an NPC. */
	private static final long SLOTS_PER_TRIP = 36L;

	/** Round trips an attentive player manages per hour: buy, walk to the NPC, sell, return. */
	private static final long TRIPS_PER_HOUR = 12L;

	/**
	 * Units one bazaar order may cover: 71,680 for a stackable item, 256 for one that is not.
	 *
	 * <p>A plan larger than this is not impossible, it just needs more than one order, and orders
	 * are a limited resource - see {@code Fees.bazaarOrderSlots()}. For a stackable item this never
	 * binds inside a two-hour session, because carrying capacity runs out at 55,296 units first.
	 * For an unstackable one it binds immediately: 864 carried units is four orders of 256.
	 */
	private static final long MAX_UNITS_PER_ORDER_STACKABLE = 71_680L;
	private static final long MAX_UNITS_PER_ORDER_UNSTACKABLE = 256L;

	/**
	 * Below this weekly turnover on the side you are acquiring from, the item does not trade enough
	 * for an hourly plan to mean anything.
	 */
	private static final long MIN_WEEKLY_VOLUME = 10_000L;

	/**
	 * Share of the hour's inbound instant-sells a top-of-book buy order actually collects.
	 *
	 * <p>Not all of it: outbidding puts you first in the queue, but anyone can outbid you back, and
	 * the orders already resting at your price were not there for decoration.
	 */
	private static final double ORDER_FILL_SHARE = 0.25d;

	/**
	 * Share of the hour's ask-side flow you can realistically lift.
	 *
	 * <p>You are not the only one watching, and the offers below the NPC price are precisely the
	 * ones being competed for.
	 */
	private static final double INSTANT_TAKE_SHARE = 0.5d;

	/** Instant-sells per hour at which a resting order is as reliable as this strategy gets. */
	private static final double LIQUID_FILL_RATE = 500.0d;

	/**
	 * Start of the NPC day the given instant falls in, as epoch millis.
	 *
	 * <p>The cap refills at UTC midnight, so this is what {@code Ledger.npcCoinsReceivedSince} has
	 * to be asked about. Lives here because the reset clock is a fact about NPC flipping rather
	 * than about bookkeeping.
	 */
	public static long npcDayStart(long nowMillis) {
		return Instant.ofEpochMilli(nowMillis)
				.atOffset(ZoneOffset.UTC)
				.toLocalDate()
				.atStartOfDay(ZoneOffset.UTC)
				.toInstant()
				.toEpochMilli();
	}

	@Override
	public StrategyKind kind() {
		return StrategyKind.NPC_FLIP;
	}

	@Override
	public List<FlipCandidate> findCandidates(StrategyContext context) {
		List<FlipCandidate> candidates = new ArrayList<>();

		if (context.catalog().isEmpty()) {
			return candidates;
		}

		for (BazaarProduct product : context.bazaar().products().values()) {
			Optional<ItemCatalog.Entry> entry = context.catalog().get(product.productId());

			if (entry.isEmpty()) {
				continue;
			}

			entry.get().npcPrice()
					.flatMap(npcPrice -> evaluate(product, entry.get(), npcPrice, context))
					.ifPresent(candidates::add);
		}

		candidates.sort(null);
		return candidates;
	}

	/**
	 * One plan per item: whichever acquisition route pays more over the session.
	 *
	 * <p>Deliberately not one candidate per route. They are the same trade with two executions, and
	 * listing both would push genuinely different items off a ranked list to show the same item
	 * twice. The losing route is stated in the notes, so the choice is auditable rather than hidden.
	 */
	private Optional<FlipCandidate> evaluate(BazaarProduct product, ItemCatalog.Entry entry,
			double npcPrice, StrategyContext context) {
		if (npcPrice <= 0.0d) {
			return Optional.empty();
		}

		// A bazaar price climbing toward a price that cannot climb with it is the only market move
		// that kills this trade. Note the direction: the spread strategies reject falling items,
		// because they have to sell what they bought. Here a falling bid is a wider margin.
		PriceTrend trend = context.trends().trendFor(product.productId()).orElse(null);

		if (trend != null && trend.isUsable() && context.maxAdverseDrift() > 0.0d
				&& trend.isRising(context.maxAdverseDrift())) {
			return Optional.empty();
		}

		Limits limits = Limits.of(entry, npcPrice, context);

		if (limits.sessionUnits() <= 0L) {
			return Optional.empty();
		}

		Route instant = instantBuyRoute(product, npcPrice, limits, context);
		Route order = buyOrderRoute(product, npcPrice, limits, context);

		Route best = better(instant, order);

		if (best == null || best.profitPerHour(limits.sessionHours()) < context.minProfitPerFlip()) {
			return Optional.empty();
		}

		Route other = best == instant ? order : instant;
		String name = context.catalog().displayName(product.productId());
		long trips = ceilDiv(best.units(), limits.unitsPerTrip());

		return Optional.of(new FlipCandidate(
				product.productId(),
				name,
				kind(),
				best.unitCost(),
				npcPrice,
				best.unitNetProfit(),
				best.units(),
				Math.round(best.capital()),
				best.profitPerHour(limits.sessionHours()),
				best.confidence(),
				best.steps(name, npcPrice, trips),
				risks(best, product, trips, limits),
				notes(best, other, product, npcPrice, limits, context)));
	}

	/**
	 * Cross the spread: walk the ask side and take every level priced under the NPC.
	 *
	 * <p>Each level is only worth taking while it is cheaper than the NPC pays, so the walk stops at
	 * the first one that is not.
	 */
	private static Route instantBuyRoute(BazaarProduct product, double npcPrice, Limits limits,
			StrategyContext context) {
		if (product.sellOffers().isEmpty()
				|| product.movingWeek().instantBought() < MIN_WEEKLY_VOLUME) {
			return null;
		}

		// Capped per flip, like every other sizing decision: an instant-buy route walking the
		// book is the easiest way to spend everything on one product.
		long budget = context.maxCapitalPerFlip();
		long depthUnits = 0L;
		double spend = 0.0d;

		for (OrderLevel level : product.sellOffers()
				.subList(0, Math.min(MAX_LEVELS, product.sellOffers().size()))) {
			if (level.pricePerUnit() >= npcPrice) {
				break;
			}

			long affordable = (long) ((budget - spend) / level.pricePerUnit());

			if (affordable <= 0L) {
				break;
			}

			long take = Math.min(level.amount(), affordable);

			depthUnits += take;
			spend += take * level.pricePerUnit();
		}

		if (depthUnits <= 0L) {
			return null;
		}

		double averageCost = spend / depthUnits;
		double flowPerHour = product.instantBuysPerHour() * INSTANT_TAKE_SHARE;
		long units = Math.min(depthUnits,
				Math.min(limits.sessionUnits(), (long) (flowPerHour * limits.sessionHours())));

		if (units <= 0L) {
			return null;
		}

		// Cost stays the average over the whole walk even when the plan is truncated. Truncation
		// keeps the cheapest levels, so the real average would be lower - this errs toward quoting
		// less profit than the trade makes, which is the only safe direction to be wrong in.
		double unitNet = npcPrice - averageCost;

		// Instant buys rest nothing on the book, so they consume no order slots. The per-purchase
		// ceiling still applies; it just costs another click rather than another slot.
		return unitNet <= 0.0d
				? null
				: new Route(false, averageCost, unitNet, units, units * averageCost, 0.95d,
						flowPerHour, false, ceilDiv(units, limits.maxUnitsPerOrder()));
	}

	/**
	 * Post a bid and wait. Pays the bid instead of the ask, and fills only as fast as people dump.
	 */
	private static Route buyOrderRoute(BazaarProduct product, double npcPrice, Limits limits,
			StrategyContext context) {
		if (product.buyOrders().isEmpty()
				|| product.movingWeek().instantSold() < MIN_WEEKLY_VOLUME) {
			return null;
		}

		double bid = product.outbidBuyOrder().orElseThrow();
		double unitNet = npcPrice - bid;

		if (bid <= 0.0d || unitNet <= 0.0d) {
			return null;
		}

		// Measured where the tape has covered this product, and the old flat share where it has
		// not. The unit cap stays hourly regardless: this route is bounded by how many inventory
		// trips a player makes, not only by how fast the order fills.
		FillEstimate fill = FillModel.estimate(
				product,
				context.trends().fillStatsFor(product.productId()).orElse(null),
				context.fillHorizon(),
				ORDER_FILL_SHARE);

		double fillPerHour = fill.buyUnitsPerHour();
		long affordable = (long) (context.maxCapitalPerFlip() / bid);
		long units = Math.min(affordable,
				Math.min(limits.sessionUnits(), (long) (fillPerHour * limits.sessionHours())));

		if (units <= 0L) {
			return null;
		}

		// Every unit of this route rests on the book, so the plan has to fit in the order slots the
		// account actually has. Unstackable items reach this quickly: 256 units to an order.
		long orders = ceilDiv(units, limits.maxUnitsPerOrder());

		if (orders > context.fees().bazaarOrderSlots()) {
			return null;
		}

		// The NPC leg is certain; only the fill is not, so confidence tracks how briskly the item
		// is actually being dumped rather than anything about the price.
		double confidence = 0.55d + 0.30d
				* Math.min(1.0d, product.instantSellsPerHour() / LIQUID_FILL_RATE);

		return new Route(true, bid, unitNet, units, units * bid, confidence, fillPerHour,
				fill.measured(), orders);
	}

	private static Route better(Route a, Route b) {
		if (a == null) {
			return b;
		}

		if (b == null) {
			return a;
		}

		// Both routes are sized over the same session, so comparing totals and comparing rates give
		// the same ordering. Totals avoid dividing by the session twice.
		return a.totalProfit() >= b.totalProfit() ? a : b;
	}

	private static long ceilDiv(long value, long divisor) {
		return divisor <= 0L ? 0L : (value + divisor - 1L) / divisor;
	}

	/**
	 * The three ceilings on one plan, resolved once so both routes size against the same numbers.
	 *
	 * @param unitsPerTrip     units one inventory holds, 2,304 stackable and 36 not
	 * @param sessionHours     how long the player intends to keep going
	 * @param carryUnits       units the session's trips can move, ignoring every other limit
	 * @param capUnits         units the remaining daily NPC budget can pay for
	 * @param maxUnitsPerOrder units one bazaar order may cover
	 */
	private record Limits(long unitsPerTrip, double sessionHours, long carryUnits, long capUnits,
			long maxUnitsPerOrder) {
		static Limits of(ItemCatalog.Entry entry, double npcPrice, StrategyContext context) {
			long unitsPerTrip = SLOTS_PER_TRIP * entry.stackSize();
			double sessionHours = context.npcRestingHours();
			long carryUnits = (long) (unitsPerTrip * TRIPS_PER_HOUR * sessionHours);

			// Unlimited stays unlimited rather than becoming an enormous but finite unit count, so
			// that a caller with no cap to report gets the sizing this had before the cap existed.
			long capUnits = context.npcCapRemaining() == StrategyContext.NPC_CAP_UNLIMITED
					? Long.MAX_VALUE
					: (long) (context.npcCapRemaining() / npcPrice);

			return new Limits(unitsPerTrip, sessionHours, carryUnits, capUnits,
					entry.unstackable()
							? MAX_UNITS_PER_ORDER_UNSTACKABLE
							: MAX_UNITS_PER_ORDER_STACKABLE);
		}

		/** The binding ceiling before market flow and capital are considered. */
		long sessionUnits() {
			return Math.min(carryUnits, capUnits);
		}

		/** True when the daily NPC budget runs out before the session's trips do. */
		boolean capBinds() {
			return capUnits < carryUnits;
		}

		/** Whether the budget is what actually held this plan to {@code units}. */
		boolean capSized(long units) {
			return capBinds() && units == capUnits;
		}

		/** False when the caller stated no budget, and so nothing about it is worth reporting. */
		boolean known() {
			return capUnits != Long.MAX_VALUE;
		}

		/** Hours of trips the remaining budget will survive, or empty when it outlasts a day. */
		Optional<Double> hoursUntilCapped() {
			double perHour = unitsPerTrip * TRIPS_PER_HOUR;

			return !capBinds() || perHour <= 0.0d
					? Optional.empty()
					: Optional.of(capUnits / perHour);
		}
	}

	/**
	 * @param viaOrder      true when the stock is acquired with a resting buy order rather than by
	 *                      crossing the spread
	 * @param fillPerHour   units an hour the acquisition leg is expected to bring in
	 * @param fillMeasured  whether {@code fillPerHour} came from recorded displacement or from an
	 *                      assumed share of volume. Always false for the instant route, which rests
	 *                      no order and so has nothing to be displaced from
	 * @param orders        bazaar orders the plan needs, given the per-order unit ceiling
	 */
	private record Route(boolean viaOrder, double unitCost, double unitNetProfit, long units,
			double capital, double confidence, double fillPerHour, boolean fillMeasured,
			long orders) {
		double totalProfit() {
			return unitNetProfit * units;
		}

		/**
		 * Average coins an hour across the whole session, which is what the plan is sized over.
		 *
		 * <p>Not the rate while you are actively trading. An item that exhausts the daily NPC
		 * budget in fifteen minutes of a two-hour session reports a quarter of its peak rate, and
		 * that is the point: the ranking is comparing what the session is worth.
		 */
		double profitPerHour(double sessionHours) {
			return sessionHours <= 0.0d ? 0.0d : totalProfit() / sessionHours;
		}

		List<String> steps(String name, double npcPrice, long trips) {
			List<String> steps = new ArrayList<>(4);

			if (viaOrder) {
				steps.add("Bazaar -> search " + name + " -> Create Buy Order");
				steps.add(orders > 1L
						? String.format("Set price %.1f and quantity %d, split across %d orders "
								+ "because one order cannot hold more", unitCost, units, orders)
						: String.format("Set price %.1f and quantity %d, then wait for the fill",
								unitCost, units));
			} else {
				steps.add(orders > 1L
						? String.format("Bazaar -> search %s -> Instant Buy %d, over %d purchases",
								name, units, orders)
						: "Bazaar -> search " + name + " -> Instant Buy " + units);
			}

			steps.add(trips > 1L
					? "Take the stock to an NPC that buys it, across roughly " + trips + " trips"
					: "Take the stock to any NPC that buys it");
			steps.add(String.format("Sell to the NPC at %.2f coins each", npcPrice));
			steps.add(viaOrder
					? "Re-check the bid before repeating; if it has risen past the NPC price, stop"
					: "Re-check the bazaar price before repeating; this closes quickly");

			return steps;
		}
	}

	private static List<String> risks(Route route, BazaarProduct product, long trips,
			Limits limits) {
		List<String> risks = new ArrayList<>();

		if (route.viaOrder()) {
			// The measured figure is what the order is expected to collect; the raw dump rate is
			// what the book sees. Quoting the first where it exists stops the risk line from
			// promising a fill the ranking never assumed.
			risks.add(route.fillMeasured()
					? String.format("Fill is not immediate: your order collects about %.0f units an "
							+ "hour of what gets dumped here", route.fillPerHour())
					: String.format("Fill is not immediate: about %.0f units an hour get dumped "
							+ "into this book", product.instantSellsPerHour()));
			risks.add("Anyone can outbid you; reprice or cancel rather than sitting behind the queue");
		} else {
			risks.add("Edge closes fast once others notice; verify prices before committing");
		}

		risks.add("Requires walking to an NPC that buys this item");

		if (trips > 1L) {
			risks.add("Needs " + trips + " inventory trips: assumes 36 slots at a "
					+ (limits.unitsPerTrip() / 36L) + " stack");
		}

		limits.hoursUntilCapped().ifPresent(hours -> risks.add(String.format(
				"NPCs stop buying after about %.1f hours of this: the daily coin cap is shared "
						+ "across every item, so spending it here spends it everywhere", hours)));

		return risks;
	}

	private static List<String> notes(Route chosen, Route other, BazaarProduct product,
			double npcPrice, Limits limits, StrategyContext context) {
		List<String> notes = new ArrayList<>(context.catalog().identityNotes(product.productId()));

		notes.add(chosen.viaOrder()
				? "Route: buy order. The NPC price is fixed, so waiting costs time, not money"
				: "Route: instant buy. Crossing the spread is worth it here");

		if (other != null) {
			notes.add(String.format("%s route instead: %.1f a unit, %s over the session",
					other.viaOrder() ? "Buy order" : "Instant buy",
					other.unitNetProfit(),
					Coins.format(other.totalProfit())));
		}

		// Coins of profit per coin of the shared daily budget. The whole reason an expensive item
		// with a fat per-unit margin can still be the wrong thing to spend the day's cap on.
		notes.add(String.format("Cap efficiency: %.3f coins earned per coin of NPC budget spent",
				chosen.unitNetProfit() / npcPrice));

		// Deliberately does not claim which limit bound the plan unless the cap demonstrably did.
		// Capital, book flow and carrying capacity all size plans too, and a note that names the
		// wrong one is worse than a note that only states the budget.
		if (limits.known()) {
			notes.add(limits.capSized(chosen.units())
					? String.format("Sized by the daily NPC cap: %s of budget left, enough for "
							+ "exactly the %d units above",
							Coins.format(context.npcCapRemaining()), limits.capUnits())
					: String.format("Daily NPC cap: %s of budget left, enough for %d units of this",
							Coins.format(context.npcCapRemaining()), limits.capUnits()));
		}

		if (chosen.orders() > 1L) {
			notes.add(String.format("Needs %d of your %d bazaar order slots: one order holds %d "
							+ "units of this item", chosen.orders(),
					context.fees().bazaarOrderSlots(), limits.maxUnitsPerOrder()));
		}

		notes.add(String.format("Weekly volume: %s units instant-bought, %s instant-sold",
				Coins.format(product.movingWeek().instantBought()),
				Coins.format(product.movingWeek().instantSold())));

		return notes;
	}
}
