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

/**
 * One thing that happened to a position, read out of a chat line.
 *
 * <p>Chat is the only event stream there is: Hypixel publishes no endpoint for your own orders, so
 * every fact about a trade arrives either as a line of text or not at all. This is that line after
 * it has been understood, and it is deliberately flat - a parser that returns a shape per message
 * form makes the caller re-learn Hypixel's wording, which is the thing being hidden here.
 *
 * <p>Fields that a given kind does not carry are zero. {@link Kind} says which ones are real, and
 * the alternative - an Optional per field - costs more at every call site than it saves.
 *
 * @param at          wall clock when the client received the line
 * @param kind        what happened
 * @param side        which side of the book, or {@link Side#NONE} for events with no side
 * @param displayName item name exactly as Hypixel wrote it, stars and level prefix included. It is
 *                    ambiguous by design - 187 of 5549 item names are a strict prefix of another -
 *                    so resolving it to an id needs the menu snapshot, not this
 * @param units       how many units the event covers
 * @param coins       coins that moved. On a sell claim this is net of bazaar tax, and on a sell
 *                    offer setup it is the net the offer will pay out if it fills completely
 * @param unitPrice   the per-unit price Hypixel printed, which on a sell is gross of tax and so
 *                    does not multiply out to {@link #coins}
 */
public record TradeEvent(long at, Kind kind, Side side, String displayName, long units,
		double coins, double unitPrice) {
	public enum Kind {
		/** A bazaar order was placed. Carries units and the escrowed or expected coins. */
		ORDER_PLACED,
		/**
		 * A bazaar order filled completely. Carries units and no coins.
		 *
		 * <p>A partial fill produces no line at all, so the absence of this is not evidence that
		 * nothing filled - the order menu is the only place a resting partial shows up.
		 */
		ORDER_FILLED,
		/**
		 * Filled units were collected. This is the only event that reports what a position actually
		 * paid, and it fires for a partial collection in the same wording as a complete one.
		 */
		ORDER_CLAIMED,
		/**
		 * An order was cancelled.
		 *
		 * <p>A sell offer refunds the items, so the line names them and this carries the units. A
		 * buy order refunds the escrowed coins and names nothing, so this carries the coins with an
		 * empty {@link #displayName()} and zero units, and the order it belongs to has to be found
		 * by the refund amount.
		 */
		ORDER_CANCELLED,
		/** An instant buy or instant sell, which settles in one line with no order behind it. */
		INSTANT,
		/**
		 * A stack was sold over an NPC shop counter. Carries units and the coins the NPC paid.
		 *
		 * <p>The other end of every NPC flip, and the one settlement that was assumed not to exist:
		 * {@code Ledger.npcCoinsReceivedSince} was written believing an NPC sale "produces no chat
		 * line and no menu row, so nothing observes it". It does produce a line - 60 of them for
		 * Hard Stone alone in the 2026-08-09 capture, worded {@code You sold Cobblestone x64 for 64
		 * Coins!} with no {@code [Bazaar]} prefix and no colour codes. Without it an NPC position
		 * bought units and could never sell them, so every one stayed open at zero sold forever.
		 *
		 * <p>Untaxed: the counter pays its posted price flat, which is why {@link #coins} divides
		 * out to {@link #unitPrice} exactly here and does not on a bazaar sell claim.
		 */
		NPC_SOLD,
		/** An auction BIN was bought by you. Carries coins paid. */
		AUCTION_BOUGHT,
		/** An item of yours sold at auction and the coins were collected. */
		AUCTION_SOLD,
		/**
		 * You listed an item. Carries no coins: the only line that names a listing price is the
		 * public broadcast, which every player's listing also produces, so it cannot be trusted to
		 * be yours.
		 */
		AUCTION_LISTED
	}

	public enum Side {
		BUY,
		SELL,
		NONE
	}
}
