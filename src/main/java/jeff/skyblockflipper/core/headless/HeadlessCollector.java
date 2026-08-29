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
package jeff.skyblockflipper.core.headless;

import jeff.skyblockflipper.core.api.HypixelApi;
import jeff.skyblockflipper.core.api.MarketData;
import jeff.skyblockflipper.core.api.MarketPoller;
import jeff.skyblockflipper.core.config.FlipperConfig;
import jeff.skyblockflipper.core.config.ScanSettings;
import jeff.skyblockflipper.core.tape.BazaarTape;
import jeff.skyblockflipper.core.tape.SalesTape;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Runs the market poller without Minecraft, so the tapes keep filling while the game is closed.
 *
 * <p>The reason this is worth having is that two of the three data sources are not backfillable.
 * The {@code auctions_ended} window is about 60 seconds wide and Hypixel keeps no history behind
 * it, so every minute nothing is polling is a minute of realized sales that stops existing - and
 * realized sales are the only thing valuations train on. The bazaar tape has the same property at a
 * coarser grain. Everything else the mod reads (the live book, the item catalog, the mayor) is
 * current state and comes back within one interval of a restart, so it is not what this is for.
 *
 * <p>This exists as a plain {@code main} because {@code core} was already built to allow it:
 * nothing in the pipeline touches {@code net.minecraft} or {@code net.fabricmc}, and the client
 * layer only ever supplied a {@link Path} and a logger. That is the whole of what is reimplemented
 * here.
 *
 * <p>The directory layout deliberately matches what {@code MarketDataService} produces under
 * {@code <.minecraft>/config/skyblock-flipper}, so a tape collected here can be copied straight
 * into a client install and read without translation.
 */
public final class HeadlessCollector {
	/**
	 * Measured against the live endpoints on 2026-07-27, gzipped, as the transfer actually costs.
	 * Used only to print an estimate at startup - nothing decides anything on these.
	 */
	private static final long BAZAAR_BYTES = 434_183L;
	private static final long ENDED_AUCTIONS_BYTES = 87_108L;

	/** The sweep is many pages; this is the order of magnitude its own javadoc quotes. */
	private static final long AUCTION_SWEEP_BYTES = 70L * 1024L * 1024L;

	/** Cadences the poller hardcodes, needed only to estimate the monthly bill. */
	private static final Duration SALES_INTERVAL = Duration.ofSeconds(45);
	private static final Duration AUCTION_INTERVAL = Duration.ofSeconds(60);

	private static final Duration HEARTBEAT = Duration.ofMinutes(10);

	private static final DateTimeFormatter STAMP =
			DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

	private HeadlessCollector() {
	}

	public static void main(String[] args) throws Exception {
		Options options;

		try {
			options = Options.parse(args);
		} catch (IllegalArgumentException e) {
			System.err.println(e.getMessage());
			System.err.println();
			System.err.println(Options.usage());
			System.exit(2);
			return;
		}

		if (options.help()) {
			System.out.println(Options.usage());
			return;
		}

		run(options);
	}

	private static void run(Options options) throws IOException, InterruptedException {
		// load() writes a default file when none exists, so a first run leaves something to edit
		// rather than requiring the operator to guess the schema.
		FlipperConfig config = FlipperConfig.load(options.configFile());

		log("Config: " + options.configFile());
		log("Data:   " + options.dataDir());

		if (!config.pollingEnabled) {
			log("pollingEnabled is false in the config, so there is nothing to collect. Exiting.");
			return;
		}

		describePlan(config);

		MarketData data = new MarketData();
		SalesTape tape = new SalesTape(options.tapeDir(), config.tapeRetentionDays);
		BazaarTape bazaarTape = new BazaarTape(options.bazaarTapeDir(), config.bazaarTapeRetentionDays);

		// A fixed snapshot, not a re-reading supplier: there is no /flip reload here, and a supplier
		// that always returned the same value would only imply otherwise. Restarting the service is
		// how a config change takes effect.
		ScanSettings settings = config.scanSettings();
		MarketPoller poller = new MarketPoller(new HypixelApi(), data, tape, bazaarTape,
				() -> settings, HeadlessCollector::log);

		CountDownLatch stopped = new CountDownLatch(1);

		// The poller's threads are daemons, so without this the JVM would exit the moment main
		// returned. The hook also gives an in-flight tape write the chance to finish its line,
		// which is the same reason the client stops the poller on CLIENT_STOPPING.
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			log("Shutting down.");
			poller.close();
			stopped.countDown();
		}, "skyblock-flipper-shutdown"));

		poller.start();
		log("Collector running. Stop it with Ctrl-C or systemctl stop.");

		ScheduledExecutorService heartbeat = heartbeat(data);

		try {
			stopped.await();
		} finally {
			heartbeat.shutdownNow();
		}
	}

	/**
	 * Prints what this run will actually do, in bytes.
	 *
	 * <p>Unattended processes are where a misread setting turns into a bandwidth bill nobody sees
	 * for a month, and the difference between the settings here is three orders of magnitude.
	 */
	private static void describePlan(FlipperConfig config) {
		long bazaarPerMonth = perMonth(BAZAAR_BYTES, Duration.ofSeconds(config.bazaarPollSeconds));
		long salesPerMonth = perMonth(ENDED_AUCTIONS_BYTES, SALES_INTERVAL);
		long total = bazaarPerMonth + salesPerMonth;

		log("Bazaar book every " + config.bazaarPollSeconds + "s (~" + gib(bazaarPerMonth) + "/month)");
		log("Ended auctions every " + SALES_INTERVAL.toSeconds() + "s (~" + gib(salesPerMonth)
				+ "/month)");

		if (config.scanAuctions) {
			long sweeps = perMonth(AUCTION_SWEEP_BYTES, AUCTION_INTERVAL);
			total += sweeps;
			log("Auction sweep every " + AUCTION_INTERVAL.toSeconds() + "s (~" + gib(sweeps)
					+ "/month)");
			log("NOTE: the sweep finds live listings to act on, which nothing here can act on. "
					+ "Set scanAuctions to false unless this box is also serving a client.");
		} else {
			log("Auction sweep off. The tapes do not need it.");
		}

		log("Estimated download: ~" + gib(total) + "/month. Upload is request headers only.");
		log("Retention: " + config.tapeRetentionDays + "d of sales, "
				+ config.bazaarTapeRetentionDays + "d of bazaar tape.");
	}

	private static ScheduledExecutorService heartbeat(MarketData data) {
		ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "skyblock-flipper-heartbeat");
			thread.setDaemon(true);
			return thread;
		});

		executor.scheduleAtFixedRate(() -> {
			StringBuilder line = new StringBuilder("Alive: ")
					.append(data.salesRecorded()).append(" sales taped, bazaar ")
					.append(data.bazaarAge().toSeconds()).append("s old, sales ")
					.append(data.salesAge().toSeconds()).append("s old");

			// Failures are counted rather than fatal, so an unattended run needs somewhere they
			// surface other than the moment they happened.
			if (data.pollFailures() > 0) {
				line.append(", ").append(data.pollFailures()).append(" poll failures (last: ")
						.append(data.lastError()).append(")");
			}

			log(line.toString());
		}, HEARTBEAT.toMillis(), HEARTBEAT.toMillis(), TimeUnit.MILLISECONDS);

		return executor;
	}

	private static long perMonth(long bytesPerFetch, Duration interval) {
		long fetchesPerMonth = Duration.ofDays(30).toSeconds() / interval.toSeconds();
		return bytesPerFetch * fetchesPerMonth;
	}

	private static String gib(long bytes) {
		double gigabytes = bytes / (1024.0d * 1024.0d * 1024.0d);
		return gigabytes < 1.0d
				? String.format("%.0fMB", bytes / (1024.0d * 1024.0d))
				: String.format("%.1fGB", gigabytes);
	}

	/** Timestamped and unbuffered, so journald and a plain terminal both read the same. */
	private static void log(String message) {
		System.out.println(STAMP.format(Instant.now()) + "  " + message);
	}

	/**
	 * Where to keep the data and where to read the config.
	 *
	 * @param dataDir the equivalent of {@code <.minecraft>/config/skyblock-flipper}
	 * @param configFile the config to read, which need not live under {@code dataDir}
	 * @param help whether the caller only asked what the flags are
	 */
	record Options(Path dataDir, Path configFile, boolean help) {
		private static final Path DEFAULT_DATA_DIR = Path.of("skyblock-flipper");

		static Options parse(String[] args) {
			Path dataDir = null;
			Path configFile = null;

			for (int i = 0; i < args.length; i++) {
				switch (args[i]) {
					case "--help", "-h" -> {
						return new Options(DEFAULT_DATA_DIR, DEFAULT_DATA_DIR, true);
					}
					case "--data-dir" -> dataDir = Path.of(value(args, i++));
					case "--config" -> configFile = Path.of(value(args, i++));
					default -> throw new IllegalArgumentException("Unknown option: " + args[i]);
				}
			}

			Path resolvedData = (dataDir != null ? dataDir : DEFAULT_DATA_DIR).toAbsolutePath()
					.normalize();
			Path resolvedConfig = (configFile != null ? configFile : resolvedData.resolve("config.json"))
					.toAbsolutePath().normalize();

			return new Options(resolvedData, resolvedConfig, false);
		}

		private static String value(String[] args, int index) {
			if (index + 1 >= args.length) {
				throw new IllegalArgumentException(args[index] + " needs a value");
			}

			return args[index + 1];
		}

		/** Siblings, never nested: each tape has its own retention and its own prune pass. */
		Path tapeDir() {
			return dataDir.resolve("tape");
		}

		Path bazaarTapeDir() {
			return dataDir.resolve("bazaar-tape");
		}

		static String usage() {
			return """
					Collects Hypixel market data into the mod's tapes, without running Minecraft.

					  --data-dir <path>  where the tapes live. Default: ./skyblock-flipper
					                     Mirrors <.minecraft>/config/skyblock-flipper, so the
					                     result can be copied into a client install as-is.
					  --config <path>    config to read. Default: <data-dir>/config.json
					                     Written with defaults if it does not exist.
					  --help             this text

					Set scanAuctions to false in the config unless this machine also plays: the
					sweep is ~70MB a minute and only produces listings to act on right now.""";
		}
	}
}
