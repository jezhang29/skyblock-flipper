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

	@Test
	void readsTheItemOutOfAPathTitle() {
		// Photographed live 2026-08-13: a product page is titled with the sub-category it was reached
		// through and then the item. Matching the bare name left the product page with no panel.
		List<String> basket = List.of("Transmission Tuner", "Jungle Heart");

		assertEquals("Transmission Tuner",
				BazaarMenu.productPageFor("Item Upgrades ➜ Transmission Tuner", basket));
		assertEquals("Jungle Heart", BazaarMenu.productPageFor("Farming ➜ Jungle Heart", basket));
		assertEquals("", BazaarMenu.productPageFor("Item Upgrades ➜ Ender Monocle", basket));
	}

	@Test
	void acceptsAPrefixOnlyFromATitleHypixelCutOff() {
		// A long item's page can only ever be matched on the part of the title that survived the cut.
		String cut = "Item Upgrades ➜ Transmission Tun";

		assertEquals(32, cut.length());
		assertEquals("Transmission Tuner",
				BazaarMenu.productPageFor(cut, List.of("Transmission Tuner")));

		// With room left in the title there was no truncation, so a prefix is a different item.
		// "Enchanted Melon" is ENCHANTED_MELON_BLOCK and "Enchanted Melon Slice" is ENCHANTED_MELON.
		assertEquals("", BazaarMenu.productPageFor("Farming ➜ Enchanted Melon",
				List.of("Enchanted Melon Slice")));
	}

	@Test
	void acceptsAPrefixFromAThirtyOneCharacterTitleToo() {
		// The cut is on rendered width, not on a character count: this one and
		// Bazaar ➜ "Enchanted Cooked Mutt were both cut at 31, while
		// Bazaar ➜ "Enchanted Nether Wart" survived whole at 32. Photographed live 2026-08-14 with
		// no highlight on it, because the rule required 32.
		String cut = "Revenant Horror ➜ Revenant Cata";

		assertEquals(31, cut.length());
		assertEquals("Revenant Catalyst",
				BazaarMenu.productPageFor(cut, List.of("Revenant Catalyst", "Jungle Heart")));
	}

	@Test
	void prefersTheItemNamedInFullOverOneItIsThePrefixOf() {
		// Both are in the basket and the title names the shorter outright. Counting it as a match for
		// each would name neither, and the page in front of the player is the one it spells.
		String title = "Farming ➜ Enchanted Melon Slice";

		assertEquals(31, title.length());
		assertEquals("Enchanted Melon Slice", BazaarMenu.productPageFor(title,
				List.of("Enchanted Melon Slice", "Enchanted Melon Slice Cake")));
	}

	@Test
	void namesNothingWhenTwoBasketItemsShareTheSurvivingPrefix() {
		String cut = "Enchantments ➜ Feather Falling V";

		assertEquals(32, cut.length());
		assertEquals("", BazaarMenu.productPageFor(cut,
				List.of("Feather Falling VI", "Feather Falling VII")));
	}
}
