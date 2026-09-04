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

/**
 * One active <b>timed</b> (non-BIN) auction currently on the house, as seen by one sweep.
 *
 * <p>The twin of {@link ActiveListing}, but for the bid side rather than buy-it-now. It exists only
 * to feed the Phase 0b reachability collection (docs/auction-bidding-plan.md): a timed auction is
 * not a snipe, so nothing in the shipped strategies looks at one. What the reachability question
 * needs, and this carries, is the trajectory a listing traces towards its {@code end} - whether the
 * top bid ever rises off the starting bid before the clock runs out.
 *
 * <p>{@code end} is the scheduled end in epoch millis, which Hypixel pushes forward by two minutes
 * on any late bid (the anti-snipe timer), so a listing sampled again after a bid reports a later
 * {@code end} than before. {@code highestBidAmount} is 0 while no one has bid; a bid auction with no
 * bids is winnable at {@code startingBid}.
 *
 * @param uuid             the auction id, the same value {@code auctions_ended} reports as
 *                         {@code auction_id}, so a trajectory joins to its realized sale on it
 * @param startingBid      the opening bid, the price to take an uncontested lead
 * @param highestBidAmount the current top bid, or 0 when no one has bid
 * @param end              scheduled end, epoch millis (moves later on a late bid)
 * @param itemBytes        the raw blob, decoded to a signature at collection time and then dropped
 */
public record TimedListing(
		String uuid,
		long startingBid,
		long highestBidAmount,
		long end,
		String itemBytes
) {
}
