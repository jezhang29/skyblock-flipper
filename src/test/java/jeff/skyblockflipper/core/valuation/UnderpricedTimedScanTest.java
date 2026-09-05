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
import jeff.skyblockflipper.core.model.TimedListing;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The bid arithmetic and the ending-soon window are the whole of the scan's job. */
class UnderpricedTimedScanTest {
	private static final Instant NOW = Instant.parse("2026-09-05T00:00:00Z");

	@Test
	void nextBidIsTheStartingBidWhenUncontested() {
		assertEquals(6_000_000L, Bids.nextBid(6_000_000L, 0L));
		assertEquals(6_000_000L, Bids.nextBid(6_000_000L, 6_000_000L));
	}

	@Test
	void nextBidIsTwoAndAHalfPercentOverTheTopBid() {
		// ceil(6_000_000 * 1.025) = 6_150_000
		assertEquals(6_150_000L, Bids.nextBid(1_000_000L, 6_000_000L));
	}

	@Test
	void keepsAnEndingSoonListingDiscountedBelowValueAndDropsAFarOneOut() {
		TimedListing cheapSoon = new TimedListing("a", 6_000_000L, 0L,
				NOW.plusSeconds(1800).toEpochMilli(), "cheap");
		TimedListing cheapButFar = new TimedListing("b", 6_000_000L, 0L,
				NOW.plus(Duration.ofHours(10)).toEpochMilli(), "cheap");

		UnderpricedTimedScan scan = scanPricing(10_000_000L);
		scan.offer(cheapSoon);
		scan.offer(cheapButFar);

		List<PricedBid> results = scan.results();
		assertEquals(1, results.size(), "only the ending-soon listing is a flip");
		assertEquals("a", results.getFirst().listing().uuid());
	}

	/** A scan whose model prices the one item at {@code median}, over a 3h window from NOW. */
	private static UnderpricedTimedScan scanPricing(double median) {
		DecodedItem item = new DecodedItem("SWORD", "Sword", 1, Rarity.EPIC, "", 0, false, 0,
				Map.of(), List.of(), Map.of(), Map.of(), null, null, null, List.of(), "", false, 0L, 0);
		FairValueModel.Builder builder = FairValueModel.builder(NOW, Duration.ofDays(2));
		for (int i = 0; i < 30; i++) {
			builder.add(item, median, NOW.minusSeconds(i).toEpochMilli());
		}

		return new UnderpricedTimedScan(builder.build(), 0.15d, 0.12d, NOW, Duration.ofHours(3),
				bytes -> Optional.of(item));
	}
}
