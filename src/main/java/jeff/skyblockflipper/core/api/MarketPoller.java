package jeff.skyblockflipper.core.api;

import jeff.skyblockflipper.core.tape.SalesTape;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

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
	/** Bazaar books turn over quickly and the payload is small. */
	private static final Duration BAZAAR_INTERVAL = Duration.ofSeconds(20);

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

	private final HypixelApi api;
	private final MarketData data;
	private final SalesTape tape;
	private final Consumer<String> log;

	private ScheduledExecutorService executor;

	public MarketPoller(HypixelApi api, MarketData data, SalesTape tape, Consumer<String> log) {
		this.api = api;
		this.data = data;
		this.tape = tape;
		this.log = log;
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

		schedule(this::pollBazaar, Duration.ZERO, BAZAAR_INTERVAL);
		schedule(this::pollSales, Duration.ofSeconds(2), SALES_INTERVAL);
		schedule(this::pollMayor, Duration.ofSeconds(4), MAYOR_INTERVAL);
		schedule(this::pollItems, Duration.ofSeconds(6), ITEMS_INTERVAL);
		schedule(this::pruneTape, Duration.ofMinutes(1), PRUNE_INTERVAL);
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

	private void pollMayor() throws ApiException {
		data.setMayor(api.fetchMayor());
	}

	private void pollItems() throws ApiException {
		data.setCatalog(api.fetchItems());
	}

	private void pruneTape() {
		try {
			int removed = tape.prune();

			if (removed > 0) {
				log.accept("Pruned " + removed + " expired sales tape file(s)");
			}
		} catch (IOException e) {
			log.accept("Failed pruning sales tape: " + e);
		}
	}

	/**
	 * Wraps each task so a thrown exception is logged rather than silently cancelling the schedule.
	 * {@code scheduleAtFixedRate} stops re-running a task that throws, which would otherwise turn
	 * one transient network blip into a permanently dead poller.
	 */
	private void schedule(PollTask task, Duration initialDelay, Duration interval) {
		executor.scheduleAtFixedRate(() -> {
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
		}, initialDelay.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
	}

	@FunctionalInterface
	private interface PollTask {
		void run() throws ApiException;
	}
}
