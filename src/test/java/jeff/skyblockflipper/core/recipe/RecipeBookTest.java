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

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the shipped table and the reader that loads it.
 *
 * <p>The bundled-resource cases are the ones that matter in play: if the table ever fails to load,
 * the craft strategy must report having no recipes rather than report finding no flips, because
 * those look identical in a candidate list and only one of them is a bug.
 */
class RecipeBookTest {
	private static RecipeBook read(String jsonl) throws IOException {
		return RecipeBook.read(new StringReader(jsonl));
	}

	/** An output is craftable more than one way, so the index must be a list. */
	@Test
	void keepsEveryVariantOfAnOutput() throws IOException {
		RecipeBook book = read("""
				{"out":"ENCHANTED_DIAMOND","count":1,"ing":[{"i":"DIAMOND","n":160}]}
				{"out":"ENCHANTED_DIAMOND","count":160,"ing":[{"i":"ENCHANTED_DIAMOND_BLOCK","n":1}]}
				""");

		List<Recipe> variants = book.forOutput("ENCHANTED_DIAMOND");

		assertEquals(2, variants.size());
		assertEquals(1, book.outputs().size());
		assertTrue(variants.stream().anyMatch(r -> r.outputCount() == 160));
	}

	@Test
	void returnsAnEmptyListForAnUnknownOutput() throws IOException {
		assertTrue(read("").forOutput("NOTHING").isEmpty());
	}

	/**
	 * The table is generated, so a malformed line is a regeneration bug. Losing the other two and a
	 * half thousand recipes over one of them would be the worse failure.
	 */
	@Test
	void skipsALineItCannotUse() throws IOException {
		RecipeBook book = read("""
				{"out":"GOOD","count":1,"ing":[{"i":"X","n":1}]}
				not json at all
				{"out":"","count":1,"ing":[{"i":"X","n":1}]}
				{"out":"NO_INGREDIENTS","count":1,"ing":[]}
				{"out":"ZERO_AMOUNT","count":1,"ing":[{"i":"X","n":0}]}

				{"out":"ALSO_GOOD","count":1,"ing":[{"i":"Y","n":2}]}
				""");

		assertEquals(2, book.size());
		assertFalse(book.forOutput("GOOD").isEmpty());
		assertFalse(book.forOutput("ALSO_GOOD").isEmpty());
		assertTrue(book.forOutput("NO_INGREDIENTS").isEmpty());
		assertTrue(book.forOutput("ZERO_AMOUNT").isEmpty());
	}

	/** A written count of 0 would mean a craft that yields nothing, which is not a real recipe. */
	@Test
	void treatsAMissingCountAsOne() throws IOException {
		assertEquals(1, read("""
				{"out":"X","ing":[{"i":"Y","n":1}]}
				""").forOutput("X").getFirst().outputCount());
	}

	/** The whole point of the class: the shipped resource must actually be there and parse. */
	@Test
	void loadsTheBundledTable() {
		RecipeBook book = RecipeBook.bundled();

		assertFalse(book.isEmpty(), "the bundled recipe table failed to load from " + RecipeBook.RESOURCE);

		// Measured at import time on 2026-08-17. A large drop means the table was regenerated
		// against a changed NEU schema and the counts in NeuRecipeImporter's javadoc need rereading.
		assertTrue(book.size() > 2_000, "only " + book.size() + " recipes in the bundled table");
	}

	/**
	 * Two recipes the user's own tape showed profitable on all 13 days measured. They are here as a
	 * spot check that the table carries a usable bill, not merely a parseable one.
	 */
	@Test
	void carriesRecipesTheTapeMeasured() {
		RecipeBook book = RecipeBook.bundled();

		assertFalse(book.forOutput("ENCHANTED_COMPOST").isEmpty());

		Recipe compactor = book.forOutput("SUPER_COMPACTOR_3000").getFirst();

		assertFalse(compactor.ingredients().isEmpty());

		for (UpgradeCost.Ingredient ingredient : compactor.ingredients()) {
			assertTrue(ingredient.amount() > 0, ingredient.productId() + " costs nothing");
		}
	}

	/** Cached, so the resource is parsed once however many strategies ask for it. */
	@Test
	void cachesTheBundledTable() {
		assertTrue(RecipeBook.bundled() == RecipeBook.bundled());
	}
}
