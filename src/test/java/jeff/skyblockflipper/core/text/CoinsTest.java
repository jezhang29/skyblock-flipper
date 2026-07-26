package jeff.skyblockflipper.core.text;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CoinsTest {
	@Test
	void abbreviatesAtEachThreshold() {
		assertEquals("900", Coins.format(900L));
		assertEquals("1.0k", Coins.format(1_000L));
		assertEquals("1.00M", Coins.format(1_000_000L));
		assertEquals("1.00B", Coins.format(1_000_000_000L));
	}

	@Test
	void keepsTheSignOnLosses() {
		// A ledger entry that went the wrong way must not read as a gain.
		assertEquals("-1.50M", Coins.format(-1_500_000L));
	}

	@Test
	void roundsRatherThanTruncatingDoubles() {
		assertEquals("1.0k", Coins.format(999.6d));
	}
}
