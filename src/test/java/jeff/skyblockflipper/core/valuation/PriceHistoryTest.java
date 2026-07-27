package jeff.skyblockflipper.core.valuation;

import jeff.skyblockflipper.core.model.BazaarDailyStat;
import jeff.skyblockflipper.core.model.BazaarSample;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The trend indicators exist to answer one question a live order book cannot: which way is this
 * going? Getting the sign wrong is worse than having no answer, because the whole point is to stop
 * the strategy market-making into a decline.
 *
 * <p>So most of these tests are about direction and about the honest degenerate cases - too few
 * samples, a series younger than the sub-window - where the right answer is "no signal" rather
 * than a confident number derived from noise.
 */
class PriceHistoryTest {
	private static final String ITEM = "TEST_ITEM";
	private static final Duration WINDOW = Duration.ofHours(24);
	private static final long STEP = Duration.ofMinutes(5).toMillis();

	/** Ask and bid straddle the midpoint symmetrically, so {@code mid()} is exactly the input. */
	private static BazaarSample sample(long timestamp, double mid) {
		return new BazaarSample(ITEM, timestamp, mid * 1.01d, mid * 0.99d, 1_000_000L, 900_000L);
	}

	/** Samples five minutes apart, oldest first, ending about now. */
	private static PriceHistory historyOf(double... mids) {
		PriceHistory history = new PriceHistory(WINDOW);
		long start = Instant.now().toEpochMilli() - (long) mids.length * STEP;

		for (int i = 0; i < mids.length; i++) {
			history.append(sample(start + i * STEP, mids[i]));
		}

		return history;
	}

	private static double[] ramp(int count, double from, double to) {
		double[] out = new double[count];

		for (int i = 0; i < count; i++) {
			out[i] = from + (to - from) * i / (count - 1.0d);
		}

		return out;
	}

	/** Flat but not perfectly so: a zero-variance series has no scale to measure a spike against. */
	private static double[] noisyFlat(int count, double level) {
		double[] out = new double[count];

		for (int i = 0; i < count; i++) {
			out[i] = level * (i % 2 == 0 ? 1.001d : 0.999d);
		}

		return out;
	}

	private static PriceTrend trendOf(PriceHistory history) {
		return history.snapshot().trendFor(ITEM).orElseThrow();
	}

	@Test
	void reportsNegativeDriftWhenThePriceHasBeenFalling() {
		PriceTrend trend = trendOf(historyOf(ramp(288, 100.0d, 80.0d)));

		assertTrue(trend.drift() < 0.0d, "expected negative drift but got " + trend.drift());
		assertTrue(trend.isFalling(0.01d));
		assertFalse(trend.isRising(0.01d));
	}

	@Test
	void reportsPositiveDriftWhenThePriceHasBeenRising() {
		PriceTrend trend = trendOf(historyOf(ramp(288, 80.0d, 100.0d)));

		assertTrue(trend.drift() > 0.0d, "expected positive drift but got " + trend.drift());
		assertTrue(trend.isRising(0.01d));
		assertFalse(trend.isFalling(0.01d));
	}

	@Test
	void reportsNoDriftOnAFlatMarket() {
		PriceTrend trend = trendOf(historyOf(noisyFlat(288, 100.0d)));

		assertEquals(0.0d, trend.drift(), 0.005d);
		assertFalse(trend.isFalling(0.01d));
		assertFalse(trend.isRising(0.01d));
	}

	@Test
	void aSeriesYoungerThanTheSubWindowReportsNoSignalRatherThanAGuess() {
		// Twenty samples is 100 minutes, well inside the 3-hour sub-window, so both averages cover
		// the same points. Reporting a trend here would be inventing one from a handful of ticks.
		PriceTrend trend = trendOf(historyOf(ramp(20, 50.0d, 150.0d)));

		assertEquals(0.0d, trend.drift(), 1e-9d);
		assertEquals(trend.longAverage(), trend.shortAverage(), 1e-9d);
	}

	@Test
	void withholdsATrendUntilEnoughSamplesBackIt() {
		PriceHistory history = historyOf(ramp(PriceTrend.MIN_SAMPLES - 1, 100.0d, 50.0d));

		// The series exists, but not enough of it to be worth reading.
		assertTrue(history.snapshot().trendFor(ITEM).isEmpty());
		assertFalse(history.isEmpty());
	}

	@Test
	void anUnknownProductHasNoTrend() {
		assertTrue(historyOf(ramp(288, 100.0d, 90.0d)).snapshot().trendFor("NOT_TRACKED").isEmpty());
		assertTrue(TrendSnapshot.empty().trendFor(ITEM).isEmpty());
	}

	@Test
	void flagsAnAbruptMoveOutsideTheSeriesOwnRange() {
		double[] mids = noisyFlat(288, 100.0d);
		mids[mids.length - 1] = 150.0d;

		PriceTrend trend = trendOf(historyOf(mids));

		assertTrue(trend.isSpiking(3.0d),
				"expected a spike but sigma was " + trend.spikeSigma());
	}

	@Test
	void doesNotFlagASteadyClimbAsASpike() {
		// The case that forced spikes to be scaled by level dispersion rather than by return
		// volatility: a smooth ramp has almost identical returns sample to sample, so its return
		// volatility is nearly zero and dividing by it called every drifting item a spike.
		PriceTrend trend = trendOf(historyOf(ramp(288, 100.0d, 105.0d)));

		assertFalse(trend.isSpiking(3.0d),
				"a slow 5% climb is not a spike, but sigma was " + trend.spikeSigma());
		assertTrue(trend.dispersion() > trend.volatility(),
				"a smooth trend should range far more than it jitters");
	}

	@Test
	void aPriceThatHasNeverMovedCannotProduceASpike() {
		double[] mids = new double[288];
		java.util.Arrays.fill(mids, 100.0d);

		// No dispersion means no scale to measure a move against, and dividing by it would report
		// an infinite sigma the moment the series ever printed anything else.
		PriceTrend trend = trendOf(historyOf(mids));

		assertEquals(0.0d, trend.dispersion(), 1e-12d);
		assertEquals(0.0d, trend.spikeSigma(), 1e-12d);
		assertFalse(trend.isSpiking(3.0d));
	}

	@Test
	void forgetsSamplesThatHaveFallenOutOfTheWindow() {
		// Two days of a steady climb from 0 to 100. Only the last day should survive, so the
		// average must sit near 75 rather than near the 50 a full-history average would give.
		PriceTrend trend = trendOf(historyOf(ramp(576, 0.0d, 100.0d)));

		assertTrue(trend.longAverage() > 70.0d && trend.longAverage() < 80.0d,
				"expected roughly the last day's average, got " + trend.longAverage());
		assertTrue(trend.samples() <= 300,
				"the ring must stay bounded, but held " + trend.samples());
	}

	@Test
	void ignoresOneSidedBooksBecauseTheyHaveNoMidpoint() {
		PriceHistory history = new PriceHistory(WINDOW);
		long now = Instant.now().toEpochMilli();

		for (int i = 0; i < 50; i++) {
			history.append(new BazaarSample(ITEM, now + i * STEP, 100.0d, 0.0d, 1L, 1L));
		}

		assertTrue(history.isEmpty());
	}

	@Test
	void multiDayReferenceIsAMedianOfDailyMediansNotAMeanOfEverything() {
		PriceHistory history = historyOf(ramp(288, 100.0d, 100.0d));

		// One manipulated day should not drag the reference the manipulation is detected against.
		history.setDailyStats(List.of(
				new BazaarDailyStat(ITEM, "2026-07-20", 100.0d, 95.0d, 105.0d, 288),
				new BazaarDailyStat(ITEM, "2026-07-21", 110.0d, 105.0d, 115.0d, 288),
				new BazaarDailyStat(ITEM, "2026-07-22", 900.0d, 800.0d, 950.0d, 288)));

		assertEquals(110.0d, history.snapshot().dailyMedianFor(ITEM).orElseThrow(), 1e-9d);
	}

	@Test
	void reportsNoMultiDayReferenceBeforeAnyDayHasCompleted() {
		assertTrue(historyOf(ramp(288, 100.0d, 90.0d)).snapshot().dailyMedianFor(ITEM).isEmpty());
	}

	@Test
	void countsResidentSamplesForStatusReporting() {
		TrendSnapshot snapshot = historyOf(ramp(100, 100.0d, 110.0d)).snapshot();

		assertEquals(1, snapshot.size());
		assertEquals(100, snapshot.samples());
		assertEquals(WINDOW, snapshot.window());
	}
}
