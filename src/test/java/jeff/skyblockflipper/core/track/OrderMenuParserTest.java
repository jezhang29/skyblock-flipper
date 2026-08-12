package jeff.skyblockflipper.core.track;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The orders-menu parser against the capture session it was written from.
 *
 * <p>Same rule as {@link ChatParserTest}: every line matched here came off Hypixel, so a wording
 * change breaks a test instead of quietly producing an order with zero units in it.
 */
class OrderMenuParserTest {
	/** The account that ran the capture. The co-op menu also lists LunarV4's orders. */
	private static final String ME = "Bee__Bot";

	private static final List<CapturedMenu> MENUS = new ArrayList<>();

	@BeforeAll
	static void loadCapture() throws IOException {
		try (InputStream in = OrderMenuParserTest.class.getResourceAsStream("/trade-capture-sample.jsonl")) {
			MENUS.addAll(CaptureSession.read(new InputStreamReader(in, StandardCharsets.UTF_8)).menus());
		}
	}

	@Test
	void readsEveryOrderTheSessionSnapshotted() {
		int orders = 0;
		int mine = 0;

		for (CapturedMenu menu : MENUS) {
			List<OrderSnapshot> parsed = OrderMenuParser.parse(menu);
			orders += parsed.size();
			mine += OrderMenuParser.ownedBy(parsed, ME).size();
		}

		// 17 snapshots of the orders menu, taken as orders were placed, filled and cancelled.
		assertEquals(107, orders);
		assertEquals(56, mine);
	}

	@Test
	void readsAPartialFillChatNeverAnnounced() {
		// The 1,344x offer that stopped at 903. There is no "was filled!" line for this anywhere in
		// the session, so without the menu the only evidence is the claim line after you notice it.
		OrderSnapshot order = onlyPartial();

		assertEquals(TradeEvent.Side.SELL, order.side());
		assertEquals("SLIME_BALL", order.itemId());
		assertEquals("Slimeball", order.displayName());
		assertEquals(903L, order.filled());
		assertEquals(34_107.0d, order.claimCoins());
		assertTrue(order.isPartial());
		assertTrue(order.hasSomethingToClaim());
	}

	@Test
	void takesTheTotalFromTheAmountLine() {
		// The lore reads "Filled: 903/1.3k (67.2%)". Reading the total out of that denominator gives
		// 1,300 and a fill fraction that is wrong in the direction that looks fine.
		assertEquals(1_344L, onlyPartial().total());
		assertEquals(38.2d, onlyPartial().unitPrice());
	}

	@Test
	void readsABuyOrderWaitingOnItems() {
		// A filled buy order holds items, not coins, and both claim lines share a sentence shape.
		OrderSnapshot order = find("ENCHANTED_ENDSTONE", TradeEvent.Side.BUY);

		assertEquals(8L, order.total());
		assertEquals(8L, order.filled());
		assertEquals(8L, order.claimItems());
		assertEquals(0.0d, order.claimCoins());
		assertFalse(order.isPartial());
		assertTrue(order.hasSomethingToClaim());
	}

	@Test
	void keepsCoopMatesOrdersOutOfYours() {
		// The co-op menu shows every member's orders in the same rows with the same lore. Only the
		// "By:" line separates them, and taking the whole menu as yours would book LunarV4's
		// 811,618 coin sell as your position.
		List<OrderSnapshot> all = parseFirstContaining("GIANT_FRAGMENT_DIAMOND");

		assertTrue(all.stream().anyMatch(o -> o.owner().equals("LunarV4")));
		assertTrue(OrderMenuParser.ownedBy(all, ME).stream().allMatch(o -> o.owner().equals(ME)));
		assertTrue(OrderMenuParser.ownedBy(all, "").isEmpty());
	}

	@Test
	void keepsAnOrderThatCarriesNoItemId() {
		// Enchantment-book orders send no custom data at all, so there is no id to read. Dropping
		// them would lose a real position; the name is what is left to resolve them by.
		OrderSnapshot book = allOrders().stream()
				.filter(o -> o.displayName().equals("Ultimate Wise I"))
				.findFirst()
				.orElseThrow();

		assertEquals("", book.itemId());
		assertEquals(1L, book.total());
	}

	@Test
	void skipsTheFurnitureAndTheOtherMenus() {
		// "Go Back" and "Claim All Coins" sit in the same menu and are not orders.
		assertTrue(allOrders().stream().noneMatch(o -> o.displayName().contains("Claim All")));

		for (CapturedMenu menu : MENUS) {
			if (!menu.title().equals("Co-op Bazaar Orders")) {
				assertTrue(OrderMenuParser.parse(menu).isEmpty(), menu.title());
			}
		}

		assertFalse(OrderMenuParser.isOrdersMenu(null));
	}

	@Test
	void readsAClaimTotalThatCameOutFractional() {
		// Five units at 1,619.2 pays 8,004.9, and the menu prints the decimal. An integer-only
		// pattern reads that order as having nothing to claim, which is the same wrong answer as
		// the order having been claimed already.
		OrderSnapshot order = find("ENCHANTED_SLIME_BALL", TradeEvent.Side.SELL);

		assertEquals(8_004.9d, order.claimCoins());
		assertTrue(order.hasSomethingToClaim());
	}

	@Test
	void separatesAFillFromAFillNobodyCollected() {
		// The 1,344x offer appears twice at the same 903/1.3k: once with "You have 34,107 coins to
		// claim!" and once, after the claim, without it. Filled: does not move between them, so the
		// claim line is the whole difference and reading only Filled: reports 903 units waiting on
		// an order that has already paid out.
		List<OrderSnapshot> partials = allOrders().stream()
				.filter(o -> o.isPartial() && o.total() == 1_344L)
				.toList();

		assertTrue(partials.stream().anyMatch(o -> o.uncollected().isEmpty()));
		assertTrue(partials.stream().anyMatch(o -> o.uncollected().orElse(-1L) == 0L));
	}

	@Test
	void countsWhatABuyOrderIsHoldingInUnits() {
		// A buy order names its items, so what is uncollected is stated rather than derived.
		assertEquals(8L, find("ENCHANTED_ENDSTONE", TradeEvent.Side.BUY).uncollected().orElseThrow());
	}

	private static OrderSnapshot onlyPartial() {
		return allOrders().stream().filter(OrderSnapshot::isPartial).findFirst().orElseThrow();
	}

	private static OrderSnapshot find(String itemId, TradeEvent.Side side) {
		return allOrders().stream()
				.filter(o -> o.itemId().equals(itemId) && o.side() == side)
				.findFirst()
				.orElseThrow();
	}

	private static List<OrderSnapshot> parseFirstContaining(String itemId) {
		for (CapturedMenu menu : MENUS) {
			List<OrderSnapshot> orders = OrderMenuParser.parse(menu);

			if (orders.stream().anyMatch(o -> o.itemId().equals(itemId))) {
				return orders;
			}
		}

		throw new IllegalStateException(itemId);
	}

	private static List<OrderSnapshot> allOrders() {
		return MENUS.stream().flatMap(m -> OrderMenuParser.parse(m).stream()).toList();
	}

}
