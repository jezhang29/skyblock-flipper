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
package jeff.skyblockflipper.core.pricing;

import jeff.skyblockflipper.core.model.BazaarProduct;
import jeff.skyblockflipper.core.model.OrderLevel;
import jeff.skyblockflipper.core.pricing.FillModel.FillEstimate;
import jeff.skyblockflipper.core.valuation.FillStats;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The fill model's job is to stop the mod quoting throughput it has no reason to expect.
 *
 * <p>The cases that matter are the two limits - an order nobody outbids collects everything, one
 * outbid constantly collects almost nothing - and the fallback, because a fresh install has no
 * history and must still rank the same way the mod did before any of this existed.
 */
class FillModelTest {
	private static final Duration HOUR = Duration.ofHours(1);
	private static final double SHARE = 0.05d;

	/** 1680 instant-sells and 3360 instant-buys a week: 10 and 20 an hour. */
	private static BazaarProduct product() {
		return new BazaarProduct(
				"TEST_ITEM",
				List.of(new OrderLevel(104.0d, 1_000L, 3)),
				List.of(new OrderLevel(100.0d, 1_000L, 3)),
				new BazaarProduct.MovingWeek(3_360L, 1_680L));
	}

	private static FillStats stats(double bidLiftsPerHour, double askDropsPerHour) {
		return new FillStats("TEST_ITEM", bidLiftsPerHour, askDropsPerHour, 24.0d, 288);
	}

	@Test
	void anOrderNobodyOutbidsCollectsTheWholeFlow() {
		FillEstimate fill = FillModel.estimate(product(), stats(0.0d, 0.0d), HOUR, SHARE);

		assertTrue(fill.measured());
		assertEquals(10.0d, fill.buyUnitsPerHour(), 1e-9);
		assertEquals(20.0d, fill.sellUnitsPerHour(), 1e-9);
	}

	@Test
	void beingOutbidConstantlyCollectsAlmostNothing() {
		// Displaced 60 times an hour: the order spends about 1/60th of the hour at the front, so
		// it should see roughly a sixtieth of the flow rather than the flow.
		FillEstimate fill = FillModel.estimate(product(), stats(60.0d, 60.0d), HOUR, SHARE);

		assertEquals(10.0d / 60.0d, fill.buyUnitsPerHour(), 0.01d);
		assertTrue(fill.buyUnitsPerHour() < 1.0d,
				"an order outbid every minute must not be credited with 10 units an hour");
	}

	@Test
	void fillRateFallsAsTheHorizonLengthens() {
		// More horizon means more chances to be displaced, so the *average* rate over it drops even
		// though the total filled rises. Anything else would let a long horizon manufacture
		// throughput out of patience alone.
		FillStats stats = stats(2.0d, 2.0d);

		double shortHorizon = FillModel.estimate(product(), stats, Duration.ofMinutes(15), SHARE)
				.buyUnitsPerHour();
		double longHorizon = FillModel.estimate(product(), stats, Duration.ofHours(6), SHARE)
				.buyUnitsPerHour();

		assertTrue(shortHorizon > longHorizon,
				"expected the average fill rate to fall with a longer horizon, got "
						+ shortHorizon + " then " + longHorizon);
	}

	@Test
	void theUnmeasuredFallbackIsExactlyTheShareOfFlowTheStrategiesUsedBefore() {
		FillEstimate fill = FillModel.estimate(product(), null, HOUR, SHARE);

		assertFalse(fill.measured(), "a guess must never present itself as a measurement");
		assertEquals(10.0d * SHARE, fill.buyUnitsPerHour(), 1e-9);
		assertEquals(20.0d * SHARE, fill.sellUnitsPerHour(), 1e-9);
	}

	@Test
	void aThinlySampledProductIsTreatedAsUnmeasured() {
		// Three samples is not a rate. Taking it as one would let a product the tape has barely
		// seen claim a displacement rate of zero and rank as though it never gets outbid.
		FillStats barely = new FillStats("TEST_ITEM", 0.0d, 0.0d, 0.2d, 3);

		assertFalse(FillModel.estimate(product(), barely, HOUR, SHARE).measured());
	}

	@Test
	void throughputIsTheSlowerLeg() {
		FillEstimate fill = FillModel.estimate(product(), stats(0.0d, 0.0d), HOUR, SHARE);

		// Buying 10 an hour and selling 20 is a 10-an-hour business; the other 10 would be
		// inventory, which is the position the strategy is trying not to hold.
		assertEquals(10.0d, fill.throughputPerHour(), 1e-9);
	}

	@Test
	void aBookNobodyTradesFillsNothing() {
		BazaarProduct dead = new BazaarProduct(
				"DEAD_ITEM",
				List.of(new OrderLevel(104.0d, 1_000L, 3)),
				List.of(new OrderLevel(100.0d, 1_000L, 3)),
				new BazaarProduct.MovingWeek(0L, 0L));

		assertEquals(0.0d, FillModel.estimate(dead, stats(0.0d, 0.0d), HOUR, SHARE).throughputPerHour());
		assertEquals(0.0d, FillModel.estimate(dead, null, HOUR, SHARE).throughputPerHour());
	}

	@Test
	void timeToFillScalesWithSizeAndIsAbsentWhenTheLegNeverClears() {
		FillEstimate fill = FillModel.estimate(product(), stats(0.0d, 0.0d), HOUR, SHARE);

		assertEquals(Duration.ofHours(1), fill.buyTimeToFill(10L).orElseThrow());
		assertEquals(Duration.ofHours(2), fill.buyTimeToFill(20L).orElseThrow());

		FillEstimate dead = FillModel.estimate(
				new BazaarProduct("DEAD_ITEM",
						List.of(new OrderLevel(104.0d, 1_000L, 3)),
						List.of(new OrderLevel(100.0d, 1_000L, 3)),
						new BazaarProduct.MovingWeek(0L, 0L)),
				stats(0.0d, 0.0d), HOUR, SHARE);

		assertTrue(dead.buyTimeToFill(10L).isEmpty(),
				"a leg that never clears must be absent, not an enormous number");
	}
}
