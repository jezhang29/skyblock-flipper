package jeff.skyblockflipper.core.strategy;

import jeff.skyblockflipper.core.model.BazaarProduct;
import jeff.skyblockflipper.core.model.BazaarSnapshot;
import jeff.skyblockflipper.core.model.ItemCatalog;
import jeff.skyblockflipper.core.model.OrderLevel;
import jeff.skyblockflipper.core.pricing.Fees;
import jeff.skyblockflipper.core.valuation.NpcEdgeSnapshot;
import jeff.skyblockflipper.core.valuation.TrendSnapshot;

import org.junit.jupiter.api.Test;

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

	/** With the default 15% floor, this is the highest price the item is worth chasing to. */
	private static final double STOP = 850.0d;

	private static BazaarProduct product(String id, Double bestBid) {
		return new BazaarProduct(
				id,
				List.of(new OrderLevel(NPC_PRICE * 2.0d, 10_000L, 20)),
				bestBid == null ? List.of() : List.of(new OrderLevel(bestBid, 10_000L, 20)),
				new BazaarProduct.MovingWeek(50_000_000L, 50_000_000L));
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
		return new StrategyContext(
				new BazaarSnapshot(Instant.now(), products),
				new ItemCatalog(items),
				List.of(),
				TrendSnapshot.empty(),
				new Fees(0, false),
				10_000_000L,
				0L,
				0.0d,
				0.0d,
				StrategyContext.DEFAULT_FILL_HORIZON,
				StrategyContext.UNCAPPED,
				new NpcContext(NpcEdgeSnapshot.empty(), NpcContext.DEFAULT_MIN_MARGIN_RATIO,
						NpcContext.DEFAULT_CHECK_IN, NpcContext.DEFAULT_RESTING_HOURS,
						NpcContext.ALL_ORDER_SLOTS, NpcContext.CAP_UNLIMITED));
	}

	private static NpcReprice.Order order(String id, double unitPrice, long remaining) {
		return new NpcReprice.Order(id, id, unitPrice, remaining);
	}

	private static NpcReprice.Advice only(String id, double yourPrice, Double bestBid) {
		List<NpcReprice.Advice> advice = NpcReprice.review(List.of(order(id, yourPrice, 500L)),
				context(id, bestBid, NPC_PRICE));

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
				context("ITEM", 820.0d, null)).isEmpty());

		// Same for an order on a product the current snapshot does not carry: that is a book that
		// has not been fetched, not an order that is wrong.
		assertTrue(NpcReprice.review(List.of(order("MISSING", 800.0d, 500L)),
				context("ITEM", 820.0d, NPC_PRICE)).isEmpty());
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
				order("BIG", 800.0d, 900L)), context(products, items));

		assertEquals(List.of("BIG", "SMALL", "HELD"),
				advice.stream().map(a -> a.order().itemId()).toList());

		// The held order is the largest of the three, so this really is action first rather than
		// size first.
		assertEquals(NpcReprice.Action.HOLD, advice.getLast().action());
	}
}
