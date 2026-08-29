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
package jeff.skyblockflipper.core.api;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class AuctionScanSnapshotTest {
	@Test
	void marketDataPublishesBothRevisionsInOneImmutableSnapshot() {
		MarketData data = new MarketData();

		data.setAuctionScan(123L, List.of(), List.of(), "complete");
		AuctionScanSnapshot snapshot = data.auctionScan();

		assertEquals(123L, snapshot.lastUpdated());
		assertEquals(1L, snapshot.ordinaryRevision());
		assertEquals(1L, snapshot.recoveryRevision());
		assertEquals("complete", snapshot.summary());
		assertSame(snapshot.ordinary(), data.underpriced());
		assertSame(snapshot.recovery(), data.recoveryOpportunities());
		assertEquals(snapshot.recoveryRevision(), data.recoveryRevision());
	}
}
