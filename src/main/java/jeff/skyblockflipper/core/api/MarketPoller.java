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

import jeff.skyblockflipper.core.config.ScanSettings;
import jeff.skyblockflipper.core.model.BazaarSample;
import jeff.skyblockflipper.core.model.BazaarSnapshot;
import jeff.skyblockflipper.core.model.ItemCatalog;
import jeff.skyblockflipper.core.model.SaleDailyStat;
import jeff.skyblockflipper.core.tape.BazaarTape;
import jeff.skyblockflipper.core.tape.SalesTape;
import jeff.skyblockflipper.core.valuation.FairValueModel;
import jeff.skyblockflipper.core.valuation.NpcEdgeHistory;
import jeff.skyblockflipper.core.valuation.NpcEdgeSnapshot;
import jeff.skyblockflipper.core.valuation.PriceHistory;
import jeff.skyblockflipper.core.valuation.SupplyCounter;
import jeff.skyblockflipper.core.valuation.SupplySignal;
import jeff.skyblockflipper.core.valuation.UnderpricedScan;
import jeff.skyblockflipper.core.recovery.RecoveryValuationModels;
import jeff.skyblockflipper.core.recovery.RecoveryListingScan;
import jeff.skyblockflipper.core.recovery.RecoveryScanPolicy;
import jeff.skyblockflipper.core.pricing.Fees;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Background polling loop for the Hypixel endpoints.
 *
 * <p>Lives in {@code core} on purpose: it touches no Minecraft class, so the whole data pipeline
 * can be driven from a plain {@code main} method or a test without launching a game.
 *
 * <p>Cadences are matched to how fast the upstream data actually changes. Polling faster than the
 * server-side cache just burns rate limit to re-download identical bytes.
 */
public final class MarketPoller implements AutoCloseable {
	/**
	 * The ended-auctions window is about 60 seconds wide and non-recoverable, so this runs slightly
	 * faster than the window to guarantee overlap. Duplicates are cheap; a gap is permanent.
	 */
	private static final Duration SALES_INTERVAL = Duration.ofSeconds(45);

	/** Elections move on the order of days. */
	private static final Duration MAYOR_INTERVAL = Duration.ofMinutes(10);

	/** Item definitions only change with game updates. */
	private static final Duration ITEMS_INTERVAL = Duration.ofHours(6);

	private static final Duration PRUNE_INTERVAL = Duration.ofHours(6);

	/**
	 * Hypixel regenerates the auction house about once a minute, and an unchanged sweep is skipped
	 * after one page, so this costs one request when nothing has moved.
	 */
	private static final Duration AUCTION_INTERVAL = Duration.ofSeconds(60);

	/**
	 * Rebuilding valuations means replaying and decoding a couple of days of tape. Item values do
	 * not move fast enough to justify doing that more often than this.
	 */
	private static final Duration VALUATION_INTERVAL = Duration.ofMinutes(10);

	/**
	 * How often bazaar top-of-book is written to the tape.
	 *
	 * <p>Far slower than the book is fetched by default, and that is the point: the trend indicators
	 * cannot resolve twenty-second granularity, so taping every poll would cost roughly fifteen times
	 * the disk to answer exactly the same questions.
	 *
	 * <p>Nothing breaks if {@code bazaarPollSeconds} is raised past this. The tape dedupes on
	 * Hypixel's own stamp, so a sample the poll has not refreshed writes nothing, and the effective
	 * tape cadence just becomes the poll cadence. That is the setting a headless collector wants:
	 * fetch only what will actually be recorded.
	 */
	private static final Duration BAZAAR_TAPE_INTERVAL = Duration.ofMinutes(5);

	/**
	 * How far back the NPC edge measurement looks, and how often it is redone.
	 *
	 * <p>Three days is what every parameter in {@code docs/npc-flipping.md} was measured over, and
	 * comfortably more than the seventeen hours {@code NpcEdge.MIN_SAMPLES} needs. Redoing it means
	 * re-reading and parsing that whole window - a couple of million taped lines - so it runs on the
	 * maintenance thread and no more often than a three-day statistic can meaningfully change.
	 */
	private static final Duration NPC_EDGE_WINDOW = Duration.ofDays(3);
	private static final Duration NPC_EDGE_INTERVAL = Duration.ofHours(2);

	/** A supply log is for studying a distribution, not scrolling one; the rest is a count. */
	private static final int MAX_SUPPLY_SIGNALS_LOGGED = 20;

	private final HypixelApi api;
	private final MarketData data;
	private final SalesTape tape;
	private final BazaarTape bazaarTape;
	private final Supplier<ScanSettings> settings;
	private final Consumer<String> log;

	/**
	 * The live price series. Mutable and owned by this class alone - readers get the immutable
	 * {@code TrendSnapshot} published onto {@link MarketData} after every append.
	 */
	private final PriceHistory history;

	private ScheduledExecutorService executor;

	/**
	 * Tape maintenance, on a thread of its own.
	 *
	 * <p>Rolling a completed sales day into its index means decoding a few hundred thousand item
	 * blobs, which takes long enough that sharing a thread with {@link #pollSales} would be a data
	 * loss: the ended-auctions window is 60 seconds wide and nothing recovers one that was missed
	 * while the thread was busy summarising yesterday. Nothing here writes what the poller writes -
	 * maintenance reads completed day files and owns the rollup index, the poller appends to
	 * today's.
	 */
	private ScheduledExecutorService maintenance;

	public MarketPoller(HypixelApi api, MarketData data, SalesTape tape, BazaarTape bazaarTape,
			Supplier<ScanSettings> settings, Consumer<String> log) {
		this.api = api;
		this.data = data;
		this.tape = tape;
		this.bazaarTape = bazaarTape;
		// A supplier rather than a copy: /flip reload then changes what the next sweep does.
		this.settings = settings;
		this.log = log;
		// Read once instead: the ring's capacity is derived from the window, so it cannot change
		// under a running poller. /flip reload restarts the poller, which is where a new window
		// takes effect.
		this.history = new PriceHistory(Duration.ofHours(settings.get().trendWindowHours()));
	}

	public synchronized void start() {
		if (executor != null) {
			return;
		}

		// Daemon threads so a stuck poll can never keep the game from exiting.
		executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "skyblock-flipper-poller");
			thread.setDaemon(true);
			return thread;
		});

		maintenance = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "skyblock-flipper-tape-maintenance");
			thread.setDaemon(true);
			return thread;
		});

		// Read once, like the trend window above: a schedule cannot change under a running executor,
		// so a new cadence takes effect where a new window does - on the restart /flip reload does.
		schedule(this::pollBazaar, Duration.ZERO,
				Duration.ofSeconds(settings.get().bazaarPollSeconds()));
		schedule(this::pollSales, Duration.ofSeconds(2), SALES_INTERVAL);
		schedule(this::pollMayor, Duration.ofSeconds(4), MAYOR_INTERVAL);
		schedule(this::pollItems, Duration.ofSeconds(6), ITEMS_INTERVAL);
		// The sales rollup is the one piece of tape work heavy enough to need its own thread; the
		// bazaar's stays with the poller, which is the only thread allowed to touch the price ring.
		scheduleMaintenance(this::maintainSalesTape, Duration.ofMinutes(1), PRUNE_INTERVAL);
		schedule(this::maintainBazaarTape, Duration.ofMinutes(2), PRUNE_INTERVAL);
		// Also on the maintenance thread, and for the same reason: this re-reads three days of the
		// bazaar tape, which is far too long to hold up a 45-second sales poll. It touches neither
		// the price ring nor anything the poller writes - the tape is read-only from here.
		//
		// As early as the catalog allows, because until this has published a snapshot every NpcEdge
		// is absent and NpcFlipStrategy prices the chase at zero - which is a premium of zero, on a
		// plan the player is about to place. The item fetch is what supplies the NPC prices, and
		// rebuildNpcEdges already returns without publishing while the catalog is empty, so the
		// guard is what orders these two and not the delay. Measured live on 2026-08-15: a plan run
		// 2m19s after launch posted at the plain outbid with the premium set to 1.0.
		scheduleMaintenance(this::rebuildNpcEdges, Duration.ofSeconds(20), NPC_EDGE_INTERVAL);

		// Replays yesterday's tape into the ring before the first live sample, so trends are
		// available immediately on a client that has run before rather than three hours in.
		scheduleOnce(this::warmPriceHistory, Duration.ofSeconds(8));
		schedule(this::recordBazaarSample, Duration.ofSeconds(45), BAZAAR_TAPE_INTERVAL);

		// Valuation runs first and early: the auction sweep is skipped entirely until there is
		// something to compare listings against, so there is no point starting it sooner.
		schedule(this::rebuildValuations, Duration.ofSeconds(10), VALUATION_INTERVAL);
		schedule(this::scanAuctions, Duration.ofSeconds(30), AUCTION_INTERVAL);
	}

	public synchronized boolean isRunning() {
		return executor != null;
	}

	@Override
	public synchronized void close() {
		if (executor != null) {
			executor.shutdownNow();
			executor = null;
		}

		if (maintenance != null) {
			maintenance.shutdownNow();
			maintenance = null;
		}
	}

	private void pollBazaar() throws ApiException {
		data.setBazaar(api.fetchBazaar());
	}

	private void pollSales() throws ApiException {
		List<jeff.skyblockflipper.core.model.EndedAuction> sales = api.fetchEndedAuctions();

		try {
			int fresh = tape.record(sales);
			data.recordSales(fresh);
		} catch (IOException e) {
			data.recordFailure("could not write sales tape: " + e.getMessage());
			log.accept("Failed writing sales tape: " + e);
		}
	}

	/**
	 * Tapes the current book and folds it into the live price series.
	 *
	 * <p>Reads the snapshot the bazaar poll already fetched rather than making a request of its
	 * own, so history costs disk and nothing else. An unchanged book writes nothing: the tape
	 * dedupes on Hypixel's own stamp, and a duplicate sample would weight one frozen moment as
	 * heavily as a real move.
	 */
	private void recordBazaarSample() {
		if (!settings.get().bazaarTapeEnabled()) {
			return;
		}

		BazaarSnapshot snapshot = data.bazaar();

		if (snapshot.isEmpty()) {
			return;
		}

		try {
			List<BazaarSample> written = bazaarTape.record(snapshot);

			if (written.isEmpty()) {
				return;
			}

			written.forEach(history::append);
			data.setTrends(history.snapshot());
		} catch (IOException e) {
			data.recordFailure("could not write bazaar tape: " + e.getMessage());
			log.accept("Failed writing bazaar tape: " + e);
		}
	}

	/**
	 * Re-reads the tape into the price ring and re-prices against it.
	 *
	 * <p>For the one thing that changes the tape underneath a running poller: a sync that just
	 * merged the hours this client was closed for. Without it those hours sit on disk unread until
	 * the next launch, which is most of what a sync was for.
	 *
	 * <p>Queued on the poll thread rather than run on the caller's, because the ring belongs to
	 * that thread and the valuation model is rebuilt from the same place.
	 */
	public synchronized void rewarm() {
		if (executor == null) {
			return;
		}

		scheduleOnce(this::warmPriceHistory, Duration.ZERO);
		scheduleOnce(this::rebuildValuations, Duration.ZERO);
	}

	/**
	 * Replays the recent tape into the price ring.
	 *
	 * <p>Only the window's worth is read back. The tape holds far more so that longer-horizon
	 * questions stay answerable later, but the ring would evict anything older on the way in, so
	 * reading it would be work thrown away.
	 */
	private void warmPriceHistory() {
		ScanSettings config = settings.get();

		if (!config.bazaarTapeEnabled()) {
			return;
		}

		try {
			int days = (int) Math.max(1L, Math.ceilDiv(config.trendWindowHours(), 24));
			// Cleared first so this is safe to run more than once: the ring cannot tell a replayed
			// sample from a second real one, and a doubled series reads as half the volatility.
			history.clear();
			int read = bazaarTape.forEachRecent(days, history::append);
			history.setDailyStats(bazaarTape.readDailyIndex());
			data.setTrends(history.snapshot());

			log.accept("Price history warmed from " + read + " taped samples across "
					+ history.productsTracked() + " products");
		} catch (IOException e) {
			log.accept("Failed reading the bazaar tape to warm price history: " + e);
		}
	}

	private void pollMayor() throws ApiException {
		data.setMayor(api.fetchMayor());
	}

	private void pollItems() throws ApiException {
		data.setCatalog(api.fetchItems());
	}

	/** Replays the recent tape into a fresh set of medians. */
	private void rebuildValuations() {
		ScanSettings config = settings.get();
		Duration window = Duration.ofDays(config.valuationWindowDays());
		RecoveryValuationModels.Builder builder = RecoveryValuationModels.builder(Instant.now(), window);

		try {
			int read = tape.forEachRecent(config.valuationWindowDays(), builder::add);
			RecoveryValuationModels models = builder.build();
			FairValueModel model = models.ordinary();
			data.setValues(model, models.recovery());

			log.accept("Valuations rebuilt from " + read + " taped sales: "
					+ model.pricedConfigurations() + " item configurations and "
					+ models.recovery().componentIdentities() + " recovery components priced");
		} catch (IOException e) {
			log.accept("Failed reading the sales tape for valuation: " + e);
		}
	}

	/**
	 * Sweeps live listings for anything below fair value.
	 *
	 * <p>Skipped whenever there is nothing to compare against. A sweep costs tens of megabytes, and
	 * running one with an empty model would spend all of it to learn nothing.
	 */
	private void scanAuctions() throws ApiException {
		ScanSettings config = settings.get();

		if (!config.scanAuctions()) {
			return;
		}

		FairValueModel model = data.values();

		if (model.isEmpty()) {
			return;
		}

		UnderpricedScan ordinary = new UnderpricedScan(model, config.minDiscount(),
				config.exactMinDiscount(), config.maxPrice());
		Fees fees = new Fees(config.bazaarFlipperLevel(), data.mayor().isDerpy());
		RecoveryListingScan recovery = new RecoveryListingScan(data.recoveryValues(), data.bazaar(),
				fees, RecoveryScanPolicy.from(config.recovery()), Instant.now());
		SupplyCounter supply = new SupplyCounter(model, fees);
		ActiveAuctionScan scan = new ActiveAuctionScan(ordinary, recovery, supply);
		OptionalLong updated = api.sweepActiveBins(data.auctionsLastUpdated(), scan);

		if (updated.isEmpty()) {
			// The house has not changed since the last sweep; that cost one page, not fifty.
			return;
		}

		logSupplySignals(scan.supplySignals());

		data.setAuctionScan(updated.getAsLong(), scan.ordinaryResults(), scan.recoveryResults(),
				scan.ordinary().listingsSeen() + " listings, " + scan.decodedBlobs() + " decoded, "
						+ scan.ordinary().rejectedOnExactValue() + " rejected on exact match, "
						+ scan.ordinaryResults().size() + " under fair value, "
						+ scan.recoveryResults().size() + " recovery, "
						+ scan.failures() + " isolated failures");
	}

	/**
	 * Data-gathering only (roadmap step 4). Records the floor-sweep candidates a sweep saw, so their
	 * frequency can be studied before step 5 decides whether a strategy is worth building. Nothing
	 * acts on these; the counts are keyed coarsely and mix configurations, so they only say which
	 * keys are worth decoding.
	 */
	private void logSupplySignals(List<SupplySignal> signals) {
		if (signals.isEmpty()) {
			return;
		}

		int shown = Math.min(signals.size(), MAX_SUPPLY_SIGNALS_LOGGED);
		StringBuilder message = new StringBuilder("Supply signals: " + signals.size()
				+ " coarse key(s) with a floor to sweep");

		for (SupplySignal signal : signals.subList(0, shown)) {
			message.append("\n  ").append(signal.describe());
		}
		if (signals.size() > shown) {
			message.append("\n  ...").append(signals.size() - shown).append(" more");
		}

		log.accept(message.toString());
	}

	/**
	 * Re-measures how durably each product's bid sits under its NPC price.
	 *
	 * <p>Skipped until the item catalog has arrived: the NPC price is the thing being measured
	 * against, and a pass with no catalog would publish an empty snapshot over a good one. Skipped
	 * with the tape off too, since there would be nothing to read.
	 */
	private void rebuildNpcEdges() {
		if (!settings.get().bazaarTapeEnabled()) {
			return;
		}

		ItemCatalog catalog = data.catalog();

		if (catalog.isEmpty()) {
			return;
		}

		NpcEdgeHistory edges = new NpcEdgeHistory(catalog, Instant.now(), NPC_EDGE_WINDOW);

		try {
			int days = (int) Math.max(1L, NPC_EDGE_WINDOW.toDays());
			int read = bazaarTape.forEachRecent(days, edges::append);
			NpcEdgeSnapshot snapshot = edges.snapshot();
			data.setNpcEdges(snapshot);

			log.accept("NPC edges measured from " + read + " taped samples: "
					+ snapshot.productsWithMeasuredEdge() + " of " + snapshot.size()
					+ " NPC-priced products have enough history");
		} catch (IOException e) {
			log.accept("Failed reading the bazaar tape to measure NPC edges: " + e);
		}
	}

	private void maintainSalesTape() {
		try {
			// Summarise before deleting, so a day that ages out still leaves its rollup behind.
			// One day per pass, so a client returning after a week spreads the decoding over
			// several passes rather than doing all of it in one.
			int rolled = tape.rollUpOneCompletedDay();
			int removed = tape.prune();

			List<SaleDailyStat> index = tape.readDailyIndex();
			data.setSalesRollup(
					(int) index.stream().map(SaleDailyStat::day).distinct().count(), index.size());

			if (rolled > 0 || removed > 0) {
				log.accept("Sales tape: rolled up " + rolled + " day(s), pruned " + removed
						+ " expired file(s)");
			}
		} catch (IOException e) {
			log.accept("Failed maintaining sales tape: " + e);
		}
	}

	private void maintainBazaarTape() {
		try {
			// Summarise before deleting, so a day that ages out still leaves its rollup behind.
			int rolled = bazaarTape.rollUpCompletedDays();
			int removed = bazaarTape.prune();

			if (rolled > 0) {
				history.setDailyStats(bazaarTape.readDailyIndex());
				data.setTrends(history.snapshot());
			}

			if (rolled > 0 || removed > 0) {
				log.accept("Bazaar tape: rolled up " + rolled + " day(s), pruned " + removed
						+ " expired file(s)");
			}
		} catch (IOException e) {
			log.accept("Failed maintaining bazaar tape: " + e);
		}
	}

	/**
	 * Wraps each task so a thrown exception is logged rather than silently cancelling the schedule.
	 * {@code scheduleAtFixedRate} stops re-running a task that throws, which would otherwise turn
	 * one transient network blip into a permanently dead poller.
	 */
	private void schedule(PollTask task, Duration initialDelay, Duration interval) {
		executor.scheduleAtFixedRate(guarded(task),
				initialDelay.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
	}

	/** As {@link #schedule}, on the thread that must never hold up a poll. */
	private void scheduleMaintenance(PollTask task, Duration initialDelay, Duration interval) {
		maintenance.scheduleAtFixedRate(guarded(task),
				initialDelay.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
	}

	/** For work that only makes sense once, like replaying the tape into an empty ring. */
	private void scheduleOnce(PollTask task, Duration delay) {
		executor.schedule(guarded(task), delay.toMillis(), TimeUnit.MILLISECONDS);
	}

	private Runnable guarded(PollTask task) {
		return () -> {
			try {
				task.run();
				data.clearError();
			} catch (ApiException e) {
				data.recordFailure(e.getMessage());

				// Rate limiting is expected traffic shaping, not a fault worth shouting about.
				if (!e.isRateLimited()) {
					log.accept("Poll failed: " + e.getMessage());
				}
			} catch (RuntimeException e) {
				data.recordFailure(e.toString());
				log.accept("Unexpected poll error: " + e);
			}
		};
	}

	@FunctionalInterface
	private interface PollTask {
		void run() throws ApiException;
	}
}
