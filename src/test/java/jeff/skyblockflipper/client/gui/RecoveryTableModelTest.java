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
package jeff.skyblockflipper.client.gui;

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
import static org.junit.jupiter.api.Assertions.assertNull;

class RecoveryTableModelTest {
	@Test
	void selectionFollowsAuctionUuidAcrossReorderAndClearsWhenGone() {
		RecoveryOpportunity first = opportunity("first", 100L);
		RecoveryOpportunity second = opportunity("second", 200L);
		RecoveryTableModel model = new RecoveryTableModel();
		model.setRows(List.of(first, second));
		model.select(1);

		model.setRows(List.of(opportunity("second", 250L), opportunity("first", 150L)));

		assertEquals("second", model.selection().auctionUuid());
		assertEquals(0, model.selectedIndex());

		model.setRows(List.of(first));
		assertNull(model.selection());
	}

	private static RecoveryOpportunity opportunity(String uuid, long price) {
		var host = RecoveryFloorCalculator.quote(new RecoveryLeg(RecoveryComponentKind.HOST,
				"HOST", "Host", 1L, RecoveryExitVenue.AH, 1_000L, 0L, 10, 5.0d,
				1_000L, 0L, RecoveryConfidence.MEDIUM, true, Set.of()), 0.10d,
				Fees.none()).orElseThrow();
		return RecoveryFloorCalculator.compose(uuid, "HOST", "Host", price, Instant.EPOCH,
				host, List.of()).orElseThrow();
	}
}
