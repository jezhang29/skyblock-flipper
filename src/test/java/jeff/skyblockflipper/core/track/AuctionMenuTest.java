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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The five titles asserted true were captured from the user's live client on 2026-09-03 (the
 * {@code /flip menu} lines in {@code latest.log}); the sixth, a results grid, is the one screen the
 * capture missed and is included because the substring rule is meant to catch it unseen. Nothing here
 * is invented wording beyond that named grid.
 */
class AuctionMenuTest {
	@Test
	void recognisesEveryCapturedAuctionMenu() {
		assertTrue(AuctionMenu.isAuction("Co-op Auction House"));
		assertTrue(AuctionMenu.isAuction("Auctions Browser"));
		assertTrue(AuctionMenu.isAuction("Manage Auctions"));
		assertTrue(AuctionMenu.isAuction("Create BIN Auction"));
		assertTrue(AuctionMenu.isAuction("Auction Duration"));
	}

	@Test
	void recognisesTheUncapturedResultsGrid() {
		// Never photographed - a sign is not a container, so the search that opens this grid was not in
		// the capture - but its title carries the word like every other auction screen, which is the
		// whole reason the loose rule can be told to show "anywhere in the auction house".
		assertTrue(AuctionMenu.isAuction("FlamedBunny21's Auctions"));
	}

	@Test
	void leavesTheBazaarAlone() {
		// The boundary against BazaarMenu's own screens. No bazaar title contains "auction", so the two
		// recognisers never both fire on one menu.
		assertFalse(AuctionMenu.isAuction("Bazaar ➜ Mining"));
		assertFalse(AuctionMenu.isAuction("Co-op Bazaar Orders"));
		assertFalse(AuctionMenu.isAuction("Confirm Buy Order"));
	}

	@Test
	void saysNothingAboutAnEmptyTitle() {
		assertFalse(AuctionMenu.isAuction(null));
		assertFalse(AuctionMenu.isAuction("   "));
	}
}
