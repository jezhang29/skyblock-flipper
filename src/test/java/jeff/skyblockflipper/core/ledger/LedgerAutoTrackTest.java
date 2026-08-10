package jeff.skyblockflipper.core.ledger;

import jeff.skyblockflipper.core.pricing.Fees;
import jeff.skyblockflipper.core.strategy.FlipCandidate;
import jeff.skyblockflipper.core.strategy.StrategyKind;
import jeff.skyblockflipper.core.track.CaptureSession;
import jeff.skyblockflipper.core.track.Settlement;
import jeff.skyblockflipper.core.track.TradeTracker;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The whole path, from a recorded hour of trading to ledger entries.
 *
 * <p>The session is not a tidy one: ten positions were bought, none of them was sold again, and the
 * things that did sell were stock from before the recording. That is what a real hour looks like,
 * and the ledger has to come out of it saying so rather than inventing round trips.
 */
class LedgerAutoTrackTest {
	private static final Fees FEES = new Fees(0, false);

	private static List<Settlement> settlements;

	@BeforeAll
	static void replayTheSession() throws IOException {
		try (InputStream in = LedgerAutoTrackTest.class.getResourceAsStream("/trade-capture-sample.jsonl")) {
			CaptureSession session = CaptureSession.read(new InputStreamReader(in, StandardCharsets.UTF_8));
			settlements = TradeTracker.replay(session, "Bee__Bot").settlements();
		}
	}

	@Test
	void booksEveryBuyAsAPosition(@TempDir Path dir) throws Exception {
		Ledger ledger = record(dir);

		// Nine bazaar buys and the 25M BIN. Every one of them is a position nobody quoted.
		assertEquals(10, ledger.all().size());
		assertTrue(ledger.all().stream()
				.allMatch(e -> e.origin() == LedgerEntry.Origin.AUTO_UNQUOTED && e.isOpen()));
		assertEquals(1, ledger.all().stream().filter(e -> e.kind() == StrategyKind.AUCTION_VALUE).count());
	}

	@Test
	void ignoresASaleWithNoPositionBehindIt(@TempDir Path dir) throws Exception {
		// 2,340 Slimeballs were sold in this session and not one was bought in it. Booking those
		// against nothing would report about 87,000 coins of pure profit that is really just stock
		// the player already had.
		Ledger ledger = record(dir);

		assertTrue(ledger.all().stream().noneMatch(e -> e.displayName().equals("Slimeball")));
		assertTrue(ledger.all().stream().noneMatch(e -> e.unitsSold() > 0L));
	}

	@Test
	void takesOverAPositionThePlayerHadAlreadyQuoted(@TempDir Path dir) throws Exception {
		Ledger ledger = new Ledger(dir.resolve("ledger.jsonl"));

		// Taken by hand before the buy went through, quoting 8 units at 200 coins.
		LedgerEntry taken = ledger.open(new FlipCandidate("ENCHANTED_ENDSTONE", "Enchanted End Stone",
				StrategyKind.BAZAAR_SPREAD, 200.0d, 260.0d, 55.0d, 8L, 1_600L, 440.0d, 0.9d,
				List.of("buy"), List.of()), 1L);

		for (Settlement settlement : settlements) {
			ledger.record(settlement, FEES, true);
		}

		LedgerEntry entry = ledger.get(taken.id()).orElseThrow();

		// The order actually filled at 223.9, so the plan becomes the trade.
		assertEquals(LedgerEntry.Origin.AUTO_QUOTED, entry.origin());
		assertEquals(223.9d, entry.unitBuyPrice());
		assertEquals(1_791L, entry.capital());

		// The quote does not move. A quote that gets corrected afterwards can never be found wrong.
		assertEquals(55.0d, entry.quotedUnitNet());

		// And it is the same entry, not a second one alongside it.
		assertEquals(10, ledger.all().size());
	}

	@Test
	void withUnquotedTrackingOffOnlyThePlanYouTookIsBooked(@TempDir Path dir) throws Exception {
		// The default. Nine of these ten buys are someone playing the game - materials bought to be
		// used, not flipped - and each one would otherwise sit open in the ledger for good.
		Ledger ledger = new Ledger(dir.resolve("ledger.jsonl"));
		LedgerEntry taken = ledger.open(new FlipCandidate("ENCHANTED_ENDSTONE", "Enchanted End Stone",
				StrategyKind.BAZAAR_SPREAD, 200.0d, 260.0d, 55.0d, 8L, 1_600L, 440.0d, 0.9d,
				List.of("buy"), List.of()), 1L);

		for (Settlement settlement : settlements) {
			ledger.record(settlement, FEES, false);
		}

		assertEquals(1, ledger.all().size());
		assertEquals(LedgerEntry.Origin.AUTO_QUOTED, ledger.get(taken.id()).orElseThrow().origin());
	}

	private static Ledger record(Path dir) throws IOException {
		Ledger ledger = new Ledger(dir.resolve("ledger.jsonl"));

		for (Settlement settlement : settlements) {
			ledger.record(settlement, FEES, true);
		}

		return ledger;
	}
}
