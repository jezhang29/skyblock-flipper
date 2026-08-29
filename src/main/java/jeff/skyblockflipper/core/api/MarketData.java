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

import jeff.skyblockflipper.core.model.BazaarSnapshot;
import jeff.skyblockflipper.core.model.ItemCatalog;
import jeff.skyblockflipper.core.model.MayorInfo;
import jeff.skyblockflipper.core.valuation.FairValueModel;
import jeff.skyblockflipper.core.valuation.NpcEdgeSnapshot;
import jeff.skyblockflipper.core.valuation.PricedListing;
import jeff.skyblockflipper.core.valuation.TrendSnapshot;
import jeff.skyblockflipper.core.recovery.RecoveryValueModel;
import jeff.skyblockflipper.core.recovery.RecoveryOpportunity;

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
	private final AtomicReference<RecoveryValueModel> recoveryValues =
			new AtomicReference<>(RecoveryValueModel.empty());
	private final AtomicReference<TrendSnapshot> trends = new AtomicReference<>(TrendSnapshot.empty());
	private final AtomicReference<NpcEdgeSnapshot> npcEdges =
			new AtomicReference<>(NpcEdgeSnapshot.empty());
	private final AtomicReference<AuctionScanSnapshot> auctionScan =
			new AtomicReference<>(AuctionScanSnapshot.empty());
	private final AtomicReference<String> lastError = new AtomicReference<>("");
	private final AtomicLong salesRecorded = new AtomicLong();

	/** Written by the tape-maintenance thread, read by whoever asks for status. */
	private volatile int salesRollupDays;
	private volatile int salesRollupEntries;
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
	 * Bumped whenever market state a ranking is derived from is replaced. Readers that cache derived
	 * work (the HUD ranks the whole market) compare this instead of re-deriving on a timer:
	 * candidates cannot change while their inputs have not, so a timer either recomputes identical
	 * results or shows stale ones.
	 *
	 * <p>The book is what moves this most, and {@link #setNpcEdges} moves it too - see there for why
	 * a snapshot arriving has to count as a change even though the book did not.
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

	public RecoveryValueModel recoveryValues() {
		return recoveryValues.get();
	}

	/** Publishes the two models built together from the same streamed sale pass. */
	public void setValues(FairValueModel model, RecoveryValueModel recoveryModel) {
		values.set(model);
		recoveryValues.set(recoveryModel);
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

	/**
	 * How durably each product's bid has sat under the price an NPC pays for it.
	 *
	 * <p>Published far less often than {@link #trends()} because it is a much longer statistic:
	 * rebuilding it reads three days of tape, and a persistence fraction measured over that does not
	 * move between polls.
	 */
	public NpcEdgeSnapshot npcEdges() {
		return npcEdges.get();
	}

	/**
	 * Publishes a rebuilt snapshot, and counts it as a revision.
	 *
	 * <p>The first one of a session changes every NPC price the mod would quote - before it there is
	 * no measured drift, so the chase costs nothing and a premium buys nothing. A cache keyed only on
	 * the book would go on serving the plan it built without it until the next poll happened to
	 * replace the book for some unrelated reason.
	 */
	public void setNpcEdges(NpcEdgeSnapshot snapshot) {
		npcEdges.set(snapshot);
		bazaarRevision.incrementAndGet();
	}

	/** Live listings found below fair value by the last sweep. */
	public List<PricedListing> underpriced() {
		return auctionScan.get().ordinary();
	}

	public List<RecoveryOpportunity> recoveryOpportunities() {
		return auctionScan.get().recovery();
	}

	public AuctionScanSnapshot auctionScan() {
		return auctionScan.get();
	}

	public long recoveryRevision() {
		return auctionScan.get().recoveryRevision();
	}

	/**
	 * @param lastUpdated Hypixel's own stamp for the auction house, so an unchanged house can be
	 *                    skipped rather than re-downloaded
	 */
	public void setAuctionScan(long lastUpdated, List<PricedListing> found, String summary) {
		setAuctionScan(lastUpdated, found, List.of(), summary);
	}

	public void setAuctionScan(long lastUpdated, List<PricedListing> ordinary,
			List<RecoveryOpportunity> recovery, String summary) {
		auctionScan.updateAndGet(previous -> new AuctionScanSnapshot(lastUpdated, Instant.now(),
				ordinary, recovery, previous.ordinaryRevision() + 1L,
				previous.recoveryRevision() + 1L, summary));
	}

	public long auctionsLastUpdated() {
		return auctionScan.get().lastUpdated();
	}

	public Duration auctionsAge() {
		return age(auctionScan.get().scannedAt());
	}

	public boolean hasScannedAuctions() {
		return !auctionScan.get().scannedAt().equals(Instant.EPOCH);
	}

	/** One line describing what the last sweep did, for {@code /flip status}. */
	public String scanSummary() {
		return auctionScan.get().summary();
	}

	public MayorInfo mayor() {
		return mayor.get();
	}

	public void setMayor(MayorInfo info) {
		mayor.set(info);
	}

	/**
	 * How much of the sales tape has been summarised, for status reporting.
	 *
	 * <p>Two counts rather than a formatted line because the answer is a claim about durability -
	 * these days survive retention - and a player who has just enabled the mod should be able to
	 * see it filling in.
	 */
	public void setSalesRollup(int days, int entries) {
		salesRollupDays = days;
		salesRollupEntries = entries;
	}

	public int salesRollupDays() {
		return salesRollupDays;
	}

	public int salesRollupEntries() {
		return salesRollupEntries;
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
