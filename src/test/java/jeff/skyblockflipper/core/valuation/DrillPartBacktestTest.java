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
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.StringJoiner;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Why the drill parts stay unread, measured. Opt-in, needs a recorded tape:
 * {@code ./gradlew test -PtapeBacktest --tests '*DrillPartBacktestTest'}.
 *
 * <p>They were the largest remaining entry on the unread-attribute list once
 * {@code power_ability_scroll} was measured out: 405 taped BIN sales carrying 97.3 billion coins
 * across {@code drill_part_engine}, {@code drill_part_fuel_tank},
 * {@code drill_part_upgrade_module}, {@code polarvoid} and {@code divan_powder_coating}. A fully
 * built drill really is worth several times a bare one - {@code MITHRIL_DRILL_2} sells at 42.9M with
 * a Mithril engine and tank against 17.7M without, under otherwise identical keys.
 *
 * <p>And the answer is the same as the scroll's, for a different reason. The scroll disappeared
 * because scrolled sales dominated their own key. The parts disappear because <b>the drill market is
 * tiny and its parts are near-unique</b>: 405 sales spread over 69 distinct part configurations and
 * 314 production signatures, so a part term produces cells of one.
 *
 * <p>Re-measured against the model that ships rather than the hand-built copy the finding was first
 * taken on - see {@link Backtest} for what the copy got wrong - and the rejection holds, more flatly
 * than before. On a 24h holdout of the sixteen ids that ever carry a part: unread prices 876 sales of
 * 1,680 with 10 fake snipes, and the full part term and a single modified bit both price 860 with the
 * same 10, at an identical median (0.046) and p90 (0.212). The 16 valuations lost are the parted
 * sales themselves. Not one held-out parted sale gets a quote under either term.
 *
 * <p>The pooling is also not doing harm where it can be acted on. Of the production signatures that
 * hold more than one drill configuration, most never reach {@link ValueEstimate#MIN_SAMPLES} and are
 * quoted by nothing at all. Among those that are quoted, <b>not one values an unparted drill at 2x or
 * more of what unparted drills of it fetch</b> - the pools that disagree by 2x or more do it in the
 * harmless direction, quoting a built drill below its worth.
 */
@EnabledIfSystemProperty(named = "skyblockflipper.tapeBacktest", matches = "true")
class DrillPartBacktestTest {
	private static final long HOLDOUT_HOURS = 24L;

	/** Longer than any tape, so training is the unbounded replay these findings were measured on. */
	private static final Duration WHOLE_TAPE = Duration.ofDays(3650);

	private static final String NONE = "(none)";

	/**
	 * The shape of the drill market, which is most of the argument.
	 *
	 * <p>Sixteen item ids, 405 parted sales in six days, and 69 distinct part configurations among
	 * them. The per-id columns are what a part term would have to beat: a parted drill runs about
	 * 1.2x to 1.5x an unparted one at the median ({@code TITANIUM_DRILL_4} 467M against 385M,
	 * {@code MITHRIL_DRILL_2} 24M against 16.5M), which is a real difference and a small one next to
	 * the 100x gaps that justified the rune, potion and dungeon-tier splits.
	 *
	 * <p>Also printed: how many parted sales are otherwise bare. Those are the ones a part term would
	 * eject from the coarse index, the coverage the term costs on top of shattering the exact index.
	 */
	@Test
	void theDrillMarketIsSmallAndItsPartsAreNearlyUnique() throws Exception {
		Map<String, Integer> byTerm = new TreeMap<>();
		Map<String, int[]> byId = new TreeMap<>();
		Map<String, List<Double>> partedById = new HashMap<>();
		Map<String, List<Double>> unpartedById = new HashMap<>();
		Set<String> signatures = new HashSet<>();
		long[] coins = {0L};
		int[] bare = {0};

		TapeFixture.forEachSale((item, extra, timestamp, unitPrice) -> {
			String term = drillTerm(item, extra);
			int[] counts = byId.computeIfAbsent(item.skyblockId(), k -> new int[2]);

			if (term.isEmpty()) {
				unpartedById.computeIfAbsent(item.skyblockId(), k -> new ArrayList<>()).add(unitPrice);
				return;
			}

			counts[0]++;
			coins[0] += (long) unitPrice;
			partedById.computeIfAbsent(item.skyblockId(), k -> new ArrayList<>()).add(unitPrice);
			byTerm.merge(term, 1, Integer::sum);
			signatures.add(item.signature());

			// A part is not a bareness clause, so production's own answer is the count wanted here:
			// these are the sales a part term would eject from the coarse index.
			if (Keying.PRODUCTION.isBare(item)) {
				counts[1]++;
				bare[0]++;
			}
		});

		int parted = byTerm.values().stream().mapToInt(Integer::intValue).sum();

		System.out.printf("%n%,d sales carry a drill part, carrying %,d coins over %,d distinct part "
						+ "configurations and %,d production signatures (%,d of the sales otherwise "
						+ "bare)%n",
				parted, coins[0], byTerm.size(), signatures.size(), bare[0]);
		System.out.printf("%n%-20s %7s %6s  %-34s %-34s%n",
				"item id", "parted", "bare", "parted min/median/max", "unparted min/median/max");

		byId.forEach((id, counts) -> {
			if (counts[0] > 0) {
				System.out.printf("%-20s %7d %6d  %s %s%n", id, counts[0], counts[1],
						spread(partedById.get(id)), spread(unpartedById.get(id)));
			}
		});

		assertTrue(parted > 0, "no drill part sales on the tape at " + TapeFixture.tapeDir());

		// The reason a part term cannot be priced: it is nearly an identifier. Sixty-nine
		// configurations over 405 sales is six sales each before the signature they sit under splits
		// them further, and MIN_SAMPLES is 6.
		assertTrue(byTerm.size() * 10 > parted, "the drill parts are expected to be near-unique per "
				+ "sale, which is why keying them prices nothing, but " + parted + " sales carried "
				+ "only " + byTerm.size() + " distinct configurations");
	}

	/**
	 * Keying the parts costs every parted valuation and fixes nothing, against the model that ships.
	 *
	 * <p>Trains on everything older than the newest 24 hours and prices what is in it, restricted to
	 * the sixteen item ids that ever carry a part. Three arms: today's key, today's key plus the full
	 * part term, and today's key plus a single bit saying something was installed.
	 *
	 * <p>The flag arm is the interesting half. Even one bit shatters these pools, because the parted
	 * sales under a given drill signature are usually one or two - so the cheapest term that could
	 * have worked does not either.
	 */
	@Test
	void keyingThePartsCostsEveryPartedValuationAndFixesNothing() throws Exception {
		Set<String> drillIds = idsThatEverCarryAPart();
		long cutoff = TapeFixture.newestTimestamp() - HOLDOUT_HOURS * 3_600_000L;

		UnreadTerms pooledTerms = new UnreadTerms(DrillPartBacktestTest::drillTerm);
		UnreadTerms exactTerms = new UnreadTerms(DrillPartBacktestTest::drillTerm);
		UnreadTerms flagTerms = new UnreadTerms(
				(item, extra) -> drillTerm(item, extra).isEmpty() ? "" : "drill=modified");

		Backtest.Result pooled = Backtest.holdout(Keying.PRODUCTION, cutoff, WHOLE_TAPE,
				item -> drillIds.contains(item.skyblockId()), pooledTerms);
		Backtest.Result exact = Backtest.holdout(exactTerms.keying(), cutoff, WHOLE_TAPE,
				item -> drillIds.contains(item.skyblockId()), exactTerms);
		Backtest.Result flag = Backtest.holdout(flagTerms.keying(), cutoff, WHOLE_TAPE,
				item -> drillIds.contains(item.skyblockId()), flagTerms);

		System.out.printf("%n%,d item ids ever carry a drill part%n", drillIds.size());
		System.out.printf("held-out sales of those ids:%n  %-26s %s%n  %-26s %s%n  %-26s %s%n",
				"today (parts unread)", pooled, "with the full part term", exact,
				"with a modified flag only", flag);
		System.out.printf("  parted sales priced: %,d unread (%,d within 1.5x), %,d with the full term, "
						+ "%,d with the flag%n",
				pooled.count(pooledTerms::carries), pooled.within(1.5d, pooledTerms::carries),
				exact.count(exactTerms::carries), flag.count(flagTerms::carries));

		assertEquals(0, exact.count(exactTerms::carries), "the exact part term is expected to price no "
				+ "parted sale at all, since each configuration is near-unique");

		// The flag is the cheapest term that could work, and it is measured rather than assumed away:
		// if the parted sales under a signature were many, one bit would split them cleanly.
		assertTrue(flag.count(flagTerms::carries) < pooled.count(pooledTerms::carries),
				"even a single modified bit is expected to cost parted valuations, but it priced "
						+ flag.count(flagTerms::carries) + " against " + pooled.count(pooledTerms::carries));

		// The benefit side, which is empty. Any fake snipe either term removes would show up here.
		assertEquals(pooled.overvaluedBy(2.0d), exact.overvaluedBy(2.0d), "the exact part term is "
				+ "expected to fix no overvaluation, because no quotable pool overvalues an unparted "
				+ "drill");
		assertEquals(pooled.overvaluedBy(2.0d), flag.overvaluedBy(2.0d), "the modified flag is expected "
				+ "to fix no overvaluation either");

		// And the pooled key is actively right about the sales in question, not merely no worse.
		assertTrue(pooled.within(1.5d, pooledTerms::carries) * 4 > pooled.count(pooledTerms::carries) * 3,
				"the pooled key is expected to value most parted sales within 1.5x of what they "
						+ "fetched, but only " + pooled.within(1.5d, pooledTerms::carries) + " of "
						+ pooled.count(pooledTerms::carries) + " landed there");
	}

	/**
	 * And no pool production can actually quote overvalues its unparted drills.
	 *
	 * <p>Walks every production signature holding more than one drill configuration and compares the
	 * pooled median each sale is priced against with what each configuration inside it fetched. Taken
	 * over all 75, the table looks alarming - a dozen pools disagree by 2x or more - and most of those
	 * pools hold two sales. {@link ValueEstimate#MIN_SAMPLES} is 6, so production quotes 23 of the 75
	 * and none of the rest, which is why this counts only the 23.
	 *
	 * <p>Among those, the unparted side - the side a flip would be bought on - is never quoted high.
	 * The two pools that still disagree 2x+ do it the other way round, quoting a built drill at the
	 * bare price, and an item quoted below its worth is one nobody buys. The mechanism is that a pool
	 * big enough to quote is also big enough for a parted sale not to move its median:
	 * {@code MITHRIL_DRILL_2|RARE} holds 153 unparted sales at a median of 16,650,000 and one
	 * 31,500,000 parted sale, and quotes 16,800,000.
	 */
	@Test
	void quotablePoolsDoNotOvervalueTheirUnpartedDrills() throws Exception {
		Map<String, Map<String, List<Double>>> perSignature = new HashMap<>();

		TapeFixture.forEachSale((item, extra, timestamp, unitPrice) -> perSignature
				.computeIfAbsent(item.signature(), k -> new TreeMap<>())
				.computeIfAbsent(orNone(drillTerm(item, extra)), k -> new ArrayList<>())
				.add(unitPrice));

		int mixed = 0;
		int quotable = 0;
		int unpartedOvervalued = 0;
		int partedOvervalued = 0;
		List<String> table = new ArrayList<>();

		for (Map.Entry<String, Map<String, List<Double>>> entry : perSignature.entrySet()) {
			Map<String, List<Double>> variants = entry.getValue();

			if (variants.size() < 2) {
				continue;
			}

			mixed++;
			// The pool as production sees it: one median, and only if it clears MIN_SAMPLES.
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
						unpartedOvervalued++;
					} else {
						partedOvervalued++;
					}
				}

				detail.append(String.format("%s n=%d med %,.0f; ",
						variant.getKey(), variant.getValue().size(), med));
			}

			table.add(String.format("  %5.1fx  quoted %,15.0f  %-46s %s",
					worst, quoted.getAsDouble(), trim(entry.getKey()), detail));
		}

		System.out.printf("%n%,d production signatures hold more than one drill configuration, %,d of "
				+ "them with enough sales to be quoted at all:%n", mixed, quotable);
		table.stream().sorted().forEach(System.out::println);
		System.out.printf("  quotable variants the pooled median overvalues 2x+: %,d unparted, "
				+ "%,d parted%n", unpartedOvervalued, partedOvervalued);

		// The load-bearing one, and the same assertion the power scroll had to pass: an unparted drill
		// quoted at a built drill's price is the fake snipe a part term would exist to prevent, and no
		// pool production quotes produces one.
		assertEquals(0, unpartedOvervalued, "no quotable pool should quote an unparted drill at 2x or "
				+ "more of what unparted drills of it fetch, since that is the error a flip would be "
				+ "acted on");

		assertTrue(quotable * 2 < mixed, "most mixed drill pools are expected to be too small to quote "
				+ "at all - that is why their 2x+ disagreements cost nothing - but " + quotable
				+ " of " + mixed + " cleared MIN_SAMPLES");
	}

	/**
	 * Which ids the question is even about.
	 *
	 * <p>A separate pass over the tape rather than a field on shared state: the tape is streamed a
	 * line at a time precisely so a day of it never sits in memory.
	 */
	private static Set<String> idsThatEverCarryAPart() throws Exception {
		Set<String> ids = new HashSet<>();

		TapeFixture.forEachSale((item, extra, timestamp, unitPrice) -> {
			if (!drillTerm(item, extra).isEmpty()) {
				ids.add(item.skyblockId());
			}
		});

		assertTrue(!ids.isEmpty(), "no drill part sales on the tape at " + TapeFixture.tapeDir());
		return ids;
	}

	/**
	 * Everything somebody installed in a drill, as one candidate signature term.
	 *
	 * <p>The three parts arrive in two formats and neither is a superset. {@code drill_part_engine}
	 * holds a lowercase part id; the {@code engine} compound holds the same id uppercase under
	 * {@code id}, alongside stored fuel and sometimes a uuid. The tape has 103 sales carrying the
	 * first and 59 the second, so both are read and normalised to one string - a measurement that
	 * read only the modern half would understate the market it is arguing about.
	 *
	 * <p>{@code polarvoid} is a count of books applied (1-5, and 5 is most of them) and
	 * {@code divan_powder_coating} is a flag. They ride along here because they are the same
	 * question: things bought and installed that the signature does not currently see.
	 */
	private static String drillTerm(DecodedItem item, NbtCompound extra) {
		StringJoiner term = new StringJoiner(",");
		part(extra, "engine").ifPresent(id -> term.add("engine=" + id));
		part(extra, "fuel_tank").ifPresent(id -> term.add("tank=" + id));
		part(extra, "upgrade_module").ifPresent(id -> term.add("module=" + id));

		int polarvoid = extra.intOr("polarvoid", 0);

		if (polarvoid > 0) {
			term.add("polarvoid=" + polarvoid);
		}

		if (extra.flag("divan_powder_coating")) {
			term.add("coated");
		}

		return term.toString();
	}

	private static Optional<String> part(NbtCompound extra, String slot) {
		return extra.string("drill_part_" + slot)
				.or(() -> extra.child(slot).string("id"))
				.map(id -> id.toUpperCase(Locale.ROOT));
	}

	private static String orNone(String term) {
		return term.isEmpty() ? NONE : term;
	}

	private static String spread(List<Double> prices) {
		if (prices == null || prices.isEmpty()) {
			return String.format("%34s", "-");
		}

		return String.format("%,10.0f %,11.0f %,11.0f",
				prices.stream().mapToDouble(Double::doubleValue).min().orElse(0),
				TapeFixture.median(prices, 1).orElse(0),
				prices.stream().mapToDouble(Double::doubleValue).max().orElse(0));
	}

	private static String trim(String key) {
		return key.length() <= 46 ? key : key.substring(0, 43) + "...";
	}
}
