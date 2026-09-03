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
package jeff.skyblockflipper.core.valuation;

import jeff.skyblockflipper.core.item.DecodedItem;
import jeff.skyblockflipper.core.item.Rarity;
import jeff.skyblockflipper.core.model.ActiveListing;
import jeff.skyblockflipper.core.pricing.Fees;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The supply counter is a data-gathering probe: it never decodes and never plans a trade. These
 * tests pin the three conditions a coarse key must meet to be logged - several listings under fair
 * value, a fast enough sale rate, and a gap to the next tier that clears fees - and that failing any
 * one drops it.
 */
class SupplyCounterTest {
	private static final Instant NOW = Instant.parse("2026-07-26T12:00:00Z");
	private static final Duration WINDOW = Duration.ofHours(48);
	private static final Fees FEES = new Fees(0, false);

	// One controllable configuration, the way UnderpricedScanTest does it: a bare item whose coarse
	// median is exactly the sales added, so the test sets fair value directly.
	private static final DecodedItem HOST = new DecodedItem("HOST", "Host", 1, Rarity.LEGENDARY,
			"", 0, false, 0, Map.of(), List.of(), Map.of(), Map.of(), null, null, null, "",
			false, 0L);

	private static FairValueModel model(long median, int sales, Duration window) {
		FairValueModel.Builder builder = FairValueModel.builder(NOW, window);
		for (int i = 0; i < sales; i++) {
			builder.add(HOST, (double) median, NOW.minusSeconds(i * 60L).toEpochMilli());
		}
		return builder.build();
	}

	private static ActiveListing listing(long price) {
		return new ActiveListing("host-" + price, "Host", Rarity.LEGENDARY, price, "unused");
	}

	@Test
	void flagsAFloorClusterWithAGapThatClearsFees() {
		// Fair value 9M, sold fast enough. Two listings sit well under it; the next tier is a 12M
		// listing above them, so sweeping the cheap pair and relisting under 12M clears fees.
		SupplyCounter counter = new SupplyCounter(model(9_000_000L, 20, WINDOW), FEES);
		counter.observe(listing(5_000_000L));
		counter.observe(listing(5_100_000L));
		counter.observe(listing(12_000_000L));

		SupplySignal signal = counter.signals().getFirst();
		assertEquals("Host", signal.itemName());
		assertEquals(3, signal.listings());
		assertEquals(2, signal.belowFair());
		assertEquals(5_000_000L, signal.cheapestPrice());
		assertEquals(5_100_000L, signal.cheapClusterMax());
		assertEquals(12_000_000L, signal.nextTier());
		assertTrue(signal.profitPerUnit() > 0L);
	}

	@Test
	void fallsBackToFairValueWhenNothingRestsAboveTheCluster() {
		// The whole live supply is under fair value, so the median is the only exit to relist under.
		SupplyCounter counter = new SupplyCounter(model(9_000_000L, 20, WINDOW), FEES);
		counter.observe(listing(4_000_000L));
		counter.observe(listing(4_500_000L));

		SupplySignal signal = counter.signals().getFirst();
		assertEquals(9_000_000L, signal.nextTier());
		assertEquals(4_500_000L, signal.cheapClusterMax());
	}

	@Test
	void ignoresAKeyWithOnlyOneListingBelowFair() {
		// A single cheap listing is a snipe the ordinary scan already finds, not a floor to sweep.
		SupplyCounter counter = new SupplyCounter(model(9_000_000L, 20, WINDOW), FEES);
		counter.observe(listing(4_000_000L));
		counter.observe(listing(12_000_000L));

		assertTrue(counter.signals().isEmpty());
	}

	@Test
	void ignoresASlowMovingKey() {
		// Same cheap cluster, but 20 sales spread over 200 hours is 0.1/h - under the 0.2 floor, so a
		// swept relist would park capital longer than the gap is worth.
		SupplyCounter counter = new SupplyCounter(
				model(9_000_000L, 20, Duration.ofHours(200)), FEES);
		counter.observe(listing(5_000_000L));
		counter.observe(listing(5_100_000L));
		counter.observe(listing(12_000_000L));

		assertTrue(counter.signals().isEmpty());
	}

	@Test
	void ignoresAGapTooSmallToSurviveFees() {
		// Two listings a hair under fair value, nothing above: the gap up to the median does not clear
		// the round-trip fee stack, so sweeping it would shred coins.
		SupplyCounter counter = new SupplyCounter(model(9_000_000L, 20, WINDOW), FEES);
		counter.observe(listing(8_900_000L));
		counter.observe(listing(8_950_000L));

		assertTrue(counter.signals().isEmpty());
	}

	@Test
	void ignoresAKeyItHasNeverSeenSell() {
		// No sales recorded for this coarse key, so there is nothing to call the listings cheap
		// against. An empty model reports no fair value at all.
		SupplyCounter counter = new SupplyCounter(model(9_000_000L, 20, WINDOW), FEES);
		counter.observe(new ActiveListing("other", "Ghost", Rarity.EPIC, 1_000_000L, "unused"));
		counter.observe(new ActiveListing("other2", "Ghost", Rarity.EPIC, 1_100_000L, "unused"));

		assertTrue(counter.signals().isEmpty());
	}

	@Test
	void ranksTheWidestGapFirst() {
		SupplyCounter counter = new SupplyCounter(model(9_000_000L, 20, WINDOW), FEES);
		// Same key, three listings: the cheap pair 5.0/5.1M and a 12M next tier ranks by its wide gap.
		counter.observe(listing(5_000_000L));
		counter.observe(listing(5_100_000L));
		counter.observe(listing(12_000_000L));

		List<SupplySignal> signals = counter.signals();
		assertEquals(1, signals.size());
		assertTrue(signals.getFirst().profitPerUnit() > 0L);
	}
}
