/*
 * Skyblock Flipper - a Hypixel Skyblock flipping advisor mod.
 * Copyright (C) 2026 SoupChugger
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package jeff.skyblockflipper.core.strategy;

import jeff.skyblockflipper.core.config.NpcRanking;
import jeff.skyblockflipper.core.model.BazaarProduct;
import jeff.skyblockflipper.core.model.BazaarSnapshot;
import jeff.skyblockflipper.core.model.ItemCatalog;
import jeff.skyblockflipper.core.model.OrderLevel;
import jeff.skyblockflipper.core.pricing.Fees;
import jeff.skyblockflipper.core.valuation.NpcEdge;
import jeff.skyblockflipper.core.valuation.NpcEdgeSnapshot;
import jeff.skyblockflipper.core.valuation.TrendSnapshot;

import org.junit.jupiter.api.Test;

import java.time.Duration;
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

	/** The shipped defaults over whatever measured history the chase is meant to be priced off. */
	private static NpcContext npcWith(NpcEdgeSnapshot edges) {
		return new NpcContext(edges, NpcContext.DEFAULT_MIN_MARGIN_RATIO, NpcContext.DEFAULT_CHECK_IN,
				NpcContext.DEFAULT_RESTING_HOURS, NpcContext.ALL_ORDER_SLOTS,
				NpcContext.CAP_UNLIMITED, NpcContext.UNLIMITED_ORDERS_PER_ITEM, NpcRanking.LOAD);
	}

	/**
	 * A snapshot with one product's drift measured, deep enough to clear {@link NpcEdge#MIN_SAMPLES}.
	 *
	 * <p>{@code persistence} is 1.0 so the gap is never the thing being tested here.
	 */
	private static NpcEdgeSnapshot edges(String id, double npcPrice, double bidDriftPerHour) {
		return new NpcEdgeSnapshot(
				Map.of(id, new NpcEdge(id, npcPrice, 1.0d, 0.3d, bidDriftPerHour, 72.0d, 1000,
						NpcEdge.MIN_SAMPLES)),
				Duration.ofDays(3), NpcEdge.MIN_SAMPLES, Instant.now());
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
	 * A position nothing may add to is dropped rather than offered a second line. The order resting
	 * on it has a price this allocator does not know, and two lines on one item quoted from two
	 * places is bidding against yourself.
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

	/**
	 * A position open to a top-up is offered the shortfall, and only the shortfall.
	 *
	 * <p>One bazaar order holds 256 unstackable units, so a 3,584-unit line is fourteen orders typed
	 * one at a time and the account spends most of the cycle holding part of one. Dropping the item
	 * on the first of the fourteen deleted the line while it was being followed, which is what a
	 * player hit on 2026-08-12: the row vanished at 256 units placed and nothing said 3,328.
	 */
	@Test
	void offersTheRestOfAPositionItIsPartWayThrough() {
		NpcContext npc = npc(NpcContext.ALL_ORDER_SLOTS, NpcContext.CAP_UNLIMITED);
		StrategyContext context = manyItems(5, 10_000_000_000L, npc, true);

		// Fourteen slots, all of them worth spending on the best item on the book.
		NpcBasket.Line whole = NpcBasket.plan(context).lines().getFirst();

		assertEquals(256L * 14L, whole.units());
		assertFalse(whole.topUp());

		NpcBasket.Basket basket = NpcBasket.plan(context, new NpcBasket.Held(1, 256L * 700L,
				Map.of(whole.plan().itemId(), new NpcBasket.Position(1, 256L, true))));
		NpcBasket.Line line = basket.lines().getFirst();

		assertEquals(whole.plan().itemId(), line.plan().itemId());
		assertEquals(256L * 13L, line.units(), "one order placed, thirteen slots left");
		assertEquals(13, line.orders());
		assertEquals("13 x 256", line.orderSplit());
		assertTrue(line.topUp());
		assertEquals(256L, line.restingUnits());
		assertEquals(256L * 14L, line.positionUnits());

		// The capital is for the units being placed now, not for the whole position again.
		assertTrue(line.capital() < whole.capital(), line.capital() + " against " + whole.capital());
	}

	/** What is already committed is never bought twice, whatever is left of the slots. */
	@Test
	void neverBuysMoreThanThePositionIsWorth() {
		NpcContext npc = npc(NpcContext.ALL_ORDER_SLOTS, NpcContext.CAP_UNLIMITED);
		StrategyContext context = manyItems(5, 10_000_000_000L, npc, true);

		NpcBasket.Line whole = NpcBasket.plan(context).lines().getFirst();
		String best = whole.plan().itemId();

		// The position already holds every unit the item is worth on its own - which is more than the
		// slots let one basket place - so there is nothing to add to it and what is left of the slots
		// goes to the next item down the ranking instead.
		NpcBasket.Basket basket = NpcBasket.plan(context, new NpcBasket.Held(1, 0L,
				Map.of(best, new NpcBasket.Position(1, whole.plan().maxUnits(), true))));

		assertTrue(basket.lines().stream().noneMatch(line -> line.plan().itemId().equals(best)),
				best + " already holds every unit it is worth");
		assertFalse(basket.lines().isEmpty(), "the free slots should have gone somewhere");
	}

	/**
	 * A line size that drifted by a unit is not a remainder to go and place.
	 *
	 * <p>{@code maxUnits} is recomputed off the live book every trip, so it moves a little between
	 * them. A player who placed an order for its full size on 2026-08-14 was told on the next trip to
	 * place another for 1 unit - an order slot and the whole six-click flow for one unit.
	 */
	@Test
	void doesNotOfferATopUpThatIsOnlyTheLineSizeDrifting() {
		NpcContext npc = npc(NpcContext.ALL_ORDER_SLOTS, NpcContext.CAP_UNLIMITED);
		StrategyContext context = manyItems(5, 10_000_000_000L, npc, true);

		NpcBasket.Line whole = NpcBasket.plan(context).lines().getFirst();
		String best = whole.plan().itemId();

		NpcBasket.Basket basket = NpcBasket.plan(context, new NpcBasket.Held(1, 0L,
				Map.of(best, new NpcBasket.Position(1, whole.plan().maxUnits() - 1L, true))));

		assertTrue(basket.lines().stream().noneMatch(line -> line.plan().itemId().equals(best)),
				"1 unit short of the position is not an order worth a slot");
	}

	/** The floor is on drift and must not reach the last real order of a part-placed line. */
	@Test
	void stillOffersTheRemainderOfAPositionThatIsMostlyPlaced() {
		NpcContext npc = npc(NpcContext.ALL_ORDER_SLOTS, NpcContext.CAP_UNLIMITED);
		StrategyContext context = manyItems(5, 10_000_000_000L, npc, true);

		NpcBasket.Line whole = NpcBasket.plan(context).lines().getFirst();
		String best = whole.plan().itemId();
		long placed = whole.plan().maxUnits() - whole.plan().maxUnits() / 4L;

		NpcBasket.Basket basket = NpcBasket.plan(context, new NpcBasket.Held(1, 0L,
				Map.of(best, new NpcBasket.Position(1, placed, true))));

		// A quarter of the position is still to place, which is more than the slots left can hold -
		// so the line is offered and sized on the slots, exactly as an untouched position would be.
		assertEquals(256L * 13L, basket.lines().stream()
				.filter(line -> line.plan().itemId().equals(best))
				.mapToLong(NpcBasket.Line::units)
				.sum());
	}

	/** A per-item order cap counts the orders already resting, not just the ones being added. */
	@Test
	void countsRestingOrdersAgainstThePerItemCap() {
		StrategyContext context = manyItems(10, 10_000_000_000L,
				npc(NpcContext.ALL_ORDER_SLOTS, NpcContext.CAP_UNLIMITED, 2), true);

		String best = NpcBasket.plan(context).lines().getFirst().plan().itemId();
		NpcBasket.Basket basket = NpcBasket.plan(context, new NpcBasket.Held(1, 0L,
				Map.of(best, new NpcBasket.Position(1, 256L, true))));

		assertEquals(1, basket.lines().stream()
						.filter(line -> line.plan().itemId().equals(best))
						.mapToInt(NpcBasket.Line::orders)
						.sum(),
				"capped at two orders an item, one of which is already on the book");
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

	// The ranking key, which is a choice between two budgets rather than a right and a wrong answer.

	private static NpcContext ranked(int maxOrderSlots, NpcRanking ranking) {
		return new NpcContext(NpcEdgeSnapshot.empty(), NpcContext.DEFAULT_MIN_MARGIN_RATIO,
				NpcContext.DEFAULT_CHECK_IN, NpcContext.DEFAULT_RESTING_HOURS, maxOrderSlots,
				NpcContext.CAP_UNLIMITED, NpcContext.UNLIMITED_ORDERS_PER_ITEM, ranking);
	}

	/**
	 * A stackable item carries 71,680 units in an order slot and 2,304 in an inventory load, and an
	 * unstackable one carries 256 in both. So the two keys disagree about the same pair of items, and
	 * which one is right depends on whether the player is short of slots or of patience.
	 *
	 * <p>DENSE is the richer item per inventory slot; WIDE is the richer item per order slot. On the
	 * live book on 2026-08-14 that disagreement was worth 65.5M a cycle against 47.3M, at 363
	 * inventory loads against 114.
	 */
	@Test
	void ranksOnWhicheverBudgetTheContextNames() {
		Map<String, BazaarProduct> products = Map.of(
				"DENSE", product("DENSE", 99.9d, 50_000_000L),
				"WIDE", product("WIDE", 49.9d, 50_000_000L));
		Map<String, ItemCatalog.Entry> items = Map.of(
				// Unstackable: 256 units an order, 36 an inventory load, 900 coins of profit a unit.
				// 32,400 a load and 230,400 an order slot.
				"DENSE", entry("DENSE", 1000.0d, true),
				// Stackable: 71,680 an order, 2,304 a load, 10 coins a unit. Less per load at 23,040,
				// and three times as much per order slot at 716,800, because one order of it is 280
				// times the size.
				"WIDE", entry("WIDE", 60.0d, false));

		assertEquals("DENSE", NpcBasket.plan(
						context(products, items, 100_000_000_000L, ranked(1, NpcRanking.LOAD)))
				.lines().getFirst().plan().itemId());

		assertEquals("WIDE", NpcBasket.plan(
						context(products, items, 100_000_000_000L, ranked(1, NpcRanking.ORDER_SLOT)))
				.lines().getFirst().plan().itemId());
	}

	/**
	 * A buy order is posted one increment above the book and never above it.
	 *
	 * <p>Paying the resting window's measured drift into the posted price instead was a setting
	 * ({@code npcDriftPremium}) until 2026-08-19. It was removed on measurement, not on taste:
	 * overnight on 2026-08-16 an order posted 3.9% above the book held the top for 4 of 145 samples,
	 * because a competitor parks a coin or two above your specific order whichever price you picked.
	 * The tape that predicted otherwise contained none of the user's own orders.
	 *
	 * <p>So the chase is charged against the margin and paid a reprice at a time, and what goes in
	 * the price box is whatever Hypixel's own "+0.1" button offers. This pins that the drift moves
	 * the cost and never the posted price.
	 */
	@Test
	void postsAtThePlainOutbidPriceWhateverTheDriftIs() {
		NpcPlan drifting = onlyPlanFor("ITEM_0",
				manyItems(1, BANKROLL, npcWith(edges("ITEM_0", 1000.0d, 1.0d)), false));
		NpcPlan still = onlyPlanFor("ITEM_0",
				manyItems(1, BANKROLL, npcWith(NpcEdgeSnapshot.empty()), false));

		// ITEM_0's book bids 700.0, so the plain outbid is 700.1 on both.
		assertEquals(700.1d, still.postPrice(), 1e-9);
		assertEquals(700.1d, drifting.postPrice(), 1e-9);

		// 1 coin an hour of drift over the default 8-hour window is 8 coins of chase, and all of it
		// lands on the cost rather than on the price.
		assertEquals(700.1d, still.unitCost(), 1e-9);
		assertEquals(708.1d, drifting.unitCost(), 1e-9);
	}

	/**
	 * A client with no measured drift at all still plans.
	 *
	 * <p>{@code rebuildNpcEdges} publishes nothing until it has read three days of tape, so for the
	 * first seconds of a session every {@code NpcEdge} is absent. That used to be a refusal, because
	 * a premium priced off an absent drift was silently zero and the plan was one the player could
	 * not tell from a correct one. With no premium there is nothing to be silently wrong about: an
	 * unmeasured chase is charged at zero, which is the same reading a fresh install gets.
	 */
	@Test
	void planningNeedsNoMeasuredDrift() {
		StrategyContext context = manyItems(20, BANKROLL, npcWith(NpcEdgeSnapshot.empty()), false);

		assertFalse(NpcBasket.plan(context).isEmpty());
	}

	private static NpcPlan onlyPlanFor(String itemId, StrategyContext context) {
		return NpcFlipStrategy.restingPlans(context).stream()
				.filter(plan -> plan.itemId().equals(itemId))
				.findFirst()
				.orElseThrow(() -> new AssertionError("no plan for " + itemId));
	}
}
