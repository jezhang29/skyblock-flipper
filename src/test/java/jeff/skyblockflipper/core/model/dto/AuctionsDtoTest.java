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
package jeff.skyblockflipper.core.model.dto;

import com.google.gson.Gson;

import jeff.skyblockflipper.core.model.ActiveListing;
import jeff.skyblockflipper.core.model.TimedListing;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two views over one auctions page split it cleanly by {@code bin}: {@link AuctionsDto#binListings()}
 * takes only buy-it-now, {@link AuctionsDto#timedListings()} only the bid side, and the timed view
 * carries the {@code end} and {@code highest_bid_amount} fields the reachability collection needs.
 */
class AuctionsDtoTest {
	private static final String PAGE = """
			{
			  "success": true,
			  "page": 0,
			  "totalPages": 1,
			  "lastUpdated": 1725300000000,
			  "auctions": [
			    {
			      "uuid": "bin1",
			      "item_name": "Hyperion",
			      "tier": "LEGENDARY",
			      "starting_bid": 900000000,
			      "bin": true,
			      "item_bytes": "BIN_BLOB",
			      "end": 0,
			      "highest_bid_amount": 0
			    },
			    {
			      "uuid": "timed1",
			      "item_name": "Necron's Blade",
			      "tier": "LEGENDARY",
			      "starting_bid": 5000000,
			      "bin": false,
			      "item_bytes": "TIMED_BLOB",
			      "end": 1725303600000,
			      "highest_bid_amount": 7500000
			    },
			    {
			      "uuid": "timed2-nobids",
			      "item_name": "Aspect of the Dragons",
			      "tier": "LEGENDARY",
			      "starting_bid": 2000000,
			      "bin": false,
			      "item_bytes": "TIMED_BLOB_2",
			      "end": 1725302000000,
			      "highest_bid_amount": 0
			    }
			  ]
			}""";

	@Test
	void binAndTimedViewsSplitThePageByBin() {
		AuctionsDto dto = new Gson().fromJson(PAGE, AuctionsDto.class);

		List<ActiveListing> bins = dto.binListings();
		assertEquals(1, bins.size(), "only the one BIN listing");
		assertEquals("bin1", bins.getFirst().uuid());

		List<TimedListing> timed = dto.timedListings();
		assertEquals(2, timed.size(), "both bid auctions, and neither BIN");
		assertTrue(timed.stream().noneMatch(t -> t.uuid().equals("bin1")));
	}

	@Test
	void timedViewCarriesEndAndHighestBid() {
		AuctionsDto dto = new Gson().fromJson(PAGE, AuctionsDto.class);

		TimedListing contested = dto.timedListings().stream()
				.filter(t -> t.uuid().equals("timed1")).findFirst().orElseThrow();
		assertEquals(5_000_000L, contested.startingBid());
		assertEquals(7_500_000L, contested.highestBidAmount());
		assertEquals(1_725_303_600_000L, contested.end());
		assertEquals("TIMED_BLOB", contested.itemBytes());

		TimedListing uncontested = dto.timedListings().stream()
				.filter(t -> t.uuid().equals("timed2-nobids")).findFirst().orElseThrow();
		assertEquals(0L, uncontested.highestBidAmount(), "no bids reports as zero");
	}
}
