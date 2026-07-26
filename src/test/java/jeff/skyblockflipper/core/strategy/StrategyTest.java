package jeff.skyblockflipper.core.strategy;

import jeff.skyblockflipper.core.model.BazaarProduct;
import jeff.skyblockflipper.core.model.BazaarSnapshot;
import jeff.skyblockflipper.core.model.ItemCatalog;
import jeff.skyblockflipper.core.model.OrderLevel;
import jeff.skyblockflipper.core.pricing.Fees;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Strategies are pure functions of a market snapshot, so they can be driven with hand-built books
 * that isolate one filter at a time. These tests are mostly about what the strategies <em>refuse</em>
 * to recommend, since that is where the money is lost.
 */
class StrategyTest {
	private static final long BANKROLL = 100_000_000L;

	// A healthy, liquid, deep book with a spread that clears tax.
	private static BazaarProduct healthy() {
		return product(100.0d, 104.0d, 40, 5_000_000L);
	}

	private static BazaarProduct product(double bid, double ask, int orders, long weeklyVolume) {
		return new BazaarProduct(
				"TEST_ITEM",
				List.of(new OrderLevel(ask, 10_000L, orders), new OrderLevel(ask + 1.0d, 10_000L, orders)),
				List.of(new OrderLevel(bid, 10_000L, orders), new OrderLevel(bid - 1.0d, 10_000L, orders)),
				new BazaarProduct.MovingWeek(weeklyVolume, weeklyVolume));
	}

	private static StrategyContext contextFor(BazaarProduct product, ItemCatalog catalog, long minProfit) {
		return new StrategyContext(
				new BazaarSnapshot(Instant.now(), Map.of(product.productId(), product)),
				catalog,
				new Fees(0, false),
				BANKROLL,
				minProfit);
	}

	private static StrategyContext contextFor(BazaarProduct product) {
		return contextFor(product, ItemCatalog.empty(), 0L);
	}

	@Test
	void findsASpreadOnAHealthyBook() {
		List<FlipCandidate> found = new BazaarSpreadStrategy().findCandidates(contextFor(healthy()));

		assertEquals(1, found.size());

		FlipCandidate candidate = found.getFirst();
		assertEquals(StrategyKind.BAZAAR_SPREAD, candidate.kind());

		// Post inside the touch on both sides: buy above the best bid, sell below the best ask.
		assertTrue(candidate.unitBuyPrice() > 100.0d);
		assertTrue(candidate.unitSellPrice() < 104.0d);
		assertTrue(candidate.unitNetProfit() > 0.0d);
	}

	@Test
	void netProfitIsAlwaysBelowTheGrossSpread() {
		FlipCandidate candidate = new BazaarSpreadStrategy().findCandidates(contextFor(healthy())).getFirst();

		double gross = candidate.unitSellPrice() - candidate.unitBuyPrice();

		// If tax were ever dropped from the path, these would be equal and every margin inflated.
		assertTrue(candidate.unitNetProfit() < gross,
				"net " + candidate.unitNetProfit() + " should sit below gross " + gross);
	}

	@Test
	void rejectsSpreadsThatOnlyLookProfitableBeforeTax() {
		// One coin of spread on a 100-coin item is 1%, under the 1.25% tax.
		assertTrue(new BazaarSpreadStrategy()
				.findCandidates(contextFor(product(100.0d, 101.0d, 40, 5_000_000L)))
				.isEmpty());
	}

	@Test
	void rejectsIlliquidProducts() {
		assertTrue(new BazaarSpreadStrategy()
				.findCandidates(contextFor(product(100.0d, 104.0d, 40, 1_000L)))
				.isEmpty());
	}

	@Test
	void rejectsBooksHeldUpByAHandfulOfOrders() {
		// A wide spread on a two-order book is a manipulation signature, not an opportunity.
		assertTrue(new BazaarSpreadStrategy()
				.findCandidates(contextFor(product(100.0d, 104.0d, 2, 5_000_000L)))
				.isEmpty());
	}

	@Test
	void rejectsImplausiblyWideSpreads() {
		// 100 -> 200 on a liquid item is not free money nobody noticed.
		assertTrue(new BazaarSpreadStrategy()
				.findCandidates(contextFor(product(100.0d, 200.0d, 40, 5_000_000L)))
				.isEmpty());
	}

	@Test
	void alwaysWarnsAboutAdverseSelection() {
		FlipCandidate candidate = new BazaarSpreadStrategy().findCandidates(contextFor(healthy())).getFirst();

		// This risk is real, always present, and not yet measurable. It must never be silent.
		assertTrue(candidate.risks().stream().anyMatch(r -> r.contains("falling")),
				"expected an adverse-selection warning, got " + candidate.risks());
	}

	@Test
	void respectsTheMinimumProfitFloor() {
		assertTrue(new BazaarSpreadStrategy()
				.findCandidates(contextFor(healthy(), ItemCatalog.empty(), Long.MAX_VALUE))
				.isEmpty());
	}

	@Test
	void sizesPositionsWithinBankroll() {
		StrategyContext poor = new StrategyContext(
				new BazaarSnapshot(Instant.now(), Map.of("TEST_ITEM", healthy())),
				ItemCatalog.empty(), new Fees(0, false), 5_000L, 0L);

		FlipCandidate candidate = new BazaarSpreadStrategy().findCandidates(poor).getFirst();

		assertTrue(candidate.capitalRequired() <= 5_000L,
				"plan needs " + candidate.capitalRequired() + " but only 5000 is available");
	}

	@Test
	void findsAnNpcFlipWhenTheBazaarPriceFallsBelowTheNpcPrice() {
		BazaarProduct cheap = product(40.0d, 50.0d, 40, 5_000_000L);
		ItemCatalog catalog = new ItemCatalog(Map.of(
				"TEST_ITEM", new ItemCatalog.Entry("TEST_ITEM", "Test Item", 80.0d)));

		List<FlipCandidate> found = new NpcFlipStrategy().findCandidates(contextFor(cheap, catalog, 0L));

		assertEquals(1, found.size());

		FlipCandidate candidate = found.getFirst();
		assertEquals(StrategyKind.NPC_FLIP, candidate.kind());
		assertEquals(80.0d, candidate.unitSellPrice(), 1e-9);

		// Both ask levels (50 and 51) sit below the NPC price, so both are worth taking:
		// 20000 units at a blended 50.5, leaving 29.5 a unit untaxed.
		assertEquals(20_000L, candidate.units());
		assertEquals(50.5d, candidate.unitBuyPrice(), 1e-9);
		assertEquals(29.5d, candidate.unitNetProfit(), 1e-9);
	}

	@Test
	void ignoresNpcPricesBelowTheMarket() {
		ItemCatalog catalog = new ItemCatalog(Map.of(
				"TEST_ITEM", new ItemCatalog.Entry("TEST_ITEM", "Test Item", 10.0d)));

		assertTrue(new NpcFlipStrategy().findCandidates(contextFor(healthy(), catalog, 0L)).isEmpty());
	}

	@Test
	void npcFlipStopsAtTheFirstUnprofitableBookLevel() {
		// Only the first level is below the NPC price of 60; the second at 105 is not.
		BazaarProduct book = new BazaarProduct(
				"TEST_ITEM",
				List.of(new OrderLevel(50.0d, 100L, 20), new OrderLevel(105.0d, 100_000L, 20)),
				List.of(new OrderLevel(40.0d, 100L, 20)),
				new BazaarProduct.MovingWeek(5_000_000L, 5_000_000L));

		ItemCatalog catalog = new ItemCatalog(Map.of(
				"TEST_ITEM", new ItemCatalog.Entry("TEST_ITEM", "Test Item", 60.0d)));

		FlipCandidate candidate = new NpcFlipStrategy()
				.findCandidates(contextFor(book, catalog, 0L))
				.getFirst();

		// Sizing off total book depth instead of profitable depth would claim 100k units here.
		assertEquals(100L, candidate.units());
	}

	@Test
	void mergedRankingIsOrderedByProfitPerHour() {
		BazaarProduct cheap = product(40.0d, 50.0d, 40, 5_000_000L);
		ItemCatalog catalog = new ItemCatalog(Map.of(
				"TEST_ITEM", new ItemCatalog.Entry("TEST_ITEM", "Test Item", 80.0d)));

		List<FlipCandidate> ranked = StrategyEngine.withDefaults()
				.rank(contextFor(cheap, catalog, 0L), 10);

		assertFalse(ranked.isEmpty());

		for (int i = 1; i < ranked.size(); i++) {
			assertTrue(ranked.get(i - 1).profitPerHour() >= ranked.get(i).profitPerHour(),
					"ranking is not sorted by profit per hour");
		}
	}

	@Test
	void npcFlipIsCappedByHowMuchCanActuallyBeSoldByHand() {
		// A huge cheap book: buying is instant and unbounded, but selling is manual.
		BazaarProduct huge = new BazaarProduct(
				"TEST_ITEM",
				List.of(new OrderLevel(2.0d, 10_000_000L, 50)),
				List.of(new OrderLevel(1.0d, 10_000L, 50)),
				new BazaarProduct.MovingWeek(50_000_000L, 50_000_000L));

		ItemCatalog catalog = new ItemCatalog(Map.of(
				"TEST_ITEM", new ItemCatalog.Entry("TEST_ITEM", "Test Item", 5.0d)));

		FlipCandidate candidate = new NpcFlipStrategy()
				.findCandidates(contextFor(huge, catalog, 0L))
				.getFirst();

		// 36 slots x 64 x 12 trips = 27648 units an hour. Without the cap this would claim the
		// full 10M-unit book and an hourly profit no player could physically realize.
		assertEquals(27_648L, candidate.units());
		assertEquals(27_648L * 3.0d, candidate.profitPerHour(), 1e-6);
		assertTrue(candidate.risks().stream().anyMatch(r -> r.contains("trips")),
				"a multi-trip plan should say so: " + candidate.risks());
	}

	@Test
	void bazaarDepthIsJudgedAcrossTheWholeBookNotTheTopLevel() {
		// Realistic shape: many levels, each holding only a couple of orders. Judging depth by the
		// top level alone rejected every real product on the live bazaar.
		List<OrderLevel> asks = new java.util.ArrayList<>();
		List<OrderLevel> bids = new java.util.ArrayList<>();

		for (int i = 0; i < 20; i++) {
			asks.add(new OrderLevel(104.0d + i, 5_000L, 2));
			bids.add(new OrderLevel(100.0d - i, 5_000L, 2));
		}

		BazaarProduct realistic = new BazaarProduct("TEST_ITEM", asks, bids,
				new BazaarProduct.MovingWeek(5_000_000L, 5_000_000L));

		assertEquals(40, realistic.sellOfferCount());
		assertFalse(new BazaarSpreadStrategy().findCandidates(contextFor(realistic)).isEmpty(),
				"a deep book with two orders per level must not be rejected as thin");
	}

	@Test
	void everyCandidateExplainsWhatToActuallyDo() {
		for (FlipCandidate candidate : new BazaarSpreadStrategy().findCandidates(contextFor(healthy()))) {
			assertFalse(candidate.steps().isEmpty(), "a candidate with no instructions is not advice");
		}
	}
}
