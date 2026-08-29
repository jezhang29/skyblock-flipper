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
