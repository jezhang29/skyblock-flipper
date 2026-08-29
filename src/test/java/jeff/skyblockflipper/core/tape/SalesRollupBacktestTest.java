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

import jeff.skyblockflipper.core.model.SaleDailyStat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What does a real day of sales cost once it is rolled up? Run with
 * {@code ./gradlew test -PtapeBacktest}, which supplies a recorded tape; see
 * {@code ValuationWindowBacktestTest} for why that is opt-in.
 *
 * <p>The rollup only earns its place if it is small enough to keep indefinitely - that is the
 * entire argument for it, since the raw day it summarises is deleted by retention and
 * {@code auctions_ended} cannot be asked about the past. A ratio measured on a synthetic fixture
 * would say nothing, because what decides it is how concentrated real trading is: a day of sales
 * spread over a few thousand configurations rolls up small, a day where every sale is its own
 * configuration does not.
 *
 * <p>Runs against symlinks to the real day files in a temp directory, so the measurement never
 * writes an index into somebody's live tape.
 */
@EnabledIfSystemProperty(named = "skyblockflipper.tapeBacktest", matches = "true")
class SalesRollupBacktestTest {
	private static final String DEFAULT_TAPE_DIR = "run/config/skyblock-flipper/tape";

	@Test
	void aRolledUpDayIsSmallEnoughToKeepForever(@TempDir Path work) throws Exception {
		LocalDate today = LocalDate.now(ZoneOffset.UTC);
		List<Path> days;

		try (Stream<Path> files = Files.list(tapeDir())) {
			days = files.filter(p -> p.getFileName().toString().matches("\\d{4}-\\d{2}-\\d{2}\\.jsonl"))
					.filter(p -> !p.getFileName().toString().startsWith(today.toString()))
					.sorted()
					.toList();
		}

		assertTrue(!days.isEmpty(), "no completed day files on the tape at " + tapeDir());

		for (Path day : days) {
			Files.createSymbolicLink(work.resolve(day.getFileName()), day.toAbsolutePath());
		}

		SalesTape tape = new SalesTape(work, 3650);
		long rawBytes = 0L;

		for (Path day : days) {
			rawBytes += Files.size(day);
			assertTrue(tape.rollUpOneCompletedDay() == 1, "expected " + day.getFileName()
					+ " to still be pending");
		}

		List<SaleDailyStat> index = tape.readDailyIndex();
		long indexBytes = Files.size(work.resolve("daily.jsonl"));

		Map<String, Long> byDay = index.stream()
				.collect(Collectors.groupingBy(SaleDailyStat::day, Collectors.counting()));

		System.out.printf("%nsales rollup over %d completed day(s): %,.1fMB raw -> %,.1fMB index "
						+ "(%.0fx smaller)%n",
				days.size(), rawBytes / 1e6, indexBytes / 1e6, rawBytes / (double) indexBytes);

		byDay.entrySet().stream()
				.sorted(Map.Entry.comparingByKey())
				.forEach(e -> System.out.printf("  %s  %,7d configurations%n", e.getKey(), e.getValue()));

		System.out.printf("  %,d index lines, %,d with more than one sale behind them%n",
				index.size(), index.stream().filter(stat -> stat.samples() > 1).count());

		index.stream()
				.filter(stat -> stat.samples() >= 20)
				.max(Comparator.comparingDouble(SaleDailyStat::range))
				.ifPresent(stat -> System.out.printf(
						"  widest well-traded day: %s n=%d median %,.0f range %.1fx%n",
						stat.signature(), stat.samples(), stat.median(), stat.range()));

		// The claim the feature rests on. Two orders of magnitude means a year of rollup costs less
		// than a day of raw tape; anything close to 1 would mean keeping the raw days instead.
		assertTrue(rawBytes / (double) indexBytes > 20.0d,
				"the rollup is only " + (rawBytes / (double) indexBytes) + "x smaller than the raw "
						+ "tape, which is not enough to justify keeping it past retention");
	}

	private static Path tapeDir() {
		return Path.of(System.getProperty("skyblockflipper.tapeDir", DEFAULT_TAPE_DIR));
	}
}
