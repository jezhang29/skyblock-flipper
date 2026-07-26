package jeff.skyblockflipper.core.api;

import jeff.skyblockflipper.core.model.BazaarSnapshot;
import jeff.skyblockflipper.core.model.MayorInfo;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Latest market state, written by the poller thread and read by strategies and the UI.
 *
 * <p>Every field is an atomic reference to an immutable snapshot, so readers never see a
 * half-updated book and never need a lock.
 */
public final class MarketData {
	private final AtomicReference<BazaarSnapshot> bazaar = new AtomicReference<>(BazaarSnapshot.empty());
	private final AtomicReference<MayorInfo> mayor = new AtomicReference<>(MayorInfo.unknown());
	private final AtomicReference<Instant> bazaarFetchedAt = new AtomicReference<>(Instant.EPOCH);
	private final AtomicReference<Instant> salesFetchedAt = new AtomicReference<>(Instant.EPOCH);
	private final AtomicReference<String> lastError = new AtomicReference<>("");
	private final AtomicLong salesRecorded = new AtomicLong();
	private final AtomicLong pollFailures = new AtomicLong();

	public BazaarSnapshot bazaar() {
		return bazaar.get();
	}

	public void setBazaar(BazaarSnapshot snapshot) {
		bazaar.set(snapshot);
		bazaarFetchedAt.set(Instant.now());
	}

	public MayorInfo mayor() {
		return mayor.get();
	}

	public void setMayor(MayorInfo info) {
		mayor.set(info);
	}

	public void recordSales(int count) {
		salesRecorded.addAndGet(count);
		salesFetchedAt.set(Instant.now());
	}

	public void recordFailure(String message) {
		pollFailures.incrementAndGet();
		lastError.set(message);
	}

	public void clearError() {
		lastError.set("");
	}

	public long salesRecorded() {
		return salesRecorded.get();
	}

	public long pollFailures() {
		return pollFailures.get();
	}

	public String lastError() {
		return lastError.get();
	}

	/** How stale the order book is, or empty if nothing has been fetched yet. */
	public Duration bazaarAge() {
		return age(bazaarFetchedAt.get());
	}

	public Duration salesAge() {
		return age(salesFetchedAt.get());
	}

	public boolean hasBazaar() {
		return !bazaar.get().isEmpty();
	}

	private static Duration age(Instant at) {
		return at.equals(Instant.EPOCH) ? Duration.ZERO : Duration.between(at, Instant.now());
	}
}
