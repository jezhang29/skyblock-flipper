package jeff.skyblockflipper.core.strategy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One batch of reprice advice, with its prices frozen for the length of a check-in interval.
 *
 * <p><b>The book is not a clock.</b> {@link NpcReprice} compares a resting price to the top bid, and
 * on a contested book that comparison is true again seconds after you act on it: the live
 * {@code TRANSMISSION_TUNER} sample on 2026-08-11 had five bots a tenth of a coin apart, so an order
 * posted at the top was outbid before the player could walk to an NPC. Advice regenerated on every
 * book move asks for a click that is already stale. {@code docs/npc-flipping.md} measures the curve
 * this trade actually sits on - 16 reprice rounds per 8-hour cycle at 59.7M, and 30 minutes against
 * an hour is worth about 1% of it - so chasing continuously is not the top of that curve, it is off
 * the end of it.
 *
 * <p>So a round is opened at most once per {@code npcCheckInMinutes} and holds still while it is
 * worked:
 *
 * <ul>
 *   <li><b>Frozen prices.</b> A price computed when the round opened can be read, walked to a menu
 *       and typed. Recomputing it against the book the player is standing in front of would change
 *       the number between reading it and using it.
 *   <li><b>It survives its own cancel.</b> The bazaar has no in-place edit, so a reprice is a cancel
 *       and then a re-post, and the cancel deletes the order the price was derived from. A row is
 *       therefore held until the item is resting at the frozen price again - see
 *       {@link #outstanding} - rather than regenerated from whatever is on the book right now.
 *   <li><b>Closing the bazaar means nothing.</b> You have to leave the menu to sell to the NPC. Only
 *       the interval ends a round, or every row in it being done.
 * </ul>
 *
 * <p><b>Claims and dead-trade cancels are not in here.</b> A claim is coins already earned, and the
 * item cannot leave the order until it is collected; a {@code CANCEL} because the book caught the
 * NPC price, or an {@code EXPIRED} past the resting window, means the trade is over and the capital
 * is stranded. Neither improves by waiting for a round, so both are emitted whenever they are true
 * and this only ever holds {@link NpcReprice.Action#REPRICE}.
 *
 * <p>Whether a reprice is worth doing at all was already decided before a row got here:
 * {@code NpcReprice.review} reports an outbid order worth less than {@code minProfitPerFlip} as a
 * hold, so everything this freezes has cleared that floor.
 *
 * <p>A record in {@code core} with {@code now} passed in, same rule as the rest of the money math.
 * The client owns the open instance and the clock.
 *
 * @param openedAt epoch millis the round opened, which is what both the supersede rule and the
 *                 remaining horizon are measured from
 * @param interval the check-in interval this round was opened for, frozen with the prices. A
 *                 settings edit part way through a round changes the next one rather than moving the
 *                 end of the one in hand
 * @param rows     the frozen work, one row per item, largest gain first
 */
public record NpcRound(long openedAt, Duration interval, List<Row> rows) {
	public NpcRound {
		interval = interval == null || interval.isZero() || interval.isNegative()
				? NpcContext.DEFAULT_CHECK_IN
				: interval;
		rows = List.copyOf(rows);
	}

	/**
	 * One item to move back to the top of the book, however many orders that takes.
	 *
	 * <p>Merged per item because that is the unit the player works in. An unstackable product takes
	 * 256 units to an order ({@code Stacking.UNITS_PER_ORDER_UNSTACKABLE}), so a 1,000-unit line on
	 * {@code TRANSMISSION_TUNER} is four orders and eight clicks, and four rows saying the same price
	 * is four times the reading for one decision.
	 *
	 * @param postPrice the price to type, frozen at the moment the round opened. The highest of the
	 *                  prices the merged orders each computed, so no order in the row is re-posted
	 *                  under the top of the book
	 * @param units     units to move, summed across the item's orders
	 * @param orders    how many resting orders the row covers, which is how many cancels it is
	 * @param gain      what the whole row is expected to make over the rest of the interval, from
	 *                  {@link NpcReprice.RepriceValue}. What the rows are ranked on
	 */
	public record Row(String itemId, String displayName, double postPrice, long units, int orders,
			double gain, String reason) {
	}

	/**
	 * Freezes a round out of a review.
	 *
	 * <p>Every reprice in {@code advice} that is {@linkplain #eligible old enough} is frozen; the
	 * holds, claims and cancels in it are ignored, for the reasons on the class. An empty result is
	 * legitimate and normal - it means the review found nothing worth a trip - and is still a round,
	 * because opening one is what starts the interval that keeps the mod quiet.
	 *
	 * @param now epoch millis, which becomes {@link #openedAt}
	 */
	public static NpcRound open(long now, Duration interval, List<NpcReprice.Advice> advice) {
		Duration length = interval == null || interval.isZero() || interval.isNegative()
				? NpcContext.DEFAULT_CHECK_IN
				: interval;

		// Insertion-ordered so the merge is deterministic before the sort, and the sort is stable:
		// two items with the same gain come out in the order the review ranked them.
		Map<String, Row> merged = new LinkedHashMap<>();

		for (NpcReprice.Advice entry : advice) {
			if (entry.action() != NpcReprice.Action.REPRICE
					|| !eligible(entry.order(), now, length)) {
				continue;
			}

			merged.merge(entry.order().itemId(), rowOf(entry), NpcRound::combine);
		}

		List<Row> rows = new ArrayList<>(merged.values());
		rows.sort(Comparator.comparingDouble(Row::gain).reversed());

		return new NpcRound(now, length, rows);
	}

	private static Row rowOf(NpcReprice.Advice entry) {
		return new Row(entry.order().itemId(), entry.order().displayName(), entry.postPrice(),
				entry.order().remaining(), 1, entry.value().gain(), entry.reason());
	}

	/** Two orders on one item, as one row: the sizes add up and the higher price wins. */
	private static Row combine(Row first, Row second) {
		return new Row(
				first.itemId(),
				first.displayName(),
				Math.max(first.postPrice(), second.postPrice()),
				first.units() + second.units(),
				first.orders() + second.orders(),
				first.gain() + second.gain(),
				first.reason());
	}

	/**
	 * Whether an order is old enough to be asked about.
	 *
	 * <p>An order has to be at least one interval old to enter a round. Without that, placing a
	 * basket and opening the next round twenty seconds later would ask you to reprice orders you had
	 * only just typed, which is the same churn the round exists to stop.
	 *
	 * <p><b>An order of unknown age is always eligible.</b> {@link NpcReprice.Order#placedAt} is
	 * "when this session first saw the order", not when Hypixel accepted it, so an order
	 * {@code TradeTracker} adopted from an orders-menu snapshot looks newborn however long it has
	 * really been resting. Dwelling on that would mute the list for a whole interval at the one
	 * moment it matters most: logging in to a basket that was outbid overnight, which is the case the
	 * whole reminder was built for. Being wrong the other way costs one early row.
	 */
	public static boolean eligible(NpcReprice.Order order, long now, Duration interval) {
		if (order.adopted() || order.placedAt() <= 0L) {
			return true;
		}

		long minimum = interval == null || interval.isZero() || interval.isNegative()
				? NpcContext.DEFAULT_CHECK_IN.toMillis()
				: interval.toMillis();

		return now - order.placedAt() >= minimum;
	}

	/** Epoch millis this round stops being the one in hand. */
	public long endsAt() {
		return openedAt + interval.toMillis();
	}

	/**
	 * Whether the interval has run out, so a fresh round may open with recomputed prices.
	 *
	 * <p>The whole supersede rule. It does not depend on the round having been worked: an unfinished
	 * round is replaced at the interval rather than kept, because a price frozen an interval ago is
	 * no longer the price to type. Nor does a finished round let the next one open early - the
	 * interval is the rate limit on opening at all, and that is what stops a worked round from
	 * immediately producing another.
	 */
	public boolean elapsed(long now) {
		return now >= endsAt();
	}

	/**
	 * Time left to collect anything in, which is what a reprice in this round is worth valuing over.
	 *
	 * <p>Feeds the four-argument {@code NpcReprice.review}: a row is only worth what it fills before
	 * the next trip, so half an interval in, a marginal row is worth half as much.
	 */
	public Duration remaining(long now) {
		return Duration.ofMillis(Math.max(0L, endsAt() - now));
	}

	public boolean isEmpty() {
		return rows.isEmpty();
	}

	/** What the whole round is expected to be worth, which is what one chime is spent on. */
	public double gain() {
		return rows.stream().mapToDouble(Row::gain).sum();
	}

	public Optional<Row> rowFor(String itemId) {
		return rows.stream().filter(row -> row.itemId().equals(itemId)).findFirst();
	}

	/**
	 * The rows still to do, given what is resting now.
	 *
	 * <p>A row is done when every unit the item still has on the book is at the frozen price and at
	 * least one order is there - so a part-worked item, where two of four orders have been moved, is
	 * still outstanding. Prices are compared to within {@link NpcReprice#OUTBID_TOLERANCE}, because a
	 * price read back from an escrow line is exact only to the coin.
	 *
	 * <p><b>An item with nothing resting on it is outstanding, not done.</b> That is the middle of a
	 * reprice: the cancel has happened and the re-post has not, and this is the moment the mod used
	 * to drop the row and the price with it. The cost of that reading is a row that lingers when a
	 * re-post fills and is claimed inside the same interval, leaving no order behind to prove it was
	 * ever moved; the next round clears it. A stale row costs a wasted click, and dropping a row
	 * mid-reprice costs the number the player was about to type.
	 */
	public List<Row> outstanding(List<NpcReprice.Order> resting) {
		List<Row> pending = new ArrayList<>();

		for (Row row : rows) {
			if (!done(row, resting)) {
				pending.add(row);
			}
		}

		return pending;
	}

	/** Whether every row has been worked, which is the other way a round ends. */
	public boolean complete(List<NpcReprice.Order> resting) {
		return outstanding(resting).isEmpty();
	}

	private static boolean done(Row row, List<NpcReprice.Order> resting) {
		boolean any = false;

		for (NpcReprice.Order order : resting) {
			if (!order.itemId().equals(row.itemId())) {
				continue;
			}

			// Below the frozen price with units still on the book: this is an order the row is about
			// and it has not been moved. A fully filled order is not evidence of anything to do, only
			// of a claim, so it is not read as unmoved.
			if (order.remaining() > 0L
					&& order.unitPrice() < row.postPrice() - NpcReprice.OUTBID_TOLERANCE) {
				return false;
			}

			any = true;
		}

		return any;
	}
}
