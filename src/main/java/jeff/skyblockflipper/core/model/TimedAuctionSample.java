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
package jeff.skyblockflipper.core.model;

import com.google.gson.annotations.SerializedName;

/**
 * One observation of one active timed auction, as a single sweep saw it - the unit of the Phase 0b
 * reachability tape (docs/auction-bidding-plan.md).
 *
 * <p>A timed auction that is sampled every minute over its final hours leaves a row here per sweep,
 * so the reachability measurement can replay the trajectory the top bid traced towards {@code end}
 * and ask the one thing the ended tape cannot answer: did the winning bid rise off the starting bid
 * before the clock ran out, or was the auction winnable by simple presence at its opening price.
 *
 * <p>Deliberately tiny. The blob is decoded to {@link #signature} once and dropped - unlike the
 * sales tape, this never keeps {@code item_bytes}, because the same listing is written many times
 * and a stack of blobs would dwarf the sales tape. The JSON keys are one or two letters for the
 * same reason: at hundreds of thousands of rows a day the field names cost more than the values.
 *
 * @param uuid             the auction id; joins to {@code auctions_ended}'s {@code auction_id}
 * @param signature        the decoded {@link DecodedItem#signature()}, the valuation model's key
 * @param count            stack size, so the whole-listing bid can be compared per unit against a
 *                         per-unit BIN median (a stack sale says what several of something cost)
 * @param end              the listing's scheduled end at this sample, epoch millis (moves on a bid)
 * @param startingBid      the opening bid, the price of an uncontested lead
 * @param highestBidAmount the top bid at this sample, or 0 with no bids
 * @param sampledAt        when this sweep observed the listing, epoch millis
 */
public record TimedAuctionSample(
		@SerializedName("u") String uuid,
		@SerializedName("sig") String signature,
		@SerializedName("c") int count,
		@SerializedName("e") long end,
		@SerializedName("sb") long startingBid,
		@SerializedName("hb") long highestBidAmount,
		@SerializedName("t") long sampledAt
) {
	/**
	 * Whether the top bid has moved off the opening bid. An auction still at its starting bid (or
	 * with no bids at all) is winnable at the starting bid by whoever is present when it ends; once
	 * a rival bids, the anti-snipe timer ratchets the price towards fair value. This is the cheap
	 * contested-or-not proxy the plan settles on, computed without the bid array.
	 */
	public boolean contested() {
		return highestBidAmount > startingBid;
	}
}
