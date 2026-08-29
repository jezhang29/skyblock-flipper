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
package jeff.skyblockflipper.client;

import jeff.skyblockflipper.core.api.MarketData;
import jeff.skyblockflipper.core.pricing.Fees;
import jeff.skyblockflipper.core.recovery.RecoveryComponentKind;
import jeff.skyblockflipper.core.recovery.RecoveryConfidence;
import jeff.skyblockflipper.core.recovery.RecoveryExitVenue;
import jeff.skyblockflipper.core.recovery.RecoveryFloorCalculator;
import jeff.skyblockflipper.core.recovery.RecoveryLeg;
import jeff.skyblockflipper.core.recovery.RecoveryOpportunity;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class RecoveryFeedTest {
	@Test
	void refreshesOnlyFromRecoveryRevisionAndKeepsAnImmutableSnapshot() {
		MarketData data = new MarketData();
		RecoveryOpportunity opportunity = opportunity("uuid");
		data.setAuctionScan(1L, List.of(), List.of(opportunity), "one");

		List<RecoveryOpportunity> first = RecoveryFeed.refresh(data);
		List<RecoveryOpportunity> unchanged = RecoveryFeed.refresh(data);

		assertEquals(List.of(opportunity), first);
		assertSame(first, unchanged);

		data.setAuctionScan(2L, List.of(), List.of(), "two");
		assertEquals(List.of(), RecoveryFeed.refresh(data));
	}

	private static RecoveryOpportunity opportunity(String uuid) {
		var host = RecoveryFloorCalculator.quote(new RecoveryLeg(RecoveryComponentKind.HOST,
				"HOST", "Host", 1L, RecoveryExitVenue.AH, 1_000L, 0L, 10, 5.0d,
				1_000L, 0L, RecoveryConfidence.MEDIUM, true, Set.of()), 0.10d,
				Fees.none()).orElseThrow();
		return RecoveryFloorCalculator.compose(uuid, "HOST", "Host", 100L, Instant.EPOCH,
				host, List.of()).orElseThrow();
	}
}
