package jeff.skyblockflipper.core.api;

import jeff.skyblockflipper.core.model.BazaarSnapshot;
import jeff.skyblockflipper.core.model.ItemCatalog;
import jeff.skyblockflipper.core.model.MayorInfo;
import jeff.skyblockflipper.core.valuation.FairValueModel;
import jeff.skyblockflipper.core.valuation.PricedListing;
import jeff.skyblockflipper.core.valuation.TrendSnapshot;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
	private final AtomicReference<ItemCatalog> catalog = new AtomicReference<>(ItemCatalog.empty());
	private final AtomicReference<Instant> bazaarFetchedAt = new AtomicReference<>(Instant.EPOCH);
	private final AtomicReference<Instant> salesFetchedAt = new AtomicReference<>(Instant.EPOCH);
	private final AtomicReference<FairValueModel> values = new AtomicReference<>(FairValueModel.empty());
	private final AtomicReference<TrendSnapshot> trends = new AtomicReference<>(TrendSnapshot.empty());
	private final AtomicReference<List<PricedListing>> underpriced = new AtomicReference<>(List.of());
	private final AtomicReference<Instant> auctionsScannedAt = new AtomicReference<>(Instant.EPOCH);
	private final AtomicReference<String> scanSummary = new AtomicReference<>("");
	private final AtomicLong auctionsLastUpdated = new AtomicLong();
	private final AtomicReference<String> lastError = new AtomicReference<>("");
	private final AtomicLong salesRecorded = new AtomicLong();
	private final AtomicLong pollFailures = new AtomicLong();
	private final AtomicLong bazaarRevision = new AtomicLong();

	public BazaarSnapshot bazaar() {
		return bazaar.get();
	}

	public void setBazaar(BazaarSnapshot snapshot) {
		bazaar.set(snapshot);
		bazaarFetchedAt.set(Instant.now());
		bazaarRevision.incrementAndGet();
	}

	/**
	 * Bumped on every book replacement. Readers that cache derived work (the HUD ranks the whole
	 * market) compare this instead of re-deriving on a timer: candidates cannot change while the
	 * book has not, so a timer either recomputes identical results or shows stale ones.
	 */
	public long bazaarRevision() {
		return bazaarRevision.get();
	}

	public ItemCatalog catalog() {
		return catalog.get();
	}

	public void setCatalog(ItemCatalog value) {
		catalog.set(value);
	}

	public FairValueModel values() {
		return values.get();
	}

	public void setValues(FairValueModel model) {
		values.set(model);
	}

	/**
	 * Which way bazaar prices have been moving.
	 *
	 * <p>Published as a frozen snapshot rather than as the live {@code PriceHistory}, which is a
	 * mutable ring the poller thread owns. Everything in this class is an immutable snapshot for
	 * the same reason, and a trend is no different.
	 */
	public TrendSnapshot trends() {
		return trends.get();
	}

	public void setTrends(TrendSnapshot snapshot) {
		trends.set(snapshot);
	}

	/** Live listings found below fair value by the last sweep. */
	public List<PricedListing> underpriced() {
		return underpriced.get();
	}

	/**
	 * @param lastUpdated Hypixel's own stamp for the auction house, so an unchanged house can be
	 *                    skipped rather than re-downloaded
	 */
	public void setAuctionScan(long lastUpdated, List<PricedListing> found, String summary) {
		auctionsLastUpdated.set(lastUpdated);
		underpriced.set(List.copyOf(found));
		scanSummary.set(summary);
		auctionsScannedAt.set(Instant.now());
	}

	public long auctionsLastUpdated() {
		return auctionsLastUpdated.get();
	}

	public Duration auctionsAge() {
		return age(auctionsScannedAt.get());
	}

	public boolean hasScannedAuctions() {
		return !auctionsScannedAt.get().equals(Instant.EPOCH);
	}

	/** One line describing what the last sweep did, for {@code /flip status}. */
	public String scanSummary() {
		return scanSummary.get();
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
