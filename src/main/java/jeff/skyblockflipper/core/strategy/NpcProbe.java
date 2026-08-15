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
 * @param worstOverbid the most any bid ever exceeded the probe's price by. This is what separates
 *                    the two things that can outbid an order, and they call for opposite responses:
 *                    a value that stays near 0.1 means the only thing that ever happened is somebody
 *                    pressing the bazaar's own increment button, which no premium can prevent and
 *                    none needs to, while a large one is a competitor repricing to their own
 *                    valuation, which is what the premium is paid to sit above
 * @param retakes     times the order went from outbid back to the top of the book. An increment
 *                    click that fills and leaves gives the top straight back; one that sits there
 *                    does not, and this counts which happened
 * @param onTop       whether the last poll found it on top, kept only so {@code retakes} can tell a
 *                    return from a continuation
 * @param filledAt    epoch millis the order was seen to fill, or 0 while it is still resting.
 *                    <b>Without this the probe reports its best result for its worst reason.</b> A
 *                    filled order leaves the book, so the top bid drops back below the probe's
 *                    price and every later poll scores as "on top" - an order that filled in three
 *                    minutes would read as one that held the top all night. Sampling stops here
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
		long firstOutbid,
		double worstOverbid,
		int retakes,
		boolean onTop,
		long filledAt
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
		// Starts on top, because the order is posted above the book and nothing has looked at it yet.
		// Starting it outbid would score the first outbid as a retake.
		return new NpcProbe(itemId, displayName, price, premium, now, 0, 0, 0L, 0L, 0.0d, 0, true, 0L);
	}

	/**
	 * This probe with the order recorded as filled, which ends the measurement.
	 *
	 * <p>A fill is the good outcome and the end of the evidence at the same time: the coins were
	 * collected at the premium price, and there is no longer an order in the book to watch. Kept at
	 * the first fill, so an item traded again later does not move the time the probe's order went.
	 */
	public NpcProbe filled(long now) {
		return isFilled() ? this : new NpcProbe(itemId, displayName, price, premium, startedAt,
				samples, atTop, lastSample, firstOutbid, worstOverbid, retakes, onTop, now);
	}

	public boolean isFilled() {
		return filledAt > 0L;
	}

	/**
	 * This probe with one poll's reading folded in.
	 *
	 * @param topBid the best bid on the book now, the probe's own order included
	 * @param now    epoch millis of the poll
	 */
	public NpcProbe sample(double topBid, long now) {
		// Once the order is gone the book says nothing about it, and what it says looks like success:
		// no order of yours to outbid means the top bid is back under your price forever.
		if (isFilled()) {
			return this;
		}

		double over = topBid - price;
		boolean top = over <= TOLERANCE;

		return new NpcProbe(itemId, displayName, price, premium, startedAt, samples + 1,
				atTop + (top ? 1 : 0), now,
				// The first outbid is kept rather than overwritten: how long the order held the top
				// before anything happened is the number the whole question turns on, and an order
				// outbid, re-taken and outbid again would otherwise report the last of them.
				firstOutbid == 0L && !top ? now : firstOutbid,
				top ? worstOverbid : Math.max(worstOverbid, over),
				retakes + (top && !onTop ? 1 : 0),
				top,
				filledAt);
	}

	/**
	 * The most an outbid can be and still be somebody pressing the increment button.
	 *
	 * <p>A coin, which is ten presses of the bazaar's own 0.1 step. Measured over three days of tape
	 * on the four items this mod keeps picking, 68-80% of every upward move in the book was exactly
	 * 0.1 - and all of those moves together carried under 1% of the drift, 5 coins of 2596 on
	 * Revenant Catalyst. The button is what happens constantly; it is not what moves the price.
	 */
	public static final double NUDGE_LIMIT = 1.0d;

	public boolean everOutbid() {
		return firstOutbid > 0L;
	}

	/**
	 * Whether the only thing that ever outbid this order was the increment button.
	 *
	 * <p>The distinction the probe exists to draw. Nothing stops a competitor pressing +0.1 on top of
	 * whatever is highest, so an order at any price can be nudged off the top; the question is
	 * whether that is all that happens, because a nudge is given back as soon as its order fills.
	 * A large overbid is a competitor pricing the item for themselves, which is what a premium is
	 * paid to sit above and the only thing it can protect against.
	 */
	public boolean nudgedOnly() {
		return everOutbid() && worstOverbid <= NUDGE_LIMIT;
	}

	/** Share of polls the order was still on top for, or 0 before the first poll. */
	public double topShare() {
		return samples <= 0 ? 0.0d : (double) atTop / samples;
	}

	/**
	 * How long the probe has been open, which is the window {@link #topShare()} covers.
	 *
	 * <p>Ends at the fill once there is one. After that the clock is still running but the order is
	 * not in the book, so counting the wait as observation would dilute every share it reports.
	 */
	public Duration age(long now) {
		return Duration.ofMillis(Math.max(0L, (isFilled() ? filledAt : now) - startedAt));
	}

	/**
	 * How long the order held the top: until it was outbid, until it filled, or so far.
	 *
	 * <p>A fill ends this as surely as an outbid does, and for the opposite reason - the order left
	 * the book because somebody sold into it, which is the whole point of posting one.
	 */
	public Duration heldFor(long now) {
		long until = everOutbid() ? firstOutbid : isFilled() ? filledAt : now;

		return Duration.ofMillis(Math.max(0L, until - startedAt));
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
				: isFilled()
						? "FILLED after " + Waits.format(heldFor(now)) + ", never outbid"
						: "still on top after " + Waits.format(age(now));

		String line = String.format("%s: %s, %.0f%% of %d polls, +%.1f premium",
				displayName, held, topShare() * 100.0d, samples, premium);

		if (!everOutbid()) {
			return line;
		}

		// Which of the two things happened is the whole finding, so it goes on the line rather than
		// being left for the player to work out from a raw coin figure.
		return line + String.format(", outbid by %.1f at worst (%s), top retaken %d time%s%s",
				worstOverbid, nudgedOnly() ? "the +0.1 button" : "a real reprice", retakes,
				retakes == 1 ? "" : "s",
				// An order outbid early and filled later still filled, and that is the outcome the
				// premium is bought for. Saying only that it was outbid would report a failure.
				isFilled() ? ", then FILLED at " + Waits.format(age(now)) : "");
	}
}
