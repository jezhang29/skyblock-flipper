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
	private final TradeEvent.Side side;
	private final String displayName;
	private final double setupCoins;

	private String itemId;
	private long total;
	private long filled;
	private long claimed;
	private double unitPrice;
	private Status status = Status.RESTING;

	TrackedOrder(long placedAt, TradeEvent.Side side, String displayName, String itemId, long total,
			double setupCoins, double unitPrice) {
		this.placedAt = placedAt;
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
		unitPrice = snapshot.unitPrice();

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

	void claim(long units, double claimUnitPrice) {
		// A claim is proof of a fill even when no notification announced one, which is the only
		// evidence a partial fill leaves in chat.
		filled = Math.clamp(Math.max(filled, claimed + units), 0L, total);
		claimed = Math.clamp(claimed + units, 0L, total);

		if (claimUnitPrice > 0.0d) {
			unitPrice = claimUnitPrice;
		}

		if (claimed >= total) {
			status = Status.CLAIMED;
		}
	}

	void cancel(long refunded) {
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
	}

	void vanish() {
		status = Status.VANISHED;
	}
}
