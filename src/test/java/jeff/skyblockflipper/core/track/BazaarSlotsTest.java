package jeff.skyblockflipper.core.track;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Against {@code bazaar-menus.jsonl}: nine menus trimmed out of the user's own capture, one of each
 * shape the mod has to find a slot in. Nothing here is invented - every index asserted was recorded
 * off a live bazaar on 2026-08-09.
 */
class BazaarSlotsTest {
	private static List<CapturedMenu> menus;

	@BeforeAll
	static void load() throws IOException {
		try (InputStream stream = BazaarSlotsTest.class.getResourceAsStream("/bazaar-menus.jsonl");
				Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
			menus = CaptureSession.read(reader).menus();
		}
	}

	private static CapturedMenu menu(String title, int size) {
		return menus.stream()
				.filter(m -> m.title().equals(title) && BazaarSlots.size(m) == size)
				.findFirst()
				.orElseThrow(() -> new AssertionError("no fixture for " + title + " of " + size));
	}

	@Test
	void readsTheSizeOfEveryMenuFromItsHighestSlot() {
		// A capture holds only filled slots, so this is inference - and it is exact here because
		// every bazaar menu fills its last row.
		assertEquals(54, BazaarSlots.size(menu("Bazaar ➜ Mining", 54)));
		assertEquals(36, BazaarSlots.size(menu("Confirm Buy Order", 36)));
	}

	@Test
	void namesTheScreenFromItsTitle() {
		assertEquals(BazaarSlots.Screen.BROWSE, BazaarSlots.screenOf(menu("Bazaar ➜ Mining", 54)));
		assertEquals(BazaarSlots.Screen.BROWSE, BazaarSlots.screenOf(menu("Bazaar ➜ \"feath\"", 54)));
		assertEquals(BazaarSlots.Screen.ORDERS, BazaarSlots.screenOf(menu("Co-op Bazaar Orders", 45)));
		assertEquals(BazaarSlots.Screen.ORDER_OPTIONS, BazaarSlots.screenOf(menu("Order options", 36)));
		assertEquals(BazaarSlots.Screen.CONFIRM_BUY, BazaarSlots.screenOf(menu("Confirm Buy Order", 36)));
		assertEquals(BazaarSlots.Screen.CONFIRM_SELL,
				BazaarSlots.screenOf(menu("Confirm Sell Offer", 36)));
	}

	@Test
	void findsTheOrdersMenuButtonsAtEverySizeItGrowsTo() {
		// The whole reason buttons are anchored to the end. One button, three indices, one rule.
		assertEquals(OptionalInt.of(32), BazaarSlots.CLAIM_ALL_COINS.in(menu("Co-op Bazaar Orders", 36)));
		assertEquals(OptionalInt.of(41), BazaarSlots.CLAIM_ALL_COINS.in(menu("Co-op Bazaar Orders", 45)));
		assertEquals(OptionalInt.of(50), BazaarSlots.CLAIM_ALL_COINS.in(menu("Co-op Bazaar Orders", 54)));
	}

	@Test
	void findsTheBrowseButtons() {
		for (String title : List.of("Bazaar ➜ Mining", "Bazaar ➜ Oddities", "Bazaar ➜ \"feath\"")) {
			CapturedMenu browse = menu(title, 54);

			assertEquals(OptionalInt.of(45), BazaarSlots.SEARCH.in(browse), title);
			assertEquals(OptionalInt.of(50), BazaarSlots.MANAGE_ORDERS.in(browse), title);
		}
	}

	@Test
	void findsNoProductOnACategoryPage() {
		// Browsing is three levels deep - Mining, then Iron, then the products - and the middle
		// level's tiles say "Click to view products!". Searching by name is the shorter path, which
		// is why the Search button is the one the plan points at.
		CapturedMenu mining = menu("Bazaar ➜ Mining", 54);

		assertEquals(OptionalInt.empty(), BazaarSlots.productTile(mining, "", "Iron"));
	}

	@Test
	void findsTheConfirmButtonWithoutAnsweringToTheCancelBesideIt() {
		CapturedMenu buy = menu("Confirm Buy Order", 36);
		CapturedMenu sell = menu("Confirm Sell Offer", 36);

		assertEquals(OptionalInt.of(13), BazaarSlots.CONFIRM_BUY_ORDER.in(buy));
		assertEquals(OptionalInt.of(13), BazaarSlots.CONFIRM_SELL_OFFER.in(sell));

		// "Cancel Buy Order" is at 31 on that same screen and contains the confirm button's whole
		// name. Matching on a substring would send the click that abandons the order.
		assertEquals(OptionalInt.empty(), BazaarSlots.CONFIRM_SELL_OFFER.in(buy));
		assertEquals(OptionalInt.empty(), BazaarSlots.CONFIRM_BUY_ORDER.in(sell));
	}

	@Test
	void findsCancelOrderWhereverThePartFillPutIt() {
		List<CapturedMenu> options = menus.stream()
				.filter(m -> m.title().equals("Order options")).toList();

		assertEquals(2, options.size());

		// 13 on a plain order and 11 on a part-filled one, which is why this button has no anchor.
		List<Integer> found = options.stream()
				.map(m -> BazaarSlots.CANCEL_ORDER.in(m).orElse(-1)).sorted().toList();

		assertEquals(List.of(11, 13), found);
	}

	@Test
	void findsAProductTileByHypixelsOwnItemId() {
		CapturedMenu search = menu("Bazaar ➜ \"feath\"", 54);

		assertEquals(OptionalInt.of(11), BazaarSlots.productTile(search, "BRAIDED_GRIFFIN_FEATHER", ""));
		assertEquals(OptionalInt.of(13), BazaarSlots.productTile(search, "FEATHER", ""));
		assertEquals(OptionalInt.of(25), BazaarSlots.productTile(search, "RAINBOW_FEATHER", ""));
	}

	@Test
	void findsAProductTileWithNoItemIdByName() {
		// 11 of the 133 product tiles in the capture carry no custom data at all - enchanted books
		// and shards - so the name is the only handle on them.
		CapturedMenu search = menu("Bazaar ➜ \"feath\"", 54);

		assertEquals(OptionalInt.of(12), BazaarSlots.productTile(search, "", "Enchanted Feather"));
		assertEquals(OptionalInt.of(12),
				BazaarSlots.productTile(search, "NOT_A_PRODUCT", "Enchanted Feather"));
	}

	@Test
	void willNotMistakeACategoryIconForTheProductItShares() {
		// The Oddities page shows 11 tiles whose stack is a real bazaar product but whose click opens
		// a sub-category: "Modifiers" is a RECOMBOBULATOR_3000, and Recombobulator 3000 is also a
		// product you can flip. Only a tile saying "Click to view details!" opens a product page.
		CapturedMenu category = menu("Bazaar ➜ Oddities", 54);

		assertTrue(category.slots().stream()
				.anyMatch(s -> !s.itemId().isEmpty() && !s.lore().contains("Click to view details!")),
				"fixture should contain a category tile carrying a product id");

		for (CapturedSlot slot : category.slots()) {
			if (!slot.itemId().isEmpty() && !slot.lore().contains("Click to view details!")) {
				assertEquals(OptionalInt.empty(),
						BazaarSlots.productTile(category, slot.itemId(), ""),
						"category icon " + slot.itemId() + " answered as a product tile");
			}
		}
	}

	@Test
	void pointsAtNothingOnAScreenItDoesNotKnow() {
		CapturedMenu orders = menu("Co-op Bazaar Orders", 45);

		assertEquals(BazaarSlots.Screen.UNKNOWN,
				BazaarSlots.screenOf(new CapturedMenu(0L, "Enchanted Melon", List.of())));
		assertEquals(OptionalInt.empty(), BazaarSlots.productTile(orders, "FEATHER", "Feather"));
		assertEquals(OptionalInt.empty(), BazaarSlots.SEARCH.in(orders));
		assertFalse(BazaarSlots.CANCEL_ORDER.in(orders).isPresent());
	}

	@Test
	void tellsTwoOrdersOnOneItemApartByTheirPrice() {
		// The measured ambiguity: two Diamante's Handle offers resting 13.3 coins apart.
		CapturedMenu orders = menu("Co-op Bazaar Orders", 36);

		assertEquals(OptionalInt.of(11), OrderMenuParser.slotOf(orders, TradeEvent.Side.SELL,
				"Diamante's Handle", 811_618.4d));
		assertEquals(OptionalInt.of(12), OrderMenuParser.slotOf(orders, TradeEvent.Side.SELL,
				"Diamante's Handle", 811_605.1d));

		// A price that matches neither is not rounded to the nearer one.
		assertEquals(OptionalInt.empty(), OrderMenuParser.slotOf(orders, TradeEvent.Side.SELL,
				"Diamante's Handle", 811_612.0d));

		// Right item, wrong side.
		assertEquals(OptionalInt.empty(), OrderMenuParser.slotOf(orders, TradeEvent.Side.BUY,
				"Diamante's Handle", 811_618.4d));
	}
}
