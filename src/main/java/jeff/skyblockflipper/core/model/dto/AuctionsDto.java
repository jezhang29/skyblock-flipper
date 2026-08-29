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
package jeff.skyblockflipper.core.model.dto;

import com.google.gson.annotations.SerializedName;

import jeff.skyblockflipper.core.item.Rarity;
import jeff.skyblockflipper.core.model.ActiveListing;

import java.util.ArrayList;
import java.util.List;

/**
 * Wire format of one page of {@code /v2/skyblock/auctions}.
 *
 * <p>Kept deliberately thin. A page carries a megabyte and a half of fields nobody here needs -
 * lore, bidder lists, coop members - and Gson only materialises what is declared, so leaving them
 * out is the cheapest possible filter.
 */
public final class AuctionsDto {
	public boolean success;
	public int page;
	public int totalPages;
	public long lastUpdated;
	public List<Auction> auctions;

	public static final class Auction {
		public String uuid;
		@SerializedName("item_name") public String itemName;
		public String tier;
		/** BIN price. A buy-it-now listing never accumulates bids, so this is the whole price. */
		@SerializedName("starting_bid") public long startingBid;
		public boolean bin;
		@SerializedName("item_bytes") public String itemBytes;
	}

	/** Buy-it-now listings only; a running bid auction has no price to compare against yet. */
	public List<ActiveListing> binListings() {
		if (auctions == null) {
			return List.of();
		}

		List<ActiveListing> out = new ArrayList<>(auctions.size());

		for (Auction auction : auctions) {
			if (!auction.bin || auction.itemName == null || auction.itemBytes == null) {
				continue;
			}

			out.add(new ActiveListing(
					auction.uuid,
					auction.itemName,
					Rarity.fromName(auction.tier),
					auction.startingBid,
					auction.itemBytes));
		}

		return out;
	}
}
