package jeff.skyblockflipper.core.pricing;

import jeff.skyblockflipper.core.model.BazaarProduct;
import jeff.skyblockflipper.core.model.BazaarSnapshot;
import jeff.skyblockflipper.core.model.ItemCatalog;
import jeff.skyblockflipper.core.model.OrderLevel;
import jeff.skyblockflipper.core.model.UpgradeCost;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpgradePricingTest {
	/**
	 * Arack's real star ladder: 15/25/35/45/65 spider essence, and the ask side of the live book at
	 * the time of writing. Ask 1510.0, bid 1443.3 - the ask is the one to price with, since putting
	 * the stars on today means instant-buying the essence today.
	 */
	private static final ItemCatalog.Entry ARACK = new ItemCatalog.Entry("ARACK", "Arack", 5_000.0d,
			List.of(
					star("ESSENCE_SPIDER", 15),
					star("ESSENCE_SPIDER", 25),
					star("ESSENCE_SPIDER", 35),
					star("ESSENCE_SPIDER", 45),
					star("ESSENCE_SPIDER", 65)));

	private static final BazaarSnapshot BOOK = book("ESSENCE_SPIDER", 1_510.0d, 1_443.3d);

	@Test
	void pricesARunOfStarsOffTheAskSide() {
		// 15 + 25 + 35 = 75 essence at 1510 each.
		UpgradePricing.StarQuote quote = UpgradePricing.quoteStars(ARACK, 3, BOOK).orElseThrow();

		assertEquals(3, quote.stars());
		assertEquals(113_250.0d, quote.coins(), 1e-6);
		assertEquals(37_750.0d, quote.coinsPerStar(), 1e-6);
	}

	@Test
	void pricesTheWholeLadder() {
		// 185 essence in total. Pricing off the bid instead would come to 108299 less, which is the
		// error this would make silently if the sides were ever swapped.
		assertEquals(279_350.0d,
				UpgradePricing.quoteStars(ARACK, 5, BOOK).orElseThrow().coins(), 1e-6);
	}

	@Test
	void sumsEveryIngredientOfAMixedStar() {
		ItemCatalog.Entry mixed = new ItemCatalog.Entry("ROD", "Rod", null, List.of(
				new UpgradeCost(List.of(
						new UpgradeCost.Ingredient("LUMP_OF_MAGMA", 20),
						new UpgradeCost.Ingredient("ESSENCE_CRIMSON", 50)))));

		BazaarSnapshot both = new BazaarSnapshot(Instant.now(), Map.of(
				"LUMP_OF_MAGMA", product("LUMP_OF_MAGMA", 100.0d, 90.0d),
				"ESSENCE_CRIMSON", product("ESSENCE_CRIMSON", 1_020.0d, 987.4d)));

		// 20 * 100 + 50 * 1020. Dropping either ingredient still yields a plausible-looking total,
		// which is why this is asserted rather than eyeballed.
		assertEquals(53_000.0d,
				UpgradePricing.quoteStars(mixed, 1, both).orElseThrow().coins(), 1e-6);
	}

	@Test
	void refusesToQuoteWhenAnIngredientHasNoBook() {
		ItemCatalog.Entry mixed = new ItemCatalog.Entry("ROD", "Rod", null, List.of(
				new UpgradeCost(List.of(
						new UpgradeCost.Ingredient("ESSENCE_CRIMSON", 50),
						new UpgradeCost.Ingredient("UNTRADED_THING", 1)))));

		// A partial total would understate the cost, and on this number understating always reads
		// as a better deal than it is.
		assertEquals(Optional.empty(), UpgradePricing.quoteStars(mixed, 1,
				book("ESSENCE_CRIMSON", 1_020.0d, 987.4d)));
	}

	@Test
	void refusesToQuoteAnEmptyAskSide() {
		BazaarSnapshot bidOnly = new BazaarSnapshot(Instant.now(), Map.of(
				"ESSENCE_SPIDER", new BazaarProduct("ESSENCE_SPIDER", List.of(),
						List.of(new OrderLevel(1_443.3d, 100L, 1)),
						new BazaarProduct.MovingWeek(0L, 0L))));

		assertTrue(UpgradePricing.quoteStars(ARACK, 1, bidOnly).isEmpty(),
				"nothing is on offer, so there is no price to buy the essence at");
	}

	@Test
	void refusesStarsTheItemCannotTake() {
		assertTrue(UpgradePricing.quoteStars(ARACK, 6, BOOK).isEmpty(),
				"Arack defines five levels; a sixth has no published cost to read");
		assertTrue(UpgradePricing.quoteStars(ARACK, 0, BOOK).isEmpty());
	}

	@Test
	void refusesAnItemWithNoStarCosts() {
		assertTrue(UpgradePricing.quoteStars(
				new ItemCatalog.Entry("MELON", "Melon", 320.0d), 1, BOOK).isEmpty());
	}

	private static UpgradeCost star(String productId, int amount) {
		return new UpgradeCost(List.of(new UpgradeCost.Ingredient(productId, amount)));
	}

	private static BazaarSnapshot book(String productId, double ask, double bid) {
		return new BazaarSnapshot(Instant.now(), Map.of(productId, product(productId, ask, bid)));
	}

	private static BazaarProduct product(String productId, double ask, double bid) {
		return new BazaarProduct(
				productId,
				List.of(new OrderLevel(ask, 100_000L, 4)),
				List.of(new OrderLevel(bid, 100_000L, 4)),
				new BazaarProduct.MovingWeek(1_000_000L, 1_000_000L));
	}
}
