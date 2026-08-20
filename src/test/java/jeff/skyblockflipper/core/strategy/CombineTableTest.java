package jeff.skyblockflipper.core.strategy;

import jeff.skyblockflipper.core.strategy.CombineTable.Entry;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the shape of the combine allowlist and the tier arithmetic every quote rests on.
 *
 * <p>The table is game data, so a test cannot check that an enchant really combines. What it can
 * check is that the table never contradicts itself - a duplicate enchant would be quoted twice, an
 * empty source range would be a divide by nothing - and that {@code 2^(T-k)} is counted right, since
 * one off there is the difference between a flip and a loss.
 */
class CombineTableTest {
	@Test
	void everyEnchantAppearsOnce() {
		Set<String> seen = new HashSet<>();

		for (Entry entry : CombineTable.all()) {
			assertTrue(seen.add(entry.enchantId()), () -> entry.enchantId() + " is listed twice");
		}

		assertEquals(36, seen.size());
	}

	@Test
	void booksAndCombinesDoubleWithEachTierBelow() {
		Entry featherFalling = new Entry("FEATHER_FALLING", 6, 10);

		// 16 tier-6 books become one tier-10 in 15 anvil merges.
		assertEquals(16L, featherFalling.booksPerOutput(6));
		assertEquals(15L, featherFalling.combinesPerOutput(6));

		// One tier below the max is a single pair and a single combine.
		assertEquals(2L, featherFalling.booksPerOutput(9));
		assertEquals(1L, featherFalling.combinesPerOutput(9));
	}

	@Test
	void sourceTiersSpanTheRangeBelowTheMax() {
		assertEquals(List.of(6, 7, 8, 9), new Entry("FEATHER_FALLING", 6, 10).sourceTiers());
		assertEquals(List.of(3, 4), new Entry("QUANTUM", 3, 5).sourceTiers());
		assertEquals(List.of(2), new Entry("TABASCO", 2, 3).sourceTiers());
	}

	@Test
	void bookIdIsTheBazaarIdForThatTier() {
		Entry rejuvenate = new Entry("REJUVENATE", 1, 5);

		assertEquals("ENCHANTMENT_REJUVENATE_1", rejuvenate.bookId(1));
		assertEquals("ENCHANTMENT_REJUVENATE_5", rejuvenate.targetId());
	}

	@Test
	void anEmptySourceRangeIsRejected() {
		// A max at or below the source floor would combine nothing.
		assertThrows(IllegalArgumentException.class, () -> new Entry("BROKEN", 5, 5));
		assertThrows(IllegalArgumentException.class, () -> new Entry("BROKEN", 5, 4));
		assertThrows(IllegalArgumentException.class, () -> new Entry("BROKEN", 0, 5));
	}
}
