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
 * The basket is where the shared resources get spent once.
 *
 * <p>A ranked list sizes every candidate against the whole bankroll and knows nothing about the
 * order slots the row above it took, so these are mostly about what a basket <em>refuses</em> to
 * allocate twice.
 */
class NpcBasketTest {
	private static final long BANKROLL = 10_000_000L;

	/**
	 * A book an NPC pays {@code npcPrice} for, with the bid the plan would post above.
	 *
	 * <p>The ask is put well above the NPC price on purpose: the basket is made of resting orders,
	 * so nothing here should depend on the instant-buy route existing.
	 */
	private static BazaarProduct product(String id, double bid, long weeklyVolume) {
		return new BazaarProduct(
				id,
				List.of(new OrderLevel(bid * 100.0d, 10_000L, 20)),
				List.of(new OrderLevel(bid, 10_000L, 20)),
				new BazaarProduct.MovingWeek(weeklyVolume, weeklyVolume));
	}

	private static ItemCatalog.Entry entry(String id, double npcPrice, boolean unstackable) {
		return new ItemCatalog.Entry(id, id, npcPrice, unstackable, List.of());
	}

	private static NpcContext npc(int maxOrderSlots, long capRemaining) {
		return new NpcContext(NpcEdgeSnapshot.empty(), NpcContext.DEFAULT_MIN_MARGIN_RATIO,
				NpcContext.DEFAULT_CHECK_IN, NpcContext.DEFAULT_RESTING_HOURS, maxOrderSlots,
				capRemaining);
	}

	private static StrategyContext context(Map<String, BazaarProduct> products,
			Map<String, ItemCatalog.Entry> items, long bankroll, NpcContext npc) {
		return new StrategyContext(
				new BazaarSnapshot(Instant.now(), products),
				new ItemCatalog(items),
				List.of(),
				TrendSnapshot.empty(),
				new Fees(0, false),
				bankroll,
				0L,
				0.0d,
				0.0d,
				StrategyContext.DEFAULT_FILL_HORIZON,
				StrategyContext.UNCAPPED,
				npc);
	}

	/** {@code count} interchangeable items, deep and liquid enough that only budgets can bind. */
	private static StrategyContext manyItems(int count, long bankroll, NpcContext npc,
			boolean unstackable) {
		return manyItems(count, bankroll, npc, unstackable, 50_000_000L);
	}

	/**
	 * The same, with the weekly volume stated, because it is what decides how many order slots one
	 * item needs: a book that dumps 595,238 units into an order over the window fills nine of them.
	 */
	private static StrategyContext manyItems(int count, long bankroll, NpcContext npc,
			boolean unstackable, long weeklyVolume) {
		Map<String, BazaarProduct> products = new LinkedHashMap<>();
		Map<String, ItemCatalog.Entry> items = new LinkedHashMap<>();

		for (int i = 0; i < count; i++) {
			String id = "ITEM_" + i;

			// Descending margins, so the order the basket picks them in is observable.
			products.put(id, product(id, 700.0d - i, weeklyVolume));
			items.put(id, entry(id, 1000.0d, unstackable));
		}

		return context(products, items, bankroll, npc);
	}

	@Test
	void ranksOnProfitPerInventoryLoadRatherThanOnMargin() {
		// One slot, two items. CHEAP has three times the margin as a percentage; RICH carries 33x
		// more profit in the same inventory load. Ranking on cap efficiency - margin over the NPC
		// price - picks CHEAP and makes 4.8M a day against 76.4M, measured over a full day.
		Map<String, BazaarProduct> products = Map.of(
				"CHEAP", product("CHEAP", 1.0d, 50_000_000L),
				"RICH", product("RICH", 700.0d, 50_000_000L));
		Map<String, ItemCatalog.Entry> items = Map.of(
				"CHEAP", entry("CHEAP", 10.0d, false),
				"RICH", entry("RICH", 1000.0d, false));

		NpcBasket.Basket basket = NpcBasket.plan(
				context(products, items, BANKROLL, npc(1, NpcContext.CAP_UNLIMITED)));

		assertEquals(1, basket.lines().size());
		assertEquals("RICH", basket.lines().getFirst().plan().itemId());

		// And the loser really does have the fatter margin, so this is the trade-off it looks like.
		assertTrue(NpcFlipStrategy.restingPlans(
						context(products, items, BANKROLL, npc(1, NpcContext.CAP_UNLIMITED)))
				.stream()
				.anyMatch(p -> p.itemId().equals("CHEAP") && p.marginRatio() > 0.85d));
	}

	@Test
	void spendsTheBankrollOnceAcrossTheWholeBasket() {
		StrategyContext context = manyItems(20, BANKROLL, npc(NpcContext.ALL_ORDER_SLOTS,
				NpcContext.CAP_UNLIMITED), false);

		NpcBasket.Basket basket = NpcBasket.plan(context);

		assertFalse(basket.isEmpty());
		assertTrue(basket.capital() <= BANKROLL,
				"basket asked for " + basket.capital() + " of a " + BANKROLL + " bankroll");
		assertTrue(basket.capitalShare() <= 1.0d);
		assertEquals(NpcBasket.Bound.CAPITAL, basket.bound());

		// The ranked list is what this exists to fix: every candidate on it is sized against the
		// whole bankroll, so following the top few spends it several times over.
		long ranked = new NpcFlipStrategy().findCandidates(context).stream()
				.mapToLong(candidate -> candidate.capitalRequired())
				.sum();

		assertTrue(ranked > BANKROLL * 2L,
				"the ranked list should double-count the bankroll, and did not: " + ranked);
	}

	@Test
	void stopsAtTheOrderSlotsAndSaysWhichResourceRanOut() {
		// Thirty items worth posting, fourteen slots to post them in, and coins to spare. The books
		// turn over 35,714 units a window, so each item fits in one order and the slots are what
		// the count of lines is really measuring.
		StrategyContext context = manyItems(30, 10_000_000_000L,
				npc(NpcContext.ALL_ORDER_SLOTS, NpcContext.CAP_UNLIMITED), false, 3_000_000L);

		NpcBasket.Basket basket = NpcBasket.plan(context);

		assertEquals(14, basket.slotsAvailable(), "14 slots with no Bazaar Flipper level");
		assertEquals(14, basket.slotsUsed());
		assertEquals(14, basket.lines().size());
		assertEquals(NpcBasket.Bound.SLOTS, basket.bound());

		// Taken in descending profit per load, so the fourteen best are the fourteen placed.
		for (int i = 1; i < basket.lines().size(); i++) {
			assertTrue(basket.lines().get(i - 1).plan().profitPerLoad()
							>= basket.lines().get(i).plan().profitPerLoad(),
					"basket is not ordered by profit per inventory load");
		}
	}

	@Test
	void anUnstackableItemEatsSeveralSlotsOnItsOwn() {
		// One order holds 256 unstackable units, so a single item can occupy the whole account.
		StrategyContext context = manyItems(5, 10_000_000_000L,
				npc(NpcContext.ALL_ORDER_SLOTS, NpcContext.CAP_UNLIMITED), true);

		NpcBasket.Basket basket = NpcBasket.plan(context);

		assertEquals(1, basket.lines().size());
		assertEquals(14, basket.lines().getFirst().orders());
		assertEquals(256L * 14L, basket.lines().getFirst().units());
		assertEquals(NpcBasket.Bound.SLOTS, basket.bound());
	}

	@Test
	void neverPlansMoreCoinsOutOfTheNpcThanTheDayHasLeft() {
		// 1M of budget at 1000 a unit is 1000 units, across every item at once.
		StrategyContext context = manyItems(20, 10_000_000_000L,
				npc(NpcContext.ALL_ORDER_SLOTS, 1_000_000L), false);

		NpcBasket.Basket basket = NpcBasket.plan(context);

		assertTrue(basket.npcPayout() <= 1_000_000L,
				"basket would collect " + basket.npcPayout() + " from NPCs against 1000000 left");
		assertEquals(NpcBasket.Bound.DAILY_CAP, basket.bound());

		// Spent by the sale price rather than by profit, so the whole budget goes on one item here
		// rather than being spread thin.
		assertEquals(1_000L, basket.lines().stream().mapToLong(NpcBasket.Line::units).sum());
	}

	@Test
	void resizesWhenTheBankrollChangesRatherThanKeepingTheOldOne() {
		NpcContext npc = npc(NpcContext.ALL_ORDER_SLOTS, NpcContext.CAP_UNLIMITED);

		NpcBasket.Basket small = NpcBasket.plan(manyItems(20, 1_000_000L, npc, false));
		NpcBasket.Basket large = NpcBasket.plan(manyItems(20, 100_000_000L, npc, false));

		assertTrue(small.capital() <= 1_000_000L);
		assertTrue(large.capital() > small.capital() * 10L,
				"a hundred times the bankroll should buy a much larger basket: " + large.capital()
						+ " against " + small.capital());
		assertTrue(large.profit() > small.profit());
	}

	@Test
	void plansNothingWhenNoItemClearsTheFilters() {
		// A 5% gap: real, and below the 15% floor every one of these has to clear.
		Map<String, BazaarProduct> products = Map.of("THIN", product("THIN", 950.0d, 50_000_000L));
		Map<String, ItemCatalog.Entry> items = Map.of("THIN", entry("THIN", 1000.0d, false));

		NpcBasket.Basket basket = NpcBasket.plan(
				context(products, items, BANKROLL, npc(NpcContext.ALL_ORDER_SLOTS,
						NpcContext.CAP_UNLIMITED)));

		assertTrue(basket.isEmpty());
		assertEquals(0, basket.slotsUsed());
		assertEquals(NpcBasket.Bound.CANDIDATES, basket.bound());
		assertEquals(0.0d, basket.profitPerHour());
	}
}
