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
import java.util.Set;

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

	private static NpcContext npc(int maxOrderSlots, long capRemaining, int maxOrdersPerItem) {
		return new NpcContext(NpcEdgeSnapshot.empty(), NpcContext.DEFAULT_MIN_MARGIN_RATIO,
				NpcContext.DEFAULT_CHECK_IN, NpcContext.DEFAULT_RESTING_HOURS, maxOrderSlots,
				capRemaining, maxOrdersPerItem);
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

	/** The same context with a per-plan share of the bankroll, so no one plan can take all of it. */
	private static StrategyContext capped(StrategyContext context, double maxCapitalShare) {
		return new StrategyContext(context.bazaar(), context.catalog(), context.underpriced(),
				context.trends(), context.fees(), context.bankroll(), context.minProfitPerFlip(),
				context.minConfidence(), context.maxAdverseDrift(), context.fillHorizon(),
				maxCapitalShare, context.npc());
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

	/** The same context with a profit floor, which is the one thing {@link #context} leaves at zero. */
	private static StrategyContext withFloor(StrategyContext context, long minProfitPerFlip) {
		return new StrategyContext(context.bazaar(), context.catalog(), context.underpriced(),
				context.trends(), context.fees(), context.bankroll(), minProfitPerFlip,
				context.minConfidence(), context.maxAdverseDrift(), context.fillHorizon(),
				context.maxCapitalShare(), context.npc());
	}

	@Test
	void theProfitFloorIsTheWholeLineNotAnHourOfIt() {
		// minProfitPerFlip used to be compared against lineProfit / restingHours here and against a
		// plain total in NpcReprice and the other two strategies, so one number the player sets once
		// meant two different things and ConfigSchema described only one of them.
		StrategyContext context = manyItems(5, 10_000_000_000L,
				npc(NpcContext.ALL_ORDER_SLOTS, NpcContext.CAP_UNLIMITED), false);

		double smallest = NpcBasket.plan(context).lines().stream()
				.mapToDouble(NpcBasket.Line::profit)
				.min()
				.orElseThrow();

		// A floor above what the line makes in total drops it, which is the whole point of a floor.
		assertTrue(NpcBasket.plan(withFloor(context, Math.round(smallest) + 1L)).lines().stream()
				.noneMatch(line -> line.profit() <= smallest));

		// And a floor at half of it keeps it. Under the old rate reading this same number excluded
		// anything under four times the line's profit, so the line would be gone.
		assertTrue(NpcBasket.plan(withFloor(context, Math.round(smallest / 2.0d))).lines().stream()
				.anyMatch(line -> line.profit() <= smallest));
	}

	@Test
	void aPerItemOrderCapSpreadsTheSlotsOverMoreItems() {
		// The same book as above, where one unstackable item took all 14 slots. Capped at two, the
		// slots the first item does not take go to the next items down the ranking rather than
		// nowhere - which is what makes the cap a trade between items and not a smaller basket.
		StrategyContext context = manyItems(10, 10_000_000_000L,
				npc(NpcContext.ALL_ORDER_SLOTS, NpcContext.CAP_UNLIMITED, 2), true);

		NpcBasket.Basket basket = NpcBasket.plan(context);

		assertEquals(7, basket.lines().size());
		assertEquals(14, basket.slotsUsed());
		basket.lines().forEach(line -> assertEquals(2, line.orders()));
		assertEquals(NpcBasket.Bound.SLOTS, basket.bound());
	}

	@Test
	void anUncappedContextSizesAnItemExactlyAsItAlwaysHas() {
		// The shipped path: zero means unlimited, and the six-argument constructor means zero. Every
		// measurement in docs/npc-flipping.md was taken here, so this is what must not move.
		StrategyContext context = manyItems(5, 10_000_000_000L,
				npc(NpcContext.ALL_ORDER_SLOTS, NpcContext.CAP_UNLIMITED,
						NpcContext.UNLIMITED_ORDERS_PER_ITEM), true);

		NpcBasket.Basket basket = NpcBasket.plan(context);

		assertEquals(1, basket.lines().size());
		assertEquals(14, basket.lines().getFirst().orders());
	}

	@Test
	void aLineSaysHowItsUnitsDivideIntoOrdersYouCanPlace() {
		// The failure this exists for: a line reading "3584" was typed into a bazaar that takes 256
		// of the item at a time. The total alone reads as one order whatever the order count says.
		StrategyContext context = manyItems(5, 10_000_000_000L,
				npc(NpcContext.ALL_ORDER_SLOTS, NpcContext.CAP_UNLIMITED), true);

		assertEquals("14 x 256", NpcBasket.plan(context).lines().getFirst().orderSplit());

		// A line the bankroll cut short mid-order says so rather than rounding to whole orders.
		NpcBasket.Line partial = new NpcBasket.Line(
				plan("ITEM_0", 256L), 500L, 2, 500_000L, 1_000.0d);

		assertEquals("256 + 244", partial.orderSplit());

		// And one order's worth is just the number: there is nothing to divide.
		assertEquals("200", new NpcBasket.Line(plan("ITEM_0", 256L), 200L, 1, 1L, 1.0d).orderSplit());
	}

	/** A plan carrying nothing but the per-order ceiling, which is all {@code orderSplit} reads. */
	private static NpcPlan plan(String id, long unitsPerOrder) {
		return new NpcPlan(id, id, 1000.0d, 800.0d, 800.0d, 200.0d, 100_000L, unitsPerOrder,
				36L, 100.0d, true, null, 0.5d);
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
	void blamesTheBankrollWhenItOnlyTruncatedTheLastLine() {
		// Three plans, each allowed 40% of the bankroll: the first two take theirs and the third
		// gets the 20% that is left. Every plan is on the basket and slots and cap are untouched,
		// so the only thing that made the last line smaller than it asked for is coins.
		NpcContext npc = npc(NpcContext.ALL_ORDER_SLOTS, NpcContext.CAP_UNLIMITED);
		NpcBasket.Basket basket = NpcBasket.plan(capped(manyItems(3, BANKROLL, npc, false), 0.4d));

		assertEquals(3, basket.lines().size());
		assertTrue(basket.lines().getLast().units() < basket.lines().getFirst().units(),
				"the last line should have been cut short by what was left");
		assertEquals(NpcBasket.Bound.CAPITAL, basket.bound());
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

	/**
	 * The second basket of a cycle is not the first one again.
	 *
	 * <p>Without this the mod tells a player with fourteen orders resting to place twenty-one more,
	 * spending coins that are already escrowed into slots that do not exist.
	 */
	@Test
	void allocatesOnlyTheSlotsAndCoinsThatAreActuallyFree() {
		// Coins to spare and thin books, so one item fills one order and slots are what bind.
		NpcContext npc = npc(10, NpcContext.CAP_UNLIMITED);
		StrategyContext context = manyItems(20, 10_000_000_000L, npc, false, 3_000_000L);

		NpcBasket.Basket empty = NpcBasket.plan(context);
		NpcBasket.Basket partial = NpcBasket.plan(context, new NpcBasket.Held(6, 0L, Set.of()));

		assertEquals(10, empty.slotsUsed());
		assertEquals(4, partial.slotsFree());
		assertEquals(4, partial.slotsUsed());
		assertEquals(NpcBasket.Bound.SLOTS, partial.bound());
	}

	@Test
	void sizesTheBasketAgainstTheCoinsNotAlreadyEscrowed() {
		NpcContext npc = npc(NpcContext.ALL_ORDER_SLOTS, NpcContext.CAP_UNLIMITED);
		StrategyContext context = manyItems(20, BANKROLL, npc, false);

		NpcBasket.Basket partial =
				NpcBasket.plan(context, new NpcBasket.Held(1, BANKROLL / 2L, Set.of()));

		assertEquals(BANKROLL / 2L, partial.bankrollFree());
		assertTrue(partial.capital() <= BANKROLL / 2L,
				"asked for " + partial.capital() + " with half the bankroll already committed");

		// And the full-account basket really does want more than that, so this is a restriction
		// rather than a book that was never worth half the bankroll.
		assertTrue(NpcBasket.plan(context).capital() > BANKROLL / 2L);
	}

	/**
	 * An item you already have an order on is dropped, not topped up. The resting order has a price
	 * this allocator does not know, and a second line at a second price is bidding against yourself.
	 */
	@Test
	void refusesToPlaceASecondOrderOnAnItemAlreadyResting() {
		NpcContext npc = npc(NpcContext.ALL_ORDER_SLOTS, NpcContext.CAP_UNLIMITED);
		StrategyContext context = manyItems(5, BANKROLL, npc, false);

		NpcBasket.Basket basket = NpcBasket.plan(context,
				new NpcBasket.Held(1, 0L, Set.of("ITEM_0")));

		assertFalse(basket.lines().isEmpty());
		assertTrue(basket.lines().stream().noneMatch(line -> line.plan().itemId().equals("ITEM_0")),
				"ITEM_0 already has an order resting on it");
	}

	/** Every slot working is a different answer from nothing being worth an order. */
	@Test
	void blamesTheSlotsWhenEveryOneIsAlreadyResting() {
		// All 14 the account has, so the settings are not what capped it and the explanation is
		// about the orders rather than about a setting to change.
		NpcContext npc = npc(NpcContext.ALL_ORDER_SLOTS, NpcContext.CAP_UNLIMITED);
		NpcBasket.Basket basket = NpcBasket.plan(manyItems(20, BANKROLL, npc, false),
				new NpcBasket.Held(14, 0L, Set.of()));

		assertTrue(basket.isEmpty());
		assertEquals(0, basket.slotsFree());
		assertEquals(NpcBasket.Bound.SLOTS, basket.bound());
		assertFalse(basket.slotsCappedBySettings());
		assertTrue(basket.boundExplanation().contains("already resting"),
				basket.boundExplanation());
	}

	/**
	 * The one slot limit that costs nothing to lift, and the one the game cannot show you.
	 *
	 * <p>Measured on the live book on 2026-08-11 against a real config that had it set to 14 of 21:
	 * 25.1M a cycle against 32.3M. The basket knew, and said "each Bazaar Flipper level adds seven",
	 * which is advice to buy a perk the player already had.
	 */
	@Test
	void namesTheSettingWhenItIsTheSettingCappingTheSlots() {
		NpcBasket.Basket basket = NpcBasket.plan(manyItems(30, 10_000_000_000L,
				npc(4, NpcContext.CAP_UNLIMITED), false, 3_000_000L));

		assertEquals(NpcBasket.Bound.SLOTS, basket.bound());
		assertEquals(4, basket.slotsAvailable());
		assertEquals(14, basket.slotsOnAccount());
		assertTrue(basket.slotsCappedBySettings());
		assertTrue(basket.boundExplanation().contains("NPC order slots in settings"),
				basket.boundExplanation());
	}
}
