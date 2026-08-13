package jeff.skyblockflipper.core.ledger;

import jeff.skyblockflipper.core.pricing.Fees;
import jeff.skyblockflipper.core.strategy.FlipCandidate;
import jeff.skyblockflipper.core.strategy.StrategyKind;
import jeff.skyblockflipper.core.track.Settlement;
import jeff.skyblockflipper.core.track.TradeEvent;

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
	void countsGrossNpcPayoutsSoTheDailyCapCanBeSpentDown(@TempDir Path dir) throws Exception {
		Ledger ledger = ledgerIn(dir);

		FlipCandidate npc = new FlipCandidate("ITEM", "ITEM", StrategyKind.NPC_FLIP, 100.0d, 120.0d,
				20.0d, 10L, 1000L, 200.0d, 0.9d, List.of("buy"), List.of());

		// Sold 6 of the 10 units to the NPC at 120. The cap is spent by what the NPC handed over,
		// so this is 720 - not the 120 of profit, and not the 1200 the plan hoped for.
		ledger.close(ledger.open(npc, 5_000L).id(), 6L, 120.0d, FEES);

		assertEquals(720L, ledger.npcCoinsReceivedSince(0L));

		// Bazaar flips spend none of it, however large.
		ledger.close(ledger.open(candidate("OTHER", 100.0d, 110.0d, 500L, 8.6d), 5_000L).id(),
				500L, 110.0d, FEES);

		assertEquals(720L, ledger.npcCoinsReceivedSince(0L));

		// A position still open counts at the price it was quoted to sell at - 10 units the plan
		// bought to hand to the NPC at 120 apiece. Stock bought under an NPC plan is stock bought to
		// hand over, so the counter runs from the buy and moves to the settled figure as it sells.
		ledger.open(npc, 6_000L);

		assertEquals(720L + 1_200L, ledger.npcCoinsReceivedSince(0L));

		// Yesterday's payouts are not this day's budget: the counter refills at the boundary.
		// Positions are closed against the real clock, so a boundary after now excludes them all.
		assertEquals(0L, ledger.npcCoinsReceivedSince(System.currentTimeMillis() + 60_000L));
	}

	/**
	 * The gap the user's own ledger showed on 2026-08-12: two Enchanted Poisonous Potato positions,
	 * 3.5M of stock between them, both reading zero sold long after the units had been carried to an
	 * NPC and sold. Nothing was wrong with the ledger - no settlement for an NPC sale ever reached
	 * it, so there was nothing to close them with.
	 */
	@Test
	void aSaleOverAnNpcCounterClosesTheNpcPosition(@TempDir Path dir) throws Exception {
		Ledger ledger = ledgerIn(dir);

		FlipCandidate npc = new FlipCandidate("EPP", "Enchanted Poisonous Potato",
				StrategyKind.NPC_FLIP, 1084.5d, 1599.8d, 515.3d, 10L, 10_845L, 5_153.0d, 0.9d,
				List.of("buy"), List.of());

		String id = ledger.open(npc, 1L).id();

		LedgerEntry part = ledger.record(npcSale("EPP", "Enchanted Poisonous Potato", 6L, 1599.8d),
				FEES, false).orElseThrow();

		assertEquals(LedgerEntry.Status.OPEN, part.status());
		assertEquals(6L, part.unitsSold());

		LedgerEntry done = ledger.record(npcSale("EPP", "Enchanted Poisonous Potato", 4L, 1599.8d),
				FEES, false).orElseThrow();

		assertEquals(LedgerEntry.Status.CLOSED, done.status());
		assertEquals(id, done.id());
		// Untaxed, so the whole posted price less what the unit cost is the realized margin.
		assertEquals(515.3d, done.realizedUnitNet(), 1e-9);
	}

	@Test
	void anNpcSaleOfSomethingYouNeverFlippedSettlesAgainstNothing(@TempDir Path dir) throws Exception {
		Ledger ledger = ledgerIn(dir);

		// Selling the cobblestone you mined is not a flip closing. With no position on the item
		// there is nothing to book it against, and inventing one would report the whole sale price
		// as profit.
		assertTrue(ledger.record(npcSale("COBBLESTONE", "Cobblestone", 64L, 1.0d), FEES, false)
				.isEmpty());
		assertEquals(0L, ledger.npcCoinsReceivedSince(0L));
	}

	private static Settlement npcSale(String itemId, String name, long units, double unitPrice) {
		return new Settlement(1L, Settlement.Venue.NPC, TradeEvent.Side.SELL, itemId, name, units,
				unitPrice, unitPrice * units);
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
	void aPositionCanSellItselfInPieces(@TempDir Path dir) throws Exception {
		// A 1,344 unit sell offer that filled 903 at 38.2 and the rest at 36.0, which is the shape
		// the recorded session produced and the shape a single close() call cannot express.
		Ledger ledger = ledgerIn(dir);
		LedgerEntry opened = ledger.open(candidate("SLIME_BALL", 30.0d, 40.0d, 1_344L, 9.5d), 1L);

		LedgerEntry part = ledger.record(sale(903L, 38.2d), FEES, true).orElseThrow();

		assertTrue(part.isOpen());
		assertEquals(903L, part.unitsSold());
		assertEquals(38.2d, part.unitSellPrice());

		LedgerEntry whole = ledger.record(sale(441L, 36.0d), FEES, true).orElseThrow();

		assertEquals(opened.id(), whole.id());
		assertEquals(LedgerEntry.Status.CLOSED, whole.status());
		assertEquals(1_344L, whole.unitsSold());

		// Weighted by units, so the cheap two thirds are not flattered by the dear third.
		assertEquals((903L * 38.2d + 441L * 36.0d) / 1_344L, whole.unitSellPrice(), 1e-9);
		assertEquals(whole.unitSellPrice() * 0.9875d - 30.0d, whole.realizedUnitNet(), 1e-9);
	}

	@Test
	void sellingMoreThanWasPlannedDoesNotGrowThePosition(@TempDir Path dir) throws Exception {
		// The extra units came from stock this position knows nothing about, and counting them
		// would credit that stock's profit to this plan.
		Ledger ledger = ledgerIn(dir);
		ledger.open(candidate("SLIME_BALL", 30.0d, 40.0d, 100L, 9.5d), 1L);

		LedgerEntry entry = ledger.record(sale(250L, 40.0d), FEES, true).orElseThrow();

		assertEquals(100L, entry.unitsSold());
		assertEquals(LedgerEntry.Status.CLOSED, entry.status());
	}

	@Test
	void aTradeNobodyQuotedStaysOutOfTheCaptureRate(@TempDir Path dir) throws Exception {
		// Bought and sold under tracking with no candidate behind it. Its quoted profit is zero, so
		// including it would report a total shortfall on a trade that did nothing wrong.
		Ledger ledger = ledgerIn(dir);
		ledger.record(new Settlement(1L, Settlement.Venue.BAZAAR_INSTANT, TradeEvent.Side.BUY,
				"SLIME_BALL", "Slimeball", 10L, 30.0d, 300.0d), FEES, true);
		ledger.record(sale(10L, 40.0d), FEES, true);

		LedgerStats stats = ledger.stats(null);

		assertEquals(0, stats.closed());
		assertEquals(1, stats.unquoted());
		assertFalse(stats.captureRate().isPresent());

		// It still says what fraction of a position comes back out, which needs no quote.
		assertEquals(1.0d, stats.fillRate().orElseThrow(), 1e-9);
	}

	@Test
	void entriesWrittenBeforeOriginsExistedAreHandTypedOnes(@TempDir Path dir) throws Exception {
		// Old ledger files have no origin field at all. Reading those as a null origin would drop
		// every one of them out of the capture rate the moment the field was added.
		Path file = dir.resolve("ledger.jsonl");
		Files.writeString(file, "{\"id\":\"old1\",\"itemId\":\"A\",\"displayName\":\"A\","
				+ "\"kind\":\"BAZAAR_SPREAD\",\"status\":\"CLOSED\",\"openedAt\":1,\"units\":10,"
				+ "\"unitBuyPrice\":100.0,\"quotedUnitNet\":8.625,\"capital\":1000,\"closedAt\":2,"
				+ "\"unitsSold\":10,\"unitSellPrice\":110.0,\"realizedUnitNet\":8.625}\n");

		Ledger ledger = new Ledger(file);
		ledger.load();

		assertEquals(LedgerEntry.Origin.MANUAL, ledger.all().getFirst().origin());
		assertEquals(1, ledger.stats(null).closed());
		assertEquals(1.0d, ledger.stats(null).captureRate().orElseThrow(), 1e-9);
	}

	@Test
	void forgettingAnEntryLeavesNoTraceOfItInTheRates(@TempDir Path dir) throws Exception {
		// Abandoning keeps the units in the fill rate, which is right for a plan that failed and
		// wrong for a stack of materials tracking recorded off an ordinary shopping trip.
		Path file = dir.resolve("ledger.jsonl");
		Ledger ledger = new Ledger(file);
		LedgerEntry kept = ledger.open(candidate("A", 100.0d, 110.0d, 10L, 8.6d), 1L);
		LedgerEntry junk = ledger.open(candidate("B", 100.0d, 110.0d, 500L, 8.6d), 2L);

		assertEquals(junk.id(), ledger.forget(junk.id()).orElseThrow().id());
		assertTrue(ledger.forget(junk.id()).isEmpty());

		ledger.close(kept.id(), 10L, 110.0d, FEES);

		assertEquals(10L, ledger.stats(null).unitsPlanned());

		Ledger reloaded = new Ledger(file);
		reloaded.load();

		assertEquals(1, reloaded.all().size());
	}

	@Test
	void clearingUnquotedEntriesKeepsTheFlipsYouTook(@TempDir Path dir) throws Exception {
		Ledger ledger = ledgerIn(dir);
		LedgerEntry taken = ledger.open(candidate("A", 100.0d, 110.0d, 10L, 8.6d), 1L);
		ledger.record(new Settlement(1L, Settlement.Venue.BAZAAR_INSTANT, TradeEvent.Side.BUY,
				"SLIME_BALL", "Slimeball", 10L, 30.0d, 300.0d), FEES, true);

		assertEquals(1L, ledger.count(entry -> !entry.isQuoted()));
		assertEquals(1, ledger.forgetAll(entry -> !entry.isQuoted()));

		assertEquals(List.of(taken.id()), ledger.all().stream().map(LedgerEntry::id).toList());
		assertEquals(0, ledger.forgetAll(entry -> !entry.isQuoted()));
	}

	private static Settlement sale(long units, double unitPrice) {
		return new Settlement(1L, Settlement.Venue.BAZAAR_ORDER, TradeEvent.Side.SELL, "SLIME_BALL",
				"Slimeball", units, unitPrice, unitPrice * units * 0.9875d);
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
