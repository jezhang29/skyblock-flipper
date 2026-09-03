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

import java.util.Locale;

/**
 * Which Hypixel menu titles mean "you are in the auction house".
 *
 * <p>The counterpart to {@link BazaarMenu}, and deliberately the looser of the two. {@code BazaarMenu}
 * decides whether to draw a green box <b>behind a specific slot</b> a click will land on, so it matches
 * a measured list of titles exactly and refuses everything else - a box on the wrong slot gets clicked.
 * This class decides only whether to draw a <b>passive read-only list</b> beside the menu; it points at
 * no slot and sends no click, so being wrong costs a panel on a screen that did not need one, never a
 * misclick. That lets it recognise the whole auction house with one rule instead of a title census.
 *
 * <p><b>The rule is: the title contains "auction".</b> Every auction-house title captured from the
 * user's live client on 2026-09-03 carries the word - {@code Co-op Auction House}, {@code Auctions
 * Browser}, {@code Manage Auctions}, {@code Create BIN Auction}, {@code Auction Duration} - and so does
 * the one screen the capture missed, a per-player or per-search results grid titled
 * {@code <name>'s Auctions}. Matching the substring catches that un-captured grid for free, which is
 * exactly why the panel can be told to show "anywhere in the auction house" without first photographing
 * every screen. No bazaar title contains the word, so the boundary against {@code BazaarMenu}'s screens
 * ({@code Bazaar ➜ Mining}, {@code Co-op Bazaar Orders}) is clean.
 */
public final class AuctionMenu {
	/** The one word every auction-house title shares, lower-cased for a case-insensitive contains. */
	private static final String AUCTION = "auction";

	private AuctionMenu() {
	}

	/** Whether this title belongs to any auction-house screen. */
	public static boolean isAuction(String title) {
		if (title == null || title.isBlank()) {
			return false;
		}

		return title.toLowerCase(Locale.ROOT).contains(AUCTION);
	}
}
