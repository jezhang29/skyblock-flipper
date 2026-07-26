package jeff.skyblockflipper.core.strategy;

import jeff.skyblockflipper.core.model.BazaarSnapshot;
import jeff.skyblockflipper.core.model.ItemCatalog;
import jeff.skyblockflipper.core.pricing.Fees;
import jeff.skyblockflipper.core.valuation.PricedListing;

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
 * @param bankroll         coins available to deploy
 * @param minProfitPerFlip candidates below this are not worth the click
 * @param minConfidence    floor for valuation-derived candidates, which are the only ones resting
 *                         on an estimate rather than on a live order book
 */
public record StrategyContext(
		BazaarSnapshot bazaar,
		ItemCatalog catalog,
		List<PricedListing> underpriced,
		Fees fees,
		long bankroll,
		long minProfitPerFlip,
		double minConfidence
) {
	public StrategyContext {
		underpriced = List.copyOf(underpriced);
	}

	/** For the bazaar-only paths and tests that have no auction scan to hand. */
	public StrategyContext(BazaarSnapshot bazaar, ItemCatalog catalog, Fees fees,
			long bankroll, long minProfitPerFlip) {
		this(bazaar, catalog, List.of(), fees, bankroll, minProfitPerFlip, 0.0d);
	}
}
