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
