package jeff.skyblockflipper.core.track;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every title here is one Hypixel actually sent, taken from the 1,150 menu records in the capture
 * file on 2026-08-09. Nothing in this test is invented wording.
 */
class BazaarMenuTest {
	@Test
	void recognisesTheBrowsingMenus() {
		assertTrue(BazaarMenu.isBazaar("Bazaar"));
		assertTrue(BazaarMenu.isBazaar("Bazaar ➜ Oddities"));
		assertTrue(BazaarMenu.isBazaar("Bazaar ➜ Woods & Fishes"));

		// A search leaves the query in the title, quoted and sometimes truncated mid-word.
		assertTrue(BazaarMenu.isBazaar("Bazaar ➜ \"feather falli\""));
		assertTrue(BazaarMenu.isBazaar("Bazaar ➜ \"Enchanted Cooked Mutt"));
	}

	@Test
	void recognisesTheOrderMenus() {
		assertTrue(BazaarMenu.isBazaar("Co-op Bazaar Orders"));
		assertTrue(BazaarMenu.isBazaar("Order options"));
		assertTrue(BazaarMenu.isBazaar("Confirm Buy Order"));
		assertTrue(BazaarMenu.isBazaar("Confirm Sell Offer"));
	}

	@Test
	void leavesTheAuctionHouseAlone() {
		// The capture filter keeps these too, because it keeps anything trade-shaped. This one must
		// not: the basket is a bazaar plan and has nothing to say over an auction menu.
		assertFalse(BazaarMenu.isBazaar("Auctions Browser"));
		assertFalse(BazaarMenu.isBazaar("Co-op Auction House"));
		assertFalse(BazaarMenu.isBazaar("Create BIN Auction"));
		assertFalse(BazaarMenu.isBazaar("BIN Auction View"));
		assertFalse(BazaarMenu.isBazaar("Manage Auctions"));
		assertFalse(BazaarMenu.isBazaar("Auction Duration"));
		assertFalse(BazaarMenu.isBazaar("Confirm BIN Auction"));
		assertFalse(BazaarMenu.isBazaar("FlamedBunny21's Auctions"));
	}

	@Test
	void saysNothingAboutAnEmptyTitle() {
		assertFalse(BazaarMenu.isBazaar(null));
		assertFalse(BazaarMenu.isBazaar("   "));
	}

	@Test
	void aProductPageIsOnlyRecognisedByWhatIsInTheBasket() {
		List<String> basket = List.of("Jungle Heart", "Clipped Wings");

		// The name is returned rather than a yes, because the panel highlights that row with it.
		assertEquals("Jungle Heart", BazaarMenu.productPageFor("Jungle Heart", basket));
		assertEquals("Clipped Wings", BazaarMenu.productPageFor("  Clipped Wings  ", basket));

		// A chest somebody renamed after an item they are not being told to buy stays a chest.
		assertEquals("", BazaarMenu.productPageFor("Enchanted Melon", basket));
		assertEquals("", BazaarMenu.productPageFor("Jungle Heart", List.of()));
		assertEquals("", BazaarMenu.productPageFor(null, basket));
	}
}
