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
 * The two halves of a cycle, joined.
 *
 * <p>What these are really pinning is that the join is arithmetic and not presentation: the orders
 * already on the book have to come out of the slots and the coins before the new lines are sized,
 * or the list is a plan for an account the player does not have.
 */
class NpcWorklistTest {
	private static final long NOW = 1_754_000_000_000L;
	private static final double NPC_PRICE = 1000.0d;
	private static final long BANKROLL = 10_000_000_000L;

	private static BazaarProduct product(String id, double bid) {
		return new BazaarProduct(
				id,
				List.of(new OrderLevel(NPC_PRICE * 100.0d, 10_000L, 20)),
				List.of(new OrderLevel(bid, 10_000L, 20)),
				new BazaarProduct.MovingWeek(3_000_000L, 3_000_000L));
	}

	/** {@code count} items an NPC pays 1000 for, each bid at 700 so every one clears the floor. */
	private static StrategyContext context(int count, int slots) {
		return context(count, slots, 700.0d);
	}

	/** The same market with the whole book at a different level, which is how it moves under a round. */
	private static StrategyContext context(int count, int slots, double topBid) {
		return context(count, slots, topBid, false);
	}

	/**
	 * The same market on products that do not stack, where one bazaar order holds 256 units.
	 *
	 * <p>The case a part-placed line only exists in: 1,024 units of an unstackable product is four
	 * orders typed one at a time, so between the first and the fourth the book holds part of a line.
	 */
	private static StrategyContext unstackable(int count, int slots) {
		return context(count, slots, 700.0d, true);
	}

	private static StrategyContext context(int count, int slots, double topBid,
			boolean unstackable) {
		Map<String, BazaarProduct> products = new LinkedHashMap<>();
		Map<String, ItemCatalog.Entry> items = new LinkedHashMap<>();

		for (int i = 0; i < count; i++) {
			String id = "ITEM_" + i;
			products.put(id, product(id, topBid - i));
			items.put(id, new ItemCatalog.Entry(id, id, NPC_PRICE, unstackable, List.of()));
		}

		return new StrategyContext(
				new BazaarSnapshot(Instant.now(), products),
				new ItemCatalog(items),
				List.of(),
				TrendSnapshot.empty(),
				new Fees(0, false),
				BANKROLL,
				0L,
				0.0d,
				0.0d,
				StrategyContext.DEFAULT_FILL_HORIZON,
				StrategyContext.UNCAPPED,
				new NpcContext(NpcEdgeSnapshot.empty(), NpcContext.DEFAULT_MIN_MARGIN_RATIO,
						NpcContext.DEFAULT_CHECK_IN, NpcContext.DEFAULT_RESTING_HOURS, slots,
						NpcContext.CAP_UNLIMITED));
	}

	private static List<NpcWorklist.Kind> kinds(NpcWorklist.Worklist worklist) {
		return worklist.pending().stream().map(NpcWorklist.Task::kind).toList();
	}

	@Test
	void withNothingRestingItIsJustTheBasket() {
		NpcWorklist.Worklist worklist = NpcWorklist.of(List.of(), context(10, 4), NOW);

		assertEquals(4, worklist.count(NpcWorklist.Kind.PLACE));
		assertEquals(0, worklist.holding());
		assertTrue(worklist.headline().contains("4 to place"), worklist.headline());
	}

	/** The whole reason the join exists: three slots resting means one slot to fill, not four. */
	@Test
	void chargesTheBasketForTheOrdersAlreadyOnTheBook() {
		List<NpcReprice.Order> resting = List.of(
				NpcReprice.Order.of("ITEM_0", "ITEM_0", 700.0d, 500L),
				NpcReprice.Order.of("ITEM_1", "ITEM_1", 699.0d, 500L),
				NpcReprice.Order.of("ITEM_2", "ITEM_2", 698.0d, 500L));

		NpcWorklist.Worklist worklist = NpcWorklist.of(resting, context(10, 4), NOW);

		assertEquals(3, worklist.holding());
		assertEquals(1, worklist.count(NpcWorklist.Kind.PLACE));

		// And never on an item that already has an order resting on it.
		assertTrue(worklist.pending().stream()
				.noneMatch(task -> task.itemId().startsWith("ITEM_0")
						|| task.itemId().equals("ITEM_1") || task.itemId().equals("ITEM_2")));
	}

	/**
	 * A line larger than one order counts down as it is placed, rather than vanishing on the first one.
	 *
	 * <p>Reported from live play on 2026-08-12: the panel asked for 1,024 units of an unstackable
	 * product, the player typed the first order of 256, and the row disappeared - the basket dropped
	 * any item with an order resting on it, so the remaining 768 were asked for by nothing and shown
	 * nowhere. There is no way to place four orders at once, so every multi-order line spent most of
	 * its life in exactly this state.
	 */
	@Test
	void countsDownAPartPlacedLineRatherThanForgettingIt() {
		StrategyContext context = unstackable(1, 4);

		// Four slots on a product that takes 256 to an order, so the plan is 1,024 units.
		NpcWorklist.Task whole = of(NpcWorklist.of(List.of(), context, NOW), NpcWorklist.Kind.PLACE)
				.getFirst();

		assertEquals(1_024L, whole.units());
		assertEquals("4 x 256", whole.orderSplit());

		// One order typed, at the price the row quoted. Three to go.
		List<NpcReprice.Order> resting =
				List.of(NpcReprice.Order.of("ITEM_0", "ITEM_0", 700.1d, 256L));
		NpcWorklist.Worklist worklist = NpcWorklist.of(resting, context, NOW);
		List<NpcWorklist.Task> places = of(worklist, NpcWorklist.Kind.PLACE);

		assertEquals(1, places.size());
		assertEquals(768L, places.getFirst().units());
		assertEquals("3 x 256", places.getFirst().orderSplit());
		assertEquals(1, worklist.holding(), "the order already placed is a hold, not a second line");

		// And the row says what is already up, so "768" cannot be read as a fresh plan of 768.
		assertTrue(places.getFirst().reason().contains("256 of the 1024 this item is worth are "
				+ "already resting"), places.getFirst().reason());
	}

	/** The position is what is sized, so what is already up is never bought twice. */
	@Test
	void stopsToppingUpWhenThePositionIsTheSizeItIsWorth() {
		StrategyContext context = unstackable(1, 4);

		// All four orders placed. Nothing is left to ask for, and the account has no slot for it.
		List<NpcReprice.Order> resting = List.of(
				NpcReprice.Order.of("ITEM_0", "ITEM_0", 700.1d, 256L),
				NpcReprice.Order.of("ITEM_0", "ITEM_0", 700.1d, 256L),
				NpcReprice.Order.of("ITEM_0", "ITEM_0", 700.1d, 256L),
				NpcReprice.Order.of("ITEM_0", "ITEM_0", 700.1d, 256L));

		NpcWorklist.Worklist worklist = NpcWorklist.of(resting, context, NOW);

		assertEquals(0, worklist.count(NpcWorklist.Kind.PLACE));
		assertEquals(4, worklist.holding());
	}

	/**
	 * A top-up is refused while anything else in the trip is asking about the item's orders.
	 *
	 * <p>A reprice and a cancel each carry a price the basket does not know - the frozen one the
	 * player is walking to the menu with, or none at all - and a place quoted from the allocator
	 * beside a reprice quoted from the round is two prices for one item, which is how a player ends
	 * up outbidding themselves.
	 */
	@Test
	void refusesToTopUpAnItemTheTripIsAlreadyRepricing() {
		StrategyContext context = unstackable(1, 4);
		List<NpcReprice.Order> resting = List.of(outbid("ITEM_0", 256L));

		NpcWorklist.Worklist worklist =
				NpcWorklist.of(resting, context, NOW, roundOver(resting, context));

		assertEquals(1, worklist.count(NpcWorklist.Kind.REPRICE));
		assertEquals(0, worklist.count(NpcWorklist.Kind.PLACE));

		// Without the round, straight off the book, the reprice is still what owns the item.
		assertEquals(0, NpcWorklist.of(resting, context, NOW).count(NpcWorklist.Kind.PLACE));
	}

	/**
	 * Claims first, then cancels, then reprices, then the new orders.
	 *
	 * <p>Not the order the coins are in. A claim is money already made and blocks the item from
	 * leaving the order at all; a cancel hands back the slot every later line is short of; a place
	 * needs coins the cancel has just returned.
	 */
	@Test
	void ordersTheTripByWhatCannotBeSkipped() {
		long expired = NOW - Math.round(NpcContext.DEFAULT_RESTING_HOURS * 3_600_000.0d) - 1L;

		List<NpcReprice.Order> resting = List.of(
				// Outbid: the book's best bid is 700 and this rests at 600.
				new NpcReprice.Order("ITEM_0", "ITEM_0", 600.0d, 500L, 500L, 0L, 0L),
				// Past its window, still on top of the book.
				new NpcReprice.Order("ITEM_1", "ITEM_1", 699.0d, 500L, 500L, 0L, expired),
				// Half filled and never collected.
				new NpcReprice.Order("ITEM_2", "ITEM_2", 698.0d, 500L, 250L, 250L, 0L));

		NpcWorklist.Worklist worklist = NpcWorklist.of(resting, context(10, 5), NOW);

		assertEquals(List.of(
						NpcWorklist.Kind.CLAIM,
						NpcWorklist.Kind.CANCEL,
						NpcWorklist.Kind.REPRICE,
						NpcWorklist.Kind.PLACE,
						NpcWorklist.Kind.PLACE),
				kinds(worklist));

		assertTrue(worklist.headline()
						.startsWith("1 to claim, 1 to cancel, 1 to reprice, 2 to place"),
				worklist.headline());
	}

	/** A claim is emitted even on an order that is about to be cancelled. Two clicks, both safe. */
	@Test
	void claimsBeforeCancellingAPartlyFilledOrder() {
		long expired = NOW - Math.round(NpcContext.DEFAULT_RESTING_HOURS * 3_600_000.0d) - 1L;

		NpcWorklist.Worklist worklist = NpcWorklist.of(
				List.of(new NpcReprice.Order("ITEM_0", "ITEM_0", 700.0d, 500L, 300L, 200L, expired)),
				context(3, 5), NOW);

		assertEquals(1, worklist.count(NpcWorklist.Kind.CLAIM));
		assertEquals(1, worklist.count(NpcWorklist.Kind.CANCEL));
		assertEquals(NpcWorklist.Kind.CLAIM, worklist.pending().getFirst().kind());
		assertTrue(worklist.pending().getFirst().reason().contains("before cancelling"),
				worklist.pending().getFirst().reason());
	}

	@Test
	void saysSoWhenThereIsNothingToDoAtAll() {
		NpcWorklist.Worklist worklist = NpcWorklist.of(List.of(), context(0, 4), NOW);

		assertTrue(worklist.isEmpty());
		assertFalse(worklist.headline().isEmpty());
		assertTrue(worklist.headline().contains("Nothing to do"), worklist.headline());
	}

	// The round, which is where the reprices come from once there is a clock.

	/** An order old enough for the round, outbid on a book bidding 700 against the 600 it rests at. */
	private static NpcReprice.Order outbid(String id, long remaining) {
		return new NpcReprice.Order(id, id, 600.0d, remaining, remaining, 0L,
				NOW - NpcContext.DEFAULT_CHECK_IN.toMillis());
	}

	private static NpcRound roundOver(List<NpcReprice.Order> resting, StrategyContext context) {
		return NpcRound.open(NOW, NpcContext.DEFAULT_CHECK_IN,
				NpcReprice.review(resting, context, NOW));
	}

	private static List<NpcWorklist.Task> of(NpcWorklist.Worklist worklist, NpcWorklist.Kind kind) {
		return worklist.tasks().stream().filter(task -> task.kind() == kind).toList();
	}

	/**
	 * The round freezes which items to work. It does not freeze the number typed into the price box.
	 *
	 * <p>The two were frozen together and only the first is what the clock is for. A player reading
	 * this row is standing in front of Hypixel's own "+0.1 coins" button, which reads the live book,
	 * so a price frozen half an hour ago is visibly a different number from the one the game offers
	 * and there is no way to tell from the panel which to believe. The top bid moved inside a
	 * thirty-minute window on 39% of Enchanted Poisonous Potato samples and 83% of Bronze Bowl ones
	 * over 2026-08-11..13 of the tape, so that disagreement is the common case rather than the edge.
	 */
	@Test
	void keepsTheRoundsRowButQuotesThePriceTheBookHasNow() {
		List<NpcReprice.Order> resting = List.of(outbid("ITEM_0", 500L));
		NpcRound round = roundOver(resting, context(10, 5));

		// The book has since walked up 60 coins. The row is the round's; the price is the book's.
		NpcWorklist.Worklist worklist =
				NpcWorklist.of(resting, context(10, 5, 760.0d), NOW, round);

		List<NpcWorklist.Task> reprices = of(worklist, NpcWorklist.Kind.REPRICE);

		assertEquals(1, reprices.size());
		assertEquals(760.1d, reprices.getFirst().price(), 1e-9d);
		assertEquals(500L, reprices.getFirst().units());
		assertTrue(worklist.hasRound());

		// Still one row and still the round's, rather than a fresh review of a moved book: an order
		// too young for this round stays out of it however far the book walks.
		assertEquals(1, round.rows().size());
	}

	/**
	 * The book crossed the chase stop while the row was in hand, and the row goes rather than asking
	 * for a price the strategy would refuse to open a position at.
	 *
	 * <p>Mid-reprice on purpose: with an order still resting the live review emits the cancel and
	 * {@code rowsToWork} drops the row on that. With the order already cancelled there is nothing to
	 * cancel and nothing to say but "do not put it back", so the row has to be dropped here.
	 */
	@Test
	void dropsAPinnedRowTheBookHasChasedPastTheStop() {
		NpcRound round = roundOver(List.of(outbid("ITEM_0", 500L)), context(10, 5));

		// The default floor is 20% of the 1000 an NPC pays, so 800 is the stop and 850.1 is past it.
		NpcWorklist.Worklist worklist = NpcWorklist.of(List.of(), context(10, 5, 850.0d), NOW, round);

		assertTrue(of(worklist, NpcWorklist.Kind.REPRICE).isEmpty());
	}

	/**
	 * A pinned row the book has chased past the stop frees its slot to this trip's basket, rather than
	 * holding it reserved for a re-post that must not happen.
	 *
	 * <p>The reunion's behaviour change. Evaluating each row's live reprice once, before anything is
	 * reserved, means a dropped row never takes a slot: the old two-pass order reserved the slot and
	 * then dropped the row, so the coins sat idle until the next round. Mid-reprice and past the 850
	 * stop, the account is whole again.
	 */
	@Test
	void freesThePinnedRowsSlotWhenTheBookHasChasedPastTheStop() {
		NpcRound round = roundOver(List.of(outbid("ITEM_0", 500L)), context(10, 5));

		// Past the stop: dropped, and its slot handed back, so all five slots are the basket's.
		NpcWorklist.Worklist past = NpcWorklist.of(List.of(), context(10, 5, 850.0d), NOW, round);
		assertTrue(of(past, NpcWorklist.Kind.REPRICE).isEmpty());
		assertEquals(5, past.count(NpcWorklist.Kind.PLACE));

		// Under the stop, the same row is pinned and keeps its slot, leaving four for the basket.
		NpcWorklist.Worklist pinned = NpcWorklist.of(List.of(), context(10, 5), NOW, round);
		assertEquals(1, pinned.count(NpcWorklist.Kind.REPRICE));
		assertEquals(4, pinned.count(NpcWorklist.Kind.PLACE));
	}

	/**
	 * Fault 3 in the ADR, as the worklist sees it. Cancelling to reprice deletes the order the price
	 * came from, and the list used to regenerate from what was resting - dropping the row, and the
	 * number the player was walking to the menu to type, at the exact moment it was needed.
	 */
	@Test
	void keepsThePinnedRowAndItsSlotWhileTheOrderIsOffTheBook() {
		NpcRound round = roundOver(List.of(outbid("ITEM_0", 500L)), context(10, 5));

		// Mid-reprice: the cancel has happened and the re-post has not, so nothing is resting.
		NpcWorklist.Worklist worklist = NpcWorklist.of(List.of(), context(10, 5), NOW, round);
		List<NpcWorklist.Task> reprices = of(worklist, NpcWorklist.Kind.REPRICE);

		assertEquals(1, reprices.size());
		assertEquals(700.1d, reprices.getFirst().price(), 1e-9d);

		// And the slot it needs is still reserved: four places, not the five an empty account gets.
		assertEquals(4, worklist.count(NpcWorklist.Kind.PLACE));
		assertEquals(5, NpcWorklist.of(List.of(), context(10, 5), NOW)
				.count(NpcWorklist.Kind.PLACE));
		assertTrue(worklist.pending().stream().noneMatch(task ->
				task.kind() == NpcWorklist.Kind.PLACE && task.itemId().equals("ITEM_0")));
	}

	/**
	 * A row merges an item's orders, and reserves every slot behind them.
	 *
	 * <p>An unstackable product takes 256 units to an order, so a four-order item is one row and four
	 * slots. Reserving one would let the basket spend the other three while the player is mid-reprice.
	 */
	@Test
	void reservesEverySlotAMergedRowNeeds() {
		List<NpcReprice.Order> resting = List.of(
				outbid("ITEM_0", 500L), outbid("ITEM_0", 500L),
				outbid("ITEM_0", 500L), outbid("ITEM_0", 500L));

		NpcRound round = roundOver(resting, context(10, 6));
		NpcWorklist.Worklist worklist = NpcWorklist.of(List.of(), context(10, 6), NOW, round);
		List<NpcWorklist.Task> reprices = of(worklist, NpcWorklist.Kind.REPRICE);

		assertEquals(1, reprices.size());
		assertEquals(2_000L, reprices.getFirst().units());
		assertTrue(reprices.getFirst().reason().contains("4 orders"), reprices.getFirst().reason());

		// Six slots, four held by the row that is mid-reprice.
		assertEquals(2, worklist.count(NpcWorklist.Kind.PLACE));
	}

	/**
	 * Where a slot and the coins are free, the new order goes on before the old one comes off, so the
	 * item is never off the book. Where they are not, the row is pinned through its own cancel.
	 */
	@Test
	void saysPlaceFirstOnlyWhenASlotAndTheCoinsAreFree() {
		List<NpcReprice.Order> resting = List.of(outbid("ITEM_0", 500L));

		// Two candidates, five slots: the basket takes one and leaves three.
		NpcWorklist.Worklist spare = NpcWorklist.of(resting, context(2, 5), NOW,
				roundOver(resting, context(2, 5)));

		assertTrue(of(spare, NpcWorklist.Kind.REPRICE).getFirst().reason()
						.contains("place the new order first"),
				of(spare, NpcWorklist.Kind.REPRICE).getFirst().reason());

		// Two slots, one of them the resting order's: the basket takes the other and none is left.
		NpcWorklist.Worklist full = NpcWorklist.of(resting, context(10, 2), NOW,
				roundOver(resting, context(10, 2)));

		assertTrue(of(full, NpcWorklist.Kind.REPRICE).getFirst().reason().contains("Cancel first"),
				of(full, NpcWorklist.Kind.REPRICE).getFirst().reason());
	}

	/** A trade the book has since killed is cancelled, not re-posted at the price it was frozen at. */
	@Test
	void letsADeadTradeOverruleItsOwnFrozenRow() {
		StrategyContext context = context(10, 5);
		NpcRound round = roundOver(List.of(outbid("ITEM_0", 500L)), context);

		// The same order, now past the window the coins were lent for.
		long expired = NOW - Math.round(NpcContext.DEFAULT_RESTING_HOURS * 3_600_000.0d) - 1L;
		NpcWorklist.Worklist worklist = NpcWorklist.of(
				List.of(new NpcReprice.Order("ITEM_0", "ITEM_0", 600.0d, 500L, 500L, 0L, expired)),
				context, NOW, round);

		assertEquals(1, worklist.count(NpcWorklist.Kind.CANCEL));
		assertEquals(0, worklist.count(NpcWorklist.Kind.REPRICE));
	}

	/** Claims are coins already earned, so they are worked whenever they are true, round or no round. */
	@Test
	void emitsAClaimWhateverTheRoundSays() {
		StrategyContext context = context(10, 5);
		NpcReprice.Order half =
				new NpcReprice.Order("ITEM_0", "ITEM_0", 600.0d, 500L, 250L, 250L, NOW);

		NpcWorklist.Worklist worklist = NpcWorklist.of(List.of(half), context, NOW,
				NpcRound.open(NOW, NpcContext.DEFAULT_CHECK_IN, List.of()));

		assertEquals(1, worklist.count(NpcWorklist.Kind.CLAIM));
		assertEquals(NpcWorklist.Kind.CLAIM, worklist.pending().getFirst().kind());
	}

	/**
	 * An order the round did not freeze is left alone until the next one opens - and still counted,
	 * rather than dropped from the list, so nothing tells the player their book is all on top of it.
	 */
	@Test
	void leavesAnOrderTheRoundDidNotFreezeForTheNextRound() {
		StrategyContext context = context(10, 5);

		// Placed just now, so the dwell rule keeps it out of the round that opens a moment later.
		List<NpcReprice.Order> resting =
				List.of(new NpcReprice.Order("ITEM_0", "ITEM_0", 600.0d, 500L, 500L, 0L, NOW));

		NpcWorklist.Worklist worklist =
				NpcWorklist.of(resting, context, NOW, roundOver(resting, context));

		assertEquals(0, worklist.count(NpcWorklist.Kind.REPRICE));
		assertEquals(1, worklist.outbidWaiting());
		assertEquals(1, worklist.holding());
		assertTrue(worklist.tasks().stream()
				.anyMatch(task -> task.reason().contains("next round will ask")));
	}

	/** Without a round this is what it always was: advice straight off the book, one row per order. */
	@Test
	void staysTheOldWayWhenNoRoundIsOffered() {
		List<NpcReprice.Order> resting = List.of(outbid("ITEM_0", 500L), outbid("ITEM_0", 500L));
		NpcWorklist.Worklist worklist = NpcWorklist.of(resting, context(10, 5), NOW);

		assertEquals(2, worklist.count(NpcWorklist.Kind.REPRICE));
		assertEquals(0, worklist.outbidWaiting());
		assertFalse(worklist.hasRound());
	}

	/** With everything waiting on the next round, the headline may not claim the book is all yours. */
	@Test
	void saysWhatIsWaitingRatherThanClaimingTheBookIsOnTop() {
		StrategyContext context = context(1, 1);
		List<NpcReprice.Order> resting =
				List.of(new NpcReprice.Order("ITEM_0", "ITEM_0", 600.0d, 500L, 500L, 0L, NOW));

		NpcWorklist.Worklist worklist =
				NpcWorklist.of(resting, context, NOW, roundOver(resting, context));

		assertTrue(worklist.pending().isEmpty());
		assertTrue(worklist.headline().contains("1 outbid"), worklist.headline());
		assertTrue(worklist.headline().contains("next round"), worklist.headline());
	}

	/**
	 * The between-rounds line the panel beside the bazaar menu draws: how many, and how long.
	 *
	 * <p>A panel that simply goes quiet over a book that has walked past your orders is
	 * indistinguishable from one that has stopped working, and the clock is the one thing the player
	 * cannot see for themselves.
	 */
	@Test
	void saysHowManyAreWaitingAndHowLongFor() {
		StrategyContext context = context(1, 1);
		List<NpcReprice.Order> resting =
				List.of(new NpcReprice.Order("ITEM_0", "ITEM_0", 600.0d, 500L, 500L, 0L, NOW));

		// Ten minutes into a thirty-minute round, so twenty are left.
		NpcWorklist.Worklist worklist =
				NpcWorklist.of(resting, context, NOW, roundOver(resting, context));
		String note = worklist.waitingNote(NOW + 600_000L);

		assertTrue(note.contains("1 outbid"), note);
		assertTrue(note.contains("20m"), note);
	}

	/** With reprices in hand the useful fact is how long their prices stay the ones to type. */
	@Test
	void saysHowLongTheFrozenPricesAreGoodFor() {
		List<NpcReprice.Order> resting = List.of(outbid("ITEM_0", 500L));
		NpcWorklist.Worklist worklist =
				NpcWorklist.of(resting, context(10, 5), NOW, roundOver(resting, context(10, 5)));
		String note = worklist.roundNote(NOW + 600_000L);

		assertEquals(1, worklist.count(NpcWorklist.Kind.REPRICE));
		assertTrue(note.contains("held for another 20m"), note);
	}

	/** Off the clock there is no due time to quote, so neither note invents one. */
	@Test
	void quotesNoDueTimeWithoutARound() {
		NpcWorklist.Worklist worklist =
				NpcWorklist.of(List.of(outbid("ITEM_0", 500L)), context(10, 5), NOW);

		assertEquals("", worklist.waitingNote(NOW));
		assertEquals("", worklist.roundNote(NOW));
	}

	// Which of an item's resting orders a row's next click is about.

	/** The same order at a stated price, for telling two rows on one item apart. */
	private static NpcReprice.Order restingAt(String id, double unitPrice, long remaining) {
		return new NpcReprice.Order(id, id, unitPrice, remaining, remaining, 0L,
				NOW - NpcContext.DEFAULT_CHECK_IN.toMillis());
	}

	/**
	 * Two orders on one item, and the cheaper one is the one to move.
	 *
	 * <p>Reported live 2026-08-14 on two Bronze Bowl orders: the panel highlighted neither, because
	 * the rule refused to choose between them. Both are outbid and both move to the same price, so
	 * either click is correct and the cheaper one is simply the furthest behind the book.
	 */
	@Test
	void namesTheCheapestOrderThatNeedsTheSameClick() {
		List<NpcReprice.Order> resting =
				List.of(restingAt("ITEM_0", 620.0d, 500L), restingAt("ITEM_0", 600.0d, 500L));
		NpcWorklist.Worklist worklist = NpcWorklist.of(resting, context(10, 5), NOW);

		List<NpcWorklist.Task> reprices = of(worklist, NpcWorklist.Kind.REPRICE);

		assertFalse(reprices.isEmpty());
		assertEquals(600.0d, worklist.restingPriceFor(reprices.getFirst()), 1e-9d);
	}

	// Orders another strategy placed, which the NPC side must not adopt as its own.

	/**
	 * A buy order the player runs under another strategy is reserved but never reviewed.
	 *
	 * <p>The bug this fixes: an NPC-sellable item bought to feed a craft, a combine or the buy leg of
	 * a spread rests a buy order like any other, and the NPC side used to reprice or cancel it. Its
	 * slot still has to be held - slots bind - so the basket cannot plan an NPC order into a slot the
	 * book physically occupies, but no click is offered for the order itself.
	 */
	@Test
	void leavesAnotherStrategysOrderAloneButKeepsItsSlot() {
		StrategyContext context = context(10, 4);
		List<NpcReprice.Order> resting = List.of(outbid("ITEM_0", 500L));

		// Unmarked, the NPC side reviews it and wants to move it back to the top of the book.
		assertEquals(1, NpcWorklist.of(resting, context, NOW).count(NpcWorklist.Kind.REPRICE));

		// Marked as another strategy's: no click of any kind is offered for it.
		NpcWorklist.Worklist worklist = NpcWorklist.of(resting, context, NOW, null,
				Set.of(), Map.of(), Set.of("ITEM_0"));

		assertEquals(0, worklist.count(NpcWorklist.Kind.REPRICE));
		assertEquals(0, worklist.count(NpcWorklist.Kind.CANCEL));
		assertEquals(0, worklist.count(NpcWorklist.Kind.HOLD));
		assertEquals(0, worklist.count(NpcWorklist.Kind.CLAIM));
		assertTrue(worklist.pending().stream().noneMatch(task -> task.itemId().equals("ITEM_0")));

		// But its slot is still held, so the basket fills three of the four rather than four.
		assertEquals(3, worklist.count(NpcWorklist.Kind.PLACE));
	}

	/** Past its window, another strategy's order is still not the NPC side's to cancel. */
	@Test
	void doesNotExpireAnotherStrategysOrder() {
		long expired = NOW - Math.round(NpcContext.DEFAULT_RESTING_HOURS * 3_600_000.0d) - 1L;
		List<NpcReprice.Order> resting =
				List.of(new NpcReprice.Order("ITEM_0", "ITEM_0", 699.0d, 500L, 500L, 0L, expired));

		// Unmarked, a resting-window expiry cancels it.
		assertEquals(1, NpcWorklist.of(resting, context(10, 5), NOW).count(NpcWorklist.Kind.CANCEL));

		// Marked as another strategy's, the window is not the NPC side's to enforce.
		NpcWorklist.Worklist worklist = NpcWorklist.of(resting, context(10, 5), NOW, null,
				Set.of(), Map.of(), Set.of("ITEM_0"));

		assertEquals(0, worklist.count(NpcWorklist.Kind.CANCEL));
	}

	/** A place has no order behind it, so there is nothing for the price to point at. */
	@Test
	void namesNoOrderForARowThatIsNotAboutOne() {
		NpcWorklist.Worklist worklist =
				NpcWorklist.of(List.of(), context(10, 5), NOW);

		List<NpcWorklist.Task> places = of(worklist, NpcWorklist.Kind.PLACE);

		assertFalse(places.isEmpty());
		assertEquals(0.0d, worklist.restingPriceFor(places.getFirst()), 1e-9d);
		assertEquals(0.0d, worklist.restingPriceFor(null), 1e-9d);
	}
}
