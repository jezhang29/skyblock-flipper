package jeff.skyblockflipper.core.track;

/**
 * One resting bazaar order as the orders menu described it at one moment.
 *
 * <p>Chat is an event stream and misses everything that happens with the client closed. This is the
 * other half: whatever the menu says is true now, whether or not a line ever announced it. An order
 * that filled overnight is invisible to {@link ChatParser} and plain here.
 *
 * @param at            when the menu was snapshotted
 * @param side          {@link TradeEvent.Side#BUY} for an order, {@link TradeEvent.Side#SELL} for an
 *                      offer, read off the {@code BUY }/{@code SELL } prefix on the stack name
 * @param itemId        Hypixel's id, or empty. Empty is real and not a failure: enchantment-book
 *                      orders carry no custom data at all, so those resolve by name or not at all
 * @param displayName   the name with the side prefix removed
 * @param owner         the player named on the {@code By:} line, rank tag stripped. The co-op orders
 *                      menu lists every member's orders side by side, so this is the only thing
 *                      separating your position from a coop-mate's
 * @param total         units the order was placed for, from {@code Offer amount:} / {@code Order
 *                      amount:}. Never from the {@code Filled:} denominator, which is abbreviated -
 *                      a 1,344 unit offer prints {@code 903/1.3k}
 * @param filled        units filled so far, exact
 * @param unitPrice     the per-unit price the order rests at, gross of bazaar tax
 * @param claimCoins    coins waiting to be collected from a filled sell offer, net of tax, or 0
 * @param claimItems    items waiting to be collected from a filled buy order, or 0
 */
public record OrderSnapshot(long at, TradeEvent.Side side, String itemId, String displayName,
		String owner, long total, long filled, double unitPrice, double claimCoins, long claimItems) {
	/** Filled but not yet collected, which is the state a tracker has to notice and act on. */
	public boolean hasSomethingToClaim() {
		return claimCoins > 0.0d || claimItems > 0L;
	}

	/** A fill that stopped short. The units left resting are {@code total - filled}. */
	public boolean isPartial() {
		return filled > 0L && filled < total;
	}
}
