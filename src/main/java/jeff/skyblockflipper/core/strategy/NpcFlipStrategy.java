package jeff.skyblockflipper.core.strategy;

import jeff.skyblockflipper.core.model.BazaarProduct;
import jeff.skyblockflipper.core.model.ItemCatalog;
import jeff.skyblockflipper.core.model.OrderLevel;
import jeff.skyblockflipper.core.pricing.FillModel;
import jeff.skyblockflipper.core.pricing.FillModel.FillEstimate;
import jeff.skyblockflipper.core.text.Coins;

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
 */
public final class NpcFlipStrategy implements FlipStrategy {
	/** How deep into the ask side to walk before giving up. */
	private static final int MAX_LEVELS = 10;

	/**
	 * Inventory capacity for one trip: 36 usable slots at a 64 stack.
	 *
	 * <p>This is the constraint that makes NPC flipping much smaller than it first looks. Buying is
	 * instant and unbounded, but selling is manual, one inventory at a time, so the bazaar side of
	 * the book is almost never the binding limit.
	 */
	private static final long UNITS_PER_TRIP = 36L * 64L;

	/** Round trips an attentive player manages per hour: buy, walk to the NPC, sell, return. */
	private static final long TRIPS_PER_HOUR = 12L;

	private static final long UNITS_PER_HOUR = UNITS_PER_TRIP * TRIPS_PER_HOUR;

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
					.flatMap(npcPrice -> evaluate(product, npcPrice, context))
					.ifPresent(candidates::add);
		}

		candidates.sort(null);
		return candidates;
	}

	/**
	 * One plan per item: whichever acquisition route pays more per hour.
	 *
	 * <p>Deliberately not one candidate per route. They are the same trade with two executions, and
	 * listing both would push genuinely different items off a ranked list to show the same item
	 * twice. The losing route is stated in the notes, so the choice is auditable rather than hidden.
	 */
	private Optional<FlipCandidate> evaluate(BazaarProduct product, double npcPrice,
			StrategyContext context) {
		if (npcPrice <= 0.0d) {
			return Optional.empty();
		}

		Route instant = instantBuyRoute(product, npcPrice, context);
		Route order = buyOrderRoute(product, npcPrice, context);

		Route best = better(instant, order);

		if (best == null || best.profitPerHour() < context.minProfitPerFlip()) {
			return Optional.empty();
		}

		Route other = best == instant ? order : instant;
		String name = context.catalog().displayName(product.productId());
		long trips = (best.units() + UNITS_PER_TRIP - 1L) / UNITS_PER_TRIP;

		return Optional.of(new FlipCandidate(
				product.productId(),
				name,
				kind(),
				best.unitCost(),
				npcPrice,
				best.unitNetProfit(),
				best.units(),
				Math.round(best.capital()),
				// Already sized to one hour, so the whole profit is the hourly rate.
				best.profitPerHour(),
				best.confidence(),
				best.steps(name, npcPrice, trips),
				risks(best, product, trips),
				notes(best, other, product, context)));
	}

	/**
	 * Cross the spread: walk the ask side and take every level priced under the NPC.
	 *
	 * <p>Each level is only worth taking while it is cheaper than the NPC pays, so the walk stops at
	 * the first one that is not.
	 */
	private static Route instantBuyRoute(BazaarProduct product, double npcPrice, StrategyContext context) {
		if (product.sellOffers().isEmpty()
				|| product.movingWeek().instantBought() < MIN_WEEKLY_VOLUME) {
			return null;
		}

		long budget = context.bankroll();
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
		// Manual selling caps the hour as hard as the market does: buying is instant, but the stock
		// still leaves one inventory at a time.
		long units = Math.min(depthUnits, Math.min(UNITS_PER_HOUR, (long) flowPerHour));

		if (units <= 0L) {
			return null;
		}

		// Cost stays the average over the whole walk even when the plan is truncated. Truncation
		// keeps the cheapest levels, so the real average would be lower - this errs toward quoting
		// less profit than the trade makes, which is the only safe direction to be wrong in.
		double unitNet = npcPrice - averageCost;

		return unitNet <= 0.0d
				? null
				: new Route(false, averageCost, unitNet, units, units * averageCost, 0.95d,
						flowPerHour, false);
	}

	/**
	 * Post a bid and wait. Pays the bid instead of the ask, and fills only as fast as people dump.
	 */
	private static Route buyOrderRoute(BazaarProduct product, double npcPrice, StrategyContext context) {
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
		long affordable = (long) (context.bankroll() / bid);
		long units = Math.min(affordable, Math.min(UNITS_PER_HOUR, (long) fillPerHour));

		if (units <= 0L) {
			return null;
		}

		// The NPC leg is certain; only the fill is not, so confidence tracks how briskly the item
		// is actually being dumped rather than anything about the price.
		double confidence = 0.55d + 0.30d
				* Math.min(1.0d, product.instantSellsPerHour() / LIQUID_FILL_RATE);

		return new Route(true, bid, unitNet, units, units * bid, confidence, fillPerHour,
				fill.measured());
	}

	private static Route better(Route a, Route b) {
		if (a == null) {
			return b;
		}

		if (b == null) {
			return a;
		}

		return a.profitPerHour() >= b.profitPerHour() ? a : b;
	}

	/**
	 * @param viaOrder      true when the stock is acquired with a resting buy order rather than by
	 *                      crossing the spread
	 * @param fillPerHour   units an hour the acquisition leg is expected to bring in
	 * @param fillMeasured  whether {@code fillPerHour} came from recorded displacement or from an
	 *                      assumed share of volume. Always false for the instant route, which rests
	 *                      no order and so has nothing to be displaced from
	 */
	private record Route(boolean viaOrder, double unitCost, double unitNetProfit, long units,
			double capital, double confidence, double fillPerHour, boolean fillMeasured) {
		double profitPerHour() {
			return unitNetProfit * units;
		}

		List<String> steps(String name, double npcPrice, long trips) {
			List<String> steps = new ArrayList<>(4);

			if (viaOrder) {
				steps.add("Bazaar -> search " + name + " -> Create Buy Order");
				steps.add(String.format("Set price %.1f and quantity %d, then wait for the fill",
						unitCost, units));
			} else {
				steps.add("Bazaar -> search " + name + " -> Instant Buy " + units);
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

	private static List<String> risks(Route route, BazaarProduct product, long trips) {
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
			risks.add("Needs " + trips + " inventory trips: assumes 36 slots at a 64 stack");
		}

		return risks;
	}

	private static List<String> notes(Route chosen, Route other, BazaarProduct product,
			StrategyContext context) {
		List<String> notes = new ArrayList<>(context.catalog().identityNotes(product.productId()));

		notes.add(chosen.viaOrder()
				? "Route: buy order. The NPC price is fixed, so waiting costs time, not money"
				: "Route: instant buy. Crossing the spread is worth it here");

		if (other != null) {
			notes.add(String.format("%s route instead: %.1f a unit, %s an hour",
					other.viaOrder() ? "Buy order" : "Instant buy",
					other.unitNetProfit(),
					Coins.format(other.profitPerHour())));
		}

		notes.add(String.format("Weekly volume: %s units instant-bought, %s instant-sold",
				Coins.format(product.movingWeek().instantBought()),
				Coins.format(product.movingWeek().instantSold())));

		return notes;
	}
}
