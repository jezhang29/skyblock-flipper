package jeff.skyblockflipper.core.pricing;

import jeff.skyblockflipper.core.model.BazaarProduct;
import jeff.skyblockflipper.core.model.BazaarSnapshot;
import jeff.skyblockflipper.core.pricing.CraftQuote.FillHistory;
import jeff.skyblockflipper.core.pricing.FillModel.FillEstimate;
import jeff.skyblockflipper.core.strategy.CombineTable.Entry;

import java.time.Duration;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * What one combinable enchant is worth per hour and per anvil click at the current order book.
 *
 * <p>The trade is one buy leg and one sell leg with dead tiers combined through in between: buy
 * {@code 2^(T-k)} books of the cheapest liquid source tier {@code k}, combine them up to tier
 * {@code T}, and rest a sell offer on {@code T}. See {@code docs/combine-flipping.md} for the
 * measurements and {@code docs/adr/0003-combine-is-its-own-quote.md} for why this is not routed
 * through {@link CraftQuote}.
 *
 * <h2>Why this is its own quote</h2>
 *
 * <p>A combine is a single-ingredient craft, and every part of the arithmetic - the two routes for
 * the source, {@link FillModel} on both legs, {@link Fees} on the sale - is shared with
 * {@link CraftQuote}. It is not routed through it for one reason: <b>the exit book is one-sided.</b>
 * The best combine targets rest a deep ask side and no bid side at all - {@code FEATHER_FALLING_10}
 * had 37 ask orders and 0 bid orders on 2026-08-20 - and {@link CraftQuote#liquid} demands depth on
 * both sides. Routing combine through it would silently reject the books it sells into.
 *
 * <p>So the exit gate here is the target's resting <b>ask</b> order count, {@link #MIN_TARGET_ASK_ORDERS},
 * with no bid-side requirement. That count is what separates a real edge from a single-order fantasy
 * price: on the same snapshot it kept {@code FEATHER_FALLING_10} and rejected
 * {@code STRONG_MANA_10}, a claimed 25M-per-book flip resting on ten ask orders.
 *
 * <h2>The source tier is scanned, not assumed</h2>
 *
 * <p>The cheapest source is not always the bottom tier. On 2026-08-20 the cheapest Rejuvenate 5
 * source was tier 2, not tier 1, because tier 1 carried a fat bid from combiner demand while tier 2
 * did not. {@link #quote} prices every listed source tier and keeps the best <b>by total profit per
 * output book</b>, after tax: the cheapest way to make one top-tier book wins. An earlier rule chose
 * by net per anvil combine, to spend the fewest clicks per coin, but at a few tens of books a day the
 * anvil grind never binds, so it was trading real coins to skip merges that were not scarce - see
 * {@code docs/combine-flipping.md}. {@link #profitPerHour} is still computed honestly, because the
 * unified ranking compares every strategy on it; it is just not what a combine is chosen on.
 * {@link #netPerCombine} is still reported and still orders the {@code /flip combine} list.
 * {@link #quoteFrom} prices one named tier for a caller that wants to compare.
 *
 * <h2>Sell via offer, source via order</h2>
 *
 * <p>The output is sold into an offer one increment under the best ask, never dumped: high-tier
 * spreads are enormous and dumping is negative almost everywhere. The source is bought on a resting
 * order at the bid where the source book has a real bid side ({@link #MIN_TARGET_ASK_ORDERS} orders),
 * which is 3-40% cheaper than the ask, and instant-bought where it has none.
 *
 * <p>Pure and static, like {@link CraftQuote}: same book, same answer.
 *
 * @param entry               the enchant being combined
 * @param sourceTier          the tier bought and combined up from
 * @param route               how the source is acquired
 * @param outputs             top-tier books planned over the horizon, after every limit
 * @param outputsPerHour      sustainable rate: the smaller of the sell demand and the source supply
 * @param sourceCostPerOutput coins to buy the {@code 2^(T-k)} source books for one output
 * @param unitSellPrice       coins per output book before tax: one increment under the best ask
 * @param netPerOutput        profit per output book after tax on the sale
 * @param profitPerHour       the shared ranking axis
 * @param bound               which side holds the rate down
 * @param fill                the sell leg's estimate, carried so a candidate can say whether the
 *                            rate was measured or assumed
 */
public record CombineQuote(
		Entry entry,
		int sourceTier,
		SourceRoute route,
		long outputs,
		double outputsPerHour,
		double sourceCostPerOutput,
		double unitSellPrice,
		double netPerOutput,
		double profitPerHour,
		Bound bound,
		FillEstimate fill
) {
	/** How the source books are acquired. */
	public enum SourceRoute {
		/** Rest a buy order one increment above the best bid. Cheaper, but it waits on dumps. */
		BUY_ORDER,
		/** Take the ask now. Dearer, for a source book with no bid side to rest against. */
		INSTANT_BUY
	}

	/** What holds the plan's rate down. */
	public enum Bound {
		/** The target's sell offer sheds slower than the source arrives. */
		SELL_LEG,
		/** The source books arrive slower than the target sells. */
		SOURCE_SUPPLY,
		/** Coins ran out first. */
		CAPITAL,
		/** The player's order slots ran out first. */
		SLOTS
	}

	/**
	 * Resting ask orders a target must show before its price is believed, and bid orders a source
	 * must show before it may be rested against.
	 *
	 * <p>The same 15 {@link CraftQuote#MIN_ORDERS_PER_SIDE} uses, referenced rather than re-typed so
	 * the two never drift. What differs from craft is only which side it is read on: the combine exit
	 * gate is one-sided, because the target of a combine is sold into instant-buy demand and its bid
	 * side is naturally empty.
	 */
	public static final int MIN_TARGET_ASK_ORDERS = CraftQuote.MIN_ORDERS_PER_SIDE;

	/**
	 * Prices {@code entry} at the current book, scanning every listed source tier and keeping the
	 * one that pays best.
	 *
	 * @param history    measured displacement per product; see {@link FillHistory#none()}
	 * @param horizon    how long the player will leave an order resting
	 * @param maxCapital the most coins this one plan may tie up
	 * @param flowShare  fraction of flow to assume where the tape has not measured it
	 */
	public static Optional<CombineQuote> quote(Entry entry, BazaarSnapshot bazaar, Fees fees,
			FillHistory history, Duration horizon, long maxCapital, double flowShare) {
		if (entry == null || bazaar == null || fees == null || maxCapital <= 0L) {
			return Optional.empty();
		}

		Exit exit = exit(entry, bazaar, history, horizon, flowShare);

		if (exit == null) {
			return Optional.empty();
		}

		CombineQuote best = null;

		for (int tier : entry.sourceTiers()) {
			CombineQuote quote = quoteFrom(entry, tier, bazaar, fees, exit, horizon, maxCapital,
					flowShare);

			// Best by total profit per output book, which is the cheapest source tier after tax. An
			// earlier rule ranked this by net per anvil combine, on the theory that clicks are the
			// scarce resource, and that picked a higher source tier for its fewer merges: Rejuvenate
			// tier 3 (4 books, 3 merges) over tier 2 (8 books, 7 merges). But the volumes here are a few
			// tens of books a day, so the anvil grind never binds and the four saved merges are not
			// worth the ~65k more each cheaper output book pays. Net per combine is still reported and
			// still orders the combine list; it just no longer decides which tier to buy.
			if (quote != null && (best == null || quote.netPerOutput() > best.netPerOutput())) {
				best = quote;
			}
		}

		return Optional.ofNullable(best);
	}

	/** {@link #quote} at the shared 5% flow share, for the caller with no opinion. */
	public static Optional<CombineQuote> quote(Entry entry, BazaarSnapshot bazaar, Fees fees,
			FillHistory history, Duration horizon, long maxCapital) {
		return quote(entry, bazaar, fees, history, horizon, maxCapital, CraftQuote.DEFAULT_FLOW_SHARE);
	}

	/**
	 * Prices {@code entry} sourced from one named tier, or empty when that tier cannot supply a
	 * profitable plan.
	 *
	 * <p>Public so a test, or a caller comparing tiers by hand, can price one corner. {@link #quote}
	 * is what production wants: it tries them all.
	 */
	public static Optional<CombineQuote> quoteFrom(Entry entry, int sourceTier, BazaarSnapshot bazaar,
			Fees fees, FillHistory history, Duration horizon, long maxCapital, double flowShare) {
		if (entry == null || bazaar == null || fees == null || maxCapital <= 0L) {
			return Optional.empty();
		}

		Exit exit = exit(entry, bazaar, history, horizon, flowShare);

		if (exit == null) {
			return Optional.empty();
		}

		return Optional.ofNullable(
				quoteFrom(entry, sourceTier, bazaar, fees, exit, horizon, maxCapital, flowShare));
	}

	/**
	 * The half of the quote every source tier shares: the target book, its offer price, and how fast
	 * that offer sheds. Null when the target alone already refuses, which is every plan's answer too.
	 */
	private record Exit(double sellPrice, double outputsPerHour, FillEstimate fill) {
	}

	private static Exit exit(Entry entry, BazaarSnapshot bazaar, FillHistory history, Duration horizon,
			double flowShare) {
		BazaarProduct target = bazaar.product(entry.targetId()).orElse(null);

		if (target == null || target.sellOffers().isEmpty()
				|| target.sellOfferCount() < MIN_TARGET_ASK_ORDERS) {
			return null;
		}

		double sellPrice = target.undercutSellOffer().orElse(-1.0d);

		if (sellPrice <= 0.0d) {
			return null;
		}

		FillHistory lookup = history == null ? FillHistory.none() : history;
		FillEstimate fill = FillModel.estimate(target, lookup.forProduct(entry.targetId()), horizon,
				flowShare);

		return new Exit(sellPrice, fill.sellUnitsPerHour(), fill);
	}

	/** One whole plan for one source tier, or null when the source cannot supply a profit here. */
	private static CombineQuote quoteFrom(Entry entry, int sourceTier, BazaarSnapshot bazaar, Fees fees,
			Exit exit, Duration horizon, long maxCapital, double flowShare) {
		BazaarProduct source = bazaar.product(entry.bookId(sourceTier)).orElse(null);

		if (source == null || source.sellOffers().isEmpty()) {
			return null;
		}

		long books = entry.booksPerOutput(sourceTier);

		// Both routes for the one source leg, priced and rated, and the better plan of the two kept.
		// A source with a real bid side can rest; every source can be instant-bought if the visible
		// ask covers the books one output needs.
		CombineQuote rested = plan(entry, sourceTier, SourceRoute.BUY_ORDER, source, books, bazaar,
				fees, exit, horizon, maxCapital, flowShare);
		CombineQuote instant = plan(entry, sourceTier, SourceRoute.INSTANT_BUY, source, books, bazaar,
				fees, exit, horizon, maxCapital, flowShare);

		return better(rested, instant);
	}

	private static CombineQuote plan(Entry entry, int sourceTier, SourceRoute route,
			BazaarProduct source, long books, BazaarSnapshot bazaar, Fees fees, Exit exit,
			Duration horizon, long maxCapital, double flowShare) {
		double horizonHours = hoursOf(horizon);

		// The source rate in output books an hour: how fast this leg can supply one output.
		double sourceRate;
		double restUnit = source.outbidBuyOrder().orElse(-1.0d);

		if (route == SourceRoute.BUY_ORDER) {
			// Only rest against a real bid side, and only when there is a bid to outbid.
			if (restUnit <= 0.0d || source.buyOrderCount() < MIN_TARGET_ASK_ORDERS) {
				return null;
			}

			sourceRate = FillModel.estimate(source, null, horizon, flowShare).buyUnitsPerHour() / books;
		} else {
			sourceRate = source.instantBuysPerHour() * flowShare / books;
		}

		double outputsPerHour = Math.min(exit.outputsPerHour(), sourceRate);

		if (outputsPerHour <= 0.0d) {
			return null;
		}

		Bound bound = sourceRate < exit.outputsPerHour() ? Bound.SOURCE_SUPPLY : Bound.SELL_LEG;
		long outputs = Math.max(1L, (long) (outputsPerHour * horizonHours));

		// Cost first at the planned size, then again at whatever the coins fund. Sizing on a cost
		// quoted for a larger order would fund fewer outputs than it claims.
		OptionalDouble cost = sourceCostPerOutput(route, source, books, outputs, restUnit);

		if (cost.isEmpty()) {
			return null;
		}

		long affordable = (long) (maxCapital / cost.getAsDouble());

		if (affordable <= 0L) {
			return null;
		}

		if (affordable < outputs) {
			outputs = affordable;
			bound = Bound.CAPITAL;
			cost = sourceCostPerOutput(route, source, books, outputs, restUnit);

			if (cost.isEmpty()) {
				return null;
			}
		}

		double sourceCost = cost.getAsDouble();
		double netPerOutput = fees.bazaarSaleProceeds(exit.sellPrice()) - sourceCost;

		if (netPerOutput <= 0.0d) {
			return null;
		}

		// The plan cannot turn over faster than it was sized for.
		double ratePerHour = Math.min(outputsPerHour, outputs / horizonHours);

		return new CombineQuote(entry, sourceTier, route, outputs, outputsPerHour, sourceCost,
				exit.sellPrice(), netPerOutput, netPerOutput * ratePerHour, bound, exit.fill());
	}

	/**
	 * Coins to buy the {@code books} source books one output needs, when buying enough for
	 * {@code outputs} of them.
	 *
	 * <p>An instant buy is depth-walked over the whole order, because eating several price levels is
	 * what a real order does and every level below the first is dearer. A resting order sits at one
	 * price and fills there or not at all. Empty when the visible ask cannot cover an instant buy of
	 * that size.
	 */
	private static OptionalDouble sourceCostPerOutput(SourceRoute route, BazaarProduct source,
			long books, long outputs, double restUnit) {
		if (route == SourceRoute.BUY_ORDER) {
			return OptionalDouble.of(books * restUnit);
		}

		OptionalDouble unit = source.costToBuy(Math.multiplyExact(books, outputs));

		return unit.isEmpty() ? OptionalDouble.empty() : OptionalDouble.of(books * unit.getAsDouble());
	}

	/**
	 * The better of the two source routes, by total profit per output book.
	 *
	 * <p>Cheapest source, not fastest, and for the same tier the two routes make the same number of
	 * books an output, so this is the plain "which route costs less" pick. The resting route is
	 * normally both cheaper and fine, but where it is only cheaper - it fills slower because it waits
	 * on dumps - it still wins here, because capital and patience are slack for this player and a
	 * cheaper source is more coins per book. Picking by profit per hour instead would take the instant
	 * route on Green Thumb (283,858 a combine) over the resting one (437,625) for its speed.
	 */
	private static CombineQuote better(CombineQuote first, CombineQuote second) {
		if (first == null) {
			return second;
		}

		if (second == null) {
			return first;
		}

		return first.netPerOutput() >= second.netPerOutput() ? first : second;
	}

	/** Books of the source tier this plan buys in total. */
	public long sourceBooks() {
		return Math.multiplyExact(outputs, entry.booksPerOutput(sourceTier));
	}

	/** Anvil combines the whole plan makes, which is the click cost it is really spending. */
	public long totalCombines() {
		return Math.multiplyExact(outputs, entry.combinesPerOutput(sourceTier));
	}

	/**
	 * Profit per anvil combine, the honest per-effort figure for a player short of clicks.
	 *
	 * <p>Sixteen tier-6 books make one tier-10 in fifteen merges, so a headline net per output book
	 * is fifteen clicks of work. This is what the combine view ranks on even though
	 * {@link #profitPerHour} is the shared axis - see {@code npc-flipping-clicks-are-the-cost}.
	 */
	public double netPerCombine() {
		return netPerOutput / entry.combinesPerOutput(sourceTier);
	}

	/** Coins tied up to run the plan. */
	public long capitalRequired() {
		return Math.round(sourceCostPerOutput * outputs);
	}

	public double totalNetProfit() {
		return netPerOutput * outputs;
	}

	/**
	 * Bazaar order slots the plan occupies, counting one per resting leg: the sell offer, plus the
	 * source order when it rests.
	 *
	 * <p>A lower bound, like {@link CraftQuote#orderSlots}: books do not stack, so a large plan
	 * splits into 256-unit orders. The job layer counts the real orders through {@code Stacking}; at
	 * the sizes these thin books support one order a leg is usually the truth.
	 */
	public int orderSlots() {
		return 1 + (route == SourceRoute.BUY_ORDER ? 1 : 0);
	}

	/** Whether the sell leg's rate came from recorded history rather than an assumed share. */
	public boolean fillMeasured() {
		return fill != null && fill.measured();
	}

	private static double hoursOf(Duration horizon) {
		double hours = horizon == null ? 0.0d : horizon.toMillis() / 3_600_000.0d;

		return hours > 0.0d ? hours : 1.0d;
	}
}
