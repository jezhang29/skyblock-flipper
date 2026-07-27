package jeff.skyblockflipper.core.strategy;

import jeff.skyblockflipper.core.model.BazaarSnapshot;
import jeff.skyblockflipper.core.model.ItemCatalog;
import jeff.skyblockflipper.core.pricing.Fees;
import jeff.skyblockflipper.core.valuation.PricedListing;
import jeff.skyblockflipper.core.valuation.TrendSnapshot;

import java.util.List;

/**
 * Everything a strategy is allowed to look at.
 *
 * <p>Passing this in rather than letting strategies reach for globals keeps them pure functions of
 * market state, which is what makes them testable against a fixture.
 *
 * @param underpriced      live listings already matched against realized sales of the same item
 *                         configuration. Pre-computed because finding them means sweeping the whole
 *                         auction house, which is not something a strategy should do per call
 * @param trends           which way bazaar prices have been moving. A live order book says what a
 *                         thing costs but not which direction it is heading, and the two cases pay
 *                         very differently. Empty until enough history has been recorded, which
 *                         every reader must treat as "no signal" rather than as "no trend"
 * @param bankroll         coins available to deploy
 * @param minProfitPerFlip candidates below this are not worth the click
 * @param minConfidence    floor for valuation-derived candidates, which are the only ones resting
 *                         on an estimate rather than on a live order book
 * @param maxAdverseDrift  reject bazaar candidates falling faster than this fraction; 0 disables
 */
public record StrategyContext(
		BazaarSnapshot bazaar,
		ItemCatalog catalog,
		List<PricedListing> underpriced,
		TrendSnapshot trends,
		Fees fees,
		long bankroll,
		long minProfitPerFlip,
		double minConfidence,
		double maxAdverseDrift
) {
	public StrategyContext {
		underpriced = List.copyOf(underpriced);
	}

	/**
	 * For the bazaar-only paths and tests that have no auction scan or price history to hand.
	 *
	 * <p>Defaults to an empty {@link TrendSnapshot}, which every strategy already has to handle:
	 * a client on its first run has no history either.
	 */
	public StrategyContext(BazaarSnapshot bazaar, ItemCatalog catalog, Fees fees,
			long bankroll, long minProfitPerFlip) {
		this(bazaar, catalog, List.of(), TrendSnapshot.empty(), fees,
				bankroll, minProfitPerFlip, 0.0d, 0.0d);
	}

	/**
	 * For the auction paths, which price against realized sales rather than against a live book
	 * and so have no use for a bazaar trend.
	 *
	 * <p>This is the shape the canonical constructor had before price history existed, kept as an
	 * overload so adding a component to the record did not force every caller to state that it
	 * has no trend to offer.
	 */
	public StrategyContext(BazaarSnapshot bazaar, ItemCatalog catalog, List<PricedListing> underpriced,
			Fees fees, long bankroll, long minProfitPerFlip, double minConfidence) {
		this(bazaar, catalog, underpriced, TrendSnapshot.empty(), fees,
				bankroll, minProfitPerFlip, minConfidence, 0.0d);
	}
}
