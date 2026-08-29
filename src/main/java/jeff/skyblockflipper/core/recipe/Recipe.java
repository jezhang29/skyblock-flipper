/*
 * Skyblock Flipper - a Hypixel Skyblock flipping advisor mod.
 * Copyright (C) 2026 SoupChugger
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package jeff.skyblockflipper.core.recipe;

import jeff.skyblockflipper.core.model.UpgradeCost;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One crafting recipe: what it consumes, and how many of what it yields.
 *
 * <p>Hypixel does not publish this. Exactly one item in the 5549-entry catalog carries a
 * {@code recipes} field, so {@code /v2/resources/skyblock/items} cannot answer the question however
 * many fields {@link jeff.skyblockflipper.core.model.dto.ItemsDto} declares. The table therefore
 * comes from the NotEnoughUpdates item dump, imported offline by {@link NeuRecipeImporter} and
 * shipped as a resource that {@link RecipeBook} reads. See {@code docs/craft-flipping.md}.
 *
 * <p><b>Ingredients are summed across grid cells, not read per cell.</b> A recipe that places
 * {@code ENCHANTED_DIAMOND:32} in five of the nine slots consumes 160, and reading any one cell as
 * the total understates the cost by a factor of five - which turns a loss into a headline margin.
 * {@link Ingredients} is what performs that sum, so no caller has to remember to.
 *
 * <p>Ingredient amounts are named by <b>bazaar product id</b>, matching
 * {@link UpgradeCost.Ingredient}, so a craft bill and a star bill price through the same code.
 *
 * @param outputId     bazaar-style item id produced, e.g. {@code ENCHANTED_COMPOST}
 * @param outputCount  units produced per craft; 1 unless the recipe says otherwise
 * @param ingredients  what one craft consumes, one entry per distinct id, cells already summed
 * @param unlockText   NEU's {@code crafttext}, e.g. {@code "Requires: Iron Ingot IX"}, or empty.
 *                     Advisory only - nothing here knows what the player has unlocked.
 */
public record Recipe(
		String outputId,
		int outputCount,
		List<UpgradeCost.Ingredient> ingredients,
		String unlockText
) {
	public Recipe {
		if (outputId == null || outputId.isBlank()) {
			throw new IllegalArgumentException("recipe with no output id");
		}

		if (outputCount < 1) {
			throw new IllegalArgumentException("recipe for " + outputId + " yields " + outputCount);
		}

		ingredients = List.copyOf(ingredients);
		unlockText = unlockText == null ? "" : unlockText;
	}

	/** Whether this recipe consumes {@code productId}. */
	public boolean uses(String productId) {
		for (UpgradeCost.Ingredient ingredient : ingredients) {
			if (ingredient.productId().equals(productId)) {
				return true;
			}
		}

		return false;
	}

	/**
	 * Accumulates grid cells into one bill.
	 *
	 * <p>Exists so the summing rule lives in one place. Both the importer and any future live-menu
	 * capture read a nine-cell grid, and a second implementation is how the two would eventually
	 * disagree about what a recipe costs.
	 */
	public static final class Ingredients {
		private final Map<String, Integer> amounts = new LinkedHashMap<>();

		/**
		 * Adds one grid cell.
		 *
		 * @param productId already normalised to the bazaar's vocabulary
		 * @param amount    units in this cell; must be positive
		 */
		public void add(String productId, int amount) {
			if (productId == null || productId.isBlank() || amount <= 0) {
				return;
			}

			amounts.merge(productId, amount, Integer::sum);
		}

		public boolean isEmpty() {
			return amounts.isEmpty();
		}

		/** Insertion-ordered, so an imported table is byte-stable across runs. */
		public List<UpgradeCost.Ingredient> toList() {
			List<UpgradeCost.Ingredient> list = new ArrayList<>(amounts.size());

			for (Map.Entry<String, Integer> entry : amounts.entrySet()) {
				list.add(new UpgradeCost.Ingredient(entry.getKey(), entry.getValue()));
			}

			return List.copyOf(list);
		}
	}
}
