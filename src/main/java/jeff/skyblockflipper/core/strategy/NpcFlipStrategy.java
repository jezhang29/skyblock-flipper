package jeff.skyblockflipper.core.strategy;

import jeff.skyblockflipper.core.model.BazaarProduct;
import jeff.skyblockflipper.core.model.ItemCatalog;
import jeff.skyblockflipper.core.model.OrderLevel;
import jeff.skyblockflipper.core.pricing.FillModel;
import jeff.skyblockflipper.core.pricing.FillModel.FillEstimate;
import jeff.skyblockflipper.core.text.Coins;
import jeff.skyblockflipper.core.valuation.NpcEdge;
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
 * is not a bazaar transaction. <b>Every parameter here is measured, and the measurements are in
 * {@code docs/npc-flipping.md}</b> - three days of the user's own bazaar tape, the live items
 * resource, and a holdout backtest.
 *
 * <p><b>There are two ways to acquire the stock, and they are not close.</b>
 *
 * <ul>
 *   <li><b>Instant buy</b> - cross the spread and take the ask. Immediate, but you pay the ask, and
 *       the size is capped by how much of the book sits below the NPC price.</li>
 *   <li><b>Buy order</b> - post a bid and wait for someone to dump into it. You pay the bid instead
 *       of the ask, which on a wide book is most of the profit. Measured on
 *       {@code ENCHANTED_MELON_BLOCK}: instant-buying at 50933.5 against an NPC price of 51200 nets
 *       266 a unit, while a buy order at 49654.2 nets 1545 - 5.8x more per unit. That example
 *       measures the size of the difference; at a 3.0% margin it does not itself clear the margin
 *       floor below. What the order route costs is time, and only that: the NPC price cannot move
 *       away while you wait, so an unfilled order is a cancelled order, not a loss.</li>
 * </ul>
 *
 * <p>Both are evaluated and the better one by profit over the resting window is what gets
 * recommended, with the other reported alongside it.
 *
 * <p><b>The resting route is filtered three ways, and each filter protects order slots rather than
 * coins.</b> With the exit price fixed and a hard chase stop, a gap that vanishes does not lose
 * money - the order simply never fills. What it costs is one of about 21 slots for a whole cycle,
 * and slots are the binding resource.
 *
 * <ul>
 *   <li><b>Persistence.</b> The gap must have been there in 95% of taped samples. On a holdout
 *       backtest none of 161 products above that line realized a loss, against 2 of 22 between
 *       50% and 95%.</li>
 *   <li><b>Margin floor.</b> {@code npcMinMarginRatio}, default 15% of the NPC price. Measured
 *       peak of a sweep: 172.5M a day at 15% against 104.6M at 5% and 161.1M at 20%, where above
 *       20% the pool gets too small to spend the day's budget.</li>
 *   <li><b>Chase cost.</b> Repricing to stay at the front of the book is charged against the margin
 *       before the floor is applied, from measured upward bid drift.</li>
 * </ul>
 *
 * <p>None of the three apply to the instant route, which occupies no slot and settles immediately:
 * the only thing a thin instant margin costs is the click.
 *
 * <p><b>The fill horizon for a resting order is the check-in interval, not the resting window.</b>
 * An order collects flow only while it is at the top of the book, and repricing puts it back there,
 * so what a player who returns every 30 minutes collects is the average over 30 minutes, applied
 * for the whole window. Measured per 8-hour cycle: 11.5M posting once and walking away, 59.7M
 * repricing every 30 minutes, 73.2M staying permanently at the top. The old flat 25% share of flow
 * quoted 40.1M for a plan that would really have made 11.5M.
 *
 * <p><b>Both routes are sized from traded volume, not from the book.</b> Resting depth is a
 * snapshot; a plan measured in units needs a flow. An item with 4000 units resting below the NPC
 * price but 30 units of weekly turnover is not a 4000-unit opportunity, it is a trap that takes a
 * week to exit.
 *
 * <p><b>There is no walking.</b> With a booster cookie {@code /trades} reaches an NPC shop from
 * anywhere, confirmed in play on 2026-08-09, so nothing here is sized in round trips. The daily
 * coin cap is real and hard, but measured against a full basket it does not bind inside one cycle -
 * capital and order slots do, which is what {@code NpcBasket} allocates.
 */
public final class NpcFlipStrategy implements FlipStrategy {
	/** How deep into the ask side to walk before giving up. */
	private static final int MAX_LEVELS = 10;

	/**
	 * Units one bazaar order may cover: 71,680 for a stackable item, 256 for one that is not.
	 *
	 * <p>A plan larger than this is not impossible, it just needs more than one order, and orders
	 * are the resource this whole strategy is competing for. For an unstackable item it binds
	 * immediately: fourteen slots is 3,584 units, whatever the book would have filled.
	 */
	private static final long MAX_UNITS_PER_ORDER_STACKABLE = 71_680L;
	private static final long MAX_UNITS_PER_ORDER_UNSTACKABLE = 256L;

	/**
	 * Below this weekly turnover on the side you are acquiring from, the item does not trade enough
	 * for a plan to mean anything. 223 of the 260 products with a gap clear it.
	 */
	private static final long MIN_WEEKLY_VOLUME = 10_000L;

	/** Share of samples the gap must have been present in. See the class javadoc. */
	private static final double MIN_PERSISTENCE = 0.95d;

	/**
	 * Share of flow a resting order collects where the tape has not measured displacement yet.
	 *
	 * <p>A fallback and nothing more. Where {@code FillStats} exists - 759 products on the live
	 * tape - {@link FillModel} uses measured displacement instead, and the two disagree by 3.5x on
	 * a post-and-wait plan. A candidate resting on this says so in its risks.
	 */
	private static final double UNMEASURED_FILL_SHARE = 0.25d;

	/**
	 * Share of the hour's ask-side flow you can realistically lift.
	 *
	 * <p>You are not the only one watching, and the offers below the NPC price are precisely the
	 * ones being competed for.
	 */
	private static final double INSTANT_TAKE_SHARE = 0.5d;

	/** Instant-sells per hour at which a resting order is as reliable as this strategy gets. */
	private static final double LIQUID_FILL_RATE = 500.0d;

	/** Inventory slots one load through {@code /trades} carries, which is what hauling is counted in. */
	private static final long SLOTS_PER_LOAD = 36L;

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

		for (Priced priced : pricedProducts(context)) {
			evaluate(priced.product(), priced.entry(), priced.npcPrice(), context)
					.ifPresent(candidates::add);
		}

		candidates.sort(null);
		return candidates;
	}

	/**
	 * Every item worth resting a buy order on right now, priced but not sized against order slots.
	 *
	 * <p>What {@link NpcBasket} allocates over. Public so that the basket does not have to
	 * re-implement a filter chain that has to stay identical to the one behind the ranked list:
	 * persistence, the margin floor and the chase charge are decided here, once.
	 *
	 * <p>Unsorted, because the two callers rank differently and for good reason. A ranked list is
	 * asking which single plan is worth the most per hour; a basket is asking what to do with a
	 * fixed number of order slots, which is profit per inventory load.
	 */
	public static List<NpcPlan> restingPlans(StrategyContext context) {
		List<NpcPlan> plans = new ArrayList<>();

		for (Priced priced : pricedProducts(context)) {
			BazaarProduct product = priced.product();

			if (climbingTowardTheNpcPrice(product, context)) {
				continue;
			}

			Limits limits = Limits.of(priced.entry(), priced.npcPrice(), context);

			if (limits.capUnits() <= 0L) {
				continue;
			}

			NpcPlan plan = buyOrderPlan(product, priced.npcPrice(), limits,
					context.npc().edgeFor(product.productId()).orElse(null), context);

			if (plan != null) {
				plans.add(plan);
			}
		}

		return plans;
	}

	/** One bazaar product an NPC will buy, with the catalog entry that says at what price. */
	private record Priced(BazaarProduct product, ItemCatalog.Entry entry, double npcPrice) {
	}

	private static List<Priced> pricedProducts(StrategyContext context) {
		List<Priced> priced = new ArrayList<>();

		if (context.catalog().isEmpty()) {
			return priced;
		}

		for (BazaarProduct product : context.bazaar().products().values()) {
			Optional<ItemCatalog.Entry> entry = context.catalog().get(product.productId());

			if (entry.isEmpty()) {
				continue;
			}

			entry.get().npcPrice()
					.filter(npcPrice -> npcPrice > 0.0d)
					.ifPresent(npcPrice -> priced.add(new Priced(product, entry.get(), npcPrice)));
		}

		return priced;
	}

	/**
	 * One plan per item: whichever acquisition route pays more over the resting window.
	 *
	 * <p>Deliberately not one candidate per route. They are the same trade with two executions, and
	 * listing both would push genuinely different items off a ranked list to show the same item
	 * twice. The losing route is stated in the notes, so the choice is auditable rather than hidden.
	 */
	private Optional<FlipCandidate> evaluate(BazaarProduct product, ItemCatalog.Entry entry,
			double npcPrice, StrategyContext context) {
		if (climbingTowardTheNpcPrice(product, context)) {
			return Optional.empty();
		}

		Limits limits = Limits.of(entry, npcPrice, context);

		if (limits.capUnits() <= 0L) {
			return Optional.empty();
		}

		NpcEdge edge = context.npc().edgeFor(product.productId()).orElse(null);
		Route instant = instantBuyRoute(product, npcPrice, limits, context);
		Route order = Route.resting(buyOrderPlan(product, npcPrice, limits, edge, context), limits);

		Route best = better(instant, order);

		if (best == null || best.profitPerHour(limits.restingHours()) < context.minProfitPerFlip()) {
			return Optional.empty();
		}

		Route other = best == instant ? order : instant;
		String name = context.catalog().displayName(product.productId());

		return Optional.of(new FlipCandidate(
				product.productId(),
				name,
				kind(),
				best.unitCost(),
				npcPrice,
				best.unitNetProfit(),
				best.units(),
				Math.round(best.capital()),
				best.profitPerHour(limits.restingHours()),
				best.confidence(),
				best.steps(name, npcPrice, context.npc()),
				risks(best, product, edge, limits, context),
				notes(best, other, product, npcPrice, edge, limits, context)));
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
				Math.min(limits.capUnits(), (long) (flowPerHour * limits.restingHours())));

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
				: new Route(false, averageCost, averageCost, unitNet, units, units * averageCost,
						0.95d, flowPerHour, false, ceilDiv(units, limits.maxUnitsPerOrder()));
	}

	/**
	 * A bazaar price climbing toward a price that cannot climb with it.
	 *
	 * <p>The only market move that kills this trade, and note the direction: the spread strategies
	 * reject falling items, because they have to sell what they bought. Here a falling bid is a
	 * wider margin.
	 */
	private static boolean climbingTowardTheNpcPrice(BazaarProduct product, StrategyContext context) {
		PriceTrend trend = context.trends().trendFor(product.productId()).orElse(null);

		return trend != null && trend.isUsable() && context.maxAdverseDrift() > 0.0d
				&& trend.isRising(context.maxAdverseDrift());
	}

	/**
	 * Post a bid, reprice it at every check-in, and sell what fills to the NPC.
	 *
	 * <p>The route the basket is built out of, and the one every measured parameter is about. Three
	 * things bound it and each is measured rather than assumed: the gap has to be a standing feature
	 * of the book, the margin net of chasing has to clear the floor, and the size is what the book
	 * actually dumps into a top-of-book order over the resting window.
	 *
	 * <p>Order slots are the one ceiling not applied here, because they are shared - see
	 * {@link NpcPlan#maxUnits()}.
	 */
	private static NpcPlan buyOrderPlan(BazaarProduct product, double npcPrice, Limits limits,
			NpcEdge edge, StrategyContext context) {
		if (product.buyOrders().isEmpty()
				|| product.movingWeek().instantSold() < MIN_WEEKLY_VOLUME) {
			return null;
		}

		NpcContext npc = context.npc();

		// A gap the tape has watched flicker is not worth committing a slot to for a cycle. An
		// unmeasured one is allowed through and says so: a fresh install has no tape at all, and
		// refusing every candidate until it does would be a filter on uptime, not on the trade.
		if (edge != null && !edge.holdsEdge(MIN_PERSISTENCE)) {
			return null;
		}

		double postPrice = product.outbidBuyOrder().orElseThrow();

		if (postPrice <= 0.0d) {
			return null;
		}

		// What staying at the front of the book costs over the window, from measured upward drift.
		// Charged before the floor rather than reported beside it, so the floor filters on what the
		// trade actually pays: eight hours of chasing costs MANTID_CLAW 14.4% of its NPC price,
		// which turns a 30% margin into 15.6%, while CLIPPED_WINGS pays 0.00%.
		double chaseCost = edge == null ? 0.0d : edge.chaseCostRatio(npc.restingWindow()) * npcPrice;
		double unitCost = postPrice + chaseCost;
		double unitNet = npcPrice - unitCost;

		if (unitNet <= 0.0d || unitNet / npcPrice < npc.minMarginRatio()) {
			return null;
		}

		// Horizon is the check-in interval: an order that gets repriced back to the top every 30
		// minutes collects the 30-minute average rate, not the rate of one left alone all cycle.
		FillEstimate fill = FillModel.estimate(
				product,
				context.trends().fillStatsFor(product.productId()).orElse(null),
				npc.checkIn(),
				UNMEASURED_FILL_SHARE);

		double fillPerHour = fill.buyUnitsPerHour();
		long affordable = (long) (context.maxCapitalPerFlip() / unitCost);
		long maxUnits = Math.min(Math.min(affordable, limits.capUnits()),
				(long) (fillPerHour * limits.restingHours()));

		if (maxUnits <= 0L) {
			return null;
		}

		// The NPC leg is certain, so confidence is about the entry: how reliably the gap has been
		// there, and how briskly the item is dumped into. Unmeasured persistence tops out at 0.65,
		// which is the honest reading of "this looks like a flip and nothing has watched it".
		double confidence = 0.50d
				+ 0.30d * (edge == null ? 0.0d : edge.persistence())
				+ 0.15d * Math.min(1.0d, product.instantSellsPerHour() / LIQUID_FILL_RATE);

		return new NpcPlan(
				product.productId(),
				context.catalog().displayName(product.productId()),
				npcPrice,
				postPrice,
				unitCost,
				unitNet,
				maxUnits,
				limits.maxUnitsPerOrder(),
				limits.unitsPerLoad(),
				fillPerHour,
				fill.measured(),
				edge,
				confidence);
	}

	private static Route better(Route a, Route b) {
		if (a == null) {
			return b;
		}

		if (b == null) {
			return a;
		}

		// Both routes are sized over the same window, so comparing totals and comparing rates give
		// the same ordering. Totals avoid dividing by the window twice.
		return a.totalProfit() >= b.totalProfit() ? a : b;
	}

	private static long ceilDiv(long value, long divisor) {
		return divisor <= 0L ? 0L : (value + divisor - 1L) / divisor;
	}

	/**
	 * The ceilings that do not depend on which route is taken, resolved once so both size against
	 * the same numbers.
	 *
	 * @param unitsPerLoad     units one inventory load carries through {@code /trades}
	 * @param restingHours     how long an order may sit, which is what plans are sized over
	 * @param capUnits         units the remaining daily NPC budget can pay for
	 * @param maxUnitsPerOrder units one bazaar order may cover
	 * @param orderSlots       slots NPC plans may rest orders in, settings and account together
	 */
	private record Limits(long unitsPerLoad, double restingHours, long capUnits,
			long maxUnitsPerOrder, int orderSlots) {
		static Limits of(ItemCatalog.Entry entry, double npcPrice, StrategyContext context) {
			NpcContext npc = context.npc();

			return new Limits(
					SLOTS_PER_LOAD * entry.stackSize(),
					npc.restingHours(),
					npc.capUnits(npcPrice),
					entry.unstackable()
							? MAX_UNITS_PER_ORDER_UNSTACKABLE
							: MAX_UNITS_PER_ORDER_STACKABLE,
					npc.orderSlots(context.fees()));
		}

		/** Units that fit in the order slots at all, which only the resting route is bounded by. */
		long maxRestingUnits() {
			return maxUnitsPerOrder * orderSlots;
		}

		/** Whether the budget is what actually held this plan to {@code units}. */
		boolean capSized(long units) {
			return capUnits != Long.MAX_VALUE && units == capUnits;
		}

		/** False when the caller stated no budget, and so nothing about it is worth reporting. */
		boolean capKnown() {
			return capUnits != Long.MAX_VALUE;
		}

		/** Inventory loads the plan hauls through {@code /trades}. */
		long loads(long units) {
			return ceilDiv(units, unitsPerLoad);
		}
	}

	/**
	 * @param viaOrder     true when the stock is acquired with a resting buy order rather than by
	 *                     crossing the spread
	 * @param postPrice    the price to actually type into the bazaar. Below {@code unitCost} on the
	 *                     resting route, because chasing the book upward is part of what you pay
	 *                     but not part of what you post
	 * @param unitCost     expected coins per unit including the chase
	 * @param fillPerHour  units an hour the acquisition leg is expected to bring in
	 * @param fillMeasured whether {@code fillPerHour} came from recorded displacement or from an
	 *                     assumed share of volume. Always false for the instant route, which rests
	 *                     no order and so has nothing to be displaced from
	 * @param orders       bazaar orders the plan needs, given the per-order unit ceiling
	 */
	private record Route(boolean viaOrder, double postPrice, double unitCost, double unitNetProfit,
			long units, double capital, double confidence, double fillPerHour, boolean fillMeasured,
			long orders) {
		/**
		 * One item's whole resting plan, trimmed to the order slots this account has.
		 *
		 * <p>Trimmed rather than refused for wanting too many, which is what this used to do: a
		 * plan needing more orders than the slots allow is a real plan at the size the slots allow,
		 * and the player would place it that way. Refusing it hid every large unstackable item.
		 */
		static Route resting(NpcPlan plan, Limits limits) {
			if (plan == null) {
				return null;
			}

			long units = Math.min(plan.maxUnits(), limits.maxRestingUnits());

			return units <= 0L
					? null
					: new Route(true, plan.postPrice(), plan.unitCost(), plan.unitNetProfit(), units,
							units * plan.unitCost(), plan.confidence(), plan.fillPerHour(),
							plan.fillMeasured(), plan.ordersFor(units));
		}

		double totalProfit() {
			return unitNetProfit * units;
		}

		/**
		 * Average coins an hour across the resting window, which is what the plan is sized over.
		 *
		 * <p>Not the rate while an order is filling. A plan the daily budget truncates to a fraction
		 * of the window reports a fraction of its peak rate, and that is the point: the ranking is
		 * comparing what the window is worth.
		 */
		double profitPerHour(double restingHours) {
			return restingHours <= 0.0d ? 0.0d : totalProfit() / restingHours;
		}

		List<String> steps(String name, double npcPrice, NpcContext npc) {
			List<String> steps = new ArrayList<>(5);

			if (viaOrder) {
				steps.add("Bazaar -> search " + name + " -> Create Buy Order");
				steps.add(orders > 1L
						? String.format("Set price %.1f and quantity %d, split across %d orders "
								+ "because one order cannot hold more", postPrice, units, orders)
						: String.format("Set price %.1f and quantity %d", postPrice, units));
				steps.add(String.format("Check back every %d minutes and move the order back to the "
								+ "top of the book if you have been outbid",
						npc.checkIn().toMinutes()));
				steps.add(String.format("Never reprice above %.1f: past there the margin is under "
								+ "%.0f%% and the slot is worth more elsewhere",
						npc.maxChasePrice(npcPrice), npc.minMarginRatio() * 100.0d));
			} else {
				steps.add(orders > 1L
						? String.format("Bazaar -> search %s -> Instant Buy %d, over %d purchases",
								name, units, orders)
						: "Bazaar -> search " + name + " -> Instant Buy " + units);
			}

			steps.add(String.format("/trades -> any NPC shop -> sell at %.2f coins each", npcPrice));

			if (!viaOrder) {
				steps.add("Re-check the bazaar price before repeating; the ask-side edge closes fast");
			}

			return steps;
		}
	}

	private static List<String> risks(Route route, BazaarProduct product, NpcEdge edge, Limits limits,
			StrategyContext context) {
		List<String> risks = new ArrayList<>();

		if (route.viaOrder()) {
			// The measured figure is what the order is expected to collect; the raw dump rate is
			// what the book sees. Quoting the first where it exists stops the risk line from
			// promising a fill the ranking never assumed.
			risks.add(route.fillMeasured()
					? String.format("Fill is not immediate: your order collects about %.0f units an "
							+ "hour of what gets dumped here", route.fillPerHour())
					: String.format("Fill is not immediate: about %.0f units an hour get dumped "
							+ "into this book, and no displacement has been recorded for it yet, so "
							+ "the size above assumes a flat share of that",
							product.instantSellsPerHour()));
			risks.add("Coins in a resting order are stuck until it fills or you cancel it. Nothing "
					+ "is at risk of loss - the NPC price cannot move - but the slot and the capital "
					+ "are committed for the window");

			if (edge == null) {
				risks.add("No tape history for this item yet, so nothing has checked whether the gap "
						+ "is a standing feature or a flicker. That takes about 17 hours of samples");
			}
		} else {
			risks.add("Edge closes fast once others notice; verify prices before committing");
		}

		if (limits.capSized(route.units())) {
			risks.add(String.format("NPCs stop buying once the day's shared coin budget runs out, "
							+ "and it is what holds this plan to %d units: %s left, spent by the sale "
							+ "price rather than by profit",
					route.units(), Coins.format(context.npc().capRemaining())));
		}

		return risks;
	}

	private static List<String> notes(Route chosen, Route other, BazaarProduct product,
			double npcPrice, NpcEdge edge, Limits limits, StrategyContext context) {
		List<String> notes = new ArrayList<>(context.catalog().identityNotes(product.productId()));

		notes.add(chosen.viaOrder()
				? "Route: buy order. The NPC price is fixed, so waiting costs time, not money"
				: "Route: instant buy. Crossing the spread is worth it here");

		if (other != null) {
			notes.add(String.format("%s route instead: %.1f a unit, %s over the window",
					other.viaOrder() ? "Buy order" : "Instant buy",
					other.unitNetProfit(),
					Coins.format(other.totalProfit())));
		}

		if (edge != null) {
			notes.add(String.format("Edge held in %.1f%% of the last %d taped samples, at a %.1f%% "
							+ "median margin", edge.persistence() * 100.0d, edge.samples(),
					edge.medianMarginRatio() * 100.0d));

			double chaseCost = chosen.unitCost() - chosen.postPrice();

			if (chosen.viaOrder() && chaseCost > 0.0d) {
				notes.add(String.format("Chase cost: %.1f a unit over %.1fh of repricing, %.2f%% of "
								+ "the NPC price, already taken out of the margin above", chaseCost,
						limits.restingHours(), chaseCost / npcPrice * 100.0d));
			}
		}

		// Deliberately does not claim which limit bound the plan unless the cap demonstrably did.
		// Capital, book flow and order slots all size plans too, and a note that names the wrong
		// one is worse than a note that only states the budget.
		if (limits.capKnown()) {
			notes.add(limits.capSized(chosen.units())
					? String.format("Sized by the daily NPC cap: %s of budget left, enough for "
									+ "exactly the %d units above",
							Coins.format(context.npc().capRemaining()), limits.capUnits())
					: String.format("Daily NPC cap: %s of budget left, enough for %d units of this",
							Coins.format(context.npc().capRemaining()), limits.capUnits()));
		}

		if (chosen.orders() > 1L) {
			notes.add(String.format("Needs %d of your %d bazaar order slots: one order holds %d "
							+ "units of this item", chosen.orders(), limits.orderSlots(),
					limits.maxUnitsPerOrder()));
		}

		long loads = limits.loads(chosen.units());

		if (loads > 1L) {
			notes.add(String.format("Hauling: %d inventory loads at %d units a load, all through "
					+ "/trades", loads, limits.unitsPerLoad()));
		}

		notes.add(String.format("Weekly volume: %s units instant-bought, %s instant-sold",
				Coins.format(product.movingWeek().instantBought()),
				Coins.format(product.movingWeek().instantSold())));

		return notes;
	}
}
