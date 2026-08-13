package jeff.skyblockflipper.core.track;

/**
 * One bazaar order as {@link TradeTracker} currently understands it.
 *
 * <p>Mutable, unlike everything else here, because an order is one thing that changes over an hour
 * rather than a series of things that happened. Replacing it on every fill would make "the same
 * order" a matching problem in the tracker as well as at its input.
 *
 * <p>{@code filled} is whatever the last evidence said, and the evidence disagrees: a chat
 * notification only ever announces a complete fill, so an order that stopped at 903 of 1,344 reads
 * as unfilled until a menu says otherwise. The menu wins, always.
 */
public final class TrackedOrder {
	private final long placedAt;
	private final boolean adopted;
	private final TradeEvent.Side side;
	private final String displayName;
	private final double setupCoins;

	private String itemId;
	private long total;
	private long filled;
	private long claimed;
	private double unitPrice;
	private Status status = Status.RESTING;
	private long finishedAt;

	TrackedOrder(long placedAt, boolean adopted, TradeEvent.Side side, String displayName,
			String itemId, long total, double setupCoins, double unitPrice) {
		this.placedAt = placedAt;
		this.adopted = adopted;
		this.side = side;
		this.displayName = displayName;
		this.itemId = itemId;
		this.total = total;
		this.setupCoins = setupCoins;
		this.unitPrice = unitPrice;
	}

	public enum Status {
		/** Still on the book as far as anything here knows. */
		RESTING,
		/** Every unit filled and collected. */
		CLAIMED,
		/** Taken off the book by you. The units that never filled are the interesting part. */
		CANCELLED,
		/**
		 * Gone from the orders menu with no line to explain it. Real and not an error: an order
		 * that fills and is collected on another device, or in a session this never saw, leaves
		 * exactly this trace.
		 */
		VANISHED
	}

	public long placedAt() {
		return placedAt;
	}

	/**
	 * Whether this order was met rather than watched being placed, so {@link #placedAt} is when it
	 * was first seen and says nothing about how long it has really been resting.
	 *
	 * <p>True for every order adopted from an orders-menu snapshot or from a fill line with no
	 * placement behind it - an order from an earlier session, from another device, or from before
	 * capture was switched on. False only when the placement itself was in chat, which is the one
	 * case where the time is the real one.
	 *
	 * <p>The distinction exists because a dwell rule cannot be built on {@link #placedAt} alone:
	 * every order in a fresh session looks newborn, so {@code NpcRound} would refuse to say anything
	 * about a basket that had been outbid overnight until it had been logged in for a whole interval.
	 */
	public boolean adopted() {
		return adopted;
	}

	public TradeEvent.Side side() {
		return side;
	}

	public String displayName() {
		return displayName;
	}

	public String itemId() {
		return itemId;
	}

	public long total() {
		return total;
	}

	public long filled() {
		return filled;
	}

	public long claimed() {
		return claimed;
	}

	/** Per-unit price the order rests at, gross of tax, or 0 until a menu or a claim reported one. */
	public double unitPrice() {
		return unitPrice;
	}

	/**
	 * Coins the setup line quoted for the whole order: what a buy order escrowed, or what a sell
	 * offer will pay out net of tax if every unit fills. Not a unit price - dividing it by
	 * {@link #total} on a sell gives the net, which is about 1% under the price on the book.
	 */
	public double setupCoins() {
		return setupCoins;
	}

	public Status status() {
		return status;
	}

	public boolean isResting() {
		return status == Status.RESTING;
	}

	/** Epoch millis this order left the book, or 0 while it is still on it. */
	public long finishedAt() {
		return finishedAt;
	}

	/**
	 * Whether this order left the book because it was bought out rather than because it was pulled.
	 *
	 * <p>The distinction {@code NpcRound} cannot work without. A reprice is a cancel and a re-post,
	 * so mid-reprice an item has nothing resting on it and the frozen row must be held; a completed
	 * flip also has nothing resting on it and the row must go. Both look identical from the resting
	 * orders alone, which is why the row survived a filled and claimed order for the rest of the
	 * interval - measured on the user's own account on 2026-08-12, where 3,391 units of Enchanted
	 * Poisonous Potato were still being asked for after the order had filled, been claimed and been
	 * sold.
	 *
	 * <p>{@link Status#CLAIMED} is the clean case: every unit filled and was collected.
	 * {@link Status#VANISHED} counts only with nothing left on the book, which is an order last seen
	 * complete that then left the menu - collected on another device, or in a session nothing was
	 * watching. A vanish with units still remaining is not counted, because a cancel whose refund
	 * line was missed lands there too and reading that as a fill would drop a live row.
	 */
	public boolean finishedByFilling() {
		return status == Status.CLAIMED || (status == Status.VANISHED && remaining() <= 0L);
	}

	/** Units still on the book. */
	public long remaining() {
		return Math.max(0L, total - filled);
	}

	/** Filled units not yet collected, which is what a claim line is about to be for. */
	public long unclaimed() {
		return Math.max(0L, filled - claimed);
	}

	void applySnapshot(OrderSnapshot snapshot) {
		// The menu is the ground truth for how much filled, including everything that happened
		// with the client shut.
		filled = Math.max(filled, snapshot.filled());
		total = snapshot.total();

		// Only when the menu actually stated one. A slot whose "Price per unit" line the parser did
		// not find reports zero, and taking that would throw away a price derived from the setup
		// line - turning a priced order back into an unpriced one on the strength of a missing lore
		// line.
		if (snapshot.unitPrice() > 0.0d) {
			unitPrice = snapshot.unitPrice();
		}

		// And for how much was collected, which used to be read as unknowable here. It is not: the
		// menu prints "You have 3 items to claim!" on top of the Filled: line, and stops printing it
		// once they are claimed, so what is uncollected is stated and what is collected is the rest.
		//
		// Without this a claim made before the tracker ever saw the order - in an earlier session,
		// on another device - was invisible, and the order sat forever reporting filled units nobody
		// had collected. A 1,525 unit Bronze Bowl order that filled 3 and had them claimed hours
		// earlier was still the top line of the worklist, telling the player to claim 3 items that
		// were already in their inventory.
		//
		// Computed against the snapshot's own filled count rather than the field above, so a chat
		// stream that has run ahead of the menu cannot turn "nothing waiting" into units collected
		// that the menu never reported filling.
		snapshot.uncollected().ifPresent(waiting ->
				claimed = Math.clamp(Math.max(claimed, snapshot.filled() - waiting), 0L, total));

		if (itemId.isEmpty()) {
			itemId = snapshot.itemId();
		}
	}

	void fill(long units) {
		filled = Math.clamp(Math.max(filled, units), 0L, total);
	}

	void claim(long at, long units, double claimUnitPrice) {
		// A claim is proof of a fill even when no notification announced one, which is the only
		// evidence a partial fill leaves in chat.
		filled = Math.clamp(Math.max(filled, claimed + units), 0L, total);
		claimed = Math.clamp(claimed + units, 0L, total);

		if (claimUnitPrice > 0.0d) {
			unitPrice = claimUnitPrice;
		}

		if (claimed >= total) {
			status = Status.CLAIMED;
			finishedAt = at;
		}
	}

	void cancel(long at, long refunded) {
		if (refunded < remaining()) {
			// Not a wording Hypixel has been observed to produce, but shrinking the order is the
			// reading that cannot invent units that never existed.
			total -= refunded;
			return;
		}

		// total is deliberately left alone. The 1,344 unit offer that filled 903 and refunded 441
		// is a plan that reached two thirds of itself, and that is the number the fill rate is
		// about; rewriting total down to what filled would score it as a complete success.
		status = Status.CANCELLED;
		finishedAt = at;
	}

	void vanish(long at) {
		status = Status.VANISHED;
		finishedAt = at;
	}
}
