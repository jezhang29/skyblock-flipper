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
				NpcReprice.Order.of(id, id, postPrice - 10.0d, remaining),
				NpcReprice.Action.REPRICE,
				NPC_PRICE, postPrice, postPrice, 850.0d,
				(NPC_PRICE - postPrice) / NPC_PRICE, "outbid");
	}

	/** The same order, with a measured gain on it, which is what a round ranks and sums. */
	private static NpcReprice.Advice worth(String id, double gain) {
		NpcReprice.Advice advice = reprice(id, 900.0d, 1000L);

		return new NpcReprice.Advice(advice.order(), advice.action(), advice.npcPrice(),
				advice.bestBid(), advice.postPrice(), advice.chaseStop(), advice.marginRatio(),
				new NpcReprice.RepriceValue(1000.0d, gain, 2.0d, true), advice.reason());
	}

	private static NpcRound round(List<NpcReprice.Advice> advice) {
		return NpcRound.open(0L, NpcContext.DEFAULT_CHECK_IN, advice);
	}

	private static NpcReprice.Advice cancel(String id, double yourPrice, long remaining) {
		return new NpcReprice.Advice(
				NpcReprice.Order.of(id, id, yourPrice, remaining),
				NpcReprice.Action.CANCEL,
				NPC_PRICE, 900.0d, 900.1d, 850.0d, 0.1d, "past the stop");
	}

	private static NpcReprice.Advice hold(String id) {
		return new NpcReprice.Advice(
				NpcReprice.Order.of(id, id, 800.0d, 500L),
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

	/**
	 * Filled units nobody has collected qualify at any size, and lead the count.
	 *
	 * <p>Unlike everything else here that is not a forecast: the coins are made, and until the units
	 * are out of the order they cannot be carried to an NPC, so the flip is stalled behind a button.
	 */
	@Test
	void alwaysReportsUnitsWaitingToBeClaimed() {
		NpcReprice.Advice filled = new NpcReprice.Advice(
				new NpcReprice.Order("A", "A", 800.0d, 500L, 0L, 500L, 0L),
				NpcReprice.Action.HOLD,
				NPC_PRICE, 800.0d, 800.0d, 850.0d, 0.2d, "filled completely");

		NpcCheckIn.Due due = NpcCheckIn.due(List.of(filled, hold("B")), MIN_PROFIT).orElseThrow();

		assertEquals(1, due.claimCount());
		assertEquals(0, due.repriceCount());
		assertEquals(0, due.cancelCount());
		assertEquals(100_000.0d, due.claimable(), 1.0d);
	}

	/** An expired order is a cancel: same click, same freed slot, different reason. */
	@Test
	void countsAnExpiredOrderAsACancel() {
		NpcReprice.Advice expired = new NpcReprice.Advice(
				NpcReprice.Order.of("A", "A", 800.0d, 500L),
				NpcReprice.Action.EXPIRED,
				NPC_PRICE, 800.0d, 800.0d, 850.0d, 0.2d, "past the window");

		NpcCheckIn.Due due = NpcCheckIn.due(List.of(expired), MIN_PROFIT).orElseThrow();

		assertEquals(1, due.cancelCount());
		assertEquals(400_000L, due.capitalToFree());
	}

	/**
	 * On a clock, the reprices announced are the round's and not the book's.
	 *
	 * <p>The live review sees every outbid order, including ones the round did not freeze. Counting
	 * those here would interrupt the player with a number the list the notice opens does not
	 * contain, which is the mismatch the round exists to remove.
	 */
	@Test
	void countsTheRoundsRepricesRatherThanTheLiveReview() {
		List<NpcReprice.Advice> frozen = List.of(worth("A", 60_000.0d));
		NpcRound round = round(frozen);

		// The book has since moved past a second order, which this round is not asking about.
		List<NpcReprice.Advice> advice = List.of(worth("A", 60_000.0d), worth("B", 60_000.0d));

		NpcCheckIn.Due due = NpcCheckIn.due(advice, MIN_PROFIT, round).orElseThrow();

		assertEquals(1, due.repriceCount());
		assertEquals(60_000.0d, due.profitAtStake(), 1.0d);
	}

	/** An empty round with a claim in the advice still speaks: a claim never waits for a round. */
	@Test
	void stillReportsClaimsAndCancelsUnderAnEmptyRound() {
		NpcCheckIn.Due due = NpcCheckIn
				.due(List.of(cancel("A", 800.0d, 1L)), MIN_PROFIT, round(List.of()))
				.orElseThrow();

		assertEquals(0, due.repriceCount());
		assertEquals(1, due.cancelCount());
	}

	/** A round with nothing in it and nothing exempt beside it is not worth a chime. */
	@Test
	void saysNothingForAnEmptyRound() {
		assertTrue(NpcCheckIn.due(List.of(hold("A")), MIN_PROFIT, round(List.of())).isEmpty());
	}

	/** Null round is the old behaviour, for a caller that tracks no clock. */
	@Test
	void judgesTheBookDirectlyWithoutARound() {
		NpcCheckIn.Due due = NpcCheckIn
				.due(List.of(reprice("A", 900.0d, 1000L)), MIN_PROFIT, null)
				.orElseThrow();

		assertEquals(1, due.repriceCount());
		assertEquals(100_000.0d, due.profitAtStake(), 1.0d);
	}
}
