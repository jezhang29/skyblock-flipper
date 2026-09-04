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

import jeff.skyblockflipper.core.model.TimedAuctionSample;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimedAuctionTapeTest {
	private static long todayAt(int hour) {
		return LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC)
				.plusHours(hour).toInstant().toEpochMilli();
	}

	private static TimedAuctionSample sample(String uuid, long sampledAt) {
		return new TimedAuctionSample(uuid, "HYPERION|LEGENDARY", 1,
				sampledAt + Duration.ofHours(1).toMillis(), 5_000_000L, 0L, sampledAt);
	}

	@Test
	void recordsAndReadsBackEveryField(@TempDir Path dir) throws IOException {
		TimedAuctionTape tape = new TimedAuctionTape(dir, 7);
		long t = todayAt(12);
		TimedAuctionSample original = new TimedAuctionSample("auc-1", "NECRON_BLADE|LEGENDARY",
				1, t + 1234L, 4_000_000L, 6_500_000L, t);

		assertEquals(1, tape.record(List.of(original)));

		List<TimedAuctionSample> read = new ArrayList<>();
		int count = tape.forEachRecent(2, read::add);

		assertEquals(1, count);
		assertEquals(original, read.getFirst(), "the record round-trips through JSON unchanged");
	}

	@Test
	void keepsEverySampleOfOneAuctionWithNoDeduplication(@TempDir Path dir) throws IOException {
		TimedAuctionTape tape = new TimedAuctionTape(dir, 7);
		long base = todayAt(10);

		// The same auction seen by three sweeps a minute apart: all three are the trajectory.
		tape.record(List.of(
				sample("same", base),
				sample("same", base + 60_000L),
				sample("same", base + 120_000L)));

		List<TimedAuctionSample> read = new ArrayList<>();
		tape.forEachRecent(2, read::add);
		assertEquals(3, read.size(), "repeated samples are the signal, never deduplicated");
	}

	@Test
	void filesSamplesUnderTheDayTheyWereObserved(@TempDir Path dir) throws IOException {
		TimedAuctionTape tape = new TimedAuctionTape(dir, 30);
		long today = todayAt(12);
		long threeDaysAgo = today - Duration.ofDays(3).toMillis();

		tape.record(List.of(sample("old", threeDaysAgo), sample("new", today)));

		List<TimedAuctionSample> lastTwoDays = new ArrayList<>();
		tape.forEachRecent(2, lastTwoDays::add);
		assertEquals(1, lastTwoDays.size(), "the 3-day-old sample is outside a 2-day read window");
		assertEquals("new", lastTwoDays.getFirst().uuid());

		List<TimedAuctionSample> lastWeek = new ArrayList<>();
		tape.forEachRecent(7, lastWeek::add);
		assertEquals(2, lastWeek.size());
	}

	@Test
	void prunesDayFilesOlderThanRetention(@TempDir Path dir) throws IOException {
		TimedAuctionTape tape = new TimedAuctionTape(dir, 2);
		long today = todayAt(12);
		long fiveDaysAgo = today - Duration.ofDays(5).toMillis();

		tape.record(List.of(sample("old", fiveDaysAgo), sample("fresh", today)));

		assertEquals(1, tape.prune(), "one expired day file is removed");

		List<TimedAuctionSample> read = new ArrayList<>();
		tape.forEachRecent(30, read::add);
		assertEquals(1, read.size());
		assertEquals("fresh", read.getFirst().uuid());
	}

	@Test
	void anEmptyRecordWritesNothing(@TempDir Path dir) throws IOException {
		TimedAuctionTape tape = new TimedAuctionTape(dir, 7);
		assertEquals(0, tape.record(List.of()));
		assertEquals(0, tape.totalRecorded());
	}

	@Test
	void ignoresANonTapeFileInTheDirectory(@TempDir Path dir) throws IOException {
		Files.writeString(dir.resolve("notes.txt"), "not a day file", StandardCharsets.UTF_8);
		TimedAuctionTape tape = new TimedAuctionTape(dir, 7);
		tape.record(List.of(sample("a", todayAt(9))));

		List<TimedAuctionSample> read = new ArrayList<>();
		assertEquals(1, tape.forEachRecent(2, read::add), "the stray file is skipped, not parsed");

		assertEquals(0, tape.prune(), "prune leaves a file it did not write alone");
		assertTrue(Files.exists(dir.resolve("notes.txt")));
		assertFalse(read.isEmpty());
	}
}
