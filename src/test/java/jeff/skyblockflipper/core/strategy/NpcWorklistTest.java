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
		Map<String, BazaarProduct> products = new LinkedHashMap<>();
		Map<String, ItemCatalog.Entry> items = new LinkedHashMap<>();

		for (int i = 0; i < count; i++) {
			String id = "ITEM_" + i;
			products.put(id, product(id, 700.0d - i));
			items.put(id, new ItemCatalog.Entry(id, id, NPC_PRICE, false, List.of()));
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
}
