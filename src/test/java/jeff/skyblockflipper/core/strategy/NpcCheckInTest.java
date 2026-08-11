package jeff.skyblockflipper.core.strategy;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * When the mod is allowed to interrupt you.
 *
 * <p>The reminder is the only thing in the mod that speaks unasked, so the rule that decides
 * whether it speaks is worth pinning: a reminder that fires for nothing is ignored by the time one
 * fires for something.
 */
class NpcCheckInTest {
	private static final double NPC_PRICE = 1000.0d;
	private static final double MIN_PROFIT = 50_000.0d;

	/** An order outbid to {@code postPrice}, still inside the stop. */
	private static NpcReprice.Advice reprice(String id, double postPrice, long remaining) {
		return new NpcReprice.Advice(
				new NpcReprice.Order(id, id, postPrice - 10.0d, remaining),
				NpcReprice.Action.REPRICE,
				NPC_PRICE, postPrice, postPrice, 850.0d,
				(NPC_PRICE - postPrice) / NPC_PRICE, "outbid");
	}

	private static NpcReprice.Advice cancel(String id, double yourPrice, long remaining) {
		return new NpcReprice.Advice(
				new NpcReprice.Order(id, id, yourPrice, remaining),
				NpcReprice.Action.CANCEL,
				NPC_PRICE, 900.0d, 900.1d, 850.0d, 0.1d, "past the stop");
	}

	private static NpcReprice.Advice hold(String id) {
		return new NpcReprice.Advice(
				new NpcReprice.Order(id, id, 800.0d, 500L),
				NpcReprice.Action.HOLD,
				NPC_PRICE, 800.0d, 800.0d, 850.0d, 0.2d, "top of the book");
	}

	@Test
	void saysNothingWhenEveryOrderIsStillOnTop() {
		assertTrue(NpcCheckIn.due(List.of(hold("A"), hold("B")), MIN_PROFIT).isEmpty());
	}

	@Test
	void saysNothingWithNoOrdersAtAll() {
		assertTrue(NpcCheckIn.due(List.of(), MIN_PROFIT).isEmpty());
	}

	/** One 5k reprice is not worth walking back to the bazaar for. */
	@Test
	void ignoresARepriceUnderTheProfitFloor() {
		assertTrue(NpcCheckIn.due(List.of(reprice("A", 950.0d, 100L)), MIN_PROFIT).isEmpty());
	}

	/**
	 * A check-in settles every order in one trip, so the reprices are judged together. Eight orders
	 * worth 10k each is a trip worth making even though no single one of them is.
	 */
	@Test
	void sumsRepricesRatherThanJudgingThemOneAtATime() {
		List<NpcReprice.Advice> advice = List.of(
				reprice("A", 900.0d, 100L),
				reprice("B", 900.0d, 100L),
				reprice("C", 900.0d, 100L),
				reprice("D", 900.0d, 100L),
				reprice("E", 900.0d, 100L),
				reprice("F", 900.0d, 100L));

		Optional<NpcCheckIn.Due> due = NpcCheckIn.due(advice, MIN_PROFIT);

		assertTrue(due.isPresent());
		assertEquals(6, due.get().repriceCount());
		assertEquals(60_000.0d, due.get().profitAtStake(), 1.0d);
	}

	/**
	 * A cancel qualifies at any size. It is not a profit opportunity at all - it is coins parked in
	 * a trade that can no longer pay, holding the resource the whole strategy is limited by.
	 */
	@Test
	void alwaysReportsACancelHoweverSmall() {
		Optional<NpcCheckIn.Due> due = NpcCheckIn.due(List.of(cancel("A", 800.0d, 1L)), MIN_PROFIT);

		assertTrue(due.isPresent());
		assertEquals(1, due.get().cancelCount());
		assertEquals(0, due.get().repriceCount());
		assertEquals(800L, due.get().capitalToFree());
	}

	/** Held orders are counted by neither figure, so the line never overstates the work. */
	@Test
	void countsOnlyWhatNeedsAClick() {
		List<NpcReprice.Advice> advice = List.of(
				hold("A"), reprice("B", 900.0d, 1000L), hold("C"), cancel("D", 700.0d, 200L));

		NpcCheckIn.Due due = NpcCheckIn.due(advice, MIN_PROFIT).orElseThrow();

		assertEquals(1, due.repriceCount());
		assertEquals(1, due.cancelCount());
		assertEquals(2, due.orders());
		assertEquals(100_000.0d, due.profitAtStake(), 1.0d);
		assertEquals(140_000L, due.capitalToFree());
	}

	/** A cancel next to cheap reprices carries the whole notice, and the reprices ride along. */
	@Test
	void aCancelCarriesRepricesThatWouldNotHaveQualifiedAlone() {
		List<NpcReprice.Advice> advice = List.of(reprice("A", 950.0d, 10L), cancel("B", 800.0d, 5L));

		NpcCheckIn.Due due = NpcCheckIn.due(advice, MIN_PROFIT).orElseThrow();

		assertEquals(1, due.repriceCount());
		assertEquals(1, due.cancelCount());
		assertFalse(due.profitAtStake() >= MIN_PROFIT);
	}
}
