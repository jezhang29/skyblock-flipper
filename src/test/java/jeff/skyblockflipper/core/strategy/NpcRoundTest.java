package jeff.skyblockflipper.core.strategy;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The clock the reprice advice is delivered on.
 *
 * <p>Three rules, and each of them is here because the alternative was measured or observed to be
 * worse: a round only opens once an interval, a row is only in it if the order is old enough to be
 * worth asking about, and a row stays until the item is resting at the price the round froze - across
 * the cancel that repricing requires.
 *
 * <p>Advice is built by hand rather than reviewed off a book. What a reprice is worth is
 * {@code NpcRepriceTest}'s subject; what this needs is a row with a known price and a known gain.
 */
class NpcRoundTest {
	private static final long NOW = 1_754_000_000_000L;
	private static final double NPC_PRICE = 1000.0d;
	private static final Duration INTERVAL = Duration.ofMinutes(30);
	private static final long INTERVAL_MILLIS = INTERVAL.toMillis();

	/** An order placed at a stated time, which is what the dwell rule reads. */
	private static NpcReprice.Order placed(String id, double unitPrice, long remaining,
			long placedAt) {
		return new NpcReprice.Order(id, id, unitPrice, remaining, remaining, 0L, placedAt);
	}

	/** The same order, met in an orders menu instead of watched being posted. */
	private static NpcReprice.Order adopted(String id, double unitPrice, long remaining,
			long seenAt) {
		return new NpcReprice.Order(id, id, unitPrice, remaining, remaining, 0L, seenAt, true);
	}

	private static NpcReprice.Advice reprice(NpcReprice.Order order, double postPrice, double gain) {
		return new NpcReprice.Advice(order, NpcReprice.Action.REPRICE, NPC_PRICE, postPrice,
				postPrice, 850.0d, (NPC_PRICE - postPrice) / NPC_PRICE,
				new NpcReprice.RepriceValue(10.0d, gain, 0.5d, true), "outbid");
	}

	private static NpcReprice.Advice advice(NpcReprice.Order order, NpcReprice.Action action) {
		return new NpcReprice.Advice(order, action, NPC_PRICE, 900.0d, 900.1d, 850.0d, 0.1d,
				action.name());
	}

	/** One reprice on an order an interval old, which is the ordinary case. */
	private static NpcRound round() {
		return NpcRound.open(NOW, INTERVAL, List.of(
				reprice(placed("ITEM", 800.0d, 500L, NOW - INTERVAL_MILLIS), 820.1d, 9_000.0d)));
	}

	@Test
	void freezesThePriceAndTheGainOfEveryEligibleReprice() {
		NpcRound round = round();

		assertEquals(NOW, round.openedAt());
		assertEquals(INTERVAL, round.interval());
		assertEquals(1, round.rows().size());

		NpcRound.Row row = round.rows().getFirst();

		assertEquals("ITEM", row.itemId());
		assertEquals(820.1d, row.postPrice(), 1e-9d);
		assertEquals(500L, row.units());
		assertEquals(1, row.orders());
		assertEquals(9_000.0d, row.gain(), 1e-9d);
		assertEquals(9_000.0d, round.gain(), 1e-9d);
	}

	/**
	 * A round holds reprices and nothing else. Claims and dead trades are worked whenever they are
	 * true, so putting them on a clock would delay coins that are already earned or already stranded.
	 */
	@Test
	void freezesNothingThatIsNotAReprice() {
		long old = NOW - INTERVAL_MILLIS;

		NpcRound round = NpcRound.open(NOW, INTERVAL, List.of(
				advice(placed("HELD", 800.0d, 500L, old), NpcReprice.Action.HOLD),
				advice(placed("DEAD", 800.0d, 500L, old), NpcReprice.Action.CANCEL),
				advice(placed("STALE", 800.0d, 500L, old), NpcReprice.Action.EXPIRED)));

		assertTrue(round.isEmpty());
		assertEquals(0.0d, round.gain());

		// Still a round: opening one is what starts the interval, whether or not there was work in it.
		assertEquals(NOW, round.openedAt());
		assertFalse(round.elapsed(NOW));
	}

	@Test
	void ranksTheRowsByWhatTheyAreWorth() {
		long old = NOW - INTERVAL_MILLIS;

		NpcRound round = NpcRound.open(NOW, INTERVAL, List.of(
				reprice(placed("SMALL", 800.0d, 100L, old), 820.1d, 6_000.0d),
				reprice(placed("BIG", 800.0d, 900L, old), 820.1d, 40_000.0d)));

		assertEquals(List.of("BIG", "SMALL"), round.rows().stream().map(NpcRound.Row::itemId).toList());
	}

	// The dwell rule.

	/** An order typed twenty seconds ago is not something to be told to retype. */
	@Test
	void leavesAnOrderYoungerThanAnIntervalOutOfTheRound() {
		NpcRound round = NpcRound.open(NOW, INTERVAL, List.of(
				reprice(placed("ITEM", 800.0d, 500L, NOW - 20_000L), 820.1d, 9_000.0d)));

		assertTrue(round.isEmpty());
		assertFalse(NpcRound.eligible(placed("ITEM", 800.0d, 500L, NOW - 20_000L), NOW, INTERVAL));
	}

	@Test
	void admitsAnOrderExactlyAnIntervalOld() {
		assertTrue(NpcRound.eligible(placed("ITEM", 800.0d, 500L, NOW - INTERVAL_MILLIS), NOW,
				INTERVAL));
	}

	/**
	 * The exception the whole rule turns on. An order read out of the orders menu is dated to when it
	 * was read, so dwelling on that date would mute the list for half an hour at the one moment it is
	 * most valuable: logging in to a basket the book walked past overnight.
	 */
	@Test
	void alwaysAdmitsAnOrderAdoptedFromAMenu() {
		NpcRound round = NpcRound.open(NOW, INTERVAL, List.of(
				reprice(adopted("ITEM", 800.0d, 500L, NOW - 1_000L), 820.1d, 9_000.0d)));

		assertEquals(1, round.rows().size());
		assertTrue(NpcRound.eligible(adopted("ITEM", 800.0d, 500L, NOW), NOW, INTERVAL));
	}

	/** Same argument, for an order nothing has timed at all. */
	@Test
	void alwaysAdmitsAnOrderNothingHasTimed() {
		assertTrue(NpcRound.eligible(NpcReprice.Order.of("ITEM", "ITEM", 800.0d, 500L), NOW,
				INTERVAL));
	}

	// The supersede rule.

	@Test
	void staysTheRoundInHandUntilTheIntervalRunsOut() {
		NpcRound round = round();

		assertFalse(round.elapsed(NOW));
		assertFalse(round.elapsed(NOW + INTERVAL_MILLIS - 1L));
		assertTrue(round.elapsed(NOW + INTERVAL_MILLIS));
		assertEquals(NOW + INTERVAL_MILLIS, round.endsAt());
	}

	/**
	 * A worked round does not let the next one open early. The interval is the rate limit on opening
	 * at all, which is what stops a finished round from immediately producing another and putting the
	 * mod back to chasing every book move.
	 */
	@Test
	void doesNotEndEarlyJustBecauseEveryRowIsDone() {
		NpcRound round = round();
		List<NpcReprice.Order> reposted = List.of(placed("ITEM", 820.1d, 500L, NOW + 60_000L));

		assertTrue(round.complete(reposted));
		assertFalse(round.elapsed(NOW + 60_000L));
	}

	/** What is left of the interval is what a reprice in this round is worth valuing over. */
	@Test
	void reportsTheTimeLeftToFillIn() {
		NpcRound round = round();

		assertEquals(INTERVAL, round.remaining(NOW));
		assertEquals(Duration.ofMinutes(12), round.remaining(NOW + Duration.ofMinutes(18).toMillis()));

		// Never negative: an elapsed round has no time left rather than time owed.
		assertEquals(Duration.ZERO, round.remaining(NOW + INTERVAL_MILLIS + 5_000L));
	}

	@Test
	void fallsBackToTheDefaultIntervalRatherThanFreezingForever() {
		for (Duration nonsense : List.of(Duration.ZERO, Duration.ofMinutes(-5))) {
			assertEquals(NpcContext.DEFAULT_CHECK_IN,
					NpcRound.open(NOW, nonsense, List.of()).interval(), nonsense.toString());
		}

		assertEquals(NpcContext.DEFAULT_CHECK_IN, NpcRound.open(NOW, null, List.of()).interval());
	}

	// The completion rule, which is also what pins a row through its own cancel.

	@Test
	void keepsARowUntilTheOrderIsRestingAtTheFrozenPrice() {
		NpcRound round = round();

		// Where it was: still under the frozen price, so nothing has been done about it.
		assertEquals(1, round.outstanding(List.of(placed("ITEM", 800.0d, 500L, NOW))).size());
		assertFalse(round.complete(List.of(placed("ITEM", 800.0d, 500L, NOW))));

		// Reposted at the price the round said.
		assertTrue(round.complete(List.of(placed("ITEM", 820.1d, 500L, NOW + 60_000L))));
	}

	/**
	 * The fault this whole design is for. Repricing is a cancel and then a re-post, because the bazaar
	 * has no in-place edit, and for the seconds in between the order does not exist. Regenerating the
	 * list from resting orders deleted the row - and with it the price the player was walking to the
	 * menu to type.
	 */
	@Test
	void holdsARowThroughTheCancelHalfOfItsOwnReprice() {
		NpcRound round = round();

		assertEquals(1, round.outstanding(List.of()).size());
		assertEquals(820.1d, round.outstanding(List.of()).getFirst().postPrice(), 1e-9d);
		assertFalse(round.complete(List.of()));
	}

	/** A price recovered from an escrow line is exact to the coin, and that is not a difference. */
	@Test
	void treatsAPriceWithinTheToleranceAsTheFrozenPrice() {
		NpcRound round = NpcRound.open(NOW, INTERVAL, List.of(reprice(
				placed("ITEM", 800.0d, 500L, NOW - INTERVAL_MILLIS), 21_076.6d, 9_000.0d)));

		double rounded = 6_554_823.0d / 311.0d;

		assertTrue(rounded < 21_076.7d && rounded > 21_076.6d);
		assertTrue(round.complete(List.of(placed("ITEM", rounded, 311L, NOW + 60_000L))));

		// A whole increment under it is a real difference, and that row is not done.
		assertFalse(round.complete(List.of(placed("ITEM", 21_076.5d, 311L, NOW + 60_000L))));
	}

	/**
	 * An unstackable product takes 256 units to an order, so a 1,000-unit line is four orders. One row
	 * says what the whole item needs, and it is only done when every one of them has moved.
	 */
	@Test
	void mergesAnItemsOrdersIntoOneRowAndFinishesWhenAllOfThemHaveMoved() {
		long old = NOW - INTERVAL_MILLIS;

		NpcRound round = NpcRound.open(NOW, INTERVAL, List.of(
				reprice(placed("TUNER", 28_594.2d, 256L, old), 28_594.7d, 4_000.0d),
				reprice(placed("TUNER", 28_594.1d, 256L, old), 28_594.6d, 4_000.0d),
				reprice(placed("TUNER", 28_594.0d, 256L, old), 28_594.5d, 4_000.0d),
				reprice(placed("TUNER", 28_593.9d, 232L, old), 28_594.4d, 3_000.0d)));

		assertEquals(1, round.rows().size());

		NpcRound.Row row = round.rows().getFirst();

		assertEquals(1_000L, row.units());
		assertEquals(4, row.orders());
		assertEquals(15_000.0d, row.gain(), 1e-9d);

		// The highest of the four prices, so no order in the row is re-posted under the top of the
		// book to save a tenth of a coin.
		assertEquals(28_594.7d, row.postPrice(), 1e-9d);

		// Two of the four moved. Still outstanding: the row is the item, not one order in it.
		assertFalse(round.complete(List.of(
				placed("TUNER", 28_594.7d, 256L, NOW + 60_000L),
				placed("TUNER", 28_594.7d, 256L, NOW + 60_000L),
				placed("TUNER", 28_594.0d, 256L, old),
				placed("TUNER", 28_593.9d, 232L, old))));

		assertTrue(round.complete(List.of(
				placed("TUNER", 28_594.7d, 256L, NOW + 60_000L),
				placed("TUNER", 28_594.7d, 256L, NOW + 60_000L),
				placed("TUNER", 28_594.7d, 256L, NOW + 60_000L),
				placed("TUNER", 28_594.7d, 232L, NOW + 60_000L))));
	}

	/**
	 * A re-post that filled is done, not unmoved. Its units are off the book and sitting in the order
	 * waiting to be claimed, and the claim is emitted outside the round anyway.
	 */
	@Test
	void countsAFilledRepostAsDone() {
		NpcRound round = round();
		NpcReprice.Order filled =
				new NpcReprice.Order("ITEM", "ITEM", 820.1d, 500L, 0L, 500L, NOW + 60_000L);

		assertTrue(round.complete(List.of(filled)));
	}

	/**
	 * The other way an item ends up with nothing resting on it, and the one the round used to get
	 * wrong. Measured on the user's account on 2026-08-12: an Enchanted Poisonous Potato order
	 * filled to the last unit, was claimed, and the units were sold to the NPC, and the panel went
	 * on asking for 3,391 units to be repriced for the rest of the interval - reserving the order
	 * slot and 3.5M of bankroll from the basket while it did.
	 *
	 * <p>Indistinguishable from a mid-reprice cancel by the resting orders alone, which is why the
	 * evidence has to be handed in.
	 */
	@Test
	void retiresARowWhoseOrderWasBoughtOut() {
		NpcRound round = round();

		assertFalse(round.complete(List.of(), Set.of()));
		assertTrue(round.complete(List.of(), Set.of("ITEM")));
		assertTrue(round.outstanding(List.of(), Set.of("ITEM")).isEmpty());

		// Something else filling says nothing about this row.
		assertFalse(round.complete(List.of(), Set.of("OTHER")));
	}

	/**
	 * A book the player cleared by hand is not a reprice that will finish.
	 *
	 * <p>Reported live 2026-08-14: every bazaar order cancelled, and {@code /flip npc plan} went on
	 * asking to reprice them - with their slots and their coins reserved out of the basket - because
	 * a row with nothing resting was read as a cancel waiting for its re-post. Both states show the
	 * same empty book position and only elapsed time separates them, so the row is held exactly as
	 * long as a re-post could plausibly still be on its way.
	 */
	@Test
	void retiresARowWhoseOrderWasCancelledAndNeverRePosted() {
		NpcRound round = round();
		long cancelled = NOW + 60_000L;
		Map<String, Long> at = Map.of("ITEM", cancelled);
		long grace = NpcRound.REPOST_GRACE.toMillis();

		// Inside the grace the re-post is still expected, and dropping the row here would delete the
		// price the player cancelled in order to type.
		assertEquals(1, round.outstanding(List.of(), Set.of(), at, cancelled + grace - 1L).size());
		assertTrue(round.outstanding(List.of(), Set.of(), at, cancelled + grace).isEmpty());

		// The clock runs from the cancel, not from the round: a cancel late in a round still gets a
		// whole grace to be re-posted in.
		assertEquals(1, round.outstanding(List.of(), Set.of(), at, NOW + grace + 1L).size());
	}

	/**
	 * With no cancel seen, the row is counted from the round instead.
	 *
	 * <p>The round was frozen off orders that were resting, so its own open time is the last moment
	 * they are known to have been on the book. That covers a cancel whose chat line was missed.
	 */
	@Test
	void countsFromTheRoundWhenNoCancelWasSeen() {
		NpcRound round = round();
		long grace = NpcRound.REPOST_GRACE.toMillis();

		assertEquals(1, round.outstanding(List.of(), Set.of(), Map.of(), NOW + grace - 1L).size());
		assertTrue(round.outstanding(List.of(), Set.of(), Map.of(), NOW + grace).isEmpty());
	}

	/** A caller with no clock keeps the old behaviour: the row is held for the whole interval. */
	@Test
	void holdsAnEmptyRowForACallerThatTracksNoClock() {
		assertEquals(1, round().outstanding(List.of()).size());
	}

	/** Orders on other items say nothing about whether this row was worked. */
	@Test
	void ignoresOrdersOnOtherItems() {
		NpcRound round = round();

		assertFalse(round.complete(List.of(placed("OTHER", 820.1d, 500L, NOW + 60_000L))));
	}

	@Test
	void findsARowByItsItem() {
		assertTrue(round().rowFor("ITEM").isPresent());
		assertTrue(round().rowFor("MISSING").isEmpty());
	}
}
