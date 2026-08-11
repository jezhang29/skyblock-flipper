package jeff.skyblockflipper.core.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stacking is read off the order book, because the items resource does not know.
 *
 * <p>The bug these pin: {@code JUNGLE_HEART} carries no {@code unstackable} flag and no reforge
 * stone does either, so a basket built on the flag asked for 500 units of an item the bazaar takes
 * 256 of in one order.
 */
class StackingTest {
	private static BazaarProduct product(List<OrderLevel> bids) {
		return new BazaarProduct("TEST_ITEM", List.of(), bids,
				new BazaarProduct.MovingWeek(1_000L, 1_000L));
	}

	private static ItemCatalog.Entry entry(boolean unstackable) {
		return new ItemCatalog.Entry("TEST_ITEM", "Test Item", 100.0d, unstackable, List.of());
	}

	@Test
	void anOrderLargerThanTheUnstackableCeilingProvesTheItemStacks() {
		// 2,000 units across 4 orders means one of them holds at least 500, which is more than a
		// bazaar order of an unstackable item may ever hold.
		BazaarProduct product = product(List.of(new OrderLevel(10.0d, 2_000L, 4)));

		assertEquals(500L, product.largestRestingOrder());
		assertTrue(Stacking.stackable(entry(false), product));
		assertEquals(Stacking.UNITS_PER_ORDER_STACKABLE,
				Stacking.unitsPerOrder(entry(false), product));
		assertEquals(Stacking.STACK_SIZE_STACKABLE, Stacking.stackSize(entry(false), product));
	}

	@Test
	void aBookThatNeverExceedsTheCeilingIsTreatedAsUnstackable() {
		// The shape a real reforge stone has: levels that top out at exactly 256. The catalog says
		// nothing about it - measured 2026-08-11, none of the 107 reforge stones carries the flag -
		// so the book has to be what decides, and unproven has to mean 256.
		BazaarProduct product = product(List.of(
				new OrderLevel(10.0d, 256L, 1),
				new OrderLevel(9.0d, 512L, 2)));

		assertEquals(256L, product.largestRestingOrder());
		assertFalse(Stacking.stackable(entry(false), product));
		assertEquals(256L, Stacking.unitsPerOrder(entry(false), product));
		assertEquals(1, Stacking.stackSize(entry(false), product));
	}

	@Test
	void theCatalogFlagStillDecidesWhereItIsSet() {
		// Where the resource does set the flag it has never been contradicted by a book: 0 of the 68
		// flagged bazaar products has shown an order over 256. So a set flag outranks the evidence.
		BazaarProduct deep = product(List.of(new OrderLevel(10.0d, 71_680L, 1)));

		assertTrue(Stacking.stackable(entry(false), deep));
		assertFalse(Stacking.stackable(entry(true), deep));
	}

	@Test
	void theLargestOrderIsALowerBoundAndNeverAGuess() {
		// A level of 300 units held by 2 orders proves an order of 150, not of 300. Rounding that
		// up would claim evidence the book has not given.
		assertEquals(150L, product(List.of(new OrderLevel(10.0d, 300L, 2))).largestRestingOrder());

		// A level with no orders on it says nothing rather than dividing by zero.
		assertEquals(0L, product(List.of(new OrderLevel(10.0d, 300L, 0))).largestRestingOrder());
	}

	@Test
	void anAbsentBookIsUnstackableRatherThanAnException() {
		assertFalse(Stacking.stackable(entry(false), null));
		assertFalse(Stacking.stackable(null, null));
		assertEquals(256L, Stacking.unitsPerOrder(null, null));
	}
}
