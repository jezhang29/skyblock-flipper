package jeff.skyblockflipper.core.ledger;

import jeff.skyblockflipper.core.strategy.StrategyKind;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlannedQuotesTest {
	private static final Duration WINDOW = Duration.ofHours(8L);

	private static PlannedQuotes quotes() {
		return new PlannedQuotes(() -> WINDOW);
	}

	private static Quote quote(String itemId, String name, double unitNet) {
		return new Quote(itemId, name, StrategyKind.NPC_FLIP, 100.0d, unitNet, 256L, 25_600L);
	}

	@Test
	void holdsAQuoteForTheWholeWindowAndNoLonger() {
		PlannedQuotes quotes = quotes();

		quotes.quoted(quote("MANTID_CLAW", "Mantid Claw", 40.0d), 0L);

		// The point of the store: a buy claims hours after the basket advised it.
		assertTrue(quotes.quoteFor("MANTID_CLAW", "Mantid Claw", WINDOW.toMillis()).isPresent());
		assertFalse(quotes.quoteFor("MANTID_CLAW", "Mantid Claw", WINDOW.toMillis() + 1L).isPresent());
	}

	@Test
	void replacesAnItemsQuoteRatherThanKeepingBoth() {
		PlannedQuotes quotes = quotes();

		quotes.quoted(quote("MANTID_CLAW", "Mantid Claw", 40.0d), 0L);
		quotes.quoted(quote("MANTID_CLAW", "Mantid Claw", 55.0d), 1_000L);

		assertEquals(1, quotes.size());
		assertEquals(55.0d,
				quotes.quoteFor("MANTID_CLAW", "Mantid Claw", 2_000L).orElseThrow().unitNetProfit());
	}

	@Test
	void fallsBackToTheNameOnlyWhenThereIsNoId() {
		PlannedQuotes quotes = quotes();

		quotes.quoted(quote("MANTID_CLAW", "Mantid Claw", 40.0d), 0L);

		// An order the tracker never saw in a menu carries no id, and the name is all there is.
		assertTrue(quotes.quoteFor("", "Mantid Claw", 1_000L).isPresent());

		// But an empty name must not match the first quote in the map, which is how every unnamed
		// trade would settle against one arbitrary plan.
		assertFalse(quotes.quoteFor("", "", 1_000L).isPresent());
	}

	@Test
	void anIdThatMatchesNothingIsNotAnswered() {
		PlannedQuotes quotes = quotes();

		quotes.quoted(quote("MANTID_CLAW", "Mantid Claw", 40.0d), 0L);

		assertFalse(quotes.quoteFor("JUNGLE_HEART", "Jungle Heart", 1_000L).isPresent());
	}

	@Test
	void pruningDropsWhatHasGoneStale() {
		PlannedQuotes quotes = quotes();

		quotes.quoted(quote("MANTID_CLAW", "Mantid Claw", 40.0d), 0L);
		quotes.quoted(quote("JUNGLE_HEART", "Jungle Heart", 40.0d), WINDOW.toMillis());
		quotes.prune(WINDOW.toMillis() + 1L);

		assertEquals(1, quotes.size());
		assertTrue(quotes.quoteFor("JUNGLE_HEART", "Jungle Heart", WINDOW.toMillis() + 1L).isPresent());
	}
}
