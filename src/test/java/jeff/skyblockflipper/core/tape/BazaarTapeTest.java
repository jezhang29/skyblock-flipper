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

import jeff.skyblockflipper.core.model.BazaarDailyStat;
import jeff.skyblockflipper.core.model.BazaarProduct;
import jeff.skyblockflipper.core.model.BazaarSample;
import jeff.skyblockflipper.core.model.BazaarSnapshot;
import jeff.skyblockflipper.core.model.OrderLevel;
import jeff.skyblockflipper.core.model.dto.BazaarDto;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bazaar tape is the mod's only memory of what prices used to be, and like the sales tape it
 * cannot be rebuilt after the fact. Its failure modes are the ones that matter: a duplicated
 * sample weights one frozen moment as heavily as a real move, and a dropped one leaves a hole no
 * later run can fill.
 *
 * <p>The side mapping gets its own test. Every downstream trend is computed from the midpoint, so
 * a tape written with ask and bid swapped produces numbers that look entirely reasonable and are
 * wrong in a direction nothing else would reveal.
 */
class BazaarTapeTest {
	/**
	 * The recorded sample book, restamped to now.
	 *
	 * <p>The captured JSON carries the {@code lastUpdated} of the minute it was taped, and every
	 * caller here records it and reads it straight back through {@link #readBack}'s recent-days
	 * window. Left at its own stamp the fixture ages out of that window: {@link BazaarTape#record}
	 * files each sample under Hypixel's day, so a months-old capture lands in a day file
	 * {@code forEachRecent} no longer visits and every count assertion reads zero. Restamping is what
	 * the callers already mean by the fixture - a book just polled - and it keeps the file's own
	 * frozen stamp from being a dated time bomb. Tests wanting a second, newer book restamp off this.
	 */
	private static BazaarSnapshot fixture() throws Exception {
		try (InputStream in = BazaarTapeTest.class.getResourceAsStream("/bazaar-sample.json")) {
			BazaarDto dto = new Gson().fromJson(
					new InputStreamReader(in, StandardCharsets.UTF_8), BazaarDto.class);
			// The sample carries a fixed lastUpdated stamp; restamp to now so shape tests stay
			// inside forEachRecent's window as the fixture ages past it.
			return restamped(dto.toSnapshot(), Instant.now());
		}
	}

	/** The same book restamped, which is what a genuinely newer snapshot looks like. */
	private static BazaarSnapshot restamped(BazaarSnapshot original, Instant when) {
		return new BazaarSnapshot(when, original.products());
	}

	/** A one-product book at a chosen price, for tests that care about values rather than shape. */
	private static BazaarSnapshot book(Instant when, double ask, double bid) {
		BazaarProduct product = new BazaarProduct(
				"TEST_ITEM",
				List.of(new OrderLevel(ask, 100L, 3)),
				List.of(new OrderLevel(bid, 100L, 3)),
				new BazaarProduct.MovingWeek(1_000_000L, 900_000L));

		return new BazaarSnapshot(when, Map.of("TEST_ITEM", product));
	}

	private static List<BazaarSample> readBack(BazaarTape tape, int days) throws Exception {
		List<BazaarSample> out = new ArrayList<>();
		tape.forEachRecent(days, out::add);
		return out;
	}

	@Test
	void writesOneLinePerTwoSidedProduct(@TempDir Path dir) throws Exception {
		BazaarTape tape = new BazaarTape(dir, 30);
		BazaarSnapshot snapshot = fixture();

		assertEquals(snapshot.products().size(), tape.record(snapshot).size());
		assertEquals(snapshot.products().size(), readBack(tape, 30).size());
	}

	@Test
	void storesAskAboveBidSoTheSideMappingCannotSilentlyInvert(@TempDir Path dir) throws Exception {
		BazaarTape tape = new BazaarTape(dir, 30);
		tape.record(fixture());

		for (BazaarSample sample : readBack(tape, 30)) {
			assertTrue(sample.askPrice() > sample.bidPrice(),
					sample.productId() + ": ask " + sample.askPrice()
							+ " must exceed bid " + sample.bidPrice());
			assertTrue(sample.isTwoSided());
		}
	}

	@Test
	void roundTripsPricesThroughDiskWithoutLoss(@TempDir Path dir) throws Exception {
		BazaarTape tape = new BazaarTape(dir, 30);
		BazaarSnapshot snapshot = fixture();
		tape.record(snapshot);

		BazaarProduct diamond = snapshot.product("ENCHANTED_DIAMOND").orElseThrow();
		BazaarSample recovered = readBack(tape, 30).stream()
				.filter(s -> s.productId().equals("ENCHANTED_DIAMOND"))
				.findFirst()
				.orElseThrow();

		assertEquals(diamond.instantBuyPrice().getAsDouble(), recovered.askPrice(), 1e-9d);
		assertEquals(diamond.instantSellPrice().getAsDouble(), recovered.bidPrice(), 1e-9d);
		assertEquals(diamond.movingWeek().instantBought(), recovered.boughtWeek());
		assertEquals(diamond.movingWeek().instantSold(), recovered.soldWeek());
	}

	@Test
	void skipsABookThatHasNotMovedSinceTheLastSample(@TempDir Path dir) throws Exception {
		BazaarTape tape = new BazaarTape(dir, 30);
		BazaarSnapshot snapshot = fixture();

		assertEquals(snapshot.products().size(), tape.record(snapshot).size());

		// Hypixel regenerates the book on its own schedule, so most polls see the same one. Taping
		// it again would make a frozen market look like a stable one with twice the evidence.
		assertTrue(tape.record(snapshot).isEmpty());
		assertTrue(tape.record(snapshot).isEmpty());
		assertEquals(snapshot.products().size(), readBack(tape, 30).size());
	}

	@Test
	void recordsAgainOnceTheBookActuallyChanges(@TempDir Path dir) throws Exception {
		BazaarTape tape = new BazaarTape(dir, 30);
		BazaarSnapshot snapshot = fixture();

		tape.record(snapshot);
		tape.record(restamped(snapshot, snapshot.lastUpdated().plusSeconds(300)));

		assertEquals(snapshot.products().size() * 2, readBack(tape, 30).size());
	}

	@Test
	void filesSamplesUnderTheDayHypixelStampedNotTheDayTheyWereFetched(@TempDir Path dir)
			throws Exception {
		Instant twoDaysAgo = Instant.now().minus(2, ChronoUnit.DAYS);

		BazaarTape tape = new BazaarTape(dir, 30);
		tape.record(book(twoDaysAgo, 100.0d, 90.0d));

		String expected = twoDaysAgo.atZone(ZoneOffset.UTC).toLocalDate() + ".jsonl";
		assertTrue(Files.exists(dir.resolve(expected)),
				"expected the sample in " + expected + " but found " + listing(dir));
	}

	@Test
	void prunesOnlyFilesOlderThanRetention(@TempDir Path dir) throws Exception {
		BazaarTape tape = new BazaarTape(dir, 3);

		tape.record(book(Instant.now().minus(10, ChronoUnit.DAYS), 100.0d, 90.0d));
		tape.record(book(Instant.now().minus(1, ChronoUnit.DAYS), 110.0d, 99.0d));
		assertEquals(2, readBack(tape, 30).size());

		assertEquals(1, tape.prune());

		List<BazaarSample> survivors = readBack(tape, 30);
		assertEquals(1, survivors.size());
		assertEquals(110.0d, survivors.getFirst().askPrice(), 1e-9d);
	}

	@Test
	void survivesATruncatedFinalLine(@TempDir Path dir) throws Exception {
		BazaarTape tape = new BazaarTape(dir, 30);
		BazaarSnapshot snapshot = fixture();
		tape.record(snapshot);

		// A write cut off by a crash must cost one sample, not the whole day.
		Path file = dir.resolve(LocalDate.now(ZoneOffset.UTC) + ".jsonl");
		Path written = Files.exists(file) ? file : Files.list(dir).findFirst().orElseThrow();
		Files.writeString(written, "{\"p\":\"TRUNCATED\",\"a\":12", StandardOpenOption.APPEND);

		assertEquals(snapshot.products().size(), readBack(tape, 30).size());
	}

	@Test
	void rollsUpCompletedDaysButNeverTheDayStillBeingWritten(@TempDir Path dir) throws Exception {
		BazaarTape tape = new BazaarTape(dir, 30);
		// Anchored at midday UTC, not now-minus-24h: the samples below are spaced 600s apart, so a
		// run inside the last ten minutes of a UTC day pushed the third one into today's file.
		Instant yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1).atStartOfDay(ZoneOffset.UTC)
				.plusHours(12).toInstant();

		tape.record(book(yesterday, 100.0d, 90.0d));
		tape.record(book(yesterday.plusSeconds(300), 120.0d, 110.0d));
		tape.record(book(yesterday.plusSeconds(600), 140.0d, 130.0d));
		// Today's samples are still accumulating, so summarising them would freeze a partial day.
		tape.record(book(Instant.now(), 999.0d, 998.0d));

		assertEquals(1, tape.rollUpCompletedDays());

		List<BazaarDailyStat> index = tape.readDailyIndex();
		assertEquals(1, index.size());

		BazaarDailyStat stat = index.getFirst();
		assertEquals("TEST_ITEM", stat.productId());
		assertEquals(3, stat.samples());
		// Midpoints of the three completed-day books: 95, 115, 135.
		assertEquals(115.0d, stat.medianMid(), 1e-9d);
		assertEquals(95.0d, stat.minMid(), 1e-9d);
		assertEquals(135.0d, stat.maxMid(), 1e-9d);
	}

	@Test
	void rollUpIsIdempotentSoACrashedRunCanRepeatIt(@TempDir Path dir) throws Exception {
		BazaarTape tape = new BazaarTape(dir, 30);
		tape.record(book(Instant.now().minus(1, ChronoUnit.DAYS), 100.0d, 90.0d));

		assertEquals(1, tape.rollUpCompletedDays());
		assertEquals(0, tape.rollUpCompletedDays());
		assertEquals(1, tape.readDailyIndex().size());
	}

	@Test
	void pruneDropsIndexEntriesForDaysItDeleted(@TempDir Path dir) throws Exception {
		BazaarTape tape = new BazaarTape(dir, 3);

		tape.record(book(Instant.now().minus(10, ChronoUnit.DAYS), 100.0d, 90.0d));
		tape.record(book(Instant.now().minus(1, ChronoUnit.DAYS), 110.0d, 99.0d));
		tape.rollUpCompletedDays();
		assertEquals(2, tape.readDailyIndex().size());

		tape.prune();

		List<BazaarDailyStat> survivors = tape.readDailyIndex();
		assertEquals(1, survivors.size());
		assertFalse(survivors.getFirst().day()
				.equals(LocalDate.now(ZoneOffset.UTC).minusDays(10).toString()));
	}

	@Test
	void theIndexFileIsNeverMistakenForADayOfSamples(@TempDir Path dir) throws Exception {
		BazaarTape tape = new BazaarTape(dir, 30);
		tape.record(book(Instant.now().minus(1, ChronoUnit.DAYS), 100.0d, 90.0d));
		tape.rollUpCompletedDays();

		// daily.jsonl sits in the same directory and holds a different shape entirely. If the
		// day-file filter ever accepts it, every reader starts seeing phantom samples.
		assertTrue(Files.exists(dir.resolve("daily.jsonl")));
		assertEquals(1, readBack(tape, 30).size());
	}

	@Test
	void skipsProductsWithOnlyOneSideOfTheBook(@TempDir Path dir) throws Exception {
		BazaarProduct askOnly = new BazaarProduct(
				"ASK_ONLY",
				List.of(new OrderLevel(100.0d, 10L, 1)),
				List.of(),
				new BazaarProduct.MovingWeek(1L, 1L));

		Map<String, BazaarProduct> products = new HashMap<>();
		products.put("ASK_ONLY", askOnly);

		BazaarTape tape = new BazaarTape(dir, 30);

		// No midpoint exists, and a midpoint is the only thing ever read back out.
		assertTrue(tape.record(new BazaarSnapshot(Instant.now(), products)).isEmpty());
	}

	private static String listing(Path dir) throws Exception {
		try (var files = Files.list(dir)) {
			return files.map(p -> p.getFileName().toString()).toList().toString();
		}
	}
}
