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
 * One completed auction sale - a realized transaction, not a quote.
 *
 * <p>This is the ground truth the valuation model trains on. Active listings are contaminated by
 * exactly the mispricings we are hunting, so fitting to them teaches the model to agree with the
 * mistake.
 *
 * <p>{@code itemBytes} is kept as the raw base64 blob rather than decoded on ingest. Decoding is
 * the expensive part, the feature set we want to extract will change as the model improves, and
 * a stored blob can be re-parsed later. A stored decode cannot be un-decoded.
 *
 * @param auctionId unique id, used to de-duplicate overlapping polls
 * @param timestamp epoch millis when the sale completed
 * @param price     final sale price in coins
 * @param bin       true for buy-it-now, false for a bid auction
 */
public record EndedAuction(
		@SerializedName("auction_id") String auctionId,
		String seller,
		String buyer,
		long timestamp,
		long price,
		boolean bin,
		@SerializedName("item_bytes") String itemBytes
) {
}
