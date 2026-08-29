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
package jeff.skyblockflipper.core.ledger;

import jeff.skyblockflipper.core.pricing.Fees;
import jeff.skyblockflipper.core.strategy.StrategyKind;
import jeff.skyblockflipper.core.track.Settlement;
import jeff.skyblockflipper.core.track.TradeEvent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A basket line is a quote, and a buy against one has to be booked as such.
 *
 * <p>The failure this exists for: on an account flipping NPC baskets full time the ledger stayed
 * empty for a whole session. Nothing opens a position for a basket line - {@code /flip take} and the
 * Take button both read the ranked candidate list, and the basket is not it - so every buy arrived
 * unquoted and {@code trackUnquotedTrades} dropped it. That took the capture rate, the fill rate and
 * the NPC daily cap down with it, the last of which reads {@code NPC_FLIP} entries and so read zero
 * spent forever.
 */
class LedgerQuotedBasketTest {
	private static final Fees FEES = new Fees(0, false);
	private static final Duration WINDOW = Duration.ofHours(8L);

	private static Ledger ledgerIn(Path dir) {
		return new Ledger(dir.resolve("ledger.jsonl"));
	}

	private static PlannedQuotes advised(long at) {
		PlannedQuotes quotes = new PlannedQuotes(() -> WINDOW);

		// 512 units at 750, quoted to net 210 each against an NPC price of 960.
		quotes.quoted(new Quote("MANTID_CLAW", "Mantid Claw", StrategyKind.NPC_FLIP, 750.0d, 210.0d,
				512L, 384_000L), at);
		return quotes;
	}

	private static Settlement bought(long at, long units, double unitPrice) {
		return new Settlement(at, Settlement.Venue.BAZAAR_ORDER, TradeEvent.Side.BUY, "MANTID_CLAW",
				"Mantid Claw", units, unitPrice, unitPrice * units);
	}

	@Test
	void booksABasketBuyAsQuotedEvenWithUnquotedTrackingOff(@TempDir Path dir) throws Exception {
		Ledger ledger = ledgerIn(dir);

		LedgerEntry entry = ledger
				.record(bought(1_000L, 512L, 752.0d), FEES, false, advised(0L))
				.orElseThrow();

		assertEquals(LedgerEntry.Origin.AUTO_QUOTED, entry.origin());
		assertTrue(entry.isQuoted());

		// The strategy that made the promise, not the venue the buy happened at. An NPC flip is
		// bought on the bazaar like anything else, and reading the kind off the settlement is what
		// kept NPC_FLIP entries out of the ledger and the daily cap reading zero.
		assertEquals(StrategyKind.NPC_FLIP, entry.kind());

		// The quote is the plan's; the buy price is what was actually paid.
		assertEquals(210.0d, entry.quotedUnitNet());
		assertEquals(752.0d, entry.unitBuyPrice());
		assertEquals(512L, entry.units());
	}

	@Test
	void aBuyWithNoPlanBehindItStillOpensNothing(@TempDir Path dir) throws Exception {
		Ledger ledger = ledgerIn(dir);
		PlannedQuotes quotes = advised(0L);

		// Most bazaar buying is playing the game rather than flipping, which is the whole reason
		// trackUnquotedTrades exists. Having quotes for other items must not change that.
		assertTrue(ledger.record(new Settlement(1_000L, Settlement.Venue.BAZAAR_INSTANT,
				TradeEvent.Side.BUY, "JUNGLE_HEART", "Jungle Heart", 4L, 1_000.0d, 4_000.0d),
				FEES, false, quotes).isEmpty());

		assertEquals(0, ledger.all().size());
	}

	@Test
	void aStaleQuoteIsNotAPlan(@TempDir Path dir) throws Exception {
		Ledger ledger = ledgerIn(dir);

		// Bought a day after the basket advised it: that is a trade on today's book, and crediting
		// it to yesterday's promise would measure the capture rate against a plan nobody followed.
		assertTrue(ledger.record(bought(WINDOW.toMillis() + 1L, 512L, 752.0d), FEES, false,
				advised(0L)).isEmpty());
	}

	@Test
	void aPartialFillBooksOnlyTheUnitsBought(@TempDir Path dir) throws Exception {
		Ledger ledger = ledgerIn(dir);

		LedgerEntry entry = ledger
				.record(bought(1_000L, 200L, 750.0d), FEES, false, advised(0L))
				.orElseThrow();

		// You cannot sell more than you bought, so the position is the fill and not the plan.
		assertEquals(200L, entry.units());
		assertEquals(150_000L, entry.capital());
	}

	@Test
	void theDailyNpcCapCountsWhatTheBasketBought(@TempDir Path dir) throws Exception {
		Ledger ledger = ledgerIn(dir);

		ledger.record(bought(1_000L, 512L, 750.0d), FEES, false, advised(0L));

		// Gross coins the NPC will hand over: the cap is spent by the payout, so it is the buy price
		// plus the quoted net, on the units actually held. 512 x (750 + 210).
		assertEquals(491_520L, ledger.npcCoinsReceivedSince(0L));

		// And nothing bought before the day started counts against today's budget.
		assertEquals(0L, ledger.npcCoinsReceivedSince(2_000L));
	}

	@Test
	void aHandTakenPositionStillWinsOverTheBasket(@TempDir Path dir) throws Exception {
		Ledger ledger = ledgerIn(dir);
		LedgerEntry taken = ledger.open(new jeff.skyblockflipper.core.strategy.FlipCandidate(
				"MANTID_CLAW", "Mantid Claw", StrategyKind.NPC_FLIP, 740.0d, 960.0d, 220.0d, 512L,
				378_880L, 14_080.0d, 0.8d, java.util.List.of("buy"), java.util.List.of()), 1L);

		LedgerEntry entry = ledger
				.record(bought(1_000L, 512L, 752.0d), FEES, false, advised(0L))
				.orElseThrow();

		// One position, not two: the player said what they were doing, and that beats an inference.
		assertEquals(taken.id(), entry.id());
		assertEquals(220.0d, entry.quotedUnitNet());
		assertEquals(1, ledger.all().size());
	}
}
