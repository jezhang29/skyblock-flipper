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
package jeff.skyblockflipper.core.ledger;

import jeff.skyblockflipper.core.strategy.FlipCandidate;
import jeff.skyblockflipper.core.strategy.StrategyKind;

/**
 * One flip the player actually took, and what it actually did.
 *
 * <p>The quoted numbers are frozen at open time on purpose. Comparing a fill against the book as
 * it looks now is how you talk yourself into believing a plan worked: the book has already moved,
 * usually in the direction that made your fill worse.
 *
 * @param origin          where the entry came from, which decides whether it can be part of the
 *                        capture rate at all
 * @param quotedUnitNet   net profit per unit the strategy promised when the position was opened
 * @param unitsSold       how many units actually came back out, which is rarely all of them.
 *                        Accumulates across partial fills
 * @param unitSellPrice   average price the sold units went out at, weighted by units
 * @param realizedUnitNet net profit per unit actually achieved, on the same fee basis as the quote
 */
public record LedgerEntry(
		String id,
		String itemId,
		String displayName,
		StrategyKind kind,
		Origin origin,
		Status status,
		long openedAt,
		long units,
		double unitBuyPrice,
		double quotedUnitNet,
		long capital,
		long closedAt,
		long unitsSold,
		double unitSellPrice,
		double realizedUnitNet
) {
	/** Entries written before origins existed are hand-typed ones, which is what they were. */
	public LedgerEntry {
		origin = origin == null ? Origin.MANUAL : origin;
	}

	public enum Origin {
		/** Opened and closed by hand, from a candidate the player chose to take. */
		MANUAL,
		/**
		 * Filled in by the tracker against a position the mod had quoted. The only kind that can
		 * answer the capture-rate question, because it is the only kind with both numbers.
		 */
		AUTO_QUOTED,
		/**
		 * Filled in by the tracker with no quote behind it - an ordinary trade the mod never
		 * suggested. Worth recording, since it still says how much of a plan reaches a fill, and
		 * kept out of the capture rate, where a quote of zero would read as infinite shortfall.
		 */
		AUTO_UNQUOTED
	}

	public enum Status {
		/** Capital is committed and the outcome is not yet known. */
		OPEN,
		/** Closed with a reported fill. Only these carry a realized number. */
		CLOSED,
		/**
		 * Given up on - cancelled, or the order never filled. Carries no realized price, but the
		 * units that never sold are exactly the evidence a plan was unrealistic, so it is recorded
		 * rather than deleted.
		 */
		ABANDONED
	}

	public static LedgerEntry open(String id, FlipCandidate candidate, long openedAt) {
		return open(id, Quote.of(candidate), openedAt, Origin.MANUAL);
	}

	public static LedgerEntry open(String id, FlipCandidate candidate, long openedAt, Origin origin) {
		return open(id, Quote.of(candidate), openedAt, origin);
	}

	public static LedgerEntry open(String id, Quote quote, long openedAt, Origin origin) {
		return new LedgerEntry(
				id,
				quote.itemId(),
				quote.displayName(),
				quote.kind(),
				origin,
				Status.OPEN,
				openedAt,
				quote.units(),
				quote.unitBuyPrice(),
				quote.unitNetProfit(),
				quote.capitalRequired(),
				0L,
				0L,
				0.0d,
				0.0d);
	}

	/** A position the tracker saw bought that no quote ever asked for. */
	public static LedgerEntry bought(String id, String itemId, String displayName, StrategyKind kind,
			long at, long units, double unitBuyPrice) {
		return new LedgerEntry(id, itemId, displayName, kind, Origin.AUTO_UNQUOTED, Status.OPEN, at,
				units, unitBuyPrice, 0.0d, Math.round(unitBuyPrice * units), 0L, 0L, 0.0d, 0.0d);
	}

	/**
	 * The same position with what the buy actually cost, once the tracker has watched it happen.
	 *
	 * <p>Only the buy side moves. {@link #quotedUnitNet} stays exactly as the strategy said it
	 * would be, because a quote that gets edited afterwards can never be found to have been wrong.
	 */
	public LedgerEntry confirmedBuy(long units, double unitBuyPrice) {
		return new LedgerEntry(id, itemId, displayName, kind, Origin.AUTO_QUOTED, status, openedAt,
				units, unitBuyPrice, quotedUnitNet, Math.round(unitBuyPrice * units), closedAt,
				unitsSold, unitSellPrice, realizedUnitNet);
	}

	public LedgerEntry closed(long closedAt, long unitsSold, double unitSellPrice, double realizedUnitNet) {
		return new LedgerEntry(id, itemId, displayName, kind, origin, Status.CLOSED, openedAt, units,
				unitBuyPrice, quotedUnitNet, capital,
				closedAt, Math.clamp(unitsSold, 0L, units), unitSellPrice, realizedUnitNet);
	}

	/**
	 * Adds one sale to a position that may take several to close.
	 *
	 * <p>A bazaar sell offer for 1,344 units that fills 903 and then 441 is one position and two
	 * settlements, and Hypixel reports each as its own claim at its own price. Prices average by
	 * units so that a position that sold most of itself cheaply is not flattered by a small
	 * expensive tail.
	 *
	 * <p>Units past the planned size are dropped rather than growing the position: selling more
	 * than you bought means the extra came from somewhere this entry knows nothing about, and
	 * counting it here would credit that stock's profit to this plan.
	 */
	public LedgerEntry filled(long at, long soldNow, double sellPrice, double netPerUnit) {
		long total = Math.clamp(unitsSold + soldNow, 0L, units);
		long added = total - unitsSold;

		if (added <= 0L) {
			return this;
		}

		double price = average(unitSellPrice, unitsSold, sellPrice, added);
		double net = average(realizedUnitNet, unitsSold, netPerUnit, added);
		boolean done = total >= units;

		return new LedgerEntry(id, itemId, displayName, kind, origin,
				done ? Status.CLOSED : Status.OPEN, openedAt, units, unitBuyPrice, quotedUnitNet,
				capital, done ? at : 0L, total, price, net);
	}

	public LedgerEntry abandoned(long closedAt) {
		return new LedgerEntry(id, itemId, displayName, kind, origin, Status.ABANDONED, openedAt,
				units, unitBuyPrice, quotedUnitNet, capital, closedAt, unitsSold, unitSellPrice,
				realizedUnitNet);
	}

	public boolean isOpen() {
		return status == Status.OPEN;
	}

	/** Whether there is a quote to hold the outcome against. */
	public boolean isQuoted() {
		return origin != Origin.AUTO_UNQUOTED;
	}

	/** Sold nothing yet, which is what makes a position free to be matched to a fresh buy. */
	public boolean isUntouched() {
		return unitsSold == 0L;
	}

	/** What the plan said this would make, on the units that actually transacted. */
	public double quotedOnFilled() {
		return quotedUnitNet * unitsSold;
	}

	/** What it did make. Units that never sold are inventory, not a loss, so they are not counted. */
	public double realizedTotal() {
		return realizedUnitNet * unitsSold;
	}

	private static double average(double soFar, long units, double added, long addedUnits) {
		// The first fill is not an average of anything, and running it through the arithmetic
		// anyway turns a price of 38.2 into 38.20000000000001 for no reason.
		if (units == 0L) {
			return added;
		}

		return (soFar * units + added * addedUnits) / (units + addedUnits);
	}
}
