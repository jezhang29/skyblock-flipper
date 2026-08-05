package jeff.skyblockflipper.core.track;

/**
 * Coins that actually moved, for units that actually moved.
 *
 * <p>This is the output the ledger wants and the only kind of event worth writing into it. Most of
 * what a trading session produces is not this: an order setup is an intention, a fill notification
 * says an intention was met but reports no money, a listing is an offer nobody has taken. Only a
 * claim, an instant order and an auction that completed say what a position cost or returned.
 *
 * <p>Sells report {@link #coins} net of bazaar tax while {@link #unitPrice} is the gross price
 * Hypixel printed, so the two do not multiply out. That asymmetry is Hypixel's, and dropping either
 * number loses something: the gross price is what the book showed and the net is what the purse
 * received.
 *
 * @param at          when it settled
 * @param venue       which fee basis the coins already had applied to them
 * @param side        {@link TradeEvent.Side#BUY} for coins out and items in
 * @param itemId      Hypixel's id, or empty when no menu in the session ever showed the item.
 *                    Empty is a real outcome for an item bought and never looked at in a menu
 * @param displayName the name exactly as Hypixel wrote it
 * @param units       units that transacted, which for a partial claim is the part that filled
 * @param unitPrice   per-unit price as displayed, gross of tax on a sell
 * @param coins       coins that moved, net of tax on a sell
 */
public record Settlement(long at, Venue venue, TradeEvent.Side side, String itemId,
		String displayName, long units, double unitPrice, double coins) {
	public enum Venue {
		/** A bazaar order that rested and was collected. */
		BAZAAR_ORDER,
		/** An instant buy or sell, which never rested. */
		BAZAAR_INSTANT,
		/** A BIN purchase or an auction of yours that sold. */
		AUCTION
	}

	/** The same settlement with an id attached, once a menu has taught the name. */
	public Settlement withItemId(String resolved) {
		return itemId.equals(resolved)
				? this
				: new Settlement(at, venue, side, resolved, displayName, units, unitPrice, coins);
	}
}
