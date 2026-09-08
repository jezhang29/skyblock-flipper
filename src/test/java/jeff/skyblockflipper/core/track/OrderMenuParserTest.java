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
package jeff.skyblockflipper.core.track;

import jeff.skyblockflipper.core.evidence.NumericEvidence;

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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The orders-menu parser against the capture session it was written from.
 *
 * <p>Same rule as {@link ChatParserTest}: every line matched here came off Hypixel, so a wording
 * change breaks a test instead of quietly producing an order with zero units in it.
 */
class OrderMenuParserTest {
	/** The account that ran the capture. The co-op menu also lists LunarV4's orders. */
	private static final String ME = "Test_Player";

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
			List<ObservedOrderRow> parsed = OrderMenuParser.parseObservation(menu)
					.map(OrderMenuObservation::rows).orElse(List.of());
			orders += parsed.size();
			mine += parsed.stream().filter(row -> row.owner().equals(ME)).count();
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
		List<ObservedOrderRow> all = parseFirstContaining("GIANT_FRAGMENT_DIAMOND");

		assertTrue(all.stream().anyMatch(o -> o.owner().equals("LunarV4")));
		OrderMenuObservation observation = new OrderMenuObservation(0L, "Co-op Bazaar Orders",
				MenuCoverage.COMPLETE, all, OrderMenuParser.PARSER_VERSION);
		assertTrue(observation.ownedBy(ME).stream().allMatch(o -> o.owner().equals(ME)));
		assertTrue(observation.ownedBy("").isEmpty());
	}

	@Test
	void keepsAnOrderThatCarriesNoItemId() {
		// Enchantment-book orders send no custom data at all, so there is no id to read. Dropping
		// them would lose a real position; the name is what is left to resolve them by.
		ObservedOrderRow book = allObservedRows().stream()
				.filter(o -> o.displayName().equals("Ultimate Wise I"))
				.findFirst()
				.orElseThrow();

		assertEquals("", book.itemId());
		assertEquals(1L, book.total());
	}

	@Test
	void abbreviatedAndRoundedNumeratorRemainsApproximate() {
		ObservedOrderRow row = observed(MenuCoverage.COMPLETE, "SELL Null Sphere", "NULL_SPHERE",
				"Offer amount: 1,091x", "Filled: 1.1k/1.1k 100%!",
				"Price per unit: 20.5 coins", "By: " + ME);

		NumericEvidence.Approximate<?> approximate = assertInstanceOf(
				NumericEvidence.Approximate.class, row.filled());
		assertEquals("1.1k", approximate.rawText());
		assertFalse(row.supportsExactQuantityActions());
		assertTrue(OrderMenuParser.parse(menu(MenuCoverage.COMPLETE, "SELL Null Sphere",
				"NULL_SPHERE", "Offer amount: 1,091x", "Filled: 1.1k/1.1k 100%!",
				"Price per unit: 20.5 coins", "By: " + ME)).isEmpty());
	}

	@Test
	void exactClaimCanTightenWithoutDefiningAnAbbreviationRule() {
		ObservedOrderRow row = observed(MenuCoverage.COMPLETE, "BUY Enchanted Nether Wart",
				"ENCHANTED_NETHER_STALK", "Order amount: 1,024x", "Filled: 1k/1k 100%!",
				"You have 1,024 items to claim!", "By: " + ME);

		NumericEvidence.Exact<?> exact = assertInstanceOf(NumericEvidence.Exact.class,
				row.filled());
		assertEquals(1_024L, exact.value());
		assertTrue(row.supportsExactQuantityActions());
	}

	@Test
	void sellClaimItemsRemainIndependentRatherThanConstrainingFill() {
		ObservedOrderRow row = observed(MenuCoverage.COMPLETE, "SELL Slimeball", "SLIME_BALL",
				"Offer amount: 10x", "Filled: ???", "You have 10 items to claim!",
				"By: " + ME);

		assertInstanceOf(NumericEvidence.Unknown.class, row.filled());
		assertEquals(10L, row.claimItems().orElseThrow());
		assertFalse(row.supportsExactQuantityActions());
	}

	@Test
	void claimOnlyAndMalformedRowsKeepIndependentEvidence() {
		ObservedOrderRow claimOnly = observed(MenuCoverage.COMPLETE, "BUY Slimeball", "SLIME_BALL",
				"Order amount: 10x", "You have 3 items to claim!", "By: " + ME);
		ObservedOrderRow malformed = observed(MenuCoverage.COMPLETE, "SELL Slimeball", "SLIME_BALL",
				"Offer amount: 10x", "Filled: ???", "You have 20.5 coins to claim!",
				"By: " + ME);

		assertInstanceOf(NumericEvidence.Bounded.class, claimOnly.filled());
		assertEquals(3L, claimOnly.claimItems().orElseThrow());
		assertInstanceOf(NumericEvidence.Unknown.class, malformed.filled());
		assertEquals("20.5", malformed.claimCoins().orElseThrow().toPlainString());
	}

	@Test
	void missingPricePartialCoverageAndDuplicateRowsStayExplicit() {
		CapturedSlot first = new CapturedSlot(11, "BUY Slimeball",
				List.of("Order amount: 10x", "Filled: 0/10 (0.0%)", "By: " + ME),
				"SLIME_BALL", 1, "");
		CapturedSlot second = new CapturedSlot(12, "BUY Slimeball",
				List.of("Order amount: 10x", "Filled: 0/10 (0.0%)", "By: LunarV4"),
				"SLIME_BALL", 1, "");
		OrderMenuObservation observation = OrderMenuParser.parseObservation(new CapturedMenu(1L,
				"Co-op Bazaar Orders", List.of(first, second), MenuCoverage.PARTIAL)).orElseThrow();

		assertEquals(MenuCoverage.PARTIAL, observation.coverage());
		assertEquals(2, observation.rows().size());
		assertTrue(observation.rows().getFirst().unitPrice().isEmpty());
		assertEquals(1, observation.ownedBy(ME).size());
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

	private static List<ObservedOrderRow> parseFirstContaining(String itemId) {
		for (CapturedMenu menu : MENUS) {
			List<ObservedOrderRow> orders = OrderMenuParser.parseObservation(menu)
					.map(OrderMenuObservation::rows).orElse(List.of());

			if (orders.stream().anyMatch(o -> o.itemId().equals(itemId))) {
				return orders;
			}
		}

		throw new IllegalStateException(itemId);
	}

	private static List<OrderSnapshot> allOrders() {
		return MENUS.stream().flatMap(m -> OrderMenuParser.parse(m).stream()).toList();
	}

	private static List<ObservedOrderRow> allObservedRows() {
		return MENUS.stream().flatMap(menu -> OrderMenuParser.parseObservation(menu).stream())
				.flatMap(observation -> observation.rows().stream()).toList();
	}

	private static ObservedOrderRow observed(MenuCoverage coverage, String name, String itemId,
			String... lore) {
		return OrderMenuParser.parseObservation(menu(coverage, name, itemId, lore)).orElseThrow()
				.rows().getFirst();
	}

	private static CapturedMenu menu(MenuCoverage coverage, String name, String itemId,
			String... lore) {
		return new CapturedMenu(1L, "Co-op Bazaar Orders",
				List.of(new CapturedSlot(11, name, List.of(lore), itemId, 1, "")), coverage);
	}

}
