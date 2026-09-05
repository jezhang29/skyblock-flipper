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

/**
 * The auction-house bid arithmetic, which is fixed game data rather than anything measured
 * (docs/auction-bidding-plan.md). Kept in one place so the strategy and the "bid up to X" ceiling
 * quote the same rule.
 */
public final class Bids {
	/** Hypixel's minimum bid increment: a new bid must beat the top bid by 2.5%. */
	public static final double MIN_INCREMENT = 0.025d;

	private Bids() {
	}

	/**
	 * The smallest legal bid that takes the lead, given the opening bid and the current top bid.
	 *
	 * <p>With no bids yet (top bid 0, or still equal to the opening bid) the lead is taken at the
	 * starting bid itself. Once a rival leads, the next bid is {@code ceil(top * 1.025)}.
	 */
	public static long nextBid(long startingBid, long highestBidAmount) {
		if (highestBidAmount <= startingBid) {
			return startingBid;
		}

		return (long) Math.ceil(highestBidAmount * (1.0d + MIN_INCREMENT));
	}
}
