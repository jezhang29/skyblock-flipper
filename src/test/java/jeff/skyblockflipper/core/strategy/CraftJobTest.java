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
package jeff.skyblockflipper.core.strategy;

import jeff.skyblockflipper.core.model.BazaarProduct;
import jeff.skyblockflipper.core.model.BazaarSnapshot;
import jeff.skyblockflipper.core.model.ItemCatalog;
import jeff.skyblockflipper.core.model.OrderLevel;
import jeff.skyblockflipper.core.model.Stacking;
import jeff.skyblockflipper.core.model.UpgradeCost;
import jeff.skyblockflipper.core.pricing.CraftQuote;
import jeff.skyblockflipper.core.pricing.Fees;
import jeff.skyblockflipper.core.recipe.Recipe;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The clicks a craft quote turns into, and the one thing about them the quote gets wrong.
 *
 * <p>{@link CraftQuote#orderSlots()} counts one order per resting ingredient. A bazaar order holds
 * 71,680 units of something that stacks and <b>256</b> of something that does not, and a craft plan
 * routinely wants tens of thousands of a material, so on an unstackable one that count is out by
 * two orders of magnitude - and the number it produces is small, which is the direction that gets a
 * plan offered rather than refused.
 */
class CraftJobTest {
	private static final Duration HOUR = Duration.ofHours(1);
	private static final long PLENTY = 1_000_000_000L;
	private static final Fees FLIPPER_1 = new Fees(1, false);

	private final Map<String, BazaarProduct> products = new HashMap<>();

	/** A deep book on an item that stacks: one resting order proves it by being over 256 units. */
	private void book(String id, double ask, double bid) {
		level(id, ask, bid, 10_000_000L, 20);
	}

	/**
	 * A book on an item that does not stack, proved the way the game proves it: no resting order on
	 * either side is bigger than {@link Stacking#UNITS_PER_ORDER_UNSTACKABLE}.
	 */
	private void unstackableBook(String id, double ask, double bid) {
		level(id, ask, bid, 256L * 40L, 40);
	}

	private void level(String id, double ask, double bid, long depth, int orders) {
		products.put(id, new BazaarProduct(id,
				List.of(new OrderLevel(ask, depth, orders)),
				List.of(new OrderLevel(bid, depth, orders)),
				new BazaarProduct.MovingWeek(5_000_000L, 5_000_000L)));
	}

	private BazaarSnapshot bazaar() {
		return new BazaarSnapshot(Instant.now(), Map.copyOf(products));
	}

	private static Recipe recipe(String output, int count, Object... idsAndAmounts) {
		List<UpgradeCost.Ingredient> ingredients = new ArrayList<>();

		for (int i = 0; i < idsAndAmounts.length; i += 2) {
			ingredients.add(new UpgradeCost.Ingredient((String) idsAndAmounts[i],
					(Integer) idsAndAmounts[i + 1]));
		}

		return new Recipe(output, count, ingredients, "");
	}

	private CraftJob job(Recipe recipe) {
		return job(recipe, null);
	}

	private CraftJob job(Recipe recipe, CraftQuote.InputRoute route) {
		CraftQuote quote = route == null
				? CraftQuote.quote(recipe, bazaar(), FLIPPER_1, CraftQuote.FillHistory.none(), HOUR,
						PLENTY).orElseThrow()
				: CraftQuote.quote(recipe, bazaar(), FLIPPER_1, CraftQuote.FillHistory.none(), HOUR,
						PLENTY, CraftQuote.DEFAULT_FLOW_SHARE, route).orElseThrow();

		return CraftJob.of(quote, ItemCatalog.empty(), bazaar()).orElseThrow();
	}

	@Test
	void listsTheMaterialsThenTheCraftThenTheOffer() {
		book("OUTPUT", 1_000.0d, 900.0d);
		book("A", 10.0d, 9.0d);
		book("B", 20.0d, 18.0d);

		List<CraftJob.Row> rows = job(recipe("OUTPUT", 1, "A", 1, "B", 1)).rows();

		assertEquals(4, rows.size());
		assertEquals("A", rows.get(0).itemId());
		assertEquals("B", rows.get(1).itemId());
		assertEquals(CraftJob.Action.CRAFT, rows.get(2).action());
		assertEquals(CraftJob.Action.SELL_OFFER, rows.get(3).action());
	}

	/** A recipe yielding four at a time is four crafts' worth of clicks, not sixteen. */
	@Test
	void countsTheCraftRowInCraftsAndTheOfferInUnits() {
		book("OUTPUT", 1_000.0d, 900.0d);
		book("A", 10.0d, 9.0d);

		CraftJob job = job(recipe("OUTPUT", 4, "A", 1));
		CraftJob.Row craft = job.rows().get(1);
		CraftJob.Row offer = job.rows().get(2);

		assertEquals(job.quote().crafts(), craft.units());
		assertEquals(craft.units() * 4L, offer.units());
	}

	/**
	 * The failure this record exists to prevent: a total typed into a box that will not take it.
	 * 58,624 units of a 256-at-a-time material is 229 orders, and the panel has to say so.
	 */
	@Test
	void splitsAnUnstackableMaterialIntoOrdersTheBazaarWillAccept() {
		book("OUTPUT", 100_000.0d, 90_000.0d);
		unstackableBook("A", 10.0d, 9.0d);

		CraftJob.Row material = job(recipe("OUTPUT", 1, "A", 300), CraftQuote.InputRoute.BUY_ORDER)
				.rows().getFirst();

		assertEquals(CraftJob.Action.BUY_ORDER, material.action());
		assertTrue(material.units() > Stacking.UNITS_PER_ORDER_UNSTACKABLE,
				"the fixture has to want more than one order for this test to mean anything");
		assertTrue(material.orderSplit().contains(" x 256"),
				"an unstackable material has to be asked for 256 at a time: " + material.orderSplit());
		assertTrue(material.orders() > 1);
	}

	/** An instant buy is a button press, so it rests nothing and costs no slot. */
	@Test
	void chargesNoSlotForAnInstantBuy() {
		book("OUTPUT", 1_000.0d, 900.0d);
		book("A", 10.0d, 9.0d);

		CraftJob job = job(recipe("OUTPUT", 1, "A", 1), CraftQuote.InputRoute.INSTANT_BUY);

		assertEquals(CraftJob.Action.INSTANT_BUY, job.rows().getFirst().action());
		assertEquals(0, job.rows().getFirst().orders());
	}

	@Test
	void countsSlotsFromTheOrdersRatherThanFromTheIngredients() {
		book("OUTPUT", 1_000.0d, 900.0d);
		book("A", 10.0d, 9.0d);

		CraftJob job = job(recipe("OUTPUT", 1, "A", 1));
		int expected = job.rows().stream().mapToInt(CraftJob.Row::orders).sum();

		assertEquals(expected, job.orderSlots());
		assertTrue(job.orderSlots() >= 1, "the sell offer alone rests on the book");
	}

	/** The line the flip screen prints, from the same row the bazaar panel draws. */
	@Test
	void describesARowAsOneLine() {
		CraftJob.Row row = new CraftJob.Row(CraftJob.Action.BUY_ORDER, "A", "Tarantula Web",
				1061.9d, 512L, "2 x 256", 2);

		assertEquals("Buy Order: Tarantula Web at 1061.9 x512 (2 x 256)", row.describe());
	}
}
