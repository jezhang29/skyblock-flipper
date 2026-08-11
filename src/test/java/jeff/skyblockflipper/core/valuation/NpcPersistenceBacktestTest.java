package jeff.skyblockflipper.core.valuation;

import jeff.skyblockflipper.core.api.HypixelApi;
import jeff.skyblockflipper.core.model.BazaarProduct;
import jeff.skyblockflipper.core.model.BazaarSample;
import jeff.skyblockflipper.core.model.ItemCatalog;
import jeff.skyblockflipper.core.tape.BazaarTape;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.ToDoubleFunction;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Does the 95% persistence filter still predict anything? Run with
 * {@code ./gradlew test -PtapeBacktest}.
 *
 * <p>{@code NpcFlipStrategy} refuses to rest an order on a gap that held in under 95% of taped
 * samples, and that threshold is the one parameter in {@code docs/npc-flipping.md} justified by a
 * holdout rather than by a sweep. A sweep would say the filter costs 17% of the profit, because a
 * sweep books every planned unit as filled at the quoted price and is structurally blind to the
 * item whose gap has closed by the time you act on it. So this exists: it is the only thing that
 * can notice the day the filter stops earning its place, and it fails loudly rather than leaving a
 * 95 in a constant nobody re-derives.
 *
 * <p><b>The measurement.</b> Pick a cutoff. Build persistence from the tape <em>before</em> it,
 * through the same {@link NpcEdgeHistory} the mod runs. Post a top-of-book buy order at the cutoff
 * and quote its margin. Then look at the eight hours after it - one resting window - and ask what
 * margin was actually available to that order while it sat there: the mean of
 * {@code (npc - (bid + 0.1)) / npc} across the holdout samples. A gap that closes drags that mean
 * negative, which is the outcome the filter claims to predict.
 *
 * <p><b>No chase stop is applied to the realized figure, deliberately.</b> In the shipped strategy a
 * closing gap costs a slot rather than coins, because the stop cancels the order long before the
 * price reaches the NPC's. Charging it here would make every cohort look identical and measure
 * nothing. The negative realizations below are what the stop is protecting you from having to sit
 * through.
 *
 * <p><b>Rolled over many cutoffs rather than measured at one.</b> A single eight-hour holdout is one
 * evening of one market: the run this was written against had 95 candidates in the top cohort and a
 * holdout quiet enough that no cohort went negative at all. Stepping the cutoff through the whole
 * tape turns that into tens of thousands of candidate-windows, which is enough to separate a 0.2%
 * failure rate from a 17% one.
 *
 * <p>Points at {@code bazaar-tape} beside the sales tape by default; override with
 * {@code -PbazaarTapeDir=...}. NPC prices come from the live items resource, which is a table of
 * constants rather than a market.
 */
@EnabledIfSystemProperty(named = "skyblockflipper.tapeBacktest", matches = "true")
class NpcPersistenceBacktestTest {
	private static final String DEFAULT_TAPE_DIR = "run/config/skyblock-flipper/bazaar-tape";

	/** How much tape to read back. More is better here; the cohorts are counted per window. */
	private static final int TAPE_DAYS = 14;

	/** The window {@code MarketPoller} builds edges over, so persistence means what it means. */
	private static final Duration HISTORY = Duration.ofDays(3);

	/** One resting window, which is what an order is committed for. */
	private static final Duration HOLDOUT = Duration.ofHours(8);

	/** Spacing between cutoffs. Windows overlap, which is fine: each is its own question. */
	private static final Duration STEP = Duration.ofHours(6);

	/** The shipped filters a candidate has to clear before it is a candidate at all. */
	private static final long MIN_WEEKLY_SOLD = 10_000L;
	private static final double MIN_MARGIN_RATIO = 0.15d;

	/** Holdout samples needed before a window is judged, i.e. about an hour of tape. */
	private static final int MIN_HOLDOUT_SAMPLES = 10;

	/** Below this the cohort comparison is noise and the test says so instead of asserting. */
	private static final int MIN_COHORT = 200;

	/** The cohorts, named as {@code docs/npc-flipping.md} names them. */
	private enum Cohort {
		HELD(">=95%"),
		FLICKERED("50-95%"),
		ABSENT("<50%");

		private final String label;

		Cohort(String label) {
			this.label = label;
		}

		static Cohort of(double persistence) {
			if (persistence >= 0.95d) {
				return HELD;
			}

			return persistence >= 0.50d ? FLICKERED : ABSENT;
		}
	}

	/** One candidate at one cutoff: what it promised, and what the next eight hours held. */
	private record Window(String productId, double quotedMargin, double realizedMargin) {
		double ratio() {
			return quotedMargin <= 0.0d ? 0.0d : realizedMargin / quotedMargin;
		}

		boolean wentNegative() {
			return realizedMargin < 0.0d;
		}
	}

	@Test
	void aPersistentGapIsStillTheOneThatIsThereWhenYouComeToTradeIt() throws Exception {
		ItemCatalog catalog = new HypixelApi().fetchItems();
		Map<String, List<BazaarSample>> tape = readTape(catalog);

		assertTrue(!tape.isEmpty(), "no NPC-priced products on the tape at " + tapeDir()
				+ " - point -PbazaarTapeDir at a directory with day files in it");

		long oldest = Long.MAX_VALUE;
		long newest = Long.MIN_VALUE;
		int samples = 0;

		for (List<BazaarSample> series : tape.values()) {
			oldest = Math.min(oldest, series.getFirst().timestamp());
			newest = Math.max(newest, series.getLast().timestamp());
			samples += series.size();
		}

		Map<Cohort, List<Window>> cohorts = new HashMap<>();

		for (Cohort cohort : Cohort.values()) {
			cohorts.put(cohort, new ArrayList<>());
		}

		int cutoffs = 0;

		for (long cutoff = oldest + HISTORY.toMillis(); cutoff <= newest - HOLDOUT.toMillis();
				cutoff += STEP.toMillis()) {
			cutoffs++;
			judge(cutoff, tape, catalog, cohorts);
		}

		report(samples, tape.size(), oldest, newest, cutoffs, cohorts);

		List<Window> held = cohorts.get(Cohort.HELD);
		List<Window> absent = cohorts.get(Cohort.ABSENT);

		assertTrue(held.size() >= MIN_COHORT, "only " + held.size() + " candidate-windows cleared "
				+ "95% persistence, which is not enough tape to conclude anything from. Read more "
				+ "days, or check that the collector was running.");

		double heldFailure = failureRate(held);

		assertTrue(heldFailure < 0.05d, String.format(
				"%.1f%% of persistent gaps closed inside the resting window. The filter is supposed "
						+ "to be selecting exactly the ones that do not, so either the market has "
						+ "changed or persistence has stopped being computed the way it was.",
				heldFailure * 100.0d));

		assertTrue(median(held, Window::ratio) >= 0.90d, String.format(
				"persistent gaps realized only %.2f of the margin they quoted over the window",
				median(held, Window::ratio)));

		// The comparison is the finding. Without a populated bottom cohort there is nothing to
		// compare against, and a tape with no flickering gaps in it is not a failing filter.
		assumeTrue(absent.size() >= MIN_COHORT,
				"only " + absent.size() + " candidate-windows below 50% persistence to compare with");

		double absentFailure = failureRate(absent);

		assertTrue(absentFailure > heldFailure * 3.0d, String.format(
				"persistence no longer separates anything: gaps below 50%% closed in %.1f%% of "
						+ "windows against %.1f%% for gaps above 95%%. Measured when the filter "
						+ "shipped: 17.5%% against 0.2%%.",
				absentFailure * 100.0d, heldFailure * 100.0d));
	}

	/**
	 * Everything one cutoff has to say, judged against the eight hours after it.
	 *
	 * <p>The edges are built by the shipped {@link NpcEdgeHistory} rather than recomputed here, so a
	 * change to how persistence is counted moves this measurement too. That is the point: a backtest
	 * that carries its own copy of the thing it is testing can only ever measure the market.
	 */
	private static void judge(long cutoff, Map<String, List<BazaarSample>> tape, ItemCatalog catalog,
			Map<Cohort, List<Window>> cohorts) {
		NpcEdgeHistory history = new NpcEdgeHistory(catalog, Instant.ofEpochMilli(cutoff), HISTORY);

		for (List<BazaarSample> series : tape.values()) {
			for (BazaarSample sample : series) {
				if (sample.timestamp() > cutoff) {
					break;
				}

				history.append(sample);
			}
		}

		NpcEdgeSnapshot edges = history.snapshot();

		for (Map.Entry<String, List<BazaarSample>> entry : tape.entrySet()) {
			NpcEdge edge = edges.edgeFor(entry.getKey()).orElse(null);

			if (edge == null || !edge.isUsable()) {
				continue;
			}

			candidateAt(cutoff, edge, entry.getValue())
					.ifPresent(window -> cohorts.get(Cohort.of(edge.persistence())).add(window));
		}
	}

	/** The candidate this product would have been at {@code cutoff}, if it would have been one. */
	private static Optional<Window> candidateAt(long cutoff, NpcEdge edge,
			List<BazaarSample> series) {
		BazaarSample last = null;
		double realizedSum = 0.0d;
		int holdout = 0;
		long end = cutoff + HOLDOUT.toMillis();

		for (BazaarSample sample : series) {
			if (sample.timestamp() <= cutoff) {
				last = sample;
			} else if (sample.timestamp() <= end) {
				realizedSum += marginRatio(edge.npcPrice(), sample.bidPrice());
				holdout++;
			} else {
				break;
			}
		}

		if (last == null || holdout < MIN_HOLDOUT_SAMPLES || last.soldWeek() < MIN_WEEKLY_SOLD) {
			return Optional.empty();
		}

		double quoted = marginRatio(edge.npcPrice(), last.bidPrice());

		// Only what the strategy would actually have offered. Judging plans it would have refused
		// would measure the margin floor rather than the persistence filter.
		return quoted < MIN_MARGIN_RATIO
				? Optional.empty()
				: Optional.of(new Window(edge.productId(), quoted, realizedSum / holdout));
	}

	/** Margin of a top-of-book buy order against the NPC price, as a share of it. */
	private static double marginRatio(double npcPrice, double bid) {
		return (npcPrice - (bid + BazaarProduct.PRICE_INCREMENT)) / npcPrice;
	}

	/**
	 * The NPC-priced part of the tape, in memory, oldest first.
	 *
	 * <p>Held rather than streamed because every cutoff needs the same samples again, and re-reading
	 * 700MB of day files once per cutoff would take longer than anyone would wait. Only products an
	 * NPC buys are kept - about 800 of the 2,100 on the bazaar - which is what keeps this to tens of
	 * megabytes rather than the whole tape.
	 */
	private static Map<String, List<BazaarSample>> readTape(ItemCatalog catalog) throws Exception {
		Map<String, List<BazaarSample>> tape = new HashMap<>();

		new BazaarTape(tapeDir(), Integer.MAX_VALUE).forEachRecent(TAPE_DAYS, sample -> {
			if (sample.bidPrice() <= 0.0d) {
				return;
			}

			boolean npcBuysIt = catalog.get(sample.productId())
					.flatMap(ItemCatalog.Entry::npcPrice)
					.filter(price -> price > 0.0d)
					.isPresent();

			if (npcBuysIt) {
				tape.computeIfAbsent(sample.productId(), id -> new ArrayList<>()).add(sample);
			}
		});

		// A synced tape appends the collector's lines after ours, so a day file is not necessarily
		// in order. Everything here walks the samples as a time series and would read a merged block
		// as a jump backwards.
		tape.values().forEach(series -> series.sort(Comparator.comparingLong(BazaarSample::timestamp)));

		return tape;
	}

	private static double failureRate(List<Window> windows) {
		return windows.isEmpty()
				? 0.0d
				: (double) windows.stream().filter(Window::wentNegative).count() / windows.size();
	}

	private static double median(List<Window> windows, ToDoubleFunction<Window> of) {
		if (windows.isEmpty()) {
			return 0.0d;
		}

		double[] values = windows.stream().mapToDouble(of).sorted().toArray();
		return values[values.length / 2];
	}

	private static void report(int samples, int products, long oldest, long newest, int cutoffs,
			Map<Cohort, List<Window>> cohorts) {
		System.out.printf("%npersistence holdout: %,d samples across %d NPC-priced products, "
						+ "%.1f days of tape%n", samples, products,
				(newest - oldest) / 86_400_000.0d);
		System.out.printf("  %d cutoffs every %dh, %dh holdout each, %dd of history behind each%n",
				cutoffs, STEP.toHours(), HOLDOUT.toHours(), HISTORY.toDays());
		System.out.printf("  %-8s %8s %14s %16s%n", "cohort", "windows", "realized/quoted",
				"gap closed");

		for (Cohort cohort : Cohort.values()) {
			List<Window> windows = cohorts.get(cohort);

			if (windows.isEmpty()) {
				System.out.printf("  %-8s %8d%n", cohort.label, 0);
				continue;
			}

			System.out.printf("  %-8s %8d %14.2f %10d (%4.1f%%)%n", cohort.label, windows.size(),
					median(windows, Window::ratio),
					windows.stream().filter(Window::wentNegative).count(),
					failureRate(windows) * 100.0d);
		}
	}

	private static Path tapeDir() {
		return Path.of(System.getProperty("skyblockflipper.bazaarTapeDir", DEFAULT_TAPE_DIR));
	}
}
