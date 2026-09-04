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
import jeff.skyblockflipper.core.model.TimedListing;

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
		/** Scheduled end, epoch millis. Pushed +2 minutes by a late bid (anti-snipe). Timed only. */
		public long end;
		/** Current top bid, or 0 with no bids. Timed only; a BIN never accumulates bids. */
		@SerializedName("highest_bid_amount") public long highestBidAmount;
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

	/**
	 * Active timed (non-BIN) listings only, for the Phase 0b reachability collection
	 * (docs/auction-bidding-plan.md). The complement of {@link #binListings()} within one page: a
	 * listing is BIN or it is timed, never both.
	 *
	 * <p>Unlike {@code binListings()} this keeps the blob, because a timed listing has no name-and-
	 * rarity cheap prune to run first - the collector decodes each one to a signature and then drops
	 * the blob. That is affordable because timed listings are a small fraction of the house (~1.9%
	 * of sales), and the collector narrows further to only those ending soon before it decodes.
	 */
	public List<TimedListing> timedListings() {
		if (auctions == null) {
			return List.of();
		}

		List<TimedListing> out = new ArrayList<>();

		for (Auction auction : auctions) {
			if (auction.bin || auction.uuid == null || auction.itemBytes == null) {
				continue;
			}

			out.add(new TimedListing(
					auction.uuid,
					auction.startingBid,
					auction.highestBidAmount,
					auction.end,
					auction.itemBytes));
		}

		return out;
	}
}
