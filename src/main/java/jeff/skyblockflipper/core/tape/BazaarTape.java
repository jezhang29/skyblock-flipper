package jeff.skyblockflipper.core.tape;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import jeff.skyblockflipper.core.model.BazaarDailyStat;
import jeff.skyblockflipper.core.model.BazaarProduct;
import jeff.skyblockflipper.core.model.BazaarSample;
import jeff.skyblockflipper.core.model.BazaarSnapshot;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Append-only log of bazaar top-of-book, sampled on a slow cadence.
 *
 * <p>The mod's live state is a single snapshot: {@code MarketData} replaces the book wholesale on
 * every poll and the previous one is gone. That makes a whole class of question unanswerable - is
 * this spread wide because the item is liquid, or because the price is falling out from under it?
 * Is this an opportunity or a manipulation spike on a book nobody trades? Neither can be answered
 * from a point in time, and neither can be answered retroactively: a price history you did not
 * record is not recoverable at any price. Same argument as {@link SalesTape}, different endpoint.
 *
 * <p>Stored as JSON Lines, one file per UTC day, so appends are cheap and a corrupt line costs one
 * sample rather than the whole history. Alongside the raw day files sits {@value #DAILY_FILE}, a
 * far smaller rollup that is what long-horizon readers actually use - see
 * {@link #rollUpCompletedDays()}.
 *
 * <p>Sampled well below the poll cadence on purpose. The poller refreshes the book every 20
 * seconds; taping all of that would be roughly fifteen times the disk for indicators that cannot
 * resolve it. The caller picks the interval by how often it calls {@link #record}.
 *
 * <p>Not thread-safe by itself; the poller owns it and writes from a single thread.
 */
public final class BazaarTape {
	private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
	private static final String SUFFIX = ".jsonl";

	/** The per-day rollup. Named so it never parses as a date, and so is never mistaken for one. */
	static final String DAILY_FILE = "daily.jsonl";

	private final Path directory;
	private final int retentionDays;
	private final Gson gson = new Gson();

	/**
	 * Hypixel's own stamp for the last book we taped.
	 *
	 * <p>The book regenerates on its own schedule, so a sample taken when nothing has changed is a
	 * duplicate that would weight one unchanged moment as heavily as a real move. Skipping on the
	 * upstream stamp rather than on our wall clock is the same test
	 * {@code MarketPoller.scanAuctions} already applies to the auction house.
	 */
	private long lastRecordedUpdate = Long.MIN_VALUE;

	private long totalRecorded;

	public BazaarTape(Path directory, int retentionDays) {
		this.directory = directory;
		this.retentionDays = Math.max(1, retentionDays);
	}

	/**
	 * Appends one line per two-sided product, unless this exact book has already been taped.
	 *
	 * <p>Filed under the day of Hypixel's snapshot rather than the day we happen to be writing, so
	 * a sample taken either side of UTC midnight lands where it belongs. Every product in one
	 * snapshot shares that timestamp, so unlike the sales tape this is always a single file.
	 *
	 * <p>Returns the samples rather than a count so the caller can feed the same objects into an
	 * in-memory series without rebuilding them. That matters less for the allocation than for
	 * correctness: the ask/bid translation is the one thing in this file that is easy to get
	 * backwards, and doing it in a second place is how the two copies eventually disagree.
	 *
	 * @return what was written, oldest first; empty when the book has not moved
	 */
	public List<BazaarSample> record(BazaarSnapshot snapshot) throws IOException {
		if (snapshot == null || snapshot.isEmpty()) {
			return List.of();
		}

		long updated = snapshot.lastUpdated().toEpochMilli();

		if (updated == lastRecordedUpdate) {
			return List.of();
		}

		List<BazaarSample> samples = new ArrayList<>(snapshot.products().size());

		for (BazaarProduct product : snapshot.products().values()) {
			OptionalDouble ask = product.instantBuyPrice();
			OptionalDouble bid = product.instantSellPrice();

			// A one-sided book has no midpoint, and a midpoint is the only thing read back out of
			// here. Storing half a price would put a fictional number in the series.
			if (ask.isEmpty() || bid.isEmpty()) {
				continue;
			}

			samples.add(new BazaarSample(
					product.productId(),
					updated,
					ask.getAsDouble(),
					bid.getAsDouble(),
					product.movingWeek().instantBought(),
					product.movingWeek().instantSold()));
		}

		Files.createDirectories(directory);

		try (BufferedWriter writer = Files.newBufferedWriter(fileFor(snapshot.lastUpdated()),
				StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
			for (BazaarSample sample : samples) {
				writer.write(gson.toJson(sample));
				writer.newLine();
			}
		}

		// Only after a successful write, so a failed flush is retried rather than skipped.
		lastRecordedUpdate = updated;
		totalRecorded += samples.size();
		return samples;
	}

	/**
	 * Streams the last {@code days} days of samples through {@code consumer}, oldest day first.
	 *
	 * <p>Streamed for the same reason the sales tape is: a fortnight of this is millions of
	 * samples, and every caller so far only wants a running statistic out of them.
	 *
	 * @return how many samples were read
	 */
	public int forEachRecent(int days, Consumer<BazaarSample> consumer) throws IOException {
		if (!Files.isDirectory(directory)) {
			return 0;
		}

		LocalDate cutoff = LocalDate.now(ZoneOffset.UTC).minusDays(Math.max(0, days - 1));
		int read = 0;

		try (Stream<Path> files = Files.list(directory)) {
			for (Path file : files.filter(p -> onOrAfter(p, cutoff)).sorted().toList()) {
				read += forEachInFile(file, consumer);
			}
		}

		return read;
	}

	/**
	 * Summarises every completed day that is not in the index yet.
	 *
	 * <p>Driven off what is missing from the index rather than off catching the moment the day
	 * rolls over, which makes it idempotent: a client that was closed at midnight, or crashed
	 * mid-rollup, picks the day up on its next run. Today is deliberately excluded - it is still
	 * being written to, and a partial day in the index would never be corrected.
	 *
	 * @return how many days were added to the index
	 */
	public int rollUpCompletedDays() throws IOException {
		if (!Files.isDirectory(directory)) {
			return 0;
		}

		LocalDate today = LocalDate.now(ZoneOffset.UTC);
		Set<String> indexed = new HashSet<>();

		for (BazaarDailyStat stat : readDailyIndex()) {
			indexed.add(stat.day());
		}

		List<Path> pending = new ArrayList<>();

		try (Stream<Path> files = Files.list(directory)) {
			for (Path file : files.sorted().toList()) {
				LocalDate day = dayOf(file);

				if (day != null && day.isBefore(today) && !indexed.contains(day.toString())) {
					pending.add(file);
				}
			}
		}

		for (Path file : pending) {
			appendDailyStats(dayOf(file), file);
		}

		return pending.size();
	}

	/** The rollup, or an empty list before the first completed day. */
	public List<BazaarDailyStat> readDailyIndex() throws IOException {
		Path file = directory.resolve(DAILY_FILE);

		if (!Files.isRegularFile(file)) {
			return List.of();
		}

		List<BazaarDailyStat> out = new ArrayList<>();

		try (Stream<String> lines = Files.lines(file, StandardCharsets.UTF_8)) {
			for (String line : lines.toList()) {
				if (line.isBlank()) {
					continue;
				}

				try {
					BazaarDailyStat stat = gson.fromJson(line, BazaarDailyStat.class);

					if (stat != null && stat.day() != null) {
						out.add(stat);
					}
				} catch (JsonSyntaxException ignored) {
					// One damaged line costs one product-day, not the index.
				}
			}
		}

		return out;
	}

	/**
	 * Deletes day files older than the retention window and drops their index entries.
	 *
	 * <p>The index is rewritten rather than appended to, since it is the one file here that is not
	 * append-only in spirit: it is small enough that rewriting it is cheaper than any alternative.
	 *
	 * @return how many raw day files were removed
	 */
	public int prune() throws IOException {
		if (!Files.isDirectory(directory)) {
			return 0;
		}

		LocalDate cutoff = LocalDate.now(ZoneOffset.UTC).minusDays(retentionDays);
		int removed = 0;

		try (Stream<Path> files = Files.list(directory)) {
			for (Path file : files.toList()) {
				LocalDate day = dayOf(file);

				// dayOf returns null for daily.jsonl and anything else we did not write.
				if (day != null && day.isBefore(cutoff)) {
					Files.deleteIfExists(file);
					removed++;
				}
			}
		}

		pruneDailyIndex(cutoff);
		return removed;
	}

	/** Samples appended since this instance was constructed. */
	public long totalRecorded() {
		return totalRecorded;
	}

	public Path directory() {
		return directory;
	}

	private void appendDailyStats(LocalDate day, Path file) throws IOException {
		Map<String, List<Double>> midsByProduct = new HashMap<>();

		for (BazaarSample sample : readFile(file)) {
			if (sample.isTwoSided()) {
				midsByProduct.computeIfAbsent(sample.productId(), k -> new ArrayList<>())
						.add(sample.mid());
			}
		}

		if (midsByProduct.isEmpty()) {
			return;
		}

		try (BufferedWriter writer = Files.newBufferedWriter(directory.resolve(DAILY_FILE),
				StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
			for (Map.Entry<String, List<Double>> entry : midsByProduct.entrySet()) {
				double[] mids = entry.getValue().stream().mapToDouble(Double::doubleValue).sorted().toArray();

				writer.write(gson.toJson(new BazaarDailyStat(
						entry.getKey(),
						DAY.format(day),
						mids[mids.length / 2],
						mids[0],
						mids[mids.length - 1],
						mids.length)));
				writer.newLine();
			}
		}
	}

	private void pruneDailyIndex(LocalDate cutoff) throws IOException {
		Path file = directory.resolve(DAILY_FILE);

		if (!Files.isRegularFile(file)) {
			return;
		}

		List<BazaarDailyStat> survivors = new ArrayList<>();

		for (BazaarDailyStat stat : readDailyIndex()) {
			try {
				if (!LocalDate.parse(stat.day(), DAY).isBefore(cutoff)) {
					survivors.add(stat);
				}
			} catch (DateTimeParseException ignored) {
				// Undatable entry: drop it rather than keep it forever.
			}
		}

		try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
				StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
			for (BazaarDailyStat stat : survivors) {
				writer.write(gson.toJson(stat));
				writer.newLine();
			}
		}
	}

	private List<BazaarSample> readFile(Path file) throws IOException {
		List<BazaarSample> out = new ArrayList<>();
		forEachInFile(file, out::add);
		return out;
	}

	/**
	 * Parses one day file a line at a time, handing each sample straight to {@code consumer}.
	 *
	 * <p>Nothing here holds the file: a day is about 40MB and the callers all want a running
	 * statistic, not the samples themselves.
	 *
	 * @return how many samples were read
	 */
	private int forEachInFile(Path file, Consumer<BazaarSample> consumer) throws IOException {
		int read = 0;

		try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			String line;

			while ((line = reader.readLine()) != null) {
				if (line.isBlank()) {
					continue;
				}

				try {
					BazaarSample sample = gson.fromJson(line, BazaarSample.class);

					if (sample != null && sample.productId() != null) {
						consumer.accept(sample);
						read++;
					}
				} catch (JsonSyntaxException ignored) {
					// A truncated final line from an interrupted write costs exactly one sample.
				}
			}
		}

		return read;
	}

	/** True for our own day files dated on or after {@code cutoff}; anything else is skipped. */
	private static boolean onOrAfter(Path file, LocalDate cutoff) {
		LocalDate day = dayOf(file);
		return day != null && !day.isBefore(cutoff);
	}

	/** The day a raw tape file covers, or null if it is not one of ours. */
	private static LocalDate dayOf(Path file) {
		String name = file.getFileName().toString();

		if (!name.endsWith(SUFFIX)) {
			return null;
		}

		try {
			return LocalDate.parse(name.substring(0, name.length() - SUFFIX.length()), DAY);
		} catch (DateTimeParseException e) {
			// daily.jsonl lands here, which is exactly why it is named the way it is.
			return null;
		}
	}

	private Path fileFor(Instant when) {
		return directory.resolve(DAY.format(LocalDate.ofInstant(when, ZoneOffset.UTC)) + SUFFIX);
	}
}
