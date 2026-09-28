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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two ways an order loses the top of the book, which the probe exists to tell apart.
 *
 * <p>Measured over three days of bazaar tape, 68-80% of every upward move on the items this mod
 * picks was exactly 0.1 - the bazaar's own increment button - and all of those moves together
 * carried under 1% of the drift. So being outbid says nothing on its own: being outbid <i>by a
 * tenth of a coin</i> and being outbid by a competitor's whole valuation are opposite findings, and
 * only the second is something a premium could have prevented.
 */
class NpcProbeTest {
	private static final long START = 1_000_000L;
	private static final long MINUTE = 60_000L;

	private static NpcProbe probe() {
		return NpcProbe.opened("REVENANT_CATALYST", "Revenant Catalyst", 5000.0d, 250.0d, START);
	}

	@Test
	void aFreshProbeHasFoundNothing() {
		NpcProbe fresh = probe();

		assertFalse(fresh.everOutbid());
		assertFalse(fresh.nudgedOnly());
		assertEquals(0, fresh.retakes());
		assertEquals(0.0d, fresh.worstOverbid());
	}

	@Test
	void aBidUnderTheOrderLeavesItOnTop() {
		NpcProbe after = probe().sample(4800.0d, START + MINUTE);

		assertFalse(after.everOutbid());
		assertEquals(1, after.samples());
		assertEquals(1, after.atTop());
	}

	@Test
	void theOrderReadingBackATenthHighIsStillTheOrderItself() {
		// A buy order's price comes back off an escrow line rounded to the coin, so the probe's own
		// order can report either side of what was typed. Below the tolerance nobody outbid anybody.
		NpcProbe after = probe().sample(5000.0d + NpcProbe.TOLERANCE / 2.0d, START + MINUTE);

		assertFalse(after.everOutbid());
		assertEquals(0.0d, after.worstOverbid());
	}

	@Test
	void anIncrementClickIsRecordedAsANudgeRatherThanAReprice() {
		NpcProbe after = probe().sample(5000.1d, START + MINUTE);

		assertTrue(after.everOutbid());
		assertTrue(after.nudgedOnly());
		assertEquals(0.1d, after.worstOverbid(), 1e-9);
	}

	@Test
	void aCompetitorPricingTheItemThemselvesIsNotANudge() {
		NpcProbe after = probe().sample(5300.0d, START + MINUTE);

		assertTrue(after.everOutbid());
		assertFalse(after.nudgedOnly());
		assertEquals(300.0d, after.worstOverbid(), 1e-9);
	}

	@Test
	void theWorstOverbidSurvivesTheOrderGettingTheTopBack() {
		// The finding is what the market did at its worst, not what it happens to be doing now.
		NpcProbe after = probe()
				.sample(5300.0d, START + MINUTE)
				.sample(4900.0d, START + 2 * MINUTE);

		assertEquals(300.0d, after.worstOverbid(), 1e-9);
		assertFalse(after.nudgedOnly());
	}

	@Test
	void countsEachReturnToTheTopButNotStayingThere() {
		NpcProbe after = probe()
				.sample(5000.1d, START + MINUTE)          // nudged off
				.sample(4900.0d, START + 2 * MINUTE)      // got it back
				.sample(4900.0d, START + 3 * MINUTE)      // still there, not a second retake
				.sample(5000.1d, START + 4 * MINUTE)      // nudged again
				.sample(4900.0d, START + 5 * MINUTE);     // and back again

		assertEquals(2, after.retakes());
		assertTrue(after.nudgedOnly());
	}

	@Test
	void aProbeThatWasNeverOutbidHasNothingToRetake() {
		NpcProbe after = probe()
				.sample(4900.0d, START + MINUTE)
				.sample(4900.0d, START + 2 * MINUTE);

		assertEquals(0, after.retakes());
		assertEquals(1.0d, after.topShare());
	}

	@Test
	void theFirstOutbidIsTheOneReported() {
		NpcProbe after = probe()
				.sample(5000.1d, START + MINUTE)
				.sample(4900.0d, START + 2 * MINUTE)
				.sample(5000.1d, START + 10 * MINUTE);

		assertEquals(MINUTE, after.heldFor(START + 20 * MINUTE).toMillis());
	}

	@Test
	void theReportNamesWhichKindOfOutbidHappened() {
		String nudged = probe().sample(5000.1d, START + MINUTE).report(START + MINUTE);
		String repriced = probe().sample(5300.0d, START + MINUTE).report(START + MINUTE);

		assertTrue(nudged.contains("the +0.1 button"), nudged);
		assertTrue(repriced.contains("a real reprice"), repriced);
	}

	@Test
	void theReportSaysNothingAboutOutbidsBeforeThereAreAny() {
		String clean = probe().sample(4900.0d, START + MINUTE).report(START + MINUTE);

		assertTrue(clean.contains("still on top"), clean);
		assertFalse(clean.contains("outbid by"), clean);
	}

	/**
	 * The failure this fixes reported success. A filled order leaves the book, so the top bid falls
	 * back under the probe's price and stays there - an order that filled in three minutes would
	 * otherwise read as one that held the top all night.
	 */
	@Test
	void aFillStopsTheProbeCountingTheBookItIsNoLongerIn() {
		NpcProbe after = probe()
				.sample(4900.0d, START + MINUTE)
				.filled(START + 3 * MINUTE)
				.sample(4000.0d, START + 60 * MINUTE)
				.sample(4000.0d, START + 120 * MINUTE);

		assertTrue(after.isFilled());
		assertEquals(1, after.samples(), "polls after the fill are not evidence about the order");
		assertEquals(3 * MINUTE, after.heldFor(START + 500 * MINUTE).toMillis());
		assertEquals(3 * MINUTE, after.age(START + 500 * MINUTE).toMillis());
	}

	@Test
	void aFillIsReportedAsTheOutcomeItIs() {
		String report = probe()
				.sample(4900.0d, START + MINUTE)
				.filled(START + 3 * MINUTE)
				.report(START + 90 * MINUTE);

		assertTrue(report.contains("FILLED"), report);
		assertTrue(report.contains("3m"), report);
	}

	@Test
	void anOrderOutbidFirstAndFilledLaterStillReportsTheFill() {
		String report = probe()
				.sample(5000.1d, START + MINUTE)
				.filled(START + 30 * MINUTE)
				.report(START + 90 * MINUTE);

		assertTrue(report.contains("the +0.1 button"), report);
		assertTrue(report.contains("then FILLED"), report);
	}

	@Test
	void theFirstFillIsTheOneKept() {
		NpcProbe after = probe().filled(START + MINUTE).filled(START + 50 * MINUTE);

		assertEquals(MINUTE, after.age(START + 100 * MINUTE).toMillis());
	}
}
