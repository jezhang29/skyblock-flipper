package jeff.skyblockflipper.core.strategy;

import jeff.skyblockflipper.core.model.BazaarSnapshot;
import jeff.skyblockflipper.core.model.ItemCatalog;
import jeff.skyblockflipper.core.pricing.Fees;

/**
 * Everything a strategy is allowed to look at.
 *
 * <p>Passing this in rather than letting strategies reach for globals keeps them pure functions of
 * market state, which is what makes them testable against a fixture.
 *
 * @param bankroll         coins available to deploy
 * @param minProfitPerFlip candidates below this are not worth the click
 */
public record StrategyContext(
		BazaarSnapshot bazaar,
		ItemCatalog catalog,
		Fees fees,
		long bankroll,
		long minProfitPerFlip
) {
}
