package jeff.skyblockflipper.core.tape;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import jeff.skyblockflipper.core.item.ItemDecoder;
import jeff.skyblockflipper.core.model.EndedAuction;
import jeff.skyblockflipper.core.model.SaleDailyStat;

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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Append-only log of realized auction sales.
 *
 * <p>This exists because {@code /auctions_ended} is a 60-second window onto the past and nothing
 * recovers a window that was missed. Every minute the client runs, the tape gets longer; after a
 * week of uptime it is a dataset that a tool starting from scratch cannot reproduce at any price.
 * The valuation model in a later step is only as good as this file.
 *
 * <p>Stored as JSON Lines, one file per UTC day, so appends are cheap and a corrupt line costs one
 * sale rather than the whole history.
 *
 * <p>Not thread-safe by itself; the poller owns it and writes from a single thread.
 */
public final class SalesTape {
	private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
	private static final String SUFFIX = ".jsonl";

	/** The per-day rollup. Named so it never parses as a date, and so is never mistaken for one. */
	static final String DAILY_FILE = "daily.jsonl";

	/**
	 * Polls overlap, so the same sale arrives several times. Remembering recent ids keeps the tape
	 * free of duplicates that would otherwise bias any average computed from it. Sized well above
	 * the ~200 sales per window so a slow poll cycle cannot let a duplicate slip through.
	 */
	private static final int DEDUPE_MEMORY = 5_000;

	private final Path directory;
	private final int retentionDays;
	private final Gson gson = new Gson();
	private final Set<String> recentIds = Collections.newSetFromMap(
			new LinkedHashMap<>(16, 0.75f, false) {
				@Override
				protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
					return size() > DEDUPE_MEMORY;
				}
			});

	private long totalRecorded;

	public SalesTape(Path directory, int retentionDays) {
		this.directory = directory;
		this.retentionDays = Math.max(1, retentionDays);
	}

	/**
	 * Appends any sales not already seen.
	 *
	 * @return how many were new
	 */
	public synchronized int record(List<EndedAuction> sales) throws IOException {
		if (sales.isEmpty()) {
			return 0;
		}

		Files.createDirectories(directory);

		List<EndedAuction> fresh = new ArrayList<>();

		for (EndedAuction sale : sales) {
			if (sale.auctionId() != null && recentIds.add(sale.auctionId())) {
				fresh.add(sale);
			}
		}

		if (fresh.isEmpty()) {
			return 0;
		}

		// Sales are bucketed by their own timestamp rather than the wall clock, so a poll that
		// straddles UTC midnight files each sale under the day it actually happened. Grouping
		// first keeps this to one file handle per day rather than one per sale.
		Map<Path, List<EndedAuction>> byDay = new LinkedHashMap<>();

		for (EndedAuction sale : fresh) {
			byDay.computeIfAbsent(fileFor(Instant.ofEpochMilli(sale.timestamp())), k -> new ArrayList<>())
					.add(sale);
		}

		for (Map.Entry<Path, List<EndedAuction>> entry : byDay.entrySet()) {
			try (BufferedWriter writer = Files.newBufferedWriter(entry.getKey(), StandardCharsets.UTF_8,
					StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
				for (EndedAuction sale : entry.getValue()) {
					writer.write(gson.toJson(sale));
					writer.newLine();
				}
			}
		}

		totalRecorded += fresh.size();
		return fresh.size();
	}

	/**
	 * Folds lines from another copy of this tape into ours, skipping sales we already hold.
	 *
	 * <p>Keyed on {@code auction_id}, the same field {@link #record} deduplicates polls on, so a day
	 * both this client and the collector taped merges to the union rather than to double.
	 *
	 * <p>Synchronized with {@code record}: the sync thread and the poller append to the same day
	 * file, and two buffered writers flushing into one file interleave mid-line.
	 *
	 * @param fileName one of our own file names - a {@code yyyy-MM-dd.jsonl} day or the rollup
	 * @return how many lines were new
	 * @throws IllegalArgumentException if the name is not one this tape would have written, which
	 *                                  is the check that keeps a remote index from naming a path
	 */
	public synchronized int merge(String fileName, List<String> lines) throws IOException {
		return TapeMerge.merge(resolve(fileName), lines, keyOf(fileName));
	}

	/** True for names this tape writes, and false for everything else, path separators included. */
	public static boolean isTapeFile(String fileName) {
		if (fileName == null || fileName.contains("/") || fileName.contains("\\")) {
			return false;
		}

		return DAILY_FILE.equals(fileName) || dayNamed(fileName) != null;
	}

	private Path resolve(String fileName) {
		if (!isTapeFile(fileName)) {
			throw new IllegalArgumentException("not a sales tape file: " + fileName);
		}

		return directory.resolve(fileName);
	}

	/**
	 * The rollup is one line per signature per day and the day files are one line per sale, so the
	 * two are deduplicated on different keys even though they live side by side.
	 */
	private static java.util.function.Function<String, String> keyOf(String fileName) {
		return DAILY_FILE.equals(fileName)
				? line -> JsonLines.pair(line, "s", "d")
				: line -> JsonLines.field(line, "auction_id");
	}

	/**
	 * Reads back every sale still on disk, all at once.
	 *
	 * <p>Only safe when the tape is known to be small - a week of a running collector is over a
	 * gigabyte of blobs. Anything that walks the real tape should use
	 * {@link #forEachRecent(int, java.util.function.Consumer)}, which holds one sale at a time.
	 */
	public List<EndedAuction> readAll() throws IOException {
		if (!Files.isDirectory(directory)) {
			return List.of();
		}

		List<EndedAuction> out = new ArrayList<>();

		// dayOf, not the suffix: the rollup index shares it, and read as sales its lines would
		// come back as blank-fielded EndedAuctions rather than as an error.
		try (Stream<Path> files = Files.list(directory)) {
			for (Path file : files.filter(p -> dayOf(p) != null).toList()) {
				out.addAll(readFile(file));
			}
		}

		return out;
	}

	/**
	 * Streams the last {@code days} days of sales through {@code consumer}, oldest day first.
	 *
	 * <p>Streamed rather than returned: a busy couple of days is a few hundred thousand sales
	 * carrying a kilobyte and a half of blob each, so the caller that only wants a median per item
	 * should never have to hold them all at once.
	 *
	 * @return how many sales were read
	 */
	public int forEachRecent(int days, java.util.function.Consumer<EndedAuction> consumer) throws IOException {
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
	 * Summarises one completed day that is not in the index yet, oldest first.
	 *
	 * <p>Driven off what is missing from the index rather than off catching the moment the day
	 * rolls over, which makes it idempotent: a client that was closed at midnight, or crashed
	 * mid-rollup, picks the day up on its next run. Today is deliberately excluded - it is still
	 * being written to, and a partial day in the index would never be corrected.
	 *
	 * <p><b>One day per call, unlike the bazaar rollup, which does every pending day at once.</b>
	 * The difference is what a day costs to summarise: a bazaar day is 40MB of numbers, while a
	 * sales day is a quarter of a gigabyte whose every line has to have its item blob decoded
	 * before it can be keyed. A client that has been away for a week would otherwise sit on the
	 * poller thread through seven of those in one pass, delaying every other poll behind it. Spread
	 * across prune cycles the same work is invisible, and nothing downstream needs the index
	 * complete on any particular tick.
	 *
	 * @return how many days were added to the index: 1, or 0 when the index is up to date
	 */
	public int rollUpOneCompletedDay() throws IOException {
		if (!Files.isDirectory(directory)) {
			return 0;
		}

		LocalDate today = LocalDate.now(ZoneOffset.UTC);
		Set<String> indexed = new HashSet<>();

		for (SaleDailyStat stat : readDailyIndex()) {
			indexed.add(stat.day());
		}

		Path pending = null;
		LocalDate pendingDay = null;

		try (Stream<Path> files = Files.list(directory)) {
			for (Path file : files.sorted().toList()) {
				LocalDate day = dayOf(file);

				if (day != null && day.isBefore(today) && !indexed.contains(day.toString())) {
					pending = file;
					pendingDay = day;
					break;
				}
			}
		}

		if (pending == null) {
			return 0;
		}

		appendDailyStats(pendingDay, pending);
		return 1;
	}

	/** The rollup, or an empty list before the first completed day. */
	public List<SaleDailyStat> readDailyIndex() throws IOException {
		Path file = directory.resolve(DAILY_FILE);

		if (!Files.isRegularFile(file)) {
			return List.of();
		}

		List<SaleDailyStat> out = new ArrayList<>();

		try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			String line;

			while ((line = reader.readLine()) != null) {
				if (line.isBlank()) {
					continue;
				}

				try {
					SaleDailyStat stat = gson.fromJson(line, SaleDailyStat.class);

					if (stat != null && stat.day() != null && stat.signature() != null) {
						out.add(stat);
					}
				} catch (JsonSyntaxException ignored) {
					// One damaged line costs one signature-day, not the index.
				}
			}
		}

		return out;
	}

	/**
	 * Summarises one day file into the index.
	 *
	 * <p>BIN sales only and unit prices throughout, matching {@code FairValueModel}: a bid auction
	 * says what nobody else was awake for, and a stack sale says what eight of something cost. The
	 * signature is the model's own key, so a rolled-up day answers the same question the live index
	 * does - which also means a change to what a signature contains cannot be applied backwards.
	 */
	private void appendDailyStats(LocalDate day, Path file) throws IOException {
		Map<String, List<Double>> pricesBySignature = new HashMap<>();

		forEachInFile(file, sale -> {
			if (!sale.bin() || sale.price() <= 0L) {
				return;
			}

			ItemDecoder.decode(sale.itemBytes()).ifPresent(item -> pricesBySignature
					.computeIfAbsent(item.signature(), k -> new ArrayList<>())
					.add((double) sale.price() / Math.max(1, item.count())));
		});

		if (pricesBySignature.isEmpty()) {
			return;
		}

		try (BufferedWriter writer = Files.newBufferedWriter(directory.resolve(DAILY_FILE),
				StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
			for (Map.Entry<String, List<Double>> entry : pricesBySignature.entrySet()) {
				double[] prices = entry.getValue().stream().mapToDouble(Double::doubleValue).sorted().toArray();

				writer.write(gson.toJson(new SaleDailyStat(
						entry.getKey(),
						DAY.format(day),
						prices[prices.length / 2],
						prices[0],
						prices[prices.length - 1],
						prices.length)));
				writer.newLine();
			}
		}
	}

	/**
	 * Deletes day files older than the retention window.
	 *
	 * <p>The rollup index outlives them on purpose, so it is not touched here. It is the only thing
	 * that survives a day ageing out, and it is three orders of magnitude smaller than what it
	 * replaces - pruning it in step with the raw tape would throw away the entire point of it.
	 */
	public int prune() throws IOException {
		if (!Files.isDirectory(directory)) {
			return 0;
		}

		LocalDate cutoff = LocalDate.now(ZoneOffset.UTC).minusDays(retentionDays);
		int removed = 0;

		try (Stream<Path> files = Files.list(directory)) {
			for (Path file : files.toList()) {
				String name = file.getFileName().toString();

				if (!name.endsWith(SUFFIX)) {
					continue;
				}

				try {
					if (LocalDate.parse(name.substring(0, name.length() - SUFFIX.length()), DAY).isBefore(cutoff)) {
						Files.deleteIfExists(file);
						removed++;
					}
				} catch (java.time.format.DateTimeParseException ignored) {
					// Not one of ours; leave it alone.
				}
			}
		}

		return removed;
	}

	/** Sales appended since this instance was constructed. */
	public long totalRecorded() {
		return totalRecorded;
	}

	public Path directory() {
		return directory;
	}

	private List<EndedAuction> readFile(Path file) throws IOException {
		List<EndedAuction> out = new ArrayList<>();
		forEachInFile(file, out::add);
		return out;
	}

	/**
	 * Parses one day file a line at a time, handing each sale straight to {@code consumer}.
	 *
	 * <p>Nothing here is allowed to hold the file. Since the collector moved to a machine that runs
	 * continuously, a day file is a quarter of a gigabyte - collecting the lines first, or the
	 * parsed sales, cost several hundred megabytes of heap per day file for a caller that only
	 * wanted a running median. Minecraft's default heap is not big enough for that to be safe.
	 *
	 * @return how many sales were read
	 */
	private int forEachInFile(Path file, java.util.function.Consumer<EndedAuction> consumer)
			throws IOException {
		int read = 0;

		try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			String line;

			while ((line = reader.readLine()) != null) {
				if (line.isBlank()) {
					continue;
				}

				try {
					EndedAuction sale = gson.fromJson(line, EndedAuction.class);

					if (sale != null) {
						consumer.accept(sale);
						read++;
					}
				} catch (JsonSyntaxException ignored) {
					// A truncated final line from an interrupted write costs exactly one sale.
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

	/** The day a file is named for, or null for {@value #DAILY_FILE} and anything we did not write. */
	private static LocalDate dayOf(Path file) {
		return dayNamed(file.getFileName().toString());
	}

	private static LocalDate dayNamed(String name) {
		if (!name.endsWith(SUFFIX)) {
			return null;
		}

		try {
			return LocalDate.parse(name.substring(0, name.length() - SUFFIX.length()), DAY);
		} catch (java.time.format.DateTimeParseException e) {
			return null;
		}
	}

	private Path fileFor(Instant when) {
		return directory.resolve(DAY.format(LocalDate.ofInstant(when, ZoneOffset.UTC)) + SUFFIX);
	}
}
