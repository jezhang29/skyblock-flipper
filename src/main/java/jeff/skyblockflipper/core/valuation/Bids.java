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
	 * <p>With no bids yet (top bid 0) the lead is taken at the starting bid itself. Once any bid
	 * stands - including a lone bid at the opening price, which is a real rival and not an empty
	 * auction - the next legal bid is {@code ceil(top * 1.025)}, never a tying bid.
	 */
	public static long nextBid(long startingBid, long highestBidAmount) {
		if (highestBidAmount <= 0L) {
			return startingBid;
		}

		// A standing bid is always >= startingBid, so the lead needs 2.5% over the top bid. The
		// max() guards a malformed top bid below the floor from producing an under-floor "winner".
		return Math.max(startingBid, (long) Math.ceil(highestBidAmount * (1.0d + MIN_INCREMENT)));
	}
}
