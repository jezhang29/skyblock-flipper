package jeff.skyblockflipper.core.strategy;

import jeff.skyblockflipper.core.model.BazaarProduct;
import jeff.skyblockflipper.core.model.BazaarSnapshot;
import jeff.skyblockflipper.core.model.ItemCatalog;
import jeff.skyblockflipper.core.model.OrderLevel;
import jeff.skyblockflipper.core.pricing.Fees;
import jeff.skyblockflipper.core.valuation.FillStats;
import jeff.skyblockflipper.core.valuation.PriceTrend;
import jeff.skyblockflipper.core.valuation.TrendSnapshot;

import org.junit.jupiter.api.Test;

import java.time.Duration;
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

	/**
	 * Live play on 2026-08-04 produced a single plan asking for 249,212,105 of a 250,000,000
	 * bankroll, because profit per hour rises with size and affordability was the only ceiling.
	 * A deep book is exactly the case that does it: throughput never runs out first.
	 */
	@Test
	void sizesAPlanWithinTheCapitalCap() {
		// A tight spread on an enormously liquid book, so throughput never runs out and the coins
		// are the only thing that can bound the plan.
		BazaarProduct deep = product(100.0d, 104.0d, 400, 5_000_000_000L);
		StrategyContext capped = new StrategyContext(
				new BazaarSnapshot(Instant.now(), Map.of(deep.productId(), deep)),
				ItemCatalog.empty(),
				List.of(),
				TrendSnapshot.empty(),
				new Fees(0, false),
				BANKROLL,
				0L,
				0.0d,
				0.0d,
				StrategyContext.DEFAULT_FILL_HORIZON,
				0.25d);

		FlipCandidate candidate = new BazaarSpreadStrategy().findCandidates(capped).getFirst();

		assertTrue(candidate.capitalRequired() <= BANKROLL / 4,
				"capital " + candidate.capitalRequired() + " exceeded a quarter of " + BANKROLL);

		// And the cap is what bound it, rather than the book running out: uncapped, the same book
		// funds a materially larger position.
		FlipCandidate uncapped = new BazaarSpreadStrategy()
				.findCandidates(contextFor(deep)).getFirst();

		assertTrue(uncapped.capitalRequired() > candidate.capitalRequired(),
				"the cap changed nothing: " + uncapped.capitalRequired() + " vs " + candidate.capitalRequired());
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
	void saysSoWhenThereIsNoPriceHistoryToJudgeDirectionFrom() {
		FlipCandidate candidate = new BazaarSpreadStrategy().findCandidates(contextFor(healthy())).getFirst();

		// Adverse selection is real whether or not it can be measured. On a client with no tape
		// yet the honest answer is "not visible here", and it must never be silent.
		assertTrue(candidate.risks().stream().anyMatch(r -> r.contains("No price history")),
				"expected an unmeasured-direction warning, got " + candidate.risks());
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
		// The bid sits just under the NPC price so the buy-order route has almost no margin, which
		// is what makes this a test of the ask walk rather than of route selection.
		BazaarProduct cheap = product(79.0d, 50.0d, 40, 2_000_000L);
		ItemCatalog catalog = new ItemCatalog(Map.of(
				"TEST_ITEM", new ItemCatalog.Entry("TEST_ITEM", "Test Item", 80.0d)));

		// Two hours rather than the default window, so what the book turns over is the binding
		// limit and the resting depth is not.
		List<FlipCandidate> found = new NpcFlipStrategy().findCandidates(
				npcContext(cheap, catalog, StrategyContext.NPC_CAP_UNLIMITED, 2.0d, new Fees(0, false)));

		assertEquals(1, found.size());

		FlipCandidate candidate = found.getFirst();
		assertEquals(StrategyKind.NPC_FLIP, candidate.kind());
		assertEquals(80.0d, candidate.unitSellPrice(), 1e-9);

		// Both ask levels (50 and 51) sit below the NPC price, so both are worth taking, at a
		// blended 50.5 leaving 29.5 a unit untaxed.
		assertEquals(50.5d, candidate.unitBuyPrice(), 1e-9);
		assertEquals(29.5d, candidate.unitNetProfit(), 1e-9);

		// 20000 units rest below the NPC price, but the book only turns over 2M a week, which is
		// 11904 an hour; taking half of that over two hours is 11904. Sizing off resting depth
		// would claim the full 20000 and a window in which it never turns over.
		assertEquals(11_904L, candidate.units());
	}

	@Test
	void npcFlipPrefersABuyOrderWhenItPaysMore() {
		// Shaped like ENCHANTED_MELON_BLOCK on the live bazaar: a wide book under a fixed NPC bid,
		// where the ask is barely under the NPC price and the bid is far under it.
		BazaarProduct wide = new BazaarProduct(
				"TEST_ITEM",
				List.of(new OrderLevel(50_933.5d, 500L, 1)),
				List.of(new OrderLevel(49_654.1d, 15_000L, 1)),
				new BazaarProduct.MovingWeek(1_200_000L, 3_380_000L));

		ItemCatalog catalog = new ItemCatalog(Map.of(
				"TEST_ITEM", new ItemCatalog.Entry("TEST_ITEM", "Test Item", 51_200.0d)));

		FlipCandidate candidate = new NpcFlipStrategy()
				.findCandidates(new StrategyContext(
						new BazaarSnapshot(Instant.now(), Map.of("TEST_ITEM", wide)),
						catalog, new Fees(0, false), 10_000_000_000L, 0L))
				.getFirst();

		// Outbidding the best bid costs 49654.2, against 50933.5 to cross the spread: 1545.8 a unit
		// instead of 266.5, which is the whole reason the route is worth evaluating.
		assertEquals(49_654.2d, candidate.unitBuyPrice(), 1e-6);
		assertEquals(1_545.8d, candidate.unitNetProfit(), 1e-6);

		assertTrue(candidate.steps().stream().anyMatch(s -> s.contains("Create Buy Order")),
				"a buy-order plan should say so: " + candidate.steps());
		assertTrue(candidate.notes().stream().anyMatch(n -> n.startsWith("Instant buy route instead")),
				"the rejected route should be reported: " + candidate.notes());
	}

	@Test
	void npcFlipRefusesAnItemNobodyTrades() {
		// Deep, cheap and under the NPC price, but 40 units a week change hands. An hourly plan on
		// this is fiction whichever route it uses.
		BazaarProduct illiquid = new BazaarProduct(
				"TEST_ITEM",
				List.of(new OrderLevel(50.0d, 100_000L, 3)),
				List.of(new OrderLevel(40.0d, 100_000L, 3)),
				new BazaarProduct.MovingWeek(40L, 40L));

		ItemCatalog catalog = new ItemCatalog(Map.of(
				"TEST_ITEM", new ItemCatalog.Entry("TEST_ITEM", "Test Item", 80.0d)));

		assertTrue(new NpcFlipStrategy().findCandidates(contextFor(illiquid, catalog, 0L)).isEmpty());
	}

	@Test
	void ignoresNpcPricesBelowTheMarket() {
		ItemCatalog catalog = new ItemCatalog(Map.of(
				"TEST_ITEM", new ItemCatalog.Entry("TEST_ITEM", "Test Item", 10.0d)));

		assertTrue(new NpcFlipStrategy().findCandidates(contextFor(healthy(), catalog, 0L)).isEmpty());
	}

	@Test
	void npcFlipStopsAtTheFirstUnprofitableBookLevel() {
		// Only the first level is below the NPC price of 60; the second at 105 is not. Nothing is
		// ever instant-sold into this book, so the buy-order route is off the table and the
		// instant-buy walk is what gets measured.
		BazaarProduct book = new BazaarProduct(
				"TEST_ITEM",
				List.of(new OrderLevel(50.0d, 100L, 20), new OrderLevel(105.0d, 100_000L, 20)),
				List.of(new OrderLevel(40.0d, 100L, 20)),
				new BazaarProduct.MovingWeek(5_000_000L, 0L));

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

		// 36 slots x 64 x 12 trips = 27648 units an hour, over the default resting window. Without
		// the ceiling this would claim the full 10M-unit book and a profit no player could
		// physically realize. Both routes hit it, since it is the manual sell leg that binds.
		long expected = (long) (27_648L * StrategyContext.DEFAULT_NPC_RESTING_HOURS);
		assertEquals(expected, candidate.units());

		// No NPC cap is stated by this context, so the whole window is carried and the hourly rate
		// is the plain average over it.
		assertEquals(expected * candidate.unitNetProfit() / StrategyContext.DEFAULT_NPC_RESTING_HOURS,
				candidate.profitPerHour(), 1e-6);
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

	// --- Price-history-aware behaviour -------------------------------------------------------
	//
	// A live book says what a thing costs but not which way it is heading, and those two cases
	// pay very differently. These cover what the strategy does once it can tell them apart, and
	// - just as importantly - that it stands down rather than guessing when it cannot.

	private static PriceTrend trend(double latest, double shortAverage, double longAverage,
			double dispersion) {
		return new PriceTrend("TEST_ITEM", latest, shortAverage, longAverage, 0.002d, dispersion, 288);
	}

	private static StrategyContext contextWith(BazaarProduct product, PriceTrend trend,
			Map<String, Double> dailyMedians, double maxAdverseDrift) {
		return contextWith(product, trend, Map.of(), dailyMedians, maxAdverseDrift);
	}

	private static StrategyContext contextWith(BazaarProduct product, PriceTrend trend,
			Map<String, FillStats> fills, Map<String, Double> dailyMedians, double maxAdverseDrift) {
		TrendSnapshot snapshot = new TrendSnapshot(
				Map.of(product.productId(), trend), fills, dailyMedians,
				Duration.ofHours(24), trend.samples(), Instant.now());

		return new StrategyContext(
				new BazaarSnapshot(Instant.now(), Map.of(product.productId(), product)),
				ItemCatalog.empty(),
				List.of(),
				snapshot,
				new Fees(0, false),
				BANKROLL,
				0L,
				0.0d,
				maxAdverseDrift);
	}

	private static StrategyContext contextWith(BazaarProduct product, PriceTrend trend,
			double maxAdverseDrift) {
		return contextWith(product, trend, Map.of(), maxAdverseDrift);
	}

	/** A book only a single player would need to move: thin, but past the liquidity floor. */
	private static BazaarProduct thin() {
		return product(100.0d, 104.0d, 40, 100_000L);
	}

	@Test
	void refusesToMarketMakeIntoAFallingPrice() {
		// Buy orders fill fastest exactly while people are dumping, so a spread quoted here is
		// not the spread that gets realized. Nothing in the order book shows this.
		assertTrue(new BazaarSpreadStrategy()
				.findCandidates(contextWith(healthy(), trend(90.0d, 90.0d, 100.0d, 0.1d), 0.05d))
				.isEmpty());
	}

	@Test
	void stillOffersAFallingPriceWhenTheFilterIsTurnedOff() {
		assertFalse(new BazaarSpreadStrategy()
				.findCandidates(contextWith(healthy(), trend(90.0d, 90.0d, 100.0d, 0.1d), 0.0d))
				.isEmpty());
	}

	@Test
	void warnsAboutAMildDeclineRatherThanRejectingIt() {
		// Between the warning threshold and the rejection threshold there is a band where the
		// flip is still worth showing, with the decline stated on it.
		FlipCandidate candidate = new BazaarSpreadStrategy()
				.findCandidates(contextWith(healthy(), trend(98.0d, 98.0d, 100.0d, 0.05d), 0.05d))
				.getFirst();

		assertTrue(candidate.risks().stream().anyMatch(r -> r.contains("Price down")),
				"expected the measured decline to be reported, got " + candidate.risks());
	}

	@Test
	void dropsTheDeclineWarningWhenThePriceIsNotFalling() {
		FlipCandidate candidate = new BazaarSpreadStrategy()
				.findCandidates(contextWith(healthy(), trend(105.0d, 104.0d, 100.0d, 0.02d), 0.05d))
				.getFirst();

		// The old unconditional note said this on every candidate, which made it worth nothing.
		assertFalse(candidate.risks().stream().anyMatch(r -> r.contains("Price down")),
				"a rising market must not carry a decline warning: " + candidate.risks());
		assertFalse(candidate.risks().stream().anyMatch(r -> r.contains("No price history")),
				"history exists here, so it must not claim otherwise: " + candidate.risks());
	}

	@Test
	void rejectsAnAbruptMoveOnABookThinEnoughToPush() {
		assertTrue(new BazaarSpreadStrategy()
				.findCandidates(contextWith(thin(), trend(150.0d, 110.0d, 100.0d, 0.03d), 0.05d))
				.isEmpty());
	}

	@Test
	void acceptsTheSameAbruptMoveOnABookTooDeepToPush() {
		// Both halves of the manipulation test are required. On a deep market an abrupt move is
		// news, and refusing to trade news would reject most of what is worth trading.
		assertFalse(new BazaarSpreadStrategy()
				.findCandidates(contextWith(healthy(), trend(150.0d, 110.0d, 100.0d, 0.03d), 0.05d))
				.isEmpty());
	}

	@Test
	void rejectsAThinBookSittingFarAboveItsMultiDayNormal() {
		// A scheme running longer than the in-memory window is already inside that window's
		// average and no longer looks abrupt against it. The daily rollup does not move.
		PriceTrend settled = trend(200.0d, 195.0d, 190.0d, 0.5d);

		assertFalse(settled.isSpiking(3.0d), "this must not be caught by the sigma test");
		assertTrue(new BazaarSpreadStrategy()
				.findCandidates(contextWith(thin(), settled, Map.of("TEST_ITEM", 100.0d), 0.05d))
				.isEmpty());
	}

	@Test
	void lowersConfidenceOnADecliningMarket() {
		double unmeasured = new BazaarSpreadStrategy()
				.findCandidates(contextFor(healthy())).getFirst().confidence();

		double declining = new BazaarSpreadStrategy()
				.findCandidates(contextWith(healthy(), trend(97.0d, 97.0d, 100.0d, 0.05d), 0.05d))
				.getFirst().confidence();

		assertTrue(declining < unmeasured,
				"a decline should cost confidence: " + declining + " vs " + unmeasured);
	}

	@Test
	void doesNotPenaliseACandidateMerelyForHavingNoHistory() {
		double unmeasured = new BazaarSpreadStrategy()
				.findCandidates(contextFor(healthy())).getFirst().confidence();

		double stable = new BazaarSpreadStrategy()
				.findCandidates(contextWith(healthy(), trend(100.0d, 100.0d, 100.0d, 0.01d), 0.05d))
				.getFirst().confidence();

		// Unmeasured is not the same as untrustworthy. The gap is reported as a risk instead.
		assertEquals(stable, unmeasured, 1e-9d);
	}

	// --- Fill-aware ranking ------------------------------------------------------------------
	//
	// The ranking axis is profit per hour, and until the tape could measure displacement the
	// "per hour" half was a flat guess at what share of the flow a resting order collects. These
	// cover what changes once it is measured, and that nothing changes where it is not.

	private static FillStats fills(double displacementsPerHour) {
		return new FillStats("TEST_ITEM", displacementsPerHour, displacementsPerHour, 24.0d, 288);
	}

	private static FlipCandidate withFills(BazaarProduct product, FillStats stats) {
		return new BazaarSpreadStrategy()
				.findCandidates(contextWith(product, trend(100.0d, 100.0d, 100.0d, 0.01d),
						Map.of(product.productId(), stats), Map.of(), 0.0d))
				.getFirst();
	}

	@Test
	void anOrderThatKeepsGettingOutbidIsWorthLessPerHourThanOneThatDoesNot() {
		double quiet = withFills(healthy(), fills(0.0d)).profitPerHour();
		double contested = withFills(healthy(), fills(30.0d)).profitPerHour();

		// Same book, same spread, same volume: the only difference is how long your order stays at
		// the front of it. Before this was measured the two ranked identically.
		assertTrue(contested < quiet,
				"a contested book should rank below a quiet one: " + contested + " vs " + quiet);
	}

	@Test
	void aMeasuredSlowBookLosesToAThinnerSpreadThatActuallyFills() {
		// 2 coins of spread that fills against 4 that does not. Ranking on margin alone - the
		// classic way a flipping tool sends people after illiquid junk - would invert this.
		double wideButStuck = withFills(product(100.0d, 104.0d, 40, 5_000_000L), fills(120.0d))
				.profitPerHour();
		double thinButLiquid = withFills(product(100.0d, 102.0d, 40, 5_000_000L), fills(0.0d))
				.profitPerHour();

		assertTrue(thinButLiquid > wideButStuck,
				"expected the fillable spread to win: " + thinButLiquid + " vs " + wideButStuck);
	}

	@Test
	void anUnmeasuredProductRanksExactlyWhereItDidBeforeFillsWereModelled() {
		// The fallback is the same share of the same flow the strategy assumed unconditionally
		// before the tape could answer this, so a fresh install's ranking is unchanged.
		FlipCandidate candidate = new BazaarSpreadStrategy()
				.findCandidates(contextFor(healthy())).getFirst();

		// 5% of the bottleneck weekly volume spread over a week, in whole units - the plan is
		// sized in units you can actually place, and always was.
		long expectedUnits = (long) (5_000_000L / 168.0d * 0.05d);

		assertEquals(expectedUnits, candidate.units());
		assertEquals(candidate.unitNetProfit() * expectedUnits, candidate.profitPerHour(), 1e-9d);
	}

	@Test
	void saysHowLongTheFillTakesWhenItHasMeasuredIt() {
		FlipCandidate measured = withFills(healthy(), fills(1.0d));

		assertTrue(measured.notes().stream().anyMatch(note -> note.contains("to buy")),
				"a measured candidate should state its fill time, got " + measured.notes());

		// And says nothing of the sort when it has not, rather than quoting the fallback as fact.
		FlipCandidate unmeasured = new BazaarSpreadStrategy()
				.findCandidates(contextFor(healthy())).getFirst();

		assertTrue(unmeasured.notes().stream().noneMatch(note -> note.contains("to buy")),
				"an unmeasured candidate must not present a guess as a measurement, got "
						+ unmeasured.notes());
	}

	// --- The daily NPC coin cap ------------------------------------------------------------------
	//
	// The cap is shared across every item and spent by the NPC's sale price rather than by profit,
	// which means it binds hardest on exactly the expensive items that rank highest without it.
	// These cover it truncating a plan, exhausting one, and the order slots a plan has to fit in.

	/** An NPC-flippable item at a fixed price, with the stacking behaviour under test. */
	private static ItemCatalog npcCatalog(double npcPrice, boolean unstackable) {
		return new ItemCatalog(Map.of("TEST_ITEM", new ItemCatalog.Entry(
				"TEST_ITEM", "Test Item", npcPrice, unstackable, List.of())));
	}

	private static StrategyContext npcContext(BazaarProduct product, ItemCatalog catalog,
			long capRemaining, double restingHours, Fees fees) {
		return new StrategyContext(
				new BazaarSnapshot(Instant.now(), Map.of(product.productId(), product)),
				catalog,
				List.of(),
				TrendSnapshot.empty(),
				fees,
				BANKROLL,
				0L,
				0.0d,
				0.0d,
				StrategyContext.DEFAULT_FILL_HORIZON,
				StrategyContext.UNCAPPED,
				capRemaining,
				restingHours);
	}

	@Test
	void npcPlanIsTruncatedByWhatTheDailyCoinCapCanStillPayFor() {
		// Expensive enough that the budget, not the day, is what runs out.
		BazaarProduct product = product(900.0d, 980.0d, 40, 50_000_000L);
		StrategyContext context = npcContext(
				product, npcCatalog(1000.0d, false), 10_000_000L, 2.0d, new Fees(0, false));

		FlipCandidate candidate = new NpcFlipStrategy().findCandidates(context).getFirst();

		// 10M of budget at 1000 a unit buys 10000 units. Carrying capacity would have allowed
		// 55296 and the book would have filled them, so the cap is the only thing stopping it.
		assertEquals(10_000L, candidate.units());

		// Ranked over the session, not over the hour it would have taken to grind the cap out.
		assertEquals(candidate.unitNetProfit() * 10_000L / 2.0d, candidate.profitPerHour(), 1e-6);

		assertTrue(candidate.notes().stream().anyMatch(n -> n.contains("daily NPC cap")),
				"a cap-limited plan should say the cap limited it, got " + candidate.notes());
		assertTrue(candidate.risks().stream().anyMatch(n -> n.contains("NPCs stop buying")),
				"a cap-limited plan should warn the budget runs out, got " + candidate.risks());
		assertTrue(candidate.notes().stream().anyMatch(n -> n.contains("Cap efficiency")),
				"cap efficiency is how two capped items compare, got " + candidate.notes());
	}

	@Test
	void npcFlipDisappearsOnceTheDayCoinCapIsSpent() {
		// The same book that ranks first with budget left is not an opportunity without it: the
		// NPC will not buy, at any margin.
		BazaarProduct product = product(900.0d, 980.0d, 40, 50_000_000L);

		assertFalse(new NpcFlipStrategy()
				.findCandidates(npcContext(product, npcCatalog(1000.0d, false), 10_000_000L, 2.0d,
						new Fees(0, false)))
				.isEmpty());

		assertTrue(new NpcFlipStrategy()
				.findCandidates(npcContext(product, npcCatalog(1000.0d, false), 0L, 2.0d,
						new Fees(0, false)))
				.isEmpty(), "a spent cap should produce no NPC plans at all");
	}

	@Test
	void unstackableNpcPlanCarriesOneSlotEachAndNeedsSeveralOrders() {
		BazaarProduct product = product(900.0d, 1100.0d, 40, 50_000_000L);
		StrategyContext context = npcContext(product, npcCatalog(1000.0d, true),
				StrategyContext.NPC_CAP_UNLIMITED, 2.0d, new Fees(0, false));

		FlipCandidate candidate = new NpcFlipStrategy().findCandidates(context).getFirst();

		// 36 slots holding one each, twelve trips an hour, over two hours. The stackable version of
		// the same book would have carried 55296.
		assertEquals(864L, candidate.units());

		// And one order holds 256 of them, so the plan spans four of the fourteen slots.
		assertTrue(candidate.notes().stream().anyMatch(n -> n.contains("4 of your 14")),
				"an unstackable plan should say how many order slots it needs, got "
						+ candidate.notes());
	}

	@Test
	void npcPlanIsRefusedWhenItWouldNeedMoreOrderSlotsThanTheAccountHas() {
		// The ask sits above the NPC price, so instant buying is not an option and the plan has to
		// rest on the book, where slots are finite.
		BazaarProduct product = product(900.0d, 1100.0d, 40, 50_000_000L);
		ItemCatalog catalog = npcCatalog(1000.0d, true);

		// Ten hours of trips is 4320 unstackable units, which is 17 orders of 256.
		assertTrue(new NpcFlipStrategy()
				.findCandidates(npcContext(product, catalog, StrategyContext.NPC_CAP_UNLIMITED,
						10.0d, new Fees(0, false)))
				.isEmpty(), "17 orders should not fit in the 14 slots a perkless account has");

		// The same plan is fine for an account that bought the perk: 14 + 7 + 7 = 28 slots.
		assertFalse(new NpcFlipStrategy()
				.findCandidates(npcContext(product, catalog, StrategyContext.NPC_CAP_UNLIMITED,
						10.0d, new Fees(2, false)))
				.isEmpty(), "28 slots is enough for the same 17 orders");
	}

	@Test
	void npcFlipStandsDownWhenTheBazaarPriceIsClimbingTowardTheNpcPrice() {
		BazaarProduct product = product(900.0d, 980.0d, 40, 50_000_000L);
		ItemCatalog catalog = npcCatalog(1000.0d, false);

		// Note the direction, which is the opposite of every other strategy here: the NPC price
		// cannot climb with the market, so a rising bid is what closes this trade. A falling one
		// widens it, and must not be penalised.
		assertTrue(new NpcFlipStrategy()
				.findCandidates(npcContextWithTrend(product, catalog, trend(110.0d, 110.0d, 100.0d,
						0.02d)))
				.isEmpty(), "a bid climbing 10% toward a fixed NPC price is not a flip");

		assertFalse(new NpcFlipStrategy()
				.findCandidates(npcContextWithTrend(product, catalog, trend(90.0d, 90.0d, 100.0d,
						0.02d)))
				.isEmpty(), "a falling bid makes an NPC flip better, not worse");
	}

	private static StrategyContext npcContextWithTrend(BazaarProduct product, ItemCatalog catalog,
			PriceTrend trend) {
		TrendSnapshot snapshot = new TrendSnapshot(
				Map.of(product.productId(), trend), Map.of(), Map.of(),
				Duration.ofHours(24), trend.samples(), Instant.now());

		return new StrategyContext(
				new BazaarSnapshot(Instant.now(), Map.of(product.productId(), product)),
				catalog,
				List.of(),
				snapshot,
				new Fees(0, false),
				BANKROLL,
				0L,
				0.0d,
				0.05d,
				StrategyContext.DEFAULT_FILL_HORIZON,
				StrategyContext.UNCAPPED,
				StrategyContext.NPC_CAP_UNLIMITED,
				2.0d);
	}
}
