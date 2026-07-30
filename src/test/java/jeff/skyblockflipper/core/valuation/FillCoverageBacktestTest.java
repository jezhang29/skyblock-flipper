package jeff.skyblockflipper.core.valuation;

import jeff.skyblockflipper.core.tape.BazaarTape;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Does the fill model actually measure anything on the recorded bazaar tape? Run with
 * {@code ./gradlew test -PtapeBacktest}.
 *
 * <p>{@link FillStats#isUsable()} needs {@link PriceTrend#MIN_SAMPLES} intervals, which at the
 * 5-minute tape cadence is an hour of *uninterrupted* recording. That bar was never cleared while
 * the collector lived on a laptop: on the tape it was written against, 0 of 1570 products were
 * usable, so every bazaar plan fell back to the assumed volume share the fill model exists to
 * replace. The collector now runs on a server that does not close, so this re-asks the question
 * against real data rather than asserting an offline invariant.
 *
 * <p>Points at {@code bazaar-tape} beside the sales tape by default; override with
 * {@code -PbazaarTapeDir=...}.
 */
@EnabledIfSystemProperty(named = "skyblockflipper.tapeBacktest", matches = "true")
class FillCoverageBacktestTest {
	private static final String DEFAULT_TAPE_DIR = "run/config/skyblock-flipper/bazaar-tape";

	/** The shipped {@code trendWindowHours} default, so this measures what a player would get. */
	private static final int WINDOW_HOURS = 24;

	@Test
	void theFillModelHasEnoughContiguousSamplesToMeasureWith() throws Exception {
		BazaarTape tape = new BazaarTape(tapeDir(), Integer.MAX_VALUE);
		PriceHistory history = new PriceHistory(Duration.ofHours(WINDOW_HOURS));

		int days = (int) Math.max(1L, Math.ceilDiv(WINDOW_HOURS, 24));
		int read = tape.forEachRecent(days, history::append);

		assertTrue(read > 0, "no bazaar samples on the tape at " + tapeDir()
				+ " - point -PbazaarTapeDir at a directory with day files in it");

		TrendSnapshot snapshot = history.snapshot();

		List<FillStats> measured = snapshot.fills().values().stream()
				.filter(FillStats::isUsable)
				.sorted(Comparator.comparingDouble(FillStats::hoursObserved).reversed())
				.toList();

		System.out.printf("%nfill coverage over %,d samples across %d products, %dh window%n",
				read, history.productsTracked(), WINDOW_HOURS);
		System.out.printf("  products with a usable fill measurement: %d of %d (%.1f%%)%n",
				measured.size(), history.productsTracked(),
				100.0d * measured.size() / Math.max(1, history.productsTracked()));

		measured.stream().limit(5).forEach(stats -> System.out.printf(
				"  %-28s %5.2f outbids/h  %5.2f undercuts/h over %5.1fh (%d intervals)%n",
				stats.productId(), stats.bidLiftsPerHour(), stats.askDropsPerHour(),
				stats.hoursObserved(), stats.intervals()));

		assertTrue(!measured.isEmpty(),
				"not one product cleared " + PriceTrend.MIN_SAMPLES + " contiguous samples, so "
						+ "every bazaar plan is still sized from the assumed volume share. Either "
						+ "the tape has a gap in its newest " + WINDOW_HOURS + " hours or the "
						+ "collector was not running.");
	}

	private static Path tapeDir() {
		return Path.of(System.getProperty("skyblockflipper.bazaarTapeDir", DEFAULT_TAPE_DIR));
	}
}
