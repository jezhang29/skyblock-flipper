package jeff.skyblockflipper.core.valuation;

import jeff.skyblockflipper.core.model.BazaarSample;
import jeff.skyblockflipper.core.model.ItemCatalog;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Buying on the bazaar to sell to an NPC only works because the exit price cannot move, so the one
 * thing worth measuring is whether the entry price stays low - and whether staying at the front of
 * the book costs more than the gap is worth.
 *
 * <p>Two mistakes would be invisible in production and are what most of these pin down. Testing the
 * bid rather than the price you would actually post at overstates every edge by one increment and
 * turns break-even products into opportunities. Counting downward bid moves as chase cost, or
 * counting hours nobody was watching, gets the cost of chasing wrong in whichever direction the
 * book happened to drift.
 */
class NpcEdgeHistoryTest {
	private static final String ITEM = "TEST_ITEM";
	private static final double NPC_PRICE = 100.0d;
	private static final Duration WINDOW = Duration.ofDays(3);
	private static final long STEP = Duration.ofMinutes(5).toMillis();

	/** Comfortably past {@link NpcEdge#MIN_SAMPLES}, so the figures under test are reported. */
	private static final int ENOUGH = NpcEdge.MIN_SAMPLES;

	private static final Instant NOW = Instant.parse("2026-08-10T00:00:00Z");

	private static final ItemCatalog CATALOG = new ItemCatalog(Map.of(
			ITEM, new ItemCatalog.Entry(ITEM, "Test Item", NPC_PRICE),
			"NO_NPC", new ItemCatalog.Entry("NO_NPC", "Unsellable", null)));

	private static NpcEdgeHistory history() {
		return new NpcEdgeHistory(CATALOG, NOW, WINDOW);
	}

	/** A live bid and nothing else: what the NPC route actually needs from the book. */
	private static BazaarSample sample(String productId, long timestamp, double bid) {
		return new BazaarSample(productId, timestamp, bid * 1.5d, bid, 1_000_000L, 900_000L);
	}

	/** {@code count} samples five minutes apart, oldest first, ending an hour before {@link #NOW}. */
	private static void fill(NpcEdgeHistory history, String productId, int count,
			java.util.function.IntToDoubleFunction bidAt) {
		long start = NOW.toEpochMilli() - Duration.ofHours(1).toMillis() - (long) count * STEP;

		for (int i = 0; i < count; i++) {
			history.append(sample(productId, start + i * STEP, bidAt.applyAsDouble(i)));
		}
	}

	private static NpcEdge edgeOf(NpcEdgeHistory history) {
		return history.snapshot().edgeFor(ITEM).orElseThrow();
	}

	@Test
	void aBidAlwaysUnderTheNpcPriceHoldsItsEdgeInEverySample() {
		NpcEdgeHistory history = history();
		fill(history, ITEM, ENOUGH, i -> 60.0d);

		NpcEdge edge = edgeOf(history);

		assertEquals(1.0d, edge.persistence(), 1e-9);
		// Posted at 60.1 against an NPC paying 100, so 39.9% of the exit price is margin.
		assertEquals(0.399d, edge.medianMarginRatio(), 1e-9);
		assertEquals(NPC_PRICE, edge.npcPrice(), 1e-9);
	}

	@Test
	void aBidAlwaysOverTheNpcPriceNeverHasAnEdge() {
		NpcEdgeHistory history = history();
		fill(history, ITEM, ENOUGH, i -> 150.0d);

		NpcEdge edge = edgeOf(history);

		assertEquals(0.0d, edge.persistence(), 1e-9);
		assertTrue(edge.medianMarginRatio() < 0.0d, "buying above the exit price is a loss");
	}

	@Test
	void theEdgeIsMeasuredAtThePostPriceAndNotAtTheBid() {
		// The whole trade is one increment narrower than the book makes it look: you cannot buy at
		// the bid, you outbid it. A bid of 99.95 sits under an NPC paying 100 and is still no trade,
		// because posting inside it costs 100.05.
		NpcEdgeHistory history = history();
		fill(history, ITEM, ENOUGH, i -> 99.95d);

		NpcEdge edge = edgeOf(history);

		assertEquals(0.0d, edge.persistence(), 1e-9);
		assertTrue(edge.medianMarginRatio() < 0.0d);
	}

	@Test
	void theMedianMarginIncludesTheSamplesThatHadNoEdgeAtAll() {
		// 80 samples deep under the NPC price and 120 well above it. Measured over its good half this
		// product quotes a 49.9% margin; measured honestly the typical sample is a loss, which is the
		// answer that stops an order slot going to a book that is usually unflippable.
		NpcEdgeHistory history = history();
		fill(history, ITEM, 200, i -> i < 80 ? 50.0d : 150.0d);

		NpcEdge edge = edgeOf(history);

		assertEquals(0.4d, edge.persistence(), 1e-9);
		assertEquals(-0.501d, edge.medianMarginRatio(), 1e-9);
	}

	@Test
	void reportsNothingUntilEnoughOfTheTapeBacksIt() {
		// A gap seen for an afternoon is not a standing feature of the book, and committing one of
		// 21 order slots to it on that evidence is exactly the mistake the bar exists to prevent.
		NpcEdgeHistory history = history();
		fill(history, ITEM, NpcEdge.MIN_SAMPLES - 1, i -> 60.0d);

		assertTrue(history.snapshot().edgeFor(ITEM).isEmpty());
		// Still counted as considered, so status can distinguish "no history" from "no product".
		assertEquals(1, history.snapshot().size());
		assertEquals(0, history.snapshot().productsWithMeasuredEdge());
	}

	@Test
	void chaseCostCountsUpwardMovesOnly() {
		// A bid that climbs a coin every sample is a coin of repricing every sample. Five minutes
		// apart, that is twelve coins an hour of chasing.
		NpcEdgeHistory history = history();
		fill(history, ITEM, ENOUGH, i -> 60.0d + i);

		NpcEdge edge = edgeOf(history);

		assertEquals(ENOUGH - 1, edge.intervals());
		assertEquals(12.0d, edge.bidDriftPerHour(), 1e-9);
	}

	@Test
	void aBidThatFallsBackCostsNothingToNotFollow() {
		// Sawtooth between 60 and 61: 100 steps up and 99 steps down across 200 samples. Chasing is
		// something you do when someone gets in front of you; a book that drops away from your
		// resting order leaves you at the front for free.
		NpcEdgeHistory history = history();
		fill(history, ITEM, 200, i -> 60.0d + (i % 2));

		NpcEdge edge = edgeOf(history);

		assertEquals(100.0d, edge.bidDriftPerHour() * edge.hoursObserved(), 1e-9,
				"only the hundred upward steps are a cost");
	}

	@Test
	void aBookThatDoesNotMoveCostsNothingToChase() {
		NpcEdgeHistory history = history();
		fill(history, ITEM, ENOUGH, i -> 60.0d);

		assertEquals(0.0d, edgeOf(history).bidDriftPerHour(), 1e-9);
	}

	@Test
	void timeTheClientWasClosedIsNotTimeSpentChasing() {
		// Two sittings either side of an eleven-hour gap, with the bid climbing a coin a sample
		// throughout. Counted end to end the gap swamps the denominator and a book that costs twelve
		// coins an hour to hold reads as costing three.
		NpcEdgeHistory history = history();
		long start = NOW.toEpochMilli() - Duration.ofHours(24).toMillis();

		for (int i = 0; i < 100; i++) {
			history.append(sample(ITEM, start + i * STEP, 60.0d + i));
		}

		long afterGap = start + 100 * STEP + Duration.ofHours(11).toMillis();

		for (int i = 0; i < 100; i++) {
			history.append(sample(ITEM, afterGap + i * STEP, 160.0d + i));
		}

		NpcEdge edge = edgeOf(history);

		// 99 intervals in each sitting; the eleven-hour one between them was not observed.
		assertEquals(198, edge.intervals());
		assertEquals(198.0d / 12.0d, edge.hoursObserved(), 1e-9);
		assertEquals(12.0d, edge.bidDriftPerHour(), 1e-9);
	}

	@Test
	void aSyncedBlockLandingOutOfOrderLosesOneIntervalAndNotTheRest() {
		// What the tape looks like after a sync: our own lines, then the collector's for the hours we
		// were closed, appended after them. The one step backwards in time is skipped. Everything
		// after it has to keep counting, or a merged day would contribute nothing at all.
		NpcEdgeHistory history = history();
		long ours = NOW.toEpochMilli() - Duration.ofHours(12).toMillis();
		long theirs = NOW.toEpochMilli() - Duration.ofHours(36).toMillis();

		for (int i = 0; i < 100; i++) {
			history.append(sample(ITEM, ours + i * STEP, 60.0d));
		}

		for (int i = 0; i < 100; i++) {
			history.append(sample(ITEM, theirs + i * STEP, 60.0d));
		}

		NpcEdge edge = edgeOf(history);

		assertEquals(200, edge.samples());
		assertEquals(198, edge.intervals(), "99 intervals in each block, none across the seam");
	}

	@Test
	void samplesOlderThanTheWindowAreNotPartOfTheMeasurement() {
		NpcEdgeHistory history = history();
		long stale = NOW.minus(Duration.ofDays(5)).toEpochMilli();

		for (int i = 0; i < 300; i++) {
			history.append(sample(ITEM, stale + i * STEP, 60.0d));
		}

		assertEquals(0, history.samplesRead());
		assertTrue(history.snapshot().isEmpty());
	}

	@Test
	void aBookWithNoSellOffersIsStillFlippableToAnNpc() {
		// Where PriceHistory needs both sides for a midpoint and skips one-sided books, this needs
		// the bid and nothing else. Dropping these would discard the thin, cheap products that carry
		// the largest percentage margins precisely because nobody bothers to trade them.
		NpcEdgeHistory history = history();
		long start = NOW.toEpochMilli() - Duration.ofHours(24).toMillis();

		for (int i = 0; i < ENOUGH; i++) {
			history.append(new BazaarSample(ITEM, start + i * STEP, 0.0d, 60.0d, 1L, 1L));
		}

		assertEquals(1.0d, edgeOf(history).persistence(), 1e-9);
	}

	@Test
	void aBidOfZeroIsAnEmptyBookAndNotAFreeItem() {
		NpcEdgeHistory history = history();
		fill(history, ITEM, ENOUGH, i -> 0.0d);

		assertEquals(0, history.samplesRead());
		assertTrue(history.snapshot().isEmpty());
	}

	@Test
	void productsNoNpcBuysAreNeverAccumulated() {
		// 816 of the bazaar's 2,123 products have an NPC price. Holding the samples of the other
		// 1,307 is what a median over three days would otherwise cost in heap.
		NpcEdgeHistory history = history();
		fill(history, "NO_NPC", ENOUGH, i -> 60.0d);
		fill(history, "NOT_IN_CATALOG", ENOUGH, i -> 60.0d);

		assertEquals(0, history.productsTracked());
		assertEquals(0, history.samplesRead());
	}

	@Test
	void persistenceAndChaseCostAreComparableOnTheSameScale() {
		// The decision the basket makes: an 8-hour cycle of chasing at 2 coins an hour costs 16% of
		// a 100-coin exit, which is more than a 15% margin is worth. Both sides of that comparison
		// have to be ratios of the NPC price or the units do not line up.
		NpcEdge edge = new NpcEdge(ITEM, NPC_PRICE, 0.97d, 0.15d, 2.0d, 20.0d, 240, 240);

		assertEquals(0.16d, edge.chaseCostRatio(Duration.ofHours(8)), 1e-9);
		assertTrue(edge.holdsEdge(0.95d));
		assertFalse(edge.holdsEdge(0.98d));
		assertEquals(0.0d, edge.chaseCostRatio(Duration.ZERO), 1e-9);
	}
}
