package jeff.skyblockflipper.core.valuation;

import jeff.skyblockflipper.core.item.DecodedItem;
import jeff.skyblockflipper.core.item.ItemDecoder;
import jeff.skyblockflipper.core.model.EndedAuction;
import jeff.skyblockflipper.core.tape.SalesTape;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How much is another day of tape worth? Run with {@code ./gradlew test -PtapeBacktest}.
 *
 * <p>Opt-in for the same reason as {@link PetLevelBacktestTest}: it needs a recorded tape, which
 * is hundreds of megabytes of somebody's {@code run/} directory and is not in the repository.
 *
 * <p>This exists to answer a question that only came up once the collector moved to a server that
 * runs continuously: retention and {@code valuationWindowDays} are both settings, and neither had
 * ever been measured. Coverage is the thing to watch, not error. A wider window cannot make a busy
 * item's median much better - {@code MAX_SAMPLES_PER_KEY} caps a popular configuration at 200
 * samples and the extra days fall off the front - but it is the only thing that can push a thin
 * configuration over {@link ValueEstimate#MIN_SAMPLES}, and thin configurations are where the
 * unpriceable majority of traded coins lives.
 *
 * <p>Method: hold out the newest {@code HOLDOUT_HOURS} of the tape, train a model on each
 * candidate window ending where the holdout starts, and price the held-out sales with each. Every
 * window is scored on the same sales, so the numbers are comparable across rows. Coverage is
 * reported by count and by coins, because the two answer different questions - the coin figure is
 * the one that says whether the money is reachable.
 */
@EnabledIfSystemProperty(named = "skyblockflipper.tapeBacktest", matches = "true")
class ValuationWindowBacktestTest {
	/** Windows to compare, in hours. 48 is the shipped default for {@code valuationWindowDays}. */
	private static final int[] WINDOW_HOURS = {6, 12, 24, 48, 72, 120, 240};

	/** Newest slice of tape held out of every model and priced by all of them. */
	private static final int HOLDOUT_HOURS = 6;

	/** "Every day file on disk", without asking {@code LocalDate} to subtract a decade of days. */
	private static final int ALL_DAYS = 365;

	private static final String DEFAULT_TAPE_DIR = "run/config/skyblock-flipper/tape";

	@Test
	void aWiderValuationWindowBuysCoverage() throws Exception {
		SalesTape tape = new SalesTape(tapeDir(), Integer.MAX_VALUE);

		long tapeEnd = latestSale(tape);
		assertTrue(tapeEnd > 0L, "no sales on the tape at " + tapeDir()
				+ " - point -PtapeDir at a directory with day files in it");

		long splitAt = tapeEnd - Duration.ofHours(HOLDOUT_HOURS).toMillis();
		Instant trainEnd = Instant.ofEpochMilli(splitAt);

		// One pass over the tape feeds every builder. Each drops what falls outside its own
		// window, so the narrow ones cost little beyond the decode, which they share anyway.
		Map<Integer, FairValueModel.Builder> builders = new LinkedHashMap<>();

		for (int hours : WINDOW_HOURS) {
			builders.put(hours, FairValueModel.builder(trainEnd, Duration.ofHours(hours)));
		}

		List<EndedAuction> holdout = new ArrayList<>();

		int read = tape.forEachRecent(ALL_DAYS, sale -> {
			if (sale.timestamp() >= splitAt) {
				if (sale.bin() && sale.price() > 0L) {
					holdout.add(sale);
				}
			} else {
				builders.values().forEach(b -> b.add(sale));
			}
		});

		assertFalse(holdout.isEmpty(), "the newest " + HOLDOUT_HOURS + " hours of the tape hold no "
				+ "BIN sales - is the tape stale?");

		System.out.printf("%nvaluation window backtest: %,d sales on tape, %,d BIN sales held out "
				+ "of the newest %dh%n", read, holdout.size(), HOLDOUT_HOURS);
		System.out.printf("%-8s %9s %9s  %9s %9s  %8s  %8s%n",
				"window", "trained", "configs", "cover-n", "cover-$", "median", "±20%");

		Map<Integer, Score> scores = new LinkedHashMap<>();

		for (Map.Entry<Integer, FairValueModel.Builder> entry : builders.entrySet()) {
			FairValueModel model = entry.getValue().build();
			Score score = score(model, holdout);
			scores.put(entry.getKey(), score);

			System.out.printf("%5dh   %,9d %,9d  %8.1f%% %8.1f%%  %8.3f  %7.1f%%%n",
					entry.getKey(), model.salesConsidered(), model.pricedConfigurations(),
					score.coverageByCount() * 100.0d, score.coverageByCoins() * 100.0d,
					score.medianError(), score.within(0.2d) * 100.0d);
		}

		Score shipped = scores.get(48);
		Score widest = scores.get(WINDOW_HOURS[WINDOW_HOURS.length - 1]);

		// Coverage is monotone in the window by construction - a sale inside a narrow window is
		// inside every wider one, and no rung is ever removed. If that ever fails, the model is
		// dropping samples somewhere it should not.
		assertTrue(widest.covered() >= shipped.covered(),
				"a wider window priced fewer held-out sales (" + widest.covered() + ") than the "
						+ "shipped 48h window (" + shipped.covered() + "), which cannot happen "
						+ "unless samples are being discarded");
	}

	/**
	 * When the newest sale on the tape happened.
	 *
	 * <p>Read rather than assumed to be "now": the tape is fetched from the collector on demand,
	 * so it is routinely hours behind the wall clock, and a holdout measured from now would be
	 * empty.
	 */
	private static long latestSale(SalesTape tape) throws IOException {
		long[] latest = {0L};
		tape.forEachRecent(ALL_DAYS, sale -> latest[0] = Math.max(latest[0], sale.timestamp()));
		return latest[0];
	}

	private static Score score(FairValueModel model, List<EndedAuction> holdout) {
		Score score = new Score();

		for (EndedAuction sale : holdout) {
			Optional<DecodedItem> decoded = ItemDecoder.decode(sale.itemBytes());

			if (decoded.isEmpty()) {
				continue;
			}

			DecodedItem item = decoded.get();
			double actual = (double) sale.price() / Math.max(1, item.count());
			Optional<ValueEstimate> estimate = model.valueOf(item);

			score.seen(sale.price());

			if (estimate.isEmpty() || estimate.get().median() <= 0.0d) {
				continue;
			}

			score.priced(sale.price(), Math.abs(Math.log(estimate.get().median() / actual)));
		}

		return score;
	}

	private static Path tapeDir() {
		return Path.of(System.getProperty("skyblockflipper.tapeDir", DEFAULT_TAPE_DIR));
	}

	/** Coverage and accuracy of one model over the held-out sales. */
	private static final class Score {
		private final List<Double> errors = new ArrayList<>();
		private int seen;
		private long seenCoins;
		private long pricedCoins;

		void seen(long price) {
			seen++;
			seenCoins += price;
		}

		void priced(long price, double error) {
			errors.add(error);
			pricedCoins += price;
		}

		int covered() {
			return errors.size();
		}

		double coverageByCount() {
			return seen == 0 ? 0.0d : errors.size() / (double) seen;
		}

		double coverageByCoins() {
			return seenCoins == 0L ? 0.0d : pricedCoins / (double) seenCoins;
		}

		double medianError() {
			if (errors.isEmpty()) {
				return Double.NaN;
			}

			List<Double> sorted = errors.stream().sorted().toList();
			return sorted.get(sorted.size() / 2);
		}

		double within(double tolerance) {
			double bound = Math.log(1.0d + tolerance);
			return errors.stream().filter(e -> e <= bound).count() / (double) Math.max(1, errors.size());
		}
	}
}
