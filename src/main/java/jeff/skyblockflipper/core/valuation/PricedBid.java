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
package jeff.skyblockflipper.core.valuation;

import jeff.skyblockflipper.core.item.DecodedItem;
import jeff.skyblockflipper.core.model.TimedListing;

import java.time.Instant;

/**
 * One timed auction ending soon, priced against its item's BIN value - the timed twin of
 * {@link PricedListing} (docs/auction-bidding-plan.md, Phase 1).
 *
 * <p>The number that matters here is not the current price but the <em>bid to win</em>: what it costs
 * to take the lead right now. On an uncontested listing that is the starting bid; once a rival has
 * bid, the next legal bid is 2.5% over the top bid ({@link Bids#nextBid}). The discount is measured
 * against the item's BIN median, never against timed-sale prices, for the same out-of-sample reason
 * the sniper resells at BIN medians: a timed price is the thing being called cheap.
 */
public record PricedBid(TimedListing listing, DecodedItem item, ValueEstimate value) {
	/** The smallest legal bid that takes the lead right now. */
	public long bidToWin() {
		return Bids.nextBid(listing.startingBid(), listing.highestBidAmount());
	}

	/**
	 * Whether a rival has bid this up off its opening price. An uncontested listing is winnable at the
	 * starting bid by whoever is present at {@code end}; a contested one ratchets towards fair value
	 * through the anti-snipe timer, so its surplus is already gone.
	 */
	public boolean contested() {
		return listing.highestBidAmount() > listing.startingBid();
	}

	/** How long until the auction is scheduled to end, in hours (may be pushed out by a late bid). */
	public double hoursLeft(Instant now) {
		return Math.max(0.0d, (listing.end() - now.toEpochMilli()) / 3_600_000.0d);
	}

	/** How far below BIN value the bid to win sits, as a fraction. */
	public double discount() {
		return value.median() <= 0.0d ? 0.0d : 1.0d - bidToWin() / value.median();
	}
}
