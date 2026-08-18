package jeff.skyblockflipper.core.strategy;

import jeff.skyblockflipper.core.model.BazaarProduct;
import jeff.skyblockflipper.core.model.BazaarSnapshot;
import jeff.skyblockflipper.core.model.ItemCatalog;
import jeff.skyblockflipper.core.model.OrderLevel;
import jeff.skyblockflipper.core.pricing.Fees;
import jeff.skyblockflipper.core.valuation.FillStats;
import jeff.skyblockflipper.core.valuation.NpcEdgeSnapshot;
import jeff.skyblockflipper.core.valuation.PriceTrend;
import jeff.skyblockflipper.core.valuation.TrendSnapshot;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Advice on orders that are already on the book.
 *
 * <p>The stop is the whole subject: a resting order is only worth chasing while the margin it is
 * chasing toward still clears the floor its plan was admitted under.
 */
class NpcRepriceTest {
	private static final double NPC_PRICE = 1000.0d;

	/** Any fixed instant. Orders built by {@link #order} carry no placement time, so no test
	 * here trips the resting-window rule; {@code expiresAnOrderPastTheRestingWindow} builds its own. */
	private static final long NOW = 1_754_000_000_000L;

	/** With the default 15% floor, this is the highest price the item is worth chasing to. */
	private static final double STOP = 850.0d;

	/**
	 * Weekly volume for the tests about the book rather than about throughput: far more flow than
	 * any order here is for, so the value of a reprice is always capped by the order and the fill
	 * model never decides an action. {@link #valued} is where flow is the subject.
	 */
	private static final long AMPLE_VOLUME = 50_000_000L;

	private static BazaarProduct product(String id, Double bestBid) {
		return product(id, bestBid, AMPLE_VOLUME);
	}

	private static BazaarProduct product(String id, Double bestBid, long weeklyVolume) {
		return new BazaarProduct(
				id,
				List.of(new OrderLevel(NPC_PRICE * 2.0d, 10_000L, 20)),
				bestBid == null ? List.of() : List.of(new OrderLevel(bestBid, 10_000L, 20)),
				new BazaarProduct.MovingWeek(weeklyVolume, weeklyVolume));
	}

	/** @param npcPrice null for an item no NPC buys, which is what a spread flip looks like here */
	private static StrategyContext context(String id, Double bestBid, Double npcPrice) {
		Map<String, BazaarProduct> products = new LinkedHashMap<>();
		products.put(id, product(id, bestBid));

		Map<String, ItemCatalog.Entry> items = new LinkedHashMap<>();
		items.put(id, new ItemCatalog.Entry(id, id, npcPrice, false, List.of()));

		return context(products, items);
	}

	private static StrategyContext context(Map<String, BazaarProduct> products,
			Map<String, ItemCatalog.Entry> items) {
		return context(products, items, TrendSnapshot.empty(), 0L);
	}

	private static StrategyContext context(Map<String, BazaarProduct> products,
			Map<String, ItemCatalog.Entry> items, TrendSnapshot trends, long minProfit) {
		return new StrategyContext(
				new BazaarSnapshot(Instant.now(), products),
				new ItemCatalog(items),
				List.of(),
				trends,
				new Fees(0, false),
				10_000_000L,
				minProfit,
				0.0d,
				0.0d,
				StrategyContext.DEFAULT_FILL_HORIZON,
				StrategyContext.UNCAPPED,
				new NpcContext(NpcEdgeSnapshot.empty(), NpcContext.DEFAULT_MIN_MARGIN_RATIO,
						NpcContext.DEFAULT_CHECK_IN, NpcContext.DEFAULT_RESTING_HOURS,
						NpcContext.ALL_ORDER_SLOTS, NpcContext.CAP_UNLIMITED));
	}

	private static NpcReprice.Order order(String id, double unitPrice, long remaining) {
		return NpcReprice.Order.of(id, id, unitPrice, remaining);
	}

	private static NpcReprice.Advice only(String id, double yourPrice, Double bestBid) {
		List<NpcReprice.Advice> advice = NpcReprice.review(List.of(order(id, yourPrice, 500L)),
				context(id, bestBid, NPC_PRICE), NOW);

		assertEquals(1, advice.size());
		return advice.getFirst();
	}

	@Test
	void holdsAnOrderThatIsStillTheBestBid() {
		// Your own order is what the best bid is, so equality is what being on top looks like.
		NpcReprice.Advice advice = only("ITEM", 800.0d, 800.0d);

		assertEquals(NpcReprice.Action.HOLD, advice.action());
		assertFalse(advice.needsAction());
		assertEquals(0.0d, advice.extraCost());
		assertEquals(0.20d, advice.marginRatio(), 1e-9d);
	}

	/**
	 * A resting price recovered from the setup line is exact to within a fraction of a coin, and a
	 * fraction of a coin is not an outbid.
	 *
	 * <p>Real line, from the recorded session: {@code 311x Purple Candy for 6,554,823 coins}. The
	 * order rests at 21,076.6 and 311 x 21,076.6 is 6,554,822.6, so Hypixel printed it rounded to
	 * the coin and dividing back gives 21,076.601. Treating that as a price below the top bid would
	 * advise moving to 21,076.7 - paying an increment on every unit to hold the place already held.
	 */
	@Test
	void treatsARoundedRestingPriceAsATieRatherThanAnOutbid() {
		double resting = 6_554_823.0d / 311.0d;

		assertTrue(resting > 21_076.6d, "the derived price is above the real one, or this proves nothing");

		NpcReprice.Advice advice = only("ITEM", 21_076.6d, resting);

		assertEquals(NpcReprice.Action.HOLD, advice.action());
		assertFalse(advice.needsAction());
	}

	/** Half an increment is the tolerance, so a real one-increment outbid still clears it. */
	@Test
	void stillRepricesWhenTheBookMovesByAWholeIncrement() {
		NpcReprice.Advice advice = only("ITEM", 800.0d, 800.0d + BazaarProduct.PRICE_INCREMENT);

		assertEquals(NpcReprice.Action.REPRICE, advice.action());
	}

	@Test
	void repricesToTheTopWhenOutbidAndTheMarginStillClears() {
		NpcReprice.Advice advice = only("ITEM", 800.0d, 820.0d);

		assertEquals(NpcReprice.Action.REPRICE, advice.action());
		assertTrue(advice.needsAction());

		// One increment above the bid that displaced you, which is still under the 850 stop.
		assertEquals(820.1d, advice.postPrice(), 1e-9d);
		assertTrue(advice.postPrice() < STOP);

		// 20.1 more a unit on the 500 units still resting.
		assertEquals(20.1d * 500.0d, advice.extraCost(), 1e-6d);
		assertEquals((NPC_PRICE - 820.1d) * 500.0d, advice.profitAtStake(), 1e-6d);
	}

	@Test
	void cancelsRatherThanChasingPastTheStop() {
		// 860.1 to get back on top is a 14% margin, under the 15% floor the plan was admitted on.
		NpcReprice.Advice advice = only("ITEM", 800.0d, 860.0d);

		assertEquals(NpcReprice.Action.CANCEL, advice.action());
		assertTrue(advice.postPrice() > STOP);
		assertTrue(advice.marginRatio() < NpcContext.DEFAULT_MIN_MARGIN_RATIO);

		// Nothing is spent chasing a cancelled order, and the coins come back rather than working.
		assertEquals(0.0d, advice.extraCost());
		assertEquals(0.0d, advice.profitAtStake());
		assertEquals(400_000L, advice.capitalAtStake());
		assertTrue(advice.reason().contains("stop"), advice.reason());
	}

	@Test
	void cancelsWhenTheBookHasCaughtTheNpcPrice() {
		NpcReprice.Advice advice = only("ITEM", 800.0d, 1005.0d);

		assertEquals(NpcReprice.Action.CANCEL, advice.action());
		// A different situation from being chased past the floor, and worth saying so: the trade is
		// gone rather than merely too thin.
		assertTrue(advice.reason().contains("caught the NPC price"), advice.reason());
	}

	@Test
	void holdsWhenNothingElseIsBidding() {
		NpcReprice.Advice advice = only("ITEM", 800.0d, null);

		assertEquals(NpcReprice.Action.HOLD, advice.action());
		assertEquals(800.0d, advice.bestBid());
	}

	@Test
	void ignoresOrdersOnItemsNoNpcBuys() {
		// An ordinary spread flip. It has a resting buy order too, and nothing here is about it.
		assertTrue(NpcReprice.review(List.of(order("ITEM", 800.0d, 500L)),
				context("ITEM", 820.0d, null), NOW).isEmpty());

		// Same for an order on a product the current snapshot does not carry: that is a book that
		// has not been fetched, not an order that is wrong.
		assertTrue(NpcReprice.review(List.of(order("MISSING", 800.0d, 500L)),
				context("ITEM", 820.0d, NPC_PRICE), NOW).isEmpty());
	}

	@Test
	void putsTheOrdersNeedingAClickFirstAndTheBiggestOfThoseAtTheTop() {
		Map<String, BazaarProduct> products = new LinkedHashMap<>();
		Map<String, ItemCatalog.Entry> items = new LinkedHashMap<>();

		for (String id : List.of("HELD", "SMALL", "BIG")) {
			// HELD is not outbid; the other two are.
			products.put(id, product(id, id.equals("HELD") ? 800.0d : 820.0d));
			items.put(id, new ItemCatalog.Entry(id, id, NPC_PRICE, false, List.of()));
		}

		List<NpcReprice.Advice> advice = NpcReprice.review(List.of(
				order("HELD", 800.0d, 5_000L),
				order("SMALL", 800.0d, 100L),
				order("BIG", 800.0d, 900L)), context(products, items), NOW);

		assertEquals(List.of("BIG", "SMALL", "HELD"),
				advice.stream().map(a -> a.order().itemId()).toList());

		// The held order is the largest of the three, so this really is action first rather than
		// size first.
		assertEquals(NpcReprice.Action.HOLD, advice.getLast().action());
	}

	/**
	 * The one rule here that is not about the book: coins were lent to this trade for a window, and
	 * an order that is still correctly priced after the window is still holding a slot.
	 */
	@Test
	void expiresAnOrderPastTheRestingWindow() {
		long placed = NOW - Math.round(NpcContext.DEFAULT_RESTING_HOURS * 3_600_000.0d) - 1L;
		NpcReprice.Order order =
				new NpcReprice.Order("ITEM", "ITEM", 800.0d, 500L, 500L, 0L, placed);

		// Top of the book, correctly priced, and nothing at all has filled in eight hours.
		List<NpcReprice.Advice> advice = NpcReprice.review(List.of(order),
				context("ITEM", 800.0d, NPC_PRICE), NOW);

		assertEquals(NpcReprice.Action.EXPIRED, advice.getFirst().action());
		assertTrue(advice.getFirst().needsAction());
		assertTrue(advice.getFirst().isCancel());
		assertEquals(400_000L, advice.getFirst().capitalAtStake());
	}

	@Test
	void leavesAnOrderInsideItsWindowAlone() {
		NpcReprice.Order order = new NpcReprice.Order("ITEM", "ITEM", 800.0d, 500L, 500L, 0L,
				NOW - 3_600_000L);

		assertEquals(NpcReprice.Action.HOLD, NpcReprice.review(List.of(order),
				context("ITEM", 800.0d, NPC_PRICE), NOW).getFirst().action());
	}

	/**
	 * An order with no placement time is every order in a fresh session, and expiring those on a
	 * default of zero would cancel a whole basket the first time the orders menu was opened.
	 */
	@Test
	void neverExpiresAnOrderNothingHasTimed() {
		assertEquals(NpcReprice.Action.HOLD,
				only("ITEM", 800.0d, 800.0d).action());
	}

	/** A partial fill is two facts: units to collect, and an order still worth repricing. */
	@Test
	void reportsUnclaimedUnitsAlongsideTheBookAdvice() {
		NpcReprice.Order order = new NpcReprice.Order("ITEM", "ITEM", 800.0d, 500L, 300L, 200L, 0L);

		NpcReprice.Advice advice = NpcReprice.review(List.of(order),
				context("ITEM", 820.0d, NPC_PRICE), NOW).getFirst();

		assertEquals(NpcReprice.Action.REPRICE, advice.action());
		assertTrue(advice.hasUnclaimed());
		assertEquals(200L, advice.order().filled());
		assertTrue(advice.order().partlyFilled());

		// 200 units bought at 800 that the NPC pays 1000 for.
		assertEquals(200.0d * 200L, advice.claimableProfit(), 1e-6d);

		// The reprice is about the 300 still on the book, not about the 500 originally ordered.
		assertEquals(20.1d * 300.0d, advice.extraCost(), 1e-6d);
	}

	/**
	 * A completely filled order has nothing left to price and everything left to collect. It used to
	 * be dropped for having no units on the book, which hid exactly the orders that had worked.
	 */
	@Test
	void keepsACompletelyFilledOrderForItsClaim() {
		NpcReprice.Order order = new NpcReprice.Order("ITEM", "ITEM", 800.0d, 500L, 0L, 500L, 0L);

		NpcReprice.Advice advice = NpcReprice.review(List.of(order),
				context("ITEM", 900.0d, NPC_PRICE), NOW).getFirst();

		assertEquals(NpcReprice.Action.HOLD, advice.action());
		assertTrue(advice.hasUnclaimed());
		assertTrue(advice.needsAnything());
		assertTrue(advice.reason().contains("Filled completely"), advice.reason());
	}

	// What a reprice is worth, which is what decides whether it is asked for at all.
	//
	// One book throughout: 16,800 units a week is 100 an hour of flow, and a 500-unit order at 800
	// on a 1000 NPC price is displaced to a 800.2 repost, so every unit that fills is worth 199.8.
	// The only thing that changes between these is how fast the book posts inside you.

	private static final long SLOW_VOLUME = 16_800L;

	/** Displaced every nine minutes, which is the live {@code TRANSMISSION_TUNER} shape. */
	private static final double CONTESTED = 6.6667d;

	/** Displaced once every ten hours, so a repost keeps almost the whole interval. */
	private static final double QUIET = 0.1d;

	/**
	 * A context whose fill rate is measured, at a stated displacement and profit floor.
	 *
	 * <p>{@code intervals} clears {@link PriceTrend#MIN_SAMPLES} so the stats are usable: below it
	 * {@code FillModel} falls back to the unmeasured share, which is a different test.
	 */
	private static StrategyContext valued(String id, double outbidsPerHour, long minProfit) {
		Map<String, BazaarProduct> products = new LinkedHashMap<>();
		products.put(id, product(id, 800.1d, SLOW_VOLUME));

		Map<String, ItemCatalog.Entry> items = new LinkedHashMap<>();
		items.put(id, new ItemCatalog.Entry(id, id, NPC_PRICE, false, List.of()));

		TrendSnapshot trends = new TrendSnapshot(Map.of(),
				Map.of(id, new FillStats(id, outbidsPerHour, outbidsPerHour, 24.0d, 24)),
				Map.of(), Duration.ofHours(24), 288, Instant.now());

		return context(products, items, trends, minProfit);
	}

	private static NpcReprice.Advice valuedAdvice(StrategyContext context) {
		List<NpcReprice.Advice> advice =
				NpcReprice.review(List.of(order("ITEM", 800.0d, 500L)), context, NOW);

		assertEquals(1, advice.size());
		return advice.getFirst();
	}

	/**
	 * The whole point of valuing a reprice: on a book five bots are penny-jumping, the repost is
	 * outbid again before it collects anything, so the advice stops asking.
	 */
	@Test
	void holdsAnOutbidOrderTheBookWillTakeStraightBackOff() {
		NpcReprice.Advice advice = valuedAdvice(valued("ITEM", CONTESTED, 5_000L));

		assertEquals(NpcReprice.Action.HOLD, advice.action());
		assertFalse(advice.needsAction());

		// Outbid all the same, which is a different fact from being told to do something about it.
		assertTrue(advice.outbid());

		// 100 units an hour of flow, collected over the 29% of a 30-minute interval a repost spends
		// on top at 6.67 displacements an hour: 14.5 units at 199.8 each.
		assertTrue(advice.value().measured());
		assertEquals(14.46d, advice.value().unitsExpected(), 0.05d);
		assertEquals(2_890.0d, advice.value().gain(), 10.0d);
		assertEquals(9.0d, advice.value().minutesToOutbid().getAsDouble(), 0.05d);

		assertTrue(advice.reason().contains("9 minutes"), advice.reason());
	}

	/** The same order, the same margin, the same floor - and a book that leaves it alone. */
	@Test
	void repricesOnAQuietBookWhereTheRepostWillHold() {
		NpcReprice.Advice advice = valuedAdvice(valued("ITEM", QUIET, 5_000L));

		assertEquals(NpcReprice.Action.REPRICE, advice.action());

		// 97.5% of the interval on top rather than 29%, so 48.8 units rather than 14.5.
		assertEquals(48.77d, advice.value().unitsExpected(), 0.05d);
		assertEquals(9_744.0d, advice.value().gain(), 10.0d);
	}

	/**
	 * A gain is only allowed to suppress advice when it is a measurement. A fresh install has no
	 * tape at all, and filtering on the fallback share would mute the mod hardest on the accounts
	 * that have never seen it work.
	 */
	@Test
	void chasesOnAnUnmeasuredBookWhateverTheFloor() {
		Map<String, BazaarProduct> products = new LinkedHashMap<>();
		products.put("ITEM", product("ITEM", 800.1d, SLOW_VOLUME));

		Map<String, ItemCatalog.Entry> items = new LinkedHashMap<>();
		items.put("ITEM", new ItemCatalog.Entry("ITEM", "ITEM", NPC_PRICE, false, List.of()));

		NpcReprice.Advice advice = valuedAdvice(
				context(products, items, TrendSnapshot.empty(), 100_000_000L));

		assertEquals(NpcReprice.Action.REPRICE, advice.action());
		assertFalse(advice.value().measured());
		assertTrue(advice.value().minutesToOutbid().isEmpty());
	}

	/** Coins already stranded are never filtered: the trade is over whatever the throughput is. */
	@Test
	void stillCancelsADeadTradeUnderAnyFloor() {
		Map<String, BazaarProduct> products = new LinkedHashMap<>();
		products.put("ITEM", product("ITEM", 1005.0d, SLOW_VOLUME));

		Map<String, ItemCatalog.Entry> items = new LinkedHashMap<>();
		items.put("ITEM", new ItemCatalog.Entry("ITEM", "ITEM", NPC_PRICE, false, List.of()));

		TrendSnapshot trends = new TrendSnapshot(Map.of(),
				Map.of("ITEM", new FillStats("ITEM", CONTESTED, CONTESTED, 24.0d, 24)),
				Map.of(), Duration.ofHours(24), 288, Instant.now());

		NpcReprice.Advice advice =
				valuedAdvice(context(products, items, trends, 100_000_000L));

		assertEquals(NpcReprice.Action.CANCEL, advice.action());
	}

	/**
	 * A reprice is worth what it fills before the next trip, so a round half spent is worth half as
	 * much - which is what turns a marginal row into one not worth walking to a menu for.
	 */
	@Test
	void valuesTheRepriceOverWhatIsLeftOfTheInterval() {
		StrategyContext context = valued("ITEM", QUIET, 5_000L);
		List<NpcReprice.Order> resting = List.of(order("ITEM", 800.0d, 500L));

		NpcReprice.Advice quarter = NpcReprice.review(resting, context, NOW,
				Duration.ofMinutes(15)).getFirst();

		assertEquals(4_933.0d, quarter.value().gain(), 10.0d);

		// Under the same 5,000 floor the full interval cleared, so the row goes away as it runs out.
		assertEquals(NpcReprice.Action.HOLD, quarter.action());
		assertEquals(NpcReprice.Action.REPRICE,
				NpcReprice.review(resting, context, NOW, Duration.ofMinutes(30))
						.getFirst().action());
	}

	/** An elapsed interval means the next trip is now, not that there is no time left to fill in. */
	@Test
	void readsAnEmptyRemainingIntervalAsAWholeOne() {
		StrategyContext context = valued("ITEM", QUIET, 5_000L);
		List<NpcReprice.Order> resting = List.of(order("ITEM", 800.0d, 500L));

		for (Duration nonsense : List.of(Duration.ZERO, Duration.ofMinutes(-5))) {
			assertEquals(9_744.0d,
					NpcReprice.review(resting, context, NOW, nonsense).getFirst().value().gain(),
					10.0d, nonsense.toString());
		}

		assertEquals(9_744.0d,
				NpcReprice.review(resting, context, NOW, null).getFirst().value().gain(), 10.0d);
	}

	/** A reprice moves an order rather than enlarging it, so the units it holds are the ceiling. */
	@Test
	void capsTheGainAtTheUnitsStillResting() {
		Map<String, BazaarProduct> products = new LinkedHashMap<>();
		products.put("ITEM", product("ITEM", 800.1d, AMPLE_VOLUME));

		Map<String, ItemCatalog.Entry> items = new LinkedHashMap<>();
		items.put("ITEM", new ItemCatalog.Entry("ITEM", "ITEM", NPC_PRICE, false, List.of()));

		TrendSnapshot trends = new TrendSnapshot(Map.of(),
				Map.of("ITEM", new FillStats("ITEM", QUIET, QUIET, 24.0d, 24)),
				Map.of(), Duration.ofHours(24), 288, Instant.now());

		NpcReprice.Advice advice = valuedAdvice(context(products, items, trends, 5_000L));

		// 297,619 units an hour of flow against an order of 500.
		assertEquals(500.0d, advice.value().unitsExpected(), 1e-9d);
		assertEquals(199.8d * 500.0d, advice.value().gain(), 1e-6d);
	}

	/** Nothing that is not a reprice carries an estimate, so no reader can mistake one for a fact. */
	@Test
	void leavesEveryOtherAdviceWithoutAnEstimate() {
		assertEquals(NpcReprice.RepriceValue.none(), only("ITEM", 800.0d, 800.0d).value());
		assertEquals(NpcReprice.RepriceValue.none(), only("ITEM", 800.0d, 860.0d).value());
		assertEquals(NpcReprice.RepriceValue.none(), only("ITEM", 800.0d, null).value());
	}
}
