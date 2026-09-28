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
package jeff.skyblockflipper.client;

import jeff.skyblockflipper.SkyblockFlipper;
import jeff.skyblockflipper.core.api.MarketPoller;
import jeff.skyblockflipper.core.config.FlipperConfig;
import jeff.skyblockflipper.core.sync.SyncException;
import jeff.skyblockflipper.core.sync.TapeSync;
import jeff.skyblockflipper.core.tape.BazaarTape;
import jeff.skyblockflipper.core.tape.SalesTape;

import java.io.Closeable;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs the collector sync on its own thread and reports what it did.
 *
 * <p>Startup, not shutdown, and off the game thread: the fetch is hundreds of megabytes the first
 * time and the merge rescans the local tape, so anything that waited for it would be a loading
 * screen. The poller starts immediately and the sync catches up beside it; when the sync finishes
 * it asks the poller to re-read the tape, which is the only way the hours it just recovered reach
 * the price history before the next launch.
 *
 * <p>One sync at a time, enforced here rather than by luck. A second pass over a day file while the
 * first is still merging it would read a byte offset that the first pass is about to move.
 */
public final class TapeSyncService implements Closeable {
	/** Long enough that a first sync of two full days is not cut off by the next tick. */
	private static final Duration FIRST_DELAY = Duration.ofSeconds(5);

	private final SalesTape tape;
	private final BazaarTape bazaarTape;
	private final MarketPoller poller;
	private final AtomicBoolean running = new AtomicBoolean();

	private ScheduledExecutorService executor;
	private volatile String lastOutcome = "not run yet";
	private volatile Instant lastRun;

	public TapeSyncService(SalesTape tape, BazaarTape bazaarTape, MarketPoller poller) {
		this.tape = tape;
		this.bazaarTape = bazaarTape;
		this.poller = poller;
	}

	public synchronized void start() {
		if (executor != null) {
			return;
		}

		FlipperConfig config = SkyblockFlipperClient.config();

		if (!config.tapeSyncEnabled || config.tapeSyncUrl.isEmpty()) {
			return;
		}

		// Daemon, like the poller's: a sync stuck on an unreachable server must not be the reason
		// the game will not exit.
		executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "skyblock-flipper-tape-sync");
			thread.setDaemon(true);
			return thread;
		});

		int minutes = config.tapeSyncIntervalMinutes;

		if (minutes <= 0) {
			executor.schedule(this::runQuietly, FIRST_DELAY.toMillis(), TimeUnit.MILLISECONDS);
		} else {
			executor.scheduleAtFixedRate(this::runQuietly, FIRST_DELAY.toMillis(),
					Duration.ofMinutes(minutes).toMillis(), TimeUnit.MILLISECONDS);
		}
	}

	/**
	 * Syncs now, on the calling thread.
	 *
	 * <p>For {@code /flip sync}. Returns the same sentence the automatic pass logs, so a player who
	 * asked for it sees what a startup sync would have said.
	 */
	public String runNow() {
		if (!running.compareAndSet(false, true)) {
			return "A sync is already running.";
		}

		try {
			return describe(sync());
		} catch (SyncException e) {
			return "Sync failed: " + e.getMessage();
		} catch (RuntimeException e) {
			// The caller runs this on a thread whose only job is to report the answer, so anything
			// thrown past it is a player watching "Syncing..." forever. A malformed tapeSyncUrl
			// arrives here, from URI parsing rather than from the fetch.
			SkyblockFlipper.LOGGER.warn("Tape sync failed", e);
			return "Sync failed: " + e;
		} finally {
			running.set(false);
		}
	}

	/** What the last sync did, for the status line. */
	public String lastOutcome() {
		return lastOutcome;
	}

	public Instant lastRun() {
		return lastRun;
	}

	@Override
	public synchronized void close() {
		if (executor != null) {
			executor.shutdownNow();
			executor = null;
		}
	}

	private void runQuietly() {
		if (!running.compareAndSet(false, true)) {
			return;
		}

		try {
			SkyblockFlipper.LOGGER.info("Tape sync: {}", describe(sync()));
		} catch (SyncException e) {
			lastOutcome = "failed: " + e.getMessage();
			lastRun = Instant.now();
			// A server that is down costs the hours it holds, not the session. The local tape is
			// still the tape and the poller has not been touched.
			SkyblockFlipper.LOGGER.warn("Tape sync failed: {}", e.getMessage());
		} catch (RuntimeException e) {
			// scheduleAtFixedRate cancels the schedule for anything thrown out of the task, so an
			// unchecked failure here would silently end syncing for the session.
			lastOutcome = "failed: " + e;
			lastRun = Instant.now();
			SkyblockFlipper.LOGGER.warn("Tape sync failed", e);
		} finally {
			running.set(false);
		}
	}

	private TapeSync.Result sync() throws SyncException {
		FlipperConfig config = SkyblockFlipperClient.config();
		TapeSync sync = new TapeSync(config.tapeSyncUrl, config.tapeSyncToken);

		TapeSync.Result result = TapeSync.Result.empty();

		for (TapeSync.Target target : targets(config)) {
			result = result.plus(sync.pull(target));
		}

		lastRun = Instant.now();
		lastOutcome = describe(result);

		// Only when something actually arrived: re-reading the tape costs a full replay of the
		// window, and a sync that merged nothing has changed nothing to replay.
		if (result.linesMerged() > 0 && poller != null) {
			poller.rewarm();
		}

		return result;
	}

	/**
	 * The two tapes, named on the server the way they are named here.
	 *
	 * <p>Each carries its own tape's name test, which is what decides that a remote index entry is
	 * a file this client is willing to write. Nothing else about the response can name a path.
	 */
	private List<TapeSync.Target> targets(FlipperConfig config) {
		return List.of(
				new TapeSync.Target("tape", MarketDataService.tapeDirectory(),
						config.tapeRetentionDays, SalesTape::isTapeFile, tape::merge),
				new TapeSync.Target("bazaar-tape", MarketDataService.bazaarTapeDirectory(),
						config.bazaarTapeRetentionDays, BazaarTape::isTapeFile, bazaarTape::merge));
	}

	private static String describe(TapeSync.Result result) {
		if (result.linesMerged() == 0) {
			return "already up to date with the collector";
		}

		return String.format("merged %,d records from %d file%s (%,.1f MB fetched)",
				result.linesMerged(), result.filesRead(), result.filesRead() == 1 ? "" : "s",
				result.bytesFetched() / 1_048_576.0d);
	}
}
