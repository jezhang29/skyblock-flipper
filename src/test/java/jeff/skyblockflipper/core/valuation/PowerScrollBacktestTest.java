package jeff.skyblockflipper.core.valuation;

import jeff.skyblockflipper.core.item.DecodedItem;
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
 * What {@code power_ability_scroll} is worth as a signature term, measured. Opt-in, needs a recorded
 * tape: {@code ./gradlew test -PtapeBacktest --tests '*PowerScrollBacktestTest'}.
 *
 * <p>It was the top of the unread-attribute list by coins - 554 taped BIN sales carrying 185.8
 * billion, a single enumerable string with six values, and 5x to 600x within one item id - and it was
 * rejected on 2026-07-31 because at {@link DecodedItem#signature()} the pooled key looked already
 * right about it.
 *
 * <p>That rejection was measured against a hand-built copy of the model, so it was re-opened here
 * once the ethermerge re-measurement showed the copy flattered the pooled arm - it kept every sample
 * where {@code FairValueModel.Builder} keeps the most recent 200 per key, and trained its coarse
 * index on bare sales only where production trains it on all of them. <b>The rejection survives.</b>
 * Against the model that ships, on a 24h holdout of the 59 ids that ever carry a scroll: keying it
 * prices 3,953 sales against 3,968 unread, leaves fake snipes at 39 either way, and moves neither the
 * median (0.062) nor the p90 (0.317) at all. It costs 15 valuations and fixes nothing.
 *
 * <p>Where ethermerge differs, and it is the whole difference: a merged item sits in a pool that
 * plain sales dominate, so the pooled median is a plain median and the merged sale is priced wrong. A
 * scrolled item <b>dominates its own signature</b> - nine of the ten sales under one Hyperion key
 * carry a Sapphire scroll - so the pooled median is already a scrolled median. Splitting it leaves a
 * cell of nine and a cell of one, and {@link ValueEstimate#MIN_SAMPLES} rejects the second.
 *
 * <p>Not one of the 39 held-out overvaluations involves a scrolled sale in either arm. They are
 * Midas staffs quoted off the bid ratio, Aspects of the End quoted off a pool of dearer ones, and one
 * 830-coin misclick.
 */
@EnabledIfSystemProperty(named = "skyblockflipper.tapeBacktest", matches = "true")
class PowerScrollBacktestTest {
	private static final String SCROLL = "power_ability_scroll";
	private static final long HOLDOUT_HOURS = 24L;
	private static final String NONE = "(none)";

	/** Longer than any tape, so training is the unbounded replay these findings were measured on. */
	private static final Duration WHOLE_TAPE = Duration.ofDays(3650);

	/**
	 * The term costs valuations and fixes nothing, measured against the model that ships.
	 *
	 * <p>Trains on everything older than the newest 24 hours and scores the sales in it, restricted to
	 * item ids that ever carry a scroll - the rest of the market cannot move either way, and leaving it
	 * in would bury the effect under 170,000 sales that are not about scrolls.
	 */
	@Test
	void keyingTheScrollCostsValuationsAndFixesNothing() throws Exception {
		Set<String> scrolledIds = idsThatEverCarryAScroll();
		long cutoff = TapeFixture.newestTimestamp() - HOLDOUT_HOURS * 3_600_000L;

		UnreadTerms pooledTerms = new UnreadTerms(PowerScrollBacktestTest::term);
		UnreadTerms keyedTerms = new UnreadTerms(PowerScrollBacktestTest::term);

		Backtest.Result pooled = Backtest.holdout(Keying.PRODUCTION, cutoff, WHOLE_TAPE,
				item -> scrolledIds.contains(item.skyblockId()), pooledTerms);
		Backtest.Result keyed = Backtest.holdout(keyedTerms.keying(), cutoff, WHOLE_TAPE,
				item -> scrolledIds.contains(item.skyblockId()), keyedTerms);

		System.out.printf("%n%,d item ids ever carry a power scroll%n", scrolledIds.size());
		System.out.printf("held-out sales of those ids:%n  %-26s %s%n  %-26s %s%n",
				"today (scroll unread)", pooled, "with scroll= keyed", keyed);
		System.out.printf("  scrolled sales priced: %,d unread (%,d within 1.5x), %,d keyed (%,d "
						+ "within 1.5x)%n",
				pooled.count(pooledTerms::carries), pooled.within(1.5d, pooledTerms::carries),
				keyed.count(keyedTerms::carries), keyed.within(1.5d, keyedTerms::carries));
		System.out.printf("  overvaluations by how the quote was matched:%n    unread %s%n    keyed  %s%n",
				overvaluedByBasis(pooled), overvaluedByBasis(keyed));

		System.out.println("  every held-out sale the unread arm overvalues 2x+:");
		pooled.priced().stream()
				.filter(priced -> priced.overvaluedBy(2.0d))
				.sorted((a, b) -> Double.compare(b.estimate() / b.actual(), a.estimate() / a.actual()))
				.forEach(priced -> System.out.printf("    %5.1fx %-7s %-9s fetched %,15.0f quoted "
								+ "%,15.0f  %s%n",
						priced.estimate() / priced.actual(), priced.basis(),
						pooledTerms.carries(priced.item()) ? "scrolled" : "plain",
						priced.actual(), priced.estimate(), trim(priced.item().signature())));

		// The benefit, or the lack of one. Anything the split fixes has to show up here, and on the
		// recorded tape the two arms produce the same 39 fake snipes - the same sales, at the same
		// quotes, none of them scrolled.
		assertTrue(keyed.overvaluedBy(2.0d) >= pooled.overvaluedBy(2.0d),
				"the scroll is kept out because keying it fixes no overvaluation, and this run fixed "
						+ (pooled.overvaluedBy(2.0d) - keyed.overvaluedBy(2.0d)) + " - if that is real, "
						+ "the term has started earning its coverage and belongs in the signature");

		// The cost, which is the scrolled sales themselves: their own cell falls under MIN_SAMPLES,
		// and the otherwise-bare ones lose the coarse index too.
		assertTrue(keyed.count(keyedTerms::carries) * 4 < pooled.count(pooledTerms::carries),
				"keying the scroll should strand most scrolled sales, but priced scrolled sales went "
						+ "from " + pooled.count(pooledTerms::carries) + " to "
						+ keyed.count(keyedTerms::carries));

		// And the pooled key is not merely no worse on average - it is actively right about the sales
		// in question. If this fails, scrolled items have stopped dominating their own signatures and
		// the whole argument has to be re-measured.
		assertTrue(pooled.within(1.5d, pooledTerms::carries) * 4 > pooled.count(pooledTerms::carries) * 3,
				"the pooled key is expected to value most scrolled sales within 1.5x of what they "
						+ "fetched, but only " + pooled.within(1.5d, pooledTerms::carries) + " of "
						+ pooled.count(pooledTerms::carries) + " landed there");
	}

	/**
	 * Which way the mixed pools are wrong, without a model.
	 *
	 * <p>The direction is the question. A pool that undervalues its scrolled sales costs nothing - an
	 * item quoted below its worth is one nobody buys - while a pool that quotes a plain sale at a
	 * scrolled one's price is a fake snipe, and that is the error money is lost on.
	 *
	 * <p>The pooled median here is the one the model would actually quote: over every sale under the
	 * key, at {@link ValueEstimate#MIN_SAMPLES}. It still differs from a trained model in ignoring the
	 * 200-sample ring, so read it as pool shape and read the holdout above for what the model does.
	 */
	@Test
	void mixedPoolsAreMeasuredForDirection() throws Exception {
		Map<String, Map<String, List<Double>>> perSignature = new HashMap<>();

		TapeFixture.forEachSale((item, extra, timestamp, unitPrice) -> perSignature
				.computeIfAbsent(item.signature(), k -> new TreeMap<>())
				.computeIfAbsent(extra.string(SCROLL).orElse(NONE), k -> new ArrayList<>())
				.add(unitPrice));

		int mixed = 0;
		int quotable = 0;
		int plainOvervalued = 0;
		int scrolledOvervalued = 0;
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
					} else {
						scrolledOvervalued++;
					}
				}

				detail.append(String.format("%s n=%d med %,.0f; ",
						variant.getKey().replace("_POWER_SCROLL", ""), variant.getValue().size(), med));
			}

			table.add(String.format("  %5.1fx  quoted %,15.0f  %-46s %s",
					worst, quoted.getAsDouble(), trim(entry.getKey()), detail));
		}

		System.out.printf("%n%,d production signatures hold more than one scroll variant, or a scroll "
				+ "variant and a plain sale, %,d of them quotable:%n", mixed, quotable);
		table.stream().sorted().forEach(System.out::println);
		System.out.printf("  quotable variants the pooled median overvalues 2x+: %,d plain, %,d "
				+ "scrolled%n", plainOvervalued, scrolledOvervalued);

		assertTrue(mixed > 0, "no production signature pools a scroll with anything else - either the "
				+ "attribute moved or there are no scroll sales on the tape at " + TapeFixture.tapeDir());
	}

	/** Which index produced each fake snipe: {@code COARSE} means the fallback pool, not the term. */
	private static Map<ValueEstimate.Basis, Long> overvaluedByBasis(Backtest.Result result) {
		return result.priced().stream()
				.filter(priced -> priced.overvaluedBy(2.0d))
				.collect(java.util.stream.Collectors.groupingBy(Backtest.Priced::basis,
						TreeMap::new, java.util.stream.Collectors.counting()));
	}

	/** The candidate term: the scroll's own id, which is one of six enumerable strings. */
	private static String term(DecodedItem item, jeff.skyblockflipper.core.nbt.NbtCompound extra) {
		return extra.string(SCROLL).map(scroll -> "scroll=" + scroll).orElse("");
	}

	/**
	 * Which ids the question is even about, plus the headline count.
	 *
	 * <p>A separate pass over the tape rather than a field on a shared state object: the tape is
	 * streamed a line at a time precisely so a day of it never sits in memory, and 452,000 decoded
	 * sales held to save a second pass would undo that.
	 */
	private static Set<String> idsThatEverCarryAScroll() throws Exception {
		Set<String> ids = new HashSet<>();
		Map<String, Integer> byScroll = new TreeMap<>();
		long[] coins = {0L};

		TapeFixture.forEachSale((item, extra, timestamp, unitPrice) ->
				extra.string(SCROLL).ifPresent(scroll -> {
					ids.add(item.skyblockId());
					byScroll.merge(scroll, 1, Integer::sum);
					coins[0] += (long) unitPrice;
				}));

		System.out.printf("%n%,d scroll sales carrying %,d coins: %s%n",
				byScroll.values().stream().mapToInt(Integer::intValue).sum(), coins[0], byScroll);

		assertTrue(ids.size() > 1, "no power scroll sales on the tape at " + TapeFixture.tapeDir());
		return ids;
	}

	private static String trim(String key) {
		return key.length() <= 46 ? key : key.substring(0, 43) + "...";
	}
}
