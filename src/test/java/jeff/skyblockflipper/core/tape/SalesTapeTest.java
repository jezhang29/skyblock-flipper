package jeff.skyblockflipper.core.tape;

import com.google.gson.Gson;

import jeff.skyblockflipper.core.model.EndedAuction;
import jeff.skyblockflipper.core.model.SaleDailyStat;
import jeff.skyblockflipper.core.model.dto.EndedAuctionsDto;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tape is the one asset here that cannot be rebuilt after the fact, so its failure modes
 * matter more than most: silently dropped sales bias every average computed downstream, and
 * duplicates do the same in the other direction.
 */
class SalesTapeTest {
	private static List<EndedAuction> fixture() throws Exception {
		try (InputStream in = SalesTapeTest.class.getResourceAsStream("/ended-auctions-sample.json")) {
			EndedAuctionsDto dto = new Gson().fromJson(
					new InputStreamReader(in, StandardCharsets.UTF_8), EndedAuctionsDto.class);
			return dto.auctions;
		}
	}

	@Test
	void bindsSnakeCaseFieldsOntoRecordComponents() throws Exception {
		EndedAuction sale = fixture().getFirst();

		// auction_id and item_bytes only bind if @SerializedName is honoured on record
		// components. If Gson ever regresses here these come back null rather than throwing.
		assertNotNull(sale.auctionId());
		assertNotNull(sale.itemBytes());
		assertFalse(sale.itemBytes().isBlank());
		assertTrue(sale.price() > 0L);
		assertTrue(sale.timestamp() > 0L);
	}

	@Test
	void roundTripsThroughDiskWithoutLoss(@TempDir Path dir) throws Exception {
		List<EndedAuction> sales = fixture();
		SalesTape tape = new SalesTape(dir, 30);

		assertEquals(sales.size(), tape.record(sales));

		List<EndedAuction> read = tape.readAll();
		assertEquals(sales.size(), read.size());

		// item_bytes is the whole point of storing these; a truncated blob is worthless later.
		EndedAuction original = sales.getFirst();
		EndedAuction recovered = read.stream()
				.filter(s -> s.auctionId().equals(original.auctionId()))
				.findFirst()
				.orElseThrow();

		assertEquals(original.itemBytes(), recovered.itemBytes());
		assertEquals(original.price(), recovered.price());
	}

	@Test
	void rejectsRepeatsBecausePollWindowsOverlap(@TempDir Path dir) throws Exception {
		List<EndedAuction> sales = fixture();
		SalesTape tape = new SalesTape(dir, 30);

		tape.record(sales);

		// The poller runs faster than the API's window on purpose, so the same sale arrives
		// several times. Every repeat must be dropped.
		assertEquals(0, tape.record(sales));
		assertEquals(0, tape.record(sales));
		assertEquals(sales.size(), tape.readAll().size());
	}

	@Test
	void filesSalesUnderTheDayTheyHappenedNotTheDayTheyWereFetched(@TempDir Path dir) throws Exception {
		EndedAuction template = fixture().getFirst();
		Instant twoDaysAgo = Instant.now().minus(2, ChronoUnit.DAYS);

		EndedAuction backdated = new EndedAuction(
				"backdated-id", template.seller(), template.buyer(),
				twoDaysAgo.toEpochMilli(), template.price(), template.bin(), template.itemBytes());

		SalesTape tape = new SalesTape(dir, 30);
		tape.record(List.of(backdated));

		String expected = twoDaysAgo.atZone(ZoneOffset.UTC).toLocalDate() + ".jsonl";
		assertTrue(Files.exists(dir.resolve(expected)),
				"expected sale to land in " + expected + " but found " + listing(dir));
	}

	@Test
	void prunesOnlyFilesOlderThanRetention(@TempDir Path dir) throws Exception {
		EndedAuction template = fixture().getFirst();

		EndedAuction old = withTimestamp(template, "old-id",
				Instant.now().minus(10, ChronoUnit.DAYS));
		EndedAuction recent = withTimestamp(template, "recent-id",
				Instant.now().minus(1, ChronoUnit.DAYS));

		SalesTape tape = new SalesTape(dir, 3);
		tape.record(List.of(old, recent));
		assertEquals(2, tape.readAll().size());

		assertEquals(1, tape.prune());

		List<EndedAuction> survivors = tape.readAll();
		assertEquals(1, survivors.size());
		assertEquals("recent-id", survivors.getFirst().auctionId());
	}

	@Test
	void survivesATruncatedFinalLine(@TempDir Path dir) throws Exception {
		List<EndedAuction> sales = fixture();
		SalesTape tape = new SalesTape(dir, 30);
		tape.record(sales);

		// Simulate a write cut off by a crash: one damaged line must cost one sale, not the file.
		Path file = Files.list(dir).findFirst().orElseThrow();
		Files.writeString(file, "{\"auction_id\":\"truncated\",\"pri",
				java.nio.file.StandardOpenOption.APPEND);

		assertEquals(sales.size(), tape.readAll().size());
	}

	@Test
	void rollsUpCompletedDaysOnlyAndOneAtATime(@TempDir Path dir) throws Exception {
		EndedAuction template = fixture().stream().filter(EndedAuction::bin).findFirst().orElseThrow();

		EndedAuction twoDaysAgo = withTimestamp(template, "two-days", Instant.now().minus(2, ChronoUnit.DAYS));
		EndedAuction yesterday = withTimestamp(template, "yesterday", Instant.now().minus(1, ChronoUnit.DAYS));
		EndedAuction today = withTimestamp(template, "today", Instant.now());

		SalesTape tape = new SalesTape(dir, 30);
		tape.record(List.of(twoDaysAgo, yesterday, today));

		assertEquals(1, tape.rollUpOneCompletedDay(), "should summarise one day per call");
		assertEquals(1, tape.rollUpOneCompletedDay());
		assertEquals(0, tape.rollUpOneCompletedDay(), "today is still being written to");

		List<SaleDailyStat> index = tape.readDailyIndex();
		assertEquals(2, index.size(), "one line per signature per completed day: " + index);

		String todaysDay = Instant.now().atZone(ZoneOffset.UTC).toLocalDate().toString();
		assertTrue(index.stream().noneMatch(stat -> stat.day().equals(todaysDay)),
				"a partial day in the index would never be corrected");

		SaleDailyStat stat = index.getFirst();
		assertEquals(1, stat.samples());
		assertEquals(template.price(), stat.median(), 1.0d);

		// Idempotent: re-running must not double-count a day already indexed.
		assertEquals(0, tape.rollUpOneCompletedDay());
		assertEquals(2, tape.readDailyIndex().size());
	}

	@Test
	void keepsTheRollupAfterTheRawDayIsPruned(@TempDir Path dir) throws Exception {
		EndedAuction template = fixture().stream().filter(EndedAuction::bin).findFirst().orElseThrow();
		EndedAuction old = withTimestamp(template, "old-id", Instant.now().minus(10, ChronoUnit.DAYS));

		SalesTape tape = new SalesTape(dir, 3);
		tape.record(List.of(old));

		assertEquals(1, tape.rollUpOneCompletedDay());
		assertEquals(1, tape.prune());

		// The whole point of the index: the raw sales are gone and the prices are not. Without
		// this, retention is also the horizon over which anything can be asked about the past.
		assertEquals(1, tape.readDailyIndex().size());
		assertTrue(tape.readAll().isEmpty(), "the raw day should be gone, and the index is not a day file");
	}

	private static EndedAuction withTimestamp(EndedAuction template, String id, Instant when) {
		return new EndedAuction(id, template.seller(), template.buyer(),
				when.toEpochMilli(), template.price(), template.bin(), template.itemBytes());
	}

	private static String listing(Path dir) throws Exception {
		try (var files = Files.list(dir)) {
			return files.map(p -> p.getFileName().toString()).toList().toString();
		}
	}
}
