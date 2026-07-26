package jeff.skyblockflipper.core.strategy;

import jeff.skyblockflipper.core.model.BazaarProduct;

import java.util.ArrayList;
import java.util.List;

/**
 * Bazaar market making: post a buy order, wait, then post a sell offer.
 *
 * <p>The reframe that matters is that this is not flipping, it is providing immediacy. Someone who
 * has been grinding for four hours instant-sells into your buy order because they want coins now;
 * someone who needs materials right now instant-buys from your sell offer. The spread is the fee
 * you charge both of them.
 *
 * <p>The spread arithmetic is the easy part. What separates paper margin from realized margin is
 * the filtering, so most of this class is filters:
 *
 * <ul>
 *   <li><b>Liquidity.</b> Ranked on profit per hour, using the thinner of the two weekly volumes.
 *       A product that only ever gets dumped into is not a two-sided market.</li>
 *   <li><b>Order-book depth.</b> A book held up by a handful of orders is trivially manipulated;
 *       a whale's buy wall evaporates the moment you commit.</li>
 *   <li><b>Implausible spreads.</b> A 40% spread on a liquid item is not free money nobody noticed,
 *       it is a signal that something is wrong with the data or the item.</li>
 * </ul>
 *
 * <p>What this cannot see is adverse selection: your buy orders fill fastest exactly when the price
 * is falling, because that is when people are dumping, and your sell offers fill slowest at the
 * same moment. Realized P&L is therefore systematically worse than the spread measured at order
 * time. Detecting it needs price history, which arrives with the sales tape, so for now it is
 * surfaced as a standing risk note on every candidate rather than silently ignored.
 */
public final class BazaarSpreadStrategy implements FlipStrategy {
	/** Below this weekly volume on the thinner side, fills are too slow to model. */
	private static final long MIN_WEEKLY_VOLUME = 50_000L;

	/**
	 * Books this thin are dominated by a few players and can be pulled at will.
	 *
	 * <p>Counted across the whole returned depth, not the top level. A single price level normally
	 * holds one to three orders even on healthy books, so thresholding the top level alone rejects
	 * effectively the entire market.
	 */
	private static final int MIN_ORDERS_PER_SIDE = 15;

	/** A spread wider than this on a supposedly liquid item means something is off. */
	private static final double MAX_PLAUSIBLE_SPREAD_FRACTION = 0.25d;

	/** Ignore sub-coin items: rounding and the 0.1 undercut dominate the economics. */
	private static final double MIN_UNIT_PRICE = 1.0d;

	private static final double HOURS_PER_WEEK = 168.0d;

	/**
	 * You are one participant among many, so you cannot expect to capture the whole flow. Assume a
	 * modest share of the thinner side's volume actually routes through your orders.
	 */
	private static final double ASSUMED_VOLUME_SHARE = 0.05d;

	@Override
	public StrategyKind kind() {
		return StrategyKind.BAZAAR_SPREAD;
	}

	@Override
	public List<FlipCandidate> findCandidates(StrategyContext context) {
		List<FlipCandidate> candidates = new ArrayList<>();

		for (BazaarProduct product : context.bazaar().products().values()) {
			evaluate(product, context).ifPresent(candidates::add);
		}

		candidates.sort(null);
		return candidates;
	}

	private java.util.Optional<FlipCandidate> evaluate(BazaarProduct product, StrategyContext context) {
		if (product.sellOffers().isEmpty() || product.buyOrders().isEmpty()) {
			return java.util.Optional.empty();
		}

		double buyPrice = product.outbidBuyOrder().orElseThrow();
		double sellPrice = product.undercutSellOffer().orElseThrow();

		if (buyPrice < MIN_UNIT_PRICE || sellPrice <= buyPrice) {
			return java.util.Optional.empty();
		}

		long weeklyVolume = product.bottleneckWeeklyVolume();

		if (weeklyVolume < MIN_WEEKLY_VOLUME) {
			return java.util.Optional.empty();
		}

		if (product.sellOfferCount() < MIN_ORDERS_PER_SIDE
				|| product.buyOrderCount() < MIN_ORDERS_PER_SIDE) {
			// A book resting on almost nothing: the price you see is not the price you get.
			return java.util.Optional.empty();
		}

		double netPerUnit = context.fees().bazaarRoundTripProfit(buyPrice, sellPrice);

		if (netPerUnit <= 0.0d) {
			return java.util.Optional.empty();
		}

		if ((sellPrice - buyPrice) / buyPrice > MAX_PLAUSIBLE_SPREAD_FRACTION) {
			return java.util.Optional.empty();
		}

		// Throughput is the lesser of what the market can absorb and what your coins can fund.
		double marketUnitsPerHour = weeklyVolume / HOURS_PER_WEEK * ASSUMED_VOLUME_SHARE;
		long affordableUnits = (long) (context.bankroll() / buyPrice);

		if (affordableUnits <= 0L) {
			return java.util.Optional.empty();
		}

		// One hour of inventory is the position we are willing to hold, which also caps how much
		// adverse selection can hurt on any single item.
		long units = Math.max(1L, Math.min(affordableUnits, (long) marketUnitsPerHour));
		double profitPerHour = netPerUnit * Math.min(marketUnitsPerHour, units);
		long capital = Math.round(buyPrice * units);

		if (netPerUnit * units < context.minProfitPerFlip()) {
			return java.util.Optional.empty();
		}

		String name = context.catalog().displayName(product.productId());

		return java.util.Optional.of(new FlipCandidate(
				product.productId(),
				name,
				kind(),
				buyPrice,
				sellPrice,
				netPerUnit,
				units,
				capital,
				profitPerHour,
				confidence(weeklyVolume, product),
				steps(name, buyPrice, sellPrice, units),
				risks(product)));
	}

	/**
	 * Confidence rises with liquidity and book depth, because both make the quoted prices more
	 * likely to still be there when you act on them.
	 */
	private static double confidence(long weeklyVolume, BazaarProduct product) {
		double volumeScore = Math.min(1.0d, weeklyVolume / 2_000_000.0d);
		int depth = Math.min(product.sellOfferCount(), product.buyOrderCount());
		double depthScore = Math.min(1.0d, depth / 60.0d);

		return 0.35d + 0.4d * volumeScore + 0.25d * depthScore;
	}

	private static List<String> steps(String name, double buyPrice, double sellPrice, long units) {
		return List.of(
				"Bazaar -> search " + name + " -> Create Buy Order",
				String.format("Set price %.1f and quantity %d", buyPrice, units),
				"Wait for the order to fill; do not chase the price if it moves away",
				String.format("Once filled, Create Sell Offer at %.1f", sellPrice),
				"Cancel and reprice if the book moves against you rather than holding stock");
	}

	private static List<String> risks(BazaarProduct product) {
		List<String> risks = new ArrayList<>();

		// Always present, never currently measurable. Saying so beats implying it is absent.
		risks.add("Buy orders fill fastest while the price is falling; realized margin runs below quoted");

		if (product.bottleneckWeeklyVolume() < 250_000L) {
			risks.add("Thin two-sided flow: fills may take hours");
		}

		int depth = Math.min(product.sellOfferCount(), product.buyOrderCount());

		if (depth < 30) {
			risks.add("Shallow book (" + depth + " resting orders): prone to undercut spirals");
		}

		return risks;
	}
}
