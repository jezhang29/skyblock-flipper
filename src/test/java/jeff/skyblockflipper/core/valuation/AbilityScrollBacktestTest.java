package jeff.skyblockflipper.core.valuation;

import jeff.skyblockflipper.core.item.DecodedItem;
import jeff.skyblockflipper.core.nbt.NbtCompound;
import jeff.skyblockflipper.core.valuation.backtest.Backtest;
import jeff.skyblockflipper.core.valuation.backtest.TapeFixture;
import jeff.skyblockflipper.core.valuation.backtest.UnreadTerms;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Is {@code ability_scroll} a real signature gap or a dominated-key artifact? Opt-in, needs a recorded
 * tape: {@code ./gradlew test -PtapeBacktest --tests '*AbilityScrollBacktestTest'}.
 *
 * <p>Why this exists: on the live tape (2026-08-27..09-03) {@code UnreadAttributeProbeTest} fails - its
 * top unread attribute is {@code ability_scroll} at 8.88B screened coins over 491 sales, an order of
 * magnitude past the 100M alarm line, with {@code power_ability_scroll} behind it at 6.37B. The alarm
 * is the roadmap's tripwire for "a new invisible upgrade both common and expensive". The probe is a
 * screen, not a verdict - {@code power_ability_scroll} screens high too and was measured flat on a
 * holdout because scrolled items dominate their own signatures. {@code ability_scroll} is a distinct,
 * never-adjudicated key (the probe renders it {@code values=1}, so it is a list/compound, not a
 * string), so it gets the same holdout the scroll and the merge got.
 *
 * <p>Read as a presence bit, the {@code ethermerge}/{@code slots} pattern: the cheapest term that can
 * close a shared-id gap, and the one to measure first. If the bit removes fake snipes on a holdout it
 * has earned the branch; if it fixes nothing it is {@code power_ability_scroll} again and the alarm can
 * be re-baselined. No verdict is asserted here - this is the measurement that decides one.
 */
@EnabledIfSystemProperty(named = "skyblockflipper.tapeBacktest", matches = "true")
class AbilityScrollBacktestTest {
	private static final String SCROLL = "ability_scroll";
	private static final long HOLDOUT_HOURS = 24L;
	private static final String NONE = "(none)";
	private static final String HAS = "ability_scroll";

	/** Longer than any tape, so training is the unbounded replay these findings are measured on. */
	private static final Duration WHOLE_TAPE = Duration.ofDays(3650);

	/** The candidate term: a presence bit, since the attribute is not a scalar the key could carry. */
	private static String term(DecodedItem item, NbtCompound extra) {
		return extra.contains(SCROLL) ? HAS : "";
	}

	/**
	 * Does keying the bit remove fake snipes, and what does it cost? The question the alarm asks.
	 *
	 * <p>Two arms over the ids that ever carry the attribute: production today (bit unread) and
	 * production with the bit keyed. Fewer 2x overvaluations in the keyed arm is the term earning its
	 * place; the same number is {@code power_ability_scroll} - a screen hit that the pool was already
	 * right about.
	 */
	@Test
	void keyingTheBitMeasuredOnAHoldout() throws Exception {
		Set<String> ids = idsThatEverCarryAScroll();
		long cutoff = TapeFixture.newestTimestamp() - HOLDOUT_HOURS * 3_600_000L;

		UnreadTerms pooledTerms = new UnreadTerms(AbilityScrollBacktestTest::term);
		UnreadTerms keyedTerms = new UnreadTerms(AbilityScrollBacktestTest::term);

		Backtest.Result pooled = Backtest.holdout(Keying.PRODUCTION, cutoff, WHOLE_TAPE,
				item -> ids.contains(item.skyblockId()), pooledTerms);
		Backtest.Result keyed = Backtest.holdout(keyedTerms.keying(), cutoff, WHOLE_TAPE,
				item -> ids.contains(item.skyblockId()), keyedTerms);

		System.out.printf("%n%,d item ids ever carry an ability_scroll%n", ids.size());
		System.out.printf("held-out sales of those ids:%n  %-26s %s%n  %-26s %s%n",
				"today (bit unread)", pooled, "with ability_scroll keyed", keyed);
		System.out.printf("  scrolled sales priced: %,d unread (%,d within 1.5x), %,d keyed (%,d within 1.5x)%n",
				pooled.count(pooledTerms::carries), pooled.within(1.5d, pooledTerms::carries),
				keyed.count(keyedTerms::carries), keyed.within(1.5d, keyedTerms::carries));
		System.out.printf("  fake snipes (quote 2x+ of what the sale fetched): %,d unread -> %,d keyed%n",
				pooled.overvaluedBy(2.0d), keyed.overvaluedBy(2.0d));
		System.out.printf("  of those, on a PLAIN (unscrolled) sale - the money-losing direction: %,d unread -> %,d keyed%n",
				plainOvervaluations(pooled, pooledTerms), plainOvervaluations(keyed, keyedTerms));

		System.out.println("  every held-out sale the unread arm overvalues 2x+ (top 25 by ratio):");
		pooled.priced().stream()
				.filter(priced -> priced.overvaluedBy(2.0d))
				.sorted((a, b) -> Double.compare(b.estimate() / b.actual(), a.estimate() / a.actual()))
				.limit(25)
				.forEach(priced -> System.out.printf("    %6.1fx %-7s %-9s fetched %,15.0f quoted %,15.0f  %s%n",
						priced.estimate() / priced.actual(), priced.basis(),
						pooledTerms.carries(priced.item()) ? "scrolled" : "plain",
						priced.actual(), priced.estimate(), trim(priced.item().signature())));

		System.out.printf("%nVERDICT INPUT: keying the bit changed 2x fake snipes by %,d (%,d -> %,d) for "
						+ "%,d fewer priced sales. A real gap removes plain-side fake snipes; a dominated key "
						+ "removes none. See PowerScrollBacktestTest for the dominated-key precedent.%n",
				pooled.overvaluedBy(2.0d) - keyed.overvaluedBy(2.0d),
				pooled.overvaluedBy(2.0d), keyed.overvaluedBy(2.0d),
				pooled.priced().size() - keyed.priced().size());

		assertTrue(pooled.priced().size() > 0 && !ids.isEmpty(),
				"no ability_scroll ids priced on the tape at " + TapeFixture.tapeDir());
	}

	/**
	 * Which way the mixed pools are wrong, without a model. A pooled median that quotes a PLAIN sale at
	 * 2x+ what plain sales fetch is a fake snipe waiting to be acted on; the reverse costs nothing.
	 */
	@Test
	void mixedPoolsDirectionAndExposure() throws Exception {
		Map<String, Map<String, List<Double>>> perSignature = new HashMap<>();
		long[] scrollSales = {0L};
		long[] scrollCoins = {0L};
		int[] bare = {0};

		TapeFixture.forEachSale((item, extra, timestamp, unitPrice) -> {
			boolean has = extra.contains(SCROLL);

			if (has) {
				scrollSales[0]++;
				scrollCoins[0] += (long) unitPrice;

				if (Keying.PRODUCTION.isBare(item)) {
					bare[0]++;
				}
			}

			perSignature
					.computeIfAbsent(item.signature(), k -> new TreeMap<>())
					.computeIfAbsent(has ? HAS : NONE, k -> new ArrayList<>())
					.add(unitPrice);
		});

		int mixed = 0;
		int quotable = 0;
		int plainOvervalued = 0;
		int scrolledOvervalued = 0;
		long plainOvervaluedCoins = 0L;
		List<String> table = new ArrayList<>();

		for (Map.Entry<String, Map<String, List<Double>>> entry : perSignature.entrySet()) {
			Map<String, List<Double>> variants = entry.getValue();

			if (variants.size() < 2) {
				continue;
			}

			mixed++;
			OptionalDouble quoted = Backtest.quotableMedian(
					variants.values().stream().flatMap(List::stream).toList());

			if (quoted.isEmpty()) {
				continue;
			}

			quotable++;
			StringBuilder detail = new StringBuilder();
			double worst = 1.0d;

			for (Map.Entry<String, List<Double>> variant : variants.entrySet()) {
				double med = TapeFixture.median(variant.getValue(), 1).orElseThrow();
				double ratio = quoted.getAsDouble() / med;
				worst = Math.max(worst, Math.max(ratio, 1.0d / ratio));

				if (ratio >= 2.0d) {
					if (variant.getKey().equals(NONE)) {
						plainOvervalued++;
						plainOvervaluedCoins += (long) (variant.getValue().size() * med);
					} else {
						scrolledOvervalued++;
					}
				}

				detail.append(String.format("%s n=%d med %,.0f; ",
						variant.getKey().equals(NONE) ? "plain" : "scroll", variant.getValue().size(), med));
			}

			table.add(String.format("  %6.1fx  quoted %,15.0f  %-42s %s",
					worst, quoted.getAsDouble(), trim(entry.getKey()), detail));
		}

		System.out.printf("%n%,d ability_scroll sales carrying %,d coins, %,d of them otherwise bare%n",
				scrollSales[0], scrollCoins[0], bare[0]);
		System.out.printf("%,d production signatures mix scrolled and plain sales, %,d quotable:%n",
				mixed, quotable);
		table.stream().sorted().forEach(System.out::println);
		System.out.printf("  quotable variants the pooled median overvalues 2x+: %,d plain (%,d coins), %,d scrolled%n",
				plainOvervalued, plainOvervaluedCoins, scrolledOvervalued);

		assertTrue(mixed > 0, "no production signature pools an ability_scroll with a plain sale - either "
				+ "the attribute moved or there are none on the tape at " + TapeFixture.tapeDir());
	}

	private static long plainOvervaluations(Backtest.Result result, UnreadTerms terms) {
		return result.priced().stream()
				.filter(priced -> priced.overvaluedBy(2.0d))
				.filter(priced -> !terms.carries(priced.item()))
				.count();
	}

	private static Set<String> idsThatEverCarryAScroll() throws Exception {
		Set<String> ids = new HashSet<>();
		long[] sales = {0L};

		TapeFixture.forEachSale((item, extra, timestamp, unitPrice) -> {
			if (extra.contains(SCROLL)) {
				ids.add(item.skyblockId());
				sales[0]++;
			}
		});

		System.out.printf("%n%,d ability_scroll sales across %,d item ids%n", sales[0], ids.size());
		assertTrue(!ids.isEmpty(), "no ability_scroll sales on the tape at " + TapeFixture.tapeDir());
		return ids;
	}

	private static String trim(String key) {
		return key.length() <= 42 ? key : key.substring(0, 39) + "...";
	}
}
