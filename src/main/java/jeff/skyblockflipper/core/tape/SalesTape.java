package jeff.skyblockflipper.core.tape;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import jeff.skyblockflipper.core.model.EndedAuction;

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
	public int record(List<EndedAuction> sales) throws IOException {
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

	/** Reads back every sale still on disk. Used to warm the valuation model at startup. */
	public List<EndedAuction> readAll() throws IOException {
		if (!Files.isDirectory(directory)) {
			return List.of();
		}

		List<EndedAuction> out = new ArrayList<>();

		try (Stream<Path> files = Files.list(directory)) {
			for (Path file : files.filter(p -> p.getFileName().toString().endsWith(SUFFIX)).toList()) {
				out.addAll(readFile(file));
			}
		}

		return out;
	}

	/** Deletes day files older than the retention window. */
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

		try (Stream<String> lines = Files.lines(file, StandardCharsets.UTF_8)) {
			for (String line : lines.toList()) {
				if (line.isBlank()) {
					continue;
				}

				try {
					EndedAuction sale = gson.fromJson(line, EndedAuction.class);

					if (sale != null) {
						out.add(sale);
					}
				} catch (JsonSyntaxException ignored) {
					// A truncated final line from an interrupted write costs exactly one sale.
				}
			}
		}

		return out;
	}

	private Path fileFor(Instant when) {
		return directory.resolve(DAY.format(LocalDate.ofInstant(when, ZoneOffset.UTC)) + SUFFIX);
	}
}
