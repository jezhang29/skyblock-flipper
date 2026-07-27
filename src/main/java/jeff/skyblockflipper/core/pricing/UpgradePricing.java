package jeff.skyblockflipper.core.pricing;

import jeff.skyblockflipper.core.model.BazaarProduct;
import jeff.skyblockflipper.core.model.BazaarSnapshot;
import jeff.skyblockflipper.core.model.ItemCatalog;
import jeff.skyblockflipper.core.model.UpgradeCost;

import java.util.Optional;
import java.util.OptionalDouble;

/**
 * What the stars on an item cost, in coins, at the current order book.
 *
 * <p>This is the one number in the mod that is neither observed nor estimated but <i>derived</i>.
 * Hypixel publishes the essence and material cost of every star level, and every one of those
 * ingredients trades on the bazaar, so the cost of starring an item is arithmetic over a live book.
 * There is no sample count, no dispersion and no confidence to discount - only the ingredient
 * prices, which are as current as the last poll.
 *
 * <p><b>Priced on the ask side, deliberately.</b> The question this answers is "what would it cost
 * to put these stars on myself, right now", and doing it right now means instant-buying the
 * essence. A resting buy order would fill cheaper - essence spreads run about 3% - so this is the
 * conservative end, which is the correct direction to be wrong in when the number is about to be
 * compared against somebody's asking price.
 *
 * <p>What it is <b>not</b> is a valuation. Starred items do not trade at the cost of their stars,
 * any more than a crafted item trades at the cost of its materials; the market pays a premium for
 * the work, or occasionally less than cost when essence has moved. Turning this into a fair value
 * is the synthesized-estimate problem, and it stays out of here.
 */
public final class UpgradePricing {
	private UpgradePricing() {
	}

	/**
	 * The cost of a run of stars, and the ingredient flow behind it.
	 *
	 * @param stars how many levels this covers, counting from bare
	 * @param coins total cost to buy every ingredient for those levels at the current ask
	 */
	public record StarQuote(int stars, double coins) {
		/** Average cost of a star across the run, for the "is the premium reasonable" question. */
		public double coinsPerStar() {
			return stars <= 0 ? 0.0d : coins / stars;
		}
	}

	/**
	 * Cost of taking {@code entry} from bare to {@code stars} stars.
	 *
	 * <p>Returns empty rather than a partial total when any single ingredient cannot be priced. A
	 * star cost that silently omits a component it could not find is exactly the plausible-looking
	 * wrong number this codebase exists to avoid: it would understate the cost, which on this
	 * particular number always reads as a better deal than it is.
	 *
	 * @param stars stars to price. Clamped to nothing at or below zero, and empty if the item does
	 *              not define that many levels
	 */
	public static Optional<StarQuote> quoteStars(ItemCatalog.Entry entry, int stars,
			BazaarSnapshot bazaar) {
		if (entry == null || stars <= 0 || stars > entry.maxStars()) {
			return Optional.empty();
		}

		double total = 0.0d;

		for (UpgradeCost level : entry.upgradeCosts().subList(0, stars)) {
			for (UpgradeCost.Ingredient ingredient : level.ingredients()) {
				OptionalDouble ask = bazaar.product(ingredient.productId())
						.map(BazaarProduct::instantBuyPrice)
						.orElse(OptionalDouble.empty());

				if (ask.isEmpty()) {
					return Optional.empty();
				}

				total += ask.getAsDouble() * ingredient.amount();
			}
		}

		return Optional.of(new StarQuote(stars, total));
	}
}
