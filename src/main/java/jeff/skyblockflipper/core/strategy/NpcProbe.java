package jeff.skyblockflipper.core.strategy;

import jeff.skyblockflipper.core.text.Waits;

import java.time.Duration;

/**
 * One live order, watched to see how long it holds the top of the book.
 *
 * <p><b>The one thing the tape cannot answer.</b> {@code docs/npc-flipping.md} measures what the top
 * bid did over four days of samples, and every one of those samples was taken from a book with none
 * of the player's orders in it. Posting above the top and walking away is worth 69.9M an 8-hour cycle
 * against 58.9M for repricing every thirty minutes <b>if</b> the competition on an item is bidding at
 * its own valuation. If it is instead a bot that re-posts one increment above whatever is on top,
 * paying the premium buys one poll rather than eight hours, and the tape cannot tell the two apart
 * because the tape never contained an order to react to.
 *
 * <p>So this is the measurement made from inside the book: post one order at the premium price, then
 * watch whether the top bid climbs over it. The reported top bid <i>includes</i> the probe's own
 * order, so {@code topBid > price} is exactly "somebody outbid me" and nothing else.
 *
 * <p><b>It reads the book and never places anything.</b> The player types the order; this records
 * what happened to it. Sampled at whatever rate the poller runs, which is the same evidence any
 * other part of the mod acts on.
 *
 * <p>Immutable and in {@code core} with {@code now} passed in, like the rest of the money math. Each
 * sample returns a new probe and the client owns the one instance.
 *
 * @param itemId      the product being watched
 * @param displayName what to call it in a message
 * @param price       what the order was posted at, which is what the top bid is compared against
 * @param premium     coins per unit paid above the plain outbid price, for the report to quote
 * @param startedAt   epoch millis the probe opened
 * @param samples     polls taken since
 * @param atTop       polls where nothing had outbid it
 * @param lastSample  epoch millis of the most recent poll, or 0 before the first
 * @param firstOutbid epoch millis it was first found outbid, or 0 while it never has been
 */
public record NpcProbe(
		String itemId,
		String displayName,
		double price,
		double premium,
		long startedAt,
		int samples,
		int atTop,
		long lastSample,
		long firstOutbid
) {
	/**
	 * How far the top bid may exceed the probe's price and still be the probe itself.
	 *
	 * <p>Half of the bazaar's own 0.1 increment. A buy order's price is read back off an escrow line
	 * rounded to the coin, so the same order can report a tenth either side of what was typed - see
	 * {@code docs/npc-flipping.md}. Below this, nobody has outbid anybody.
	 */
	public static final double TOLERANCE = 0.05d;

	/** A probe opened now, before any poll has looked at it. */
	public static NpcProbe opened(String itemId, String displayName, double price, double premium,
			long now) {
		return new NpcProbe(itemId, displayName, price, premium, now, 0, 0, 0L, 0L);
	}

	/**
	 * This probe with one poll's reading folded in.
	 *
	 * @param topBid the best bid on the book now, the probe's own order included
	 * @param now    epoch millis of the poll
	 */
	public NpcProbe sample(double topBid, long now) {
		boolean top = topBid <= price + TOLERANCE;

		return new NpcProbe(itemId, displayName, price, premium, startedAt, samples + 1,
				atTop + (top ? 1 : 0), now,
				// The first outbid is kept rather than overwritten: how long the order held the top
				// before anything happened is the number the whole question turns on, and an order
				// outbid, re-taken and outbid again would otherwise report the last of them.
				firstOutbid == 0L && !top ? now : firstOutbid);
	}

	public boolean everOutbid() {
		return firstOutbid > 0L;
	}

	/** Share of polls the order was still on top for, or 0 before the first poll. */
	public double topShare() {
		return samples <= 0 ? 0.0d : (double) atTop / samples;
	}

	/** How long the probe has been open, which is the window {@link #topShare()} covers. */
	public Duration age(long now) {
		return Duration.ofMillis(Math.max(0L, now - startedAt));
	}

	/** How long the order held the top before anything outbid it, or the whole age if nothing has. */
	public Duration heldFor(long now) {
		return Duration.ofMillis(Math.max(0L, (everOutbid() ? firstOutbid : now) - startedAt));
	}

	/**
	 * What the probe has found, in one line.
	 *
	 * <p>Says what it is rather than what it means. The premium is only worth paying if an order
	 * holds the top for hours, and how many hours is a judgement about the item - so the numbers go
	 * to the player and the conclusion does not.
	 */
	public String report(long now) {
		if (samples <= 0) {
			return displayName + ": posted at " + String.format("%.1f", price) + ", nothing polled yet";
		}

		String held = everOutbid()
				? "held the top " + Waits.format(heldFor(now)) + " before being outbid"
				: "still on top after " + Waits.format(age(now));

		return String.format("%s: %s, %.0f%% of %d polls, +%.1f premium",
				displayName, held, topShare() * 100.0d, samples, premium);
	}
}
