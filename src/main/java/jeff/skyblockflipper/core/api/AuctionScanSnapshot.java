package jeff.skyblockflipper.core.api;

import jeff.skyblockflipper.core.recovery.RecoveryOpportunity;
import jeff.skyblockflipper.core.valuation.PricedListing;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Ordinary and recovery outputs atomically published from one completed active-AH sweep. */
public record AuctionScanSnapshot(long lastUpdated, Instant scannedAt,
		List<PricedListing> ordinary, List<RecoveryOpportunity> recovery,
		long ordinaryRevision, long recoveryRevision, String summary) {
	public AuctionScanSnapshot {
		Objects.requireNonNull(scannedAt, "scannedAt");
		ordinary = List.copyOf(Objects.requireNonNull(ordinary, "ordinary"));
		recovery = List.copyOf(Objects.requireNonNull(recovery, "recovery"));
		summary = Objects.requireNonNull(summary, "summary");
		if (lastUpdated < 0L || ordinaryRevision < 0L || recoveryRevision < 0L) {
			throw new IllegalArgumentException("invalid auction scan snapshot");
		}
	}

	public static AuctionScanSnapshot empty() {
		return new AuctionScanSnapshot(0L, Instant.EPOCH, List.of(), List.of(), 0L, 0L, "");
	}
}
