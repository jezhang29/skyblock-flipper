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
import jeff.skyblockflipper.core.pricing.Fees;
import jeff.skyblockflipper.core.strategy.CombineTable.Entry;
import jeff.skyblockflipper.core.valuation.TrendSnapshot;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the seam between {@link CombineQuote} and the shared candidate shape: that a profitable
 * enchant becomes one candidate, that the click cost the shared shape cannot carry ends up in the
 * notes, and that a book with no catalog entry is still named.
 */
class BazaarCombineStrategyTest {
	private static final Duration HOUR = Duration.ofHours(1);
	private static final long PLENTY = 1_000_000_000L;

	private final Map<String, BazaarProduct> products = new HashMap<>();

	private void book(String id, double ask, double bid, long weeklyBought, long weeklySold) {
		products.put(id, new BazaarProduct(id,
				List.of(new OrderLevel(ask, 1_000_000L, 20)),
				List.of(new OrderLevel(bid, 1_000_000L, 20)),
				new BazaarProduct.MovingWeek(weeklyBought, weeklySold)));
	}

	private void askOnly(String id, double ask, int askOrders, long weeklyBought) {
		products.put(id, new BazaarProduct(id,
				List.of(new OrderLevel(ask, 1_000_000L, askOrders)),
				List.of(),
				new BazaarProduct.MovingWeek(weeklyBought, 0L)));
	}

	private StrategyContext context(boolean combineOn) {
		return new StrategyContext(new BazaarSnapshot(Instant.now(), Map.copyOf(products)),
				ItemCatalog.empty(), List.of(), TrendSnapshot.empty(), Fees.none(), PLENTY, 0L, 0.0d,
				0.0d, HOUR, 1.0d, NpcContext.unlimited(), CraftContext.off(),
				combineOn ? CombineContext.defaults() : CombineContext.off());
	}

	@Test
	void turnsAProfitableCombineIntoOneCandidate() {
		Entry test = new Entry("TEST", 2, 3);
		askOnly("ENCHANTMENT_TEST_3", 1000.0d, 20, 168_000L);
		book("ENCHANTMENT_TEST_2", 150.0d, 100.0d, 168_000L, 168_000L);

		List<FlipCandidate> found =
				new BazaarCombineStrategy(List.of(test)).findCandidates(context(true));

		assertEquals(1, found.size());

		FlipCandidate candidate = found.getFirst();
		assertEquals(StrategyKind.COMBINE, candidate.kind());
		assertEquals("ENCHANTMENT_TEST_3", candidate.itemId());
		assertTrue(candidate.steps().stream().anyMatch(s -> s.startsWith("Combine:")),
				"the anvil step must be shown");
		assertTrue(candidate.notes().stream().anyMatch(s -> s.contains("per anvil combine")),
				"net per combine is the honest axis and must be a note");
	}

	@Test
	void producesNothingWhenCombiningIsOff() {
		Entry test = new Entry("TEST", 2, 3);
		askOnly("ENCHANTMENT_TEST_3", 1000.0d, 20, 168_000L);
		book("ENCHANTMENT_TEST_2", 150.0d, 100.0d, 168_000L, 168_000L);

		assertTrue(new BazaarCombineStrategy(List.of(test)).findCandidates(context(false)).isEmpty());
	}

	@Test
	void namesTheBookFromTheIdWhenTheCatalogHasNone() {
		// Most enchanted books are not in the items resource, so the name is built from the id.
		Entry featherFalling = new Entry("FEATHER_FALLING", 9, 10);
		askOnly("ENCHANTMENT_FEATHER_FALLING_10", 155_000.0d, 37, 693_000L);
		askOnly("ENCHANTMENT_FEATHER_FALLING_9", 30.0d, 20, 168_000L);

		List<FlipCandidate> found =
				new BazaarCombineStrategy(List.of(featherFalling)).findCandidates(context(true));

		assertFalse(found.isEmpty());
		assertEquals("Feather Falling 10", found.getFirst().displayName());
	}

	@Test
	void jobFollowsTheChosenTargetAsThreeStations() {
		// What the bazaar overlay picks up when the player works a combine row: source, anvil, offer.
		Entry featherFalling = new Entry("FEATHER_FALLING", 9, 10);
		askOnly("ENCHANTMENT_FEATHER_FALLING_10", 155_000.0d, 37, 693_000L);
		askOnly("ENCHANTMENT_FEATHER_FALLING_9", 30.0d, 20, 168_000L);

		CombineJob job = new BazaarCombineStrategy(List.of(featherFalling))
				.job("ENCHANTMENT_FEATHER_FALLING_10", context(true)).orElseThrow();

		assertEquals("ENCHANTMENT_FEATHER_FALLING_10", job.targetId());
		assertEquals("Feather Falling 10", job.displayName());
		assertEquals(3, job.rows().size());
		assertEquals(CombineJob.Action.COMBINE, job.rows().get(1).action());
		assertEquals(CombineJob.Action.SELL_OFFER, job.rows().get(2).action());
		// One tier-9 pair combines into one tier-10 in a single merge, so the anvil row counts merges,
		// not output books.
		assertEquals(job.quote().totalCombines(), job.rows().get(1).units());
		assertEquals(job.quote().sourceBooks(), job.rows().getFirst().units());
	}

	@Test
	void jobIsEmptyForATargetNotInTheTable() {
		Entry featherFalling = new Entry("FEATHER_FALLING", 9, 10);
		askOnly("ENCHANTMENT_FEATHER_FALLING_10", 155_000.0d, 37, 693_000L);
		askOnly("ENCHANTMENT_FEATHER_FALLING_9", 30.0d, 20, 168_000L);

		BazaarCombineStrategy strategy = new BazaarCombineStrategy(List.of(featherFalling));

		assertTrue(strategy.job("ENCHANTMENT_SHARPNESS_10", context(true)).isEmpty());
		assertTrue(strategy.job(null, context(true)).isEmpty());
		assertTrue(strategy.job("ENCHANTMENT_FEATHER_FALLING_10", context(false)).isEmpty(),
				"a combine off in config follows nothing");
	}
}
