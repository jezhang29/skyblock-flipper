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
