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
package jeff.skyblockflipper.core.tape;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import jeff.skyblockflipper.core.model.TimedAuctionSample;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Append-only log of active timed-auction observations, for the Phase 0b reachability measurement
 * (docs/auction-bidding-plan.md).
 *
 * <p>Its own tape, kept apart from the sales tape on purpose. The sales tape records what
 * <i>ended</i>, one line per realized sale; this records what is <i>still open</i>, one line per
 * active listing per sweep, so the same auction appears many times as its clock winds down. Joining
 * the two on the auction id is what turns "703 ended cheap" into "N of those were winnable by
 * presence" - the number the whole strategy is gated on.
 *
 * <p>Simpler than {@link SalesTape} in every way that tape earns its complexity: there is no
 * de-duplication (repeated samples of one listing are the signal, not noise), no rollup (the
 * measurement reads the raw trajectory), and no stored blob (the sweep decodes each listing to a
 * signature before it ever reaches here). What it keeps is the two things that tape has for a
 * reason: JSON Lines one file per UTC day, so a corrupt line costs one sample; and a shared
 * per-directory write lock, because the poller appends here while a prune runs.
 */
public final class TimedAuctionTape {
	private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
	private static final String SUFFIX = ".jsonl";

	private final Path directory;
	private final int retentionDays;
	private final Gson gson = new Gson();
	private final Object writeLock;

	private long totalRecorded;

	public TimedAuctionTape(Path directory, int retentionDays) {
		this.directory = directory;
		this.retentionDays = Math.max(1, retentionDays);
		this.writeLock = TapeLock.forDirectory(directory);
	}

	/**
	 * Appends a sweep's worth of samples, each filed under the UTC day it was observed.
	 *
	 * @return how many were written
	 */
	public int record(List<TimedAuctionSample> samples) throws IOException {
		if (samples.isEmpty()) {
			return 0;
		}

		synchronized (writeLock) {
			Files.createDirectories(directory);

			// Grouped by day so a sweep that straddles UTC midnight files each sample under the day
			// it happened, at one file handle per day rather than one per sample.
			Map<Path, List<TimedAuctionSample>> byDay = new LinkedHashMap<>();

			for (TimedAuctionSample sample : samples) {
				byDay.computeIfAbsent(fileFor(Instant.ofEpochMilli(sample.sampledAt())),
						k -> new ArrayList<>()).add(sample);
			}

			for (Map.Entry<Path, List<TimedAuctionSample>> entry : byDay.entrySet()) {
				try (BufferedWriter writer = Files.newBufferedWriter(entry.getKey(),
						StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
					for (TimedAuctionSample sample : entry.getValue()) {
						writer.write(gson.toJson(sample));
						writer.newLine();
					}
				}
			}

			totalRecorded += samples.size();
			return samples.size();
		}
	}

	/**
	 * Streams the last {@code days} days of samples through {@code consumer}, oldest day first.
	 *
	 * <p>Streamed, never returned: a busy day is hundreds of thousands of samples, and the
	 * measurement only ever groups them by auction id and reduces each group.
	 *
	 * @return how many samples were read
	 */
	public int forEachRecent(int days, Consumer<TimedAuctionSample> consumer) throws IOException {
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

	/** Deletes day files older than the retention window. */
	public int prune() throws IOException {
		synchronized (writeLock) {
			if (!Files.isDirectory(directory)) {
				return 0;
			}

			LocalDate cutoff = LocalDate.now(ZoneOffset.UTC).minusDays(retentionDays);
			int removed = 0;

			try (Stream<Path> files = Files.list(directory)) {
				for (Path file : files.toList()) {
					LocalDate day = dayOf(file);

					if (day != null && day.isBefore(cutoff)) {
						Files.deleteIfExists(file);
						removed++;
					}
				}
			}

			return removed;
		}
	}

	/** Samples appended since this instance was constructed. */
	public long totalRecorded() {
		return totalRecorded;
	}

	public Path directory() {
		return directory;
	}

	private int forEachInFile(Path file, Consumer<TimedAuctionSample> consumer) throws IOException {
		int read = 0;

		try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			String line;

			while ((line = reader.readLine()) != null) {
				if (line.isBlank()) {
					continue;
				}

				try {
					TimedAuctionSample sample = gson.fromJson(line, TimedAuctionSample.class);

					if (sample != null && sample.uuid() != null) {
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

	private static boolean onOrAfter(Path file, LocalDate cutoff) {
		LocalDate day = dayOf(file);
		return day != null && !day.isBefore(cutoff);
	}

	private static LocalDate dayOf(Path file) {
		String name = file.getFileName().toString();

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
