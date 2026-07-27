package jeff.skyblockflipper.core.model;

import java.util.List;

/**
 * What it costs to add one more star to an item, as bazaar-tradeable ingredients.
 *
 * <p>One of these per star level, in order, so the cost of taking an item from bare to three stars
 * is the first three summed. Hypixel states these costs exactly, which makes star pricing a
 * calculation rather than a regression: unlike a fair value learned from sales, there is no sample
 * size, no dispersion and nothing to be uncertain about except the price of the ingredients.
 *
 * <p><b>Ingredients are named by bazaar product id, not by the wire format's own vocabulary.</b> The
 * API distinguishes {@code {type: ESSENCE, essence_type: SPIDER}} from
 * {@code {type: ITEM, item_id: LUMP_OF_MAGMA}}, but every essence is itself a bazaar product
 * ({@code ESSENCE_SPIDER}), so collapsing both to a product id at the edge means everything
 * downstream prices an ingredient the same way. That translation happens once, in
 * {@link jeff.skyblockflipper.core.model.dto.ItemsDto}.
 *
 * <p>Measured against the live catalog: 544 items carry upgrade costs, drawing on 9 essence types
 * and 43 distinct item ingredients, and <b>all 43 of those items are bazaar products</b>. So every
 * star tier in the game is priceable from the order book alone, with nothing left to estimate.
 */
public record UpgradeCost(List<Ingredient> ingredients) {
	/**
	 * @param productId bazaar product id, already translated from whichever wire form named it
	 * @param amount    units consumed at this star level
	 */
	public record Ingredient(String productId, int amount) {
	}

	public UpgradeCost {
		ingredients = List.copyOf(ingredients);
	}
}
