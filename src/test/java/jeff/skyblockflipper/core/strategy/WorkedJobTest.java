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

import jeff.skyblockflipper.core.track.CaptureRecord;
import jeff.skyblockflipper.core.track.CaptureSession;
import jeff.skyblockflipper.core.track.TrackedOrder;
import jeff.skyblockflipper.core.track.TradeTracker;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Progress on a worked flip, measured against the recorded session rather than against orders
 * invented for the test.
 *
 * <p>The panel's whole claim is that a step marked done really happened, so the orders it is read
 * from are the real ones: an hour of trading with a partial fill, a cancel, three offers on one
 * item at once, and one order that left the menu with nothing said about it.
 */
class WorkedJobTest {
	private static final String ME = "Bee__Bot";

	private static List<TrackedOrder> orders;

	@BeforeAll
	static void replayTheSession() throws IOException {
		try (InputStream in = WorkedJobTest.class.getResourceAsStream("/trade-capture-sample.jsonl")) {
			CaptureSession session = CaptureSession.read(new InputStreamReader(in,
					StandardCharsets.UTF_8));
			TradeTracker tracker = new TradeTracker(ME);

			for (CaptureRecord record : session.records()) {
				tracker.accept(record);
			}

			orders = tracker.orders();
		}
	}

	private static WorkedJob.Step step(WorkedJob.Stage stage, String itemId) {
		return new WorkedJob.Step(stage, stage.label(), itemId, itemId, 1.0d, 10L, "10", 0);
	}

	private static WorkedJob job(WorkedJob.Step... steps) {
		return new WorkedJob(StrategyKind.CRAFT, "OUT", "Out", List.of(steps), 0L, 0.0d, "");
	}

	@Test
	void aSellOfferThatFilledAndWasCollectedReadsAsDone() {
		// Slimeball sold across four offers in the session, and every one of them left the book
		// having filled. Nothing is resting on it by the end.
		WorkedJob.Step sell = step(WorkedJob.Stage.SELL_OFFER, "SLIME_BALL");
		WorkedJob.Progress progress = job(sell).progressOf(sell, orders);

		assertEquals(WorkedJob.State.DONE, progress.state());
		assertTrue(progress.filled() > 0L, "a done step has units behind it");
	}

	@Test
	void theBuySideIsNotReadFromTheSellSide() {
		// The same item, the other leg. Reading either side's orders for both is how a craft would
		// mark its materials bought because it had sold something with the same id.
		WorkedJob.Step buy = step(WorkedJob.Stage.BUY_ORDER, "SLIME_BALL");

		assertEquals(WorkedJob.State.TODO, job(buy).progressOf(buy, orders).state());
	}

	@Test
	void anItemNothingWasTradedOnIsStillToDo() {
		WorkedJob.Step buy = step(WorkedJob.Stage.BUY_ORDER, "ENCHANTED_MITHRIL");

		assertEquals(WorkedJob.State.TODO, job(buy).progressOf(buy, orders).state());
	}

	@Test
	void theAnvilAndTheCraftingBenchLeaveNoTrace() {
		// Nothing on the bazaar records a merge, so guessing at one would be inventing evidence.
		WorkedJob.Step craft = step(WorkedJob.Stage.TRANSFORM, "SLIME_BALL");

		assertEquals(WorkedJob.State.UNTRACKED, job(craft).progressOf(craft, orders).state());
		assertEquals("   ", job(craft).progressOf(craft, orders).badge(),
				"an untracked badge is blank, and the same width as the others");
	}

	@Test
	void trackingSwitchedOffMarksNothingRatherThanGuessing() {
		WorkedJob.Step sell = step(WorkedJob.Stage.SELL_OFFER, "SLIME_BALL");

		assertEquals(WorkedJob.State.UNTRACKED, job(sell).progressOf(sell, List.of()).state());
	}

	@Test
	void onlyTheStepsAnOrderCouldShowAreCounted() {
		WorkedJob job = job(
				step(WorkedJob.Stage.BUY_ORDER, "SLIME_BALL"),
				step(WorkedJob.Stage.TRANSFORM, "OUT"),
				step(WorkedJob.Stage.SELL_OFFER, "SLIME_BALL"));

		// Two of the three, so the count a player reads is out of what can be known rather than out
		// of a total that includes a step nothing will ever tick.
		assertEquals(2, job.trackableCount());
		assertEquals(1, job.doneCount(orders));
	}

	@Test
	void aSpreadIsTwoStepsOnOneItem() {
		FlipCandidate candidate = new FlipCandidate("SLIME_BALL", "Slimeball",
				StrategyKind.BAZAAR_SPREAD, 10.0d, 12.0d, 1.5d, 500L, 5_000L, 900.0d, 0.7d,
				List.of(), List.of());

		WorkedJob job = WorkedJob.ofSpread("SLIME_BALL", "Slimeball", candidate, 71_680L);

		assertEquals(2, job.steps().size());
		assertEquals(WorkedJob.Stage.BUY_ORDER, job.steps().get(0).stage());
		assertEquals(10.0d, job.steps().get(0).price());
		assertEquals(WorkedJob.Stage.SELL_OFFER, job.steps().get(1).stage());
		assertEquals(12.0d, job.steps().get(1).price());
		assertEquals(5_000L, job.capital());
		assertTrue(job.note().isEmpty());
	}

	@Test
	void aFlipThatStoppedClearingKeepsItsNameAndSaysWhy() {
		// The panel draws this rather than vanishing: a job that disappears is indistinguishable
		// from one that broke, and the player may have coins resting on it.
		WorkedJob job = WorkedJob.ofSpread("SLIME_BALL", "Slimeball", null, 71_680L);

		assertEquals("Slimeball", job.displayName());
		assertTrue(job.steps().isEmpty());
		assertFalse(job.note().isEmpty());
	}

	@Test
	void anUnnamedStalledFlipFallsBackToItsIdRatherThanToNothing() {
		assertEquals("SLIME_BALL", WorkedJob.ofCraft("SLIME_BALL", null, null).displayName());
	}
}
