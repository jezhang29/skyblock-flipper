package jeff.skyblockflipper.core.ledger;

import jeff.skyblockflipper.core.pricing.Fees;
import jeff.skyblockflipper.core.strategy.FlipCandidate;
import jeff.skyblockflipper.core.strategy.StrategyKind;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ledger is the only thing in the mod that can contradict the mod, so these tests are mostly
 * about it staying honest: quotes frozen at open time, fees applied on the same basis as the quote,
 * and unsold units never quietly counted as profit.
 */
class LedgerTest {
	private static final Fees FEES = new Fees(0, false);

	private static FlipCandidate candidate(String id, double buy, double sell, long units, double netPerUnit) {
		return new FlipCandidate(id, id, StrategyKind.BAZAAR_SPREAD, buy, sell, netPerUnit, units,
				Math.round(buy * units), netPerUnit * units, 0.8d,
				List.of("buy", "sell"), List.of());
	}

	private static Ledger ledgerIn(Path dir) {
		return new Ledger(dir.resolve("ledger.jsonl"));
	}

	@Test
	void closesAPositionWithWhatActuallyHappened(@TempDir Path dir) throws Exception {
		Ledger ledger = ledgerIn(dir);
		LedgerEntry opened = ledger.open(candidate("ITEM", 100.0d, 110.0d, 10L, 8.6d), 1L);

		// Sold at 105 rather than the quoted 110, and only 6 of the 10 units went out.
		LedgerEntry closed = ledger.close(opened.id(), 6L, 105.0d, FEES).orElseThrow();

		assertEquals(6L, closed.unitsSold());
		// 105 less the 1.25% bazaar tax, less the 100 paid.
		assertEquals(105.0d * 0.9875d - 100.0d, closed.realizedUnitNet(), 1e-9);
		assertEquals(closed.realizedUnitNet() * 6L, closed.realizedTotal(), 1e-9);
	}

	@Test
	void unsoldUnitsAreInventoryNotProfit(@TempDir Path dir) throws Exception {
		Ledger ledger = ledgerIn(dir);
		LedgerEntry opened = ledger.open(candidate("ITEM", 100.0d, 110.0d, 100L, 8.625d), 1L);

		LedgerEntry closed = ledger.close(opened.id(), 10L, 110.0d, FEES).orElseThrow();

		// Both sides count the ten units that transacted, so the capture rate compares like with
		// like. Counting the quote on all 100 would invent a 90% shortfall out of nothing.
		assertEquals(closed.quotedUnitNet() * 10L, closed.quotedOnFilled(), 1e-9);
		assertEquals(1.0d, ledger.stats(null).captureRate().orElseThrow(), 1e-6);
		assertEquals(0.1d, ledger.stats(null).fillRate().orElseThrow(), 1e-9);
	}

	@Test
	void measuresTheGapBetweenQuotedAndRealized(@TempDir Path dir) throws Exception {
		Ledger ledger = ledgerIn(dir);

		for (int i = 0; i < 5; i++) {
			// Quoted 10 net per unit; fills come back at half that, which is what adverse
			// selection looks like from the inside.
			LedgerEntry entry = ledger.open(candidate("ITEM", 100.0d, 111.4d, 10L, 10.0d), 1L);
			ledger.close(entry.id(), 10L, 106.0d, FEES);
		}

		LedgerStats stats = ledger.stats(null);

		assertEquals(5, stats.closed());
		assertTrue(stats.isMeaningful());
		assertEquals(0.4675d, stats.captureRate().orElseThrow(), 1e-4);
	}

	@Test
	void npcSalesAreNotTaxed(@TempDir Path dir) throws Exception {
		Ledger ledger = ledgerIn(dir);
		FlipCandidate npc = new FlipCandidate("ITEM", "ITEM", StrategyKind.NPC_FLIP, 100.0d, 120.0d,
				20.0d, 1L, 100L, 20.0d, 0.9d, List.of("buy"), List.of());

		LedgerEntry closed = ledger.close(ledger.open(npc, 1L).id(), 1L, 120.0d, FEES).orElseThrow();

		// The NPC pays its posted price flat; deducting bazaar tax here would understate every
		// NPC flip and make the strategy look broken.
		assertEquals(20.0d, closed.realizedUnitNet(), 1e-9);
	}

	@Test
	void abandonedPositionsCountAgainstFillRateButNotCaptureRate(@TempDir Path dir) throws Exception {
		Ledger ledger = ledgerIn(dir);
		LedgerEntry filled = ledger.open(candidate("A", 100.0d, 110.0d, 10L, 8.625d), 1L);
		ledger.close(filled.id(), 10L, 110.0d, FEES);
		ledger.abandon(ledger.open(candidate("B", 100.0d, 110.0d, 10L, 8.625d), 1L).id());

		LedgerStats stats = ledger.stats(null);

		assertEquals(1, stats.closed());
		assertEquals(1, stats.abandoned());
		// An order that never filled says nothing about price, but everything about reachability.
		assertEquals(1.0d, stats.captureRate().orElseThrow(), 1e-6);
		assertEquals(0.5d, stats.fillRate().orElseThrow(), 1e-9);
	}

	@Test
	void quotesAreFrozenAtOpenTime(@TempDir Path dir) throws Exception {
		Ledger ledger = ledgerIn(dir);
		LedgerEntry opened = ledger.open(candidate("ITEM", 100.0d, 110.0d, 10L, 8.6d), 1L);

		ledger.close(opened.id(), 10L, 90.0d, FEES);

		// The book has moved against us; the entry still remembers what was promised.
		assertEquals(8.6d, ledger.get(opened.id()).orElseThrow().quotedUnitNet(), 1e-9);
	}

	@Test
	void refusesToCloseTwice(@TempDir Path dir) throws Exception {
		Ledger ledger = ledgerIn(dir);
		LedgerEntry opened = ledger.open(candidate("ITEM", 100.0d, 110.0d, 10L, 8.6d), 1L);

		assertTrue(ledger.close(opened.id(), 10L, 110.0d, FEES).isPresent());
		// Otherwise a mistyped id could double-count a good fill into the capture rate.
		assertTrue(ledger.close(opened.id(), 10L, 110.0d, FEES).isEmpty());
		assertTrue(ledger.abandon(opened.id()).isEmpty());
	}

	@Test
	void survivesSaveAndReload(@TempDir Path dir) throws Exception {
		Ledger ledger = ledgerIn(dir);
		LedgerEntry open = ledger.open(candidate("A", 100.0d, 110.0d, 10L, 8.6d), 1L);
		LedgerEntry done = ledger.open(candidate("B", 50.0d, 60.0d, 4L, 8.0d), 2L);
		ledger.close(done.id(), 4L, 60.0d, FEES);

		Ledger reloaded = ledgerIn(dir);
		reloaded.load();

		assertEquals(2, reloaded.all().size());
		assertEquals(List.of(open.id()), reloaded.openEntries().stream().map(LedgerEntry::id).toList());
		assertEquals(1, reloaded.stats(null).closed());
		assertEquals(1_000L, reloaded.committedCapital());
	}

	@Test
	void statsCanBeReadPerStrategy(@TempDir Path dir) throws Exception {
		Ledger ledger = ledgerIn(dir);
		ledger.close(ledger.open(candidate("A", 100.0d, 110.0d, 10L, 8.625d), 1L).id(), 10L, 110.0d, FEES);

		assertEquals(1, ledger.stats(StrategyKind.BAZAAR_SPREAD).closed());
		assertEquals(0, ledger.stats(StrategyKind.NPC_FLIP).closed());
		assertFalse(ledger.stats(StrategyKind.NPC_FLIP).captureRate().isPresent());
	}

	@Test
	void oneCorruptLineCostsOneEntry(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("ledger.jsonl");
		Ledger ledger = new Ledger(file);
		ledger.open(candidate("A", 100.0d, 110.0d, 10L, 8.6d), 1L);
		ledger.open(candidate("B", 100.0d, 110.0d, 10L, 8.6d), 2L);

		Files.writeString(file, "{\"id\": \"broke\", \"itemId\": \n" + Files.readString(file));

		Ledger reloaded = new Ledger(file);
		reloaded.load();

		assertEquals(2, reloaded.all().size());
	}
}
