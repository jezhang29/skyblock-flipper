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
import jeff.skyblockflipper.core.valuation.FillStats;
import jeff.skyblockflipper.core.valuation.TrendSnapshot;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BazaarSpreadStrategyTest {
	@Test
	void spreadCandidateIsQuoteOnlyEvenWhenItsLegacyScoreIsLarge() {
		FlipCandidate candidate = new BazaarSpreadStrategy().findCandidates(context()).getFirst();
		OpportunityPresentation presentation = OpportunityPresentation.of(candidate);

		assertEquals(OutcomeAvailability.QUOTE_ONLY, candidate.outcomeAvailability());
		assertTrue(candidate.profitPerHour() > 0.0d, "fixture must carry a legacy score");
		assertTrue(candidate.confidence() > 0.0d, "fixture must carry legacy confidence");
		assertFalse(presentation.actionable());
		assertTrue(presentation.hourly().isEmpty());
		assertEquals(OpportunityPresentation.PERSONAL_COMPLETION_UNAVAILABLE,
				presentation.completion());
	}

	@Test
	void quoteReferenceQuantityDoesNotChangeWithHorizonOrFillHistory() {
		FlipCandidate shortUnmeasured = new BazaarSpreadStrategy()
				.findCandidates(context(Duration.ofMinutes(15), TrendSnapshot.empty())).getFirst();
		TrendSnapshot measured = new TrendSnapshot(Map.of(),
				Map.of("TEST_ITEM", new FillStats("TEST_ITEM", 120.0d, 120.0d, 24.0d, 288)),
				Map.of(), Duration.ofHours(24), 288, Instant.now());
		FlipCandidate longMeasured = new BazaarSpreadStrategy()
				.findCandidates(context(Duration.ofHours(12), measured)).getFirst();

		assertEquals(shortUnmeasured.units(), longMeasured.units());
		assertEquals(shortUnmeasured.totalNetProfit(), longMeasured.totalNetProfit(), 1e-9d);
		assertEquals(10_000L, shortUnmeasured.units());
	}

	private static StrategyContext context() {
		return context(StrategyContext.DEFAULT_FILL_HORIZON, TrendSnapshot.empty());
	}

	private static StrategyContext context(Duration horizon, TrendSnapshot trends) {
		BazaarProduct product = new BazaarProduct(
				"TEST_ITEM",
				List.of(new OrderLevel(104.0d, 10_000L, 40)),
				List.of(new OrderLevel(100.0d, 10_000L, 40)),
				new BazaarProduct.MovingWeek(5_000_000L, 5_000_000L));

		return new StrategyContext(
				new BazaarSnapshot(Instant.now(), Map.of(product.productId(), product)),
				ItemCatalog.empty(), List.of(), trends, new Fees(0, false), 100_000_000L, 0L,
				0.0d, 0.0d, horizon, StrategyContext.UNCAPPED);
	}
}
