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
package jeff.skyblockflipper.core.strategy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
 *   <li><b>A frozen list.</b> Which items are being worked holds still for the interval, so the
 *       panel does not grow a row, drop one or re-rank them while the player is walking between
 *       menus. This is the part the measurement above is about.
 *   <li><b>Frozen prices, for judging the round rather than for typing.</b> {@link Row#postPrice}
 *       is what {@link #outstanding} calls a row worked against and what the reserved capital is
 *       sized on - both have to be the number the player acted on. It is <b>not</b> what the panel
 *       quotes: {@code NpcWorklist} re-reads {@code outbidBuyOrder()} off the snapshot in hand,
 *       because the player is standing in front of Hypixel's own "+0.1 coins" button and a price
 *       frozen half an hour ago is visibly a different number from the one the game is offering.
 *       Over 2026-08-11..13 of the bazaar tape the top bid moved inside a thirty-minute window on
 *       39% of {@code ENCHANTED_POISONOUS_POTATO} samples and 83% of {@code BRONZE_BOWL} ones, so
 *       the disagreement was the common case and the player had no way to tell which number to
 *       believe.
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
	 * @param postPrice where the book was when the round opened, and the yardstick a row is judged
	 *                  worked against. The highest of the prices the merged orders each computed, so
	 *                  no order in the row is read as unmoved for being under a cheaper sibling's
	 *                  price. <b>Not the number the panel tells anyone to type</b> - that is re-read
	 *                  live, see the class docs
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
	 * <p><b>An item with nothing resting on it is outstanding unless it was bought out.</b> Two
	 * different things produce an empty book position and they need telling apart. The middle of a
	 * reprice is one: the cancel has happened and the re-post has not, and dropping the row there
	 * would delete the number the player was about to type. An order that filled to the last unit
	 * and was claimed is the other, and there the trade is over - the flip worked, the coins are in
	 * hand, and there is no order left to move.
	 *
	 * <p>{@code filled} is what separates them, and it has to come from outside: the resting orders
	 * show nothing in both cases. Measured on the user's account on 2026-08-12 - an Enchanted
	 * Poisonous Potato order filled completely, was claimed and was sold to the NPC, and the panel
	 * went on asking for 3,391 units to be repriced until the interval ran out, reserving the slot
	 * and 3.5M of bankroll from the basket the whole time.
	 *
	 * @param filled item ids whose orders left the book by filling since this round opened, from
	 *               {@code TradeTracker.filledSince(openedAt)}. Empty is the old behaviour, which is
	 *               the right answer for a caller with no tracker behind it
	 */
	public List<Row> outstanding(List<NpcReprice.Order> resting, Set<String> filled) {
		return outstanding(resting, filled, Map.of(), 0L);
	}

	/**
	 * The same question, told when each item was last cancelled and what time it is now.
	 *
	 * <p>What separates the middle of a reprice from a book the player has simply cleared. Both leave
	 * an item with nothing resting, and the row was held indefinitely for either - so cancelling every
	 * order by hand left {@code /flip npc plan} asking to reprice orders that no longer existed, with
	 * their slots and their coins reserved out of the basket, until the interval ran out. Reported
	 * live 2026-08-14.
	 *
	 * <p>The evidence is elapsed time rather than the cancel itself, because a reprice <i>is</i> a
	 * cancel: dropping a row when one arrives would delete the price the player cancelled in order to
	 * re-post at. A re-post is six clicks and two signs away, so it lands inside
	 * {@link #REPOST_GRACE}; a clear-out never lands at all.
	 *
	 * @param cancelledAt when each item's last buy order was cancelled, from
	 *                    {@code TradeTracker.cancelledSince(openedAt)}. An item missing from it is
	 *                    counted from {@link #openedAt} instead, which is the last moment its orders
	 *                    were known to be resting
	 * @param now         epoch millis, or 0 for a caller that tracks no clock - which keeps the old
	 *                    behaviour of holding an empty row for the whole interval
	 */
	public List<Row> outstanding(List<NpcReprice.Order> resting, Set<String> filled,
			Map<String, Long> cancelledAt, long now) {
		List<Row> pending = new ArrayList<>();

		for (Row row : rows) {
			if (!done(row, resting, filled, cancelledAt, now)) {
				pending.add(row);
			}
		}

		return pending;
	}

	/** The same question with nothing known about what filled. */
	public List<Row> outstanding(List<NpcReprice.Order> resting) {
		return outstanding(resting, Set.of());
	}

	/** Whether every row has been worked, which is the other way a round ends. */
	public boolean complete(List<NpcReprice.Order> resting, Set<String> filled) {
		return outstanding(resting, filled).isEmpty();
	}

	public boolean complete(List<NpcReprice.Order> resting) {
		return complete(resting, Set.of());
	}

	/**
	 * How long a row with nothing resting is held before it is read as abandoned rather than as a
	 * reprice half done.
	 *
	 * <p>Sized off the re-post, which is the only thing it must not interrupt: search, the item's
	 * tile, Create Buy Order, the amount sign, the price sign, confirm. Five minutes is generous for
	 * six clicks and two signs, and it is a sixth of the shortest interval worth setting, so a row
	 * that is genuinely being worked is never dropped under the player.
	 *
	 * <p>Being wrong in this direction costs one row that comes back on the next round. Being wrong
	 * the other way is what was reported: a basket held down by orders that do not exist.
	 */
	public static final Duration REPOST_GRACE = Duration.ofMinutes(5);

	private boolean done(Row row, List<NpcReprice.Order> resting, Set<String> filled,
			Map<String, Long> cancelledAt, long now) {
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

		if (any || filled.contains(row.itemId())) {
			return true;
		}

		// Nothing of this item is resting and it was not bought out. Held while a re-post could still
		// be on its way, and read as abandoned once no plausible re-post has arrived.
		return now > 0L && now - lastSeenResting(row, cancelledAt) >= REPOST_GRACE.toMillis();
	}

	/** The latest moment this row's orders are known to have been on the book. */
	private long lastSeenResting(Row row, Map<String, Long> cancelledAt) {
		return Math.max(openedAt, cancelledAt.getOrDefault(row.itemId(), 0L));
	}
}
