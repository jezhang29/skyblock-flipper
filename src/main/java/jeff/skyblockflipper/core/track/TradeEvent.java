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
