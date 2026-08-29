package jeff.skyblockflipper.core.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link BazaarProduct#costToBuy(long)}, which is where a plan finds out what it will really
 * pay.
 *
 * <p>Quoting top of book for a whole order understates the bill, always in the flattering
 * direction, because every level below the first is dearer. That makes it the same shape of silent
 * error as reading {@code quick_status.buyPrice} as a top-of-book price - a number that looks right
 * and is not.
 */
class BazaarProductTest {
	private static BazaarProduct withAsks(OrderLevel... levels) {
		return new BazaarProduct("X", List.of(levels),
				List.of(new OrderLevel(1.0d, 100L, 5)),
				new BazaarProduct.MovingWeek(1L, 1L));
	}

	private static BazaarProduct withBids(OrderLevel... levels) {
		return new BazaarProduct("X", List.of(new OrderLevel(999.0d, 100L, 1)),
				List.of(levels), new BazaarProduct.MovingWeek(1L, 1L));
	}

	@Test
	void quotesTheTopLevelForAnOrderThatFitsInIt() {
		BazaarProduct product = withAsks(new OrderLevel(10.0d, 100L, 5));

		assertEquals(10.0d, product.costToBuy(1L).orElseThrow(), 1e-9);
		assertEquals(10.0d, product.costToBuy(100L).orElseThrow(), 1e-9);
	}

	/** Ten units at 10 and ten at 20 average 15, not the 10 the top level advertises. */
	@Test
	void averagesAcrossEveryLevelItEats() {
		BazaarProduct product = withAsks(
				new OrderLevel(10.0d, 10L, 5),
				new OrderLevel(20.0d, 10L, 5));

		assertEquals(15.0d, product.costToBuy(20L).orElseThrow(), 1e-9);
		// Ten at 10 plus two at 20 is 140 for twelve units.
		assertEquals(140.0d / 12.0d, product.costToBuy(12L).orElseThrow(), 1e-9);
	}

	/**
	 * The price of the sixty units that happen to be resting answers a different question than the
	 * one asked, and answers it more cheaply. Empty is the only honest reply.
	 */
	@Test
	void refusesAnOrderTheVisibleBookCannotCover() {
		BazaarProduct product = withAsks(new OrderLevel(10.0d, 60L, 5));

		assertTrue(product.costToBuy(61L).isEmpty());
		assertTrue(withAsks().costToBuy(1L).isEmpty());
	}

	@Test
	void refusesANonPositiveQuantity() {
		BazaarProduct product = withAsks(new OrderLevel(10.0d, 100L, 5));

		assertTrue(product.costToBuy(0L).isEmpty());
		assertTrue(product.costToBuy(-1L).isEmpty());
	}

	/** One unit is the top of book, which is what {@link BazaarProduct#instantBuyPrice()} says. */
	@Test
	void agreesWithInstantBuyPriceForOneUnit() {
		BazaarProduct product = withAsks(
				new OrderLevel(10.0d, 5L, 5),
				new OrderLevel(20.0d, 5L, 5));

		assertEquals(product.instantBuyPrice().orElseThrow(), product.costToBuy(1L).orElseThrow(), 1e-9);
	}

	@Test
	void instantSellWalksBidsRatherThanAsks() {
		BazaarProduct product = withBids(
				new OrderLevel(20.0d, 2L, 1),
				new OrderLevel(10.0d, 3L, 1));

		assertEquals(70.0d, product.proceedsFromInstantSell(5L).orElseThrow(), 1e-9);
		assertEquals(20.0d, product.proceedsFromInstantSell(1L).orElseThrow(), 1e-9);
	}

	@Test
	void instantSellRefusesPartialDepthAndInvalidQuantities() {
		BazaarProduct product = withBids(new OrderLevel(20.0d, 2L, 1));

		assertTrue(product.proceedsFromInstantSell(3L).isEmpty());
		assertTrue(product.proceedsFromInstantSell(0L).isEmpty());
		assertTrue(withBids().proceedsFromInstantSell(1L).isEmpty());
	}
}
