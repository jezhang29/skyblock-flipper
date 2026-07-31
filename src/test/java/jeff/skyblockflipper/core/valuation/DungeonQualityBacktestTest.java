package jeff.skyblockflipper.core.valuation;

import jeff.skyblockflipper.core.item.DecodedItem;
import jeff.skyblockflipper.core.item.ItemDecoder;
import jeff.skyblockflipper.core.nbt.NbtCompound;
import jeff.skyblockflipper.core.nbt.NbtReader;
import jeff.skyblockflipper.core.tape.SalesTape;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What reading a dungeon drop's quality roll is worth, measured. Opt-in, needs a recorded tape:
 * {@code ./gradlew test -PtapeBacktest --tests '*DungeonQualityBacktestTest'}.
 *
 * <p>Not a shared item id like {@code PET}, {@code RUNE} and {@code POTION} - these drops keep their
 * own ids - but the same pooling, and after those three the largest one left by coins. 9,759 of
 * 222,430 taped BIN sales carry {@code baseStatBoostPercentage}, and under one key
 * {@code SKELETON_MASTER_CHESTPLATE} spans 980,000 to 113,000,000 coins on the floor tier alone.
 *
 * <p>This also decides the shape of the key rather than assuming it, by scoring five ways of
 * writing the same two attributes down. The interesting comparison is not against the pooled key -
 * that one obviously loses - but between the variants, because {@code baseStatBoostPercentage} runs
 * 1 to 50 and splitting on all fifty values would cost most of the coverage.
 *
 * <p>Held out newest-first and priced from the older sales, because a key always fits the sales it
 * was built from.
 */
@EnabledIfSystemProperty(named = "skyblockflipper.tapeBacktest", matches = "true")
class DungeonQualityBacktestTest {
	private static final String DEFAULT_TAPE_DIR = "run/config/skyblock-flipper/tape";

	private static final long HOLDOUT_HOURS = 6L;
	private static final int ALL_DAYS = 365;
	private static final int MIN_SAMPLES = ValueEstimate.MIN_SAMPLES;

	private static final int MAX_STAT_BOOST = 50;
	private static final int NO_TIER = -1;

	@Test
	void keyingOnTheQualityRollStopsDungeonDropsInventingSnipes() throws Exception {
		SalesTape tape = new SalesTape(Path.of(
				System.getProperty("skyblockflipper.tapeDir", DEFAULT_TAPE_DIR)), 3650);

		// Every way of writing the roll down that is worth scoring, from ignoring it to taking the
		// stat boost at face value.
		Map<String, Keying> variants = new LinkedHashMap<>();
		variants.put("pooled (no quality term)", (boost, tier) -> "");
		variants.put("tier only", (boost, tier) -> tier == NO_TIER ? "" : "tier=" + tier);
		variants.put("maxed flag only", (boost, tier) -> boost >= MAX_STAT_BOOST ? "maxed" : "");
		variants.put("maxed flag + tier (shipped)", DungeonQualityBacktestTest::shipped);
		variants.put("boost banded in tens + tier", (boost, tier) -> term(
				boost >= MAX_STAT_BOOST ? "maxed" : "boost" + (boost / 10) + "x", tier));
		variants.put("exact boost + tier", (boost, tier) -> term("boost=" + boost, tier));

		Map<String, Map<String, List<Double>>> trainedIndexes = new HashMap<>();
		Map<String, List<String>> holdoutKeys = new HashMap<>();

		for (String name : variants.keySet()) {
			trainedIndexes.put(name, new HashMap<>());
			holdoutKeys.put(name, new ArrayList<>());
		}

		List<Long> stamps = new ArrayList<>();
		tape.forEachRecent(ALL_DAYS, sale -> stamps.add(sale.timestamp()));
		assertTrue(!stamps.isEmpty(), "no sales on the tape at " + DEFAULT_TAPE_DIR);

		long newest = stamps.stream().mapToLong(Long::longValue).max().orElseThrow();
		long cutoff = newest - HOLDOUT_HOURS * 3_600_000L;

		List<Double> holdoutPrices = new ArrayList<>();
		int[] carrying = {0};

		tape.forEachRecent(ALL_DAYS, sale -> {
			if (!sale.bin() || sale.price() <= 0L) {
				return;
			}

			int[] roll = qualityRoll(sale.itemBytes());

			if (roll == null) {
				return;
			}

			ItemDecoder.decode(sale.itemBytes()).ifPresent(item -> {
				carrying[0]++;

				double unitPrice = (double) sale.price() / Math.max(1, item.count());
				String base = withoutQuality(item);
				boolean held = sale.timestamp() >= cutoff;

				if (held) {
					holdoutPrices.add(unitPrice);
				}

				variants.forEach((name, keying) -> {
					String term = keying.term(roll[0], roll[1]);
					String key = term.isEmpty() ? base : base + "|quality=" + term;

					if (held) {
						holdoutKeys.get(name).add(key);
					} else {
						trainedIndexes.get(name)
								.computeIfAbsent(key, k -> new ArrayList<>()).add(unitPrice);
					}
				});
			});
		});

		System.out.printf("%ndungeon quality backtest: %,d sales carry a quality roll, "
						+ "%,d held out in the newest %dh%n",
				carrying[0], holdoutPrices.size(), HOLDOUT_HOURS);

		Map<String, Scored> scores = new LinkedHashMap<>();

		variants.forEach((name, keying) -> {
			Scored scored = score(holdoutPrices, holdoutKeys.get(name), trainedIndexes.get(name));
			scores.put(name, scored);
			System.out.printf("  %-28s %s%n", name, scored);
		});

		Scored pooled = scores.get("pooled (no quality term)");
		Scored shipped = scores.get("maxed flag + tier (shipped)");

		// The metric that matches the harm, per the potion split: a valuation is only ever acted on
		// when it sits far above the asking price, so an overvaluation is a flip taken at a loss.
		// The median is deliberately not asserted on - the median dungeon sale is a cheap floor drop
		// that the pooled key is accidentally right about, exactly as Stinky Cheese was.
		assertTrue(shipped.overvaluedByHalf() < pooled.overvaluedByHalf(),
				"keying on the quality roll should invent fewer fake snipes than pooling, but it "
						+ "went from " + pooled.overvaluedByHalf() + " to "
						+ shipped.overvaluedByHalf());

		assertTrue(shipped.p90() < pooled.p90(), "keying on the quality roll should cut the p90 "
				+ "error, but it went from " + pooled.p90() + " to " + shipped.p90());

		// And the reason the boost is a flag rather than a number: fifty values per item shatter the
		// key into cells too thin to price, so the exact variant buys its accuracy with coverage the
		// shipped one keeps. If this ever stops holding, the flag is the wrong call.
		Scored exact = scores.get("exact boost + tier");

		assertTrue(exact.priced() < shipped.priced(), "splitting on the exact stat boost should "
				+ "price fewer sales than treating it as a maxed flag, but it priced "
				+ exact.priced() + " against " + shipped.priced());

		// The flag itself is inert against the full signature - see the second test below for why it
		// is shipped regardless. What matters here is that it is inert in the harmless direction:
		// carrying it costs no coverage at all, which is the whole reason keeping a dormant term is
		// affordable. If this stops holding, the flag has started splitting keys and the case for it
		// should be re-argued on what that split is worth rather than on it being free.
		Scored tierOnly = scores.get("tier only");

		assertEquals(tierOnly.priced(), shipped.priced(), "the maxed flag is expected to split no "
				+ "key at all, so adding it to the tier should price exactly as many sales");
	}

	/**
	 * Why the maxed flag ships even though the test above measures it changing nothing.
	 *
	 * <p>It is redundant, not worthless. Keyed coarsely on id, rarity and tier - dropping the
	 * investment terms that make the full signature so fine - maxedness is worth up to 44x, and the
	 * full signature only separates it by accident, because a maxed drop happens to be one somebody
	 * also starred and enchanted. This measures the size of the hole that correlation is covering.
	 */
	@Test
	void maxednessIsWorthKeepingBecauseNothingElseActuallyStatesIt() throws Exception {
		SalesTape tape = new SalesTape(Path.of(
				System.getProperty("skyblockflipper.tapeDir", DEFAULT_TAPE_DIR)), 3650);

		Map<String, List<Double>> maxed = new HashMap<>();
		Map<String, List<Double>> plain = new HashMap<>();

		tape.forEachRecent(ALL_DAYS, sale -> {
			if (!sale.bin() || sale.price() <= 0L) {
				return;
			}

			int[] roll = qualityRoll(sale.itemBytes());

			if (roll == null) {
				return;
			}

			ItemDecoder.decode(sale.itemBytes()).ifPresent(item -> {
				String coarse = item.skyblockId() + "|" + item.rarity()
						+ (roll[1] == NO_TIER ? "" : "|tier=" + roll[1]);
				double unitPrice = (double) sale.price() / Math.max(1, item.count());
				(roll[0] >= MAX_STAT_BOOST ? maxed : plain)
						.computeIfAbsent(coarse, k -> new ArrayList<>()).add(unitPrice);
			});
		});

		double widest = 1.0d;
		String widestKey = "";

		System.out.printf("%nmaxed against unmaxed at the same id, rarity and tier:%n");

		for (Map.Entry<String, List<Double>> entry : maxed.entrySet()) {
			OptionalDouble withRoll = median(entry.getValue());
			OptionalDouble without = median(plain.get(entry.getKey()));

			if (withRoll.isEmpty() || without.isEmpty() || without.getAsDouble() <= 0.0d) {
				continue;
			}

			double ratio = withRoll.getAsDouble() / without.getAsDouble();
			System.out.printf("  %-52s maxed %,14.0f | plain %,14.0f  (%.2fx)%n",
					entry.getKey(), withRoll.getAsDouble(), without.getAsDouble(), ratio);

			if (ratio > widest) {
				widest = ratio;
				widestKey = entry.getKey();
			}
		}

		System.out.printf("  widest: %s at %.2fx%n", widestKey, widest);

		// Most of these cells sit at about 1x, which is the flatness that makes the flag inert. The
		// one that does not is the reason it ships: on the recorded tape SKELETON_MASTER_CHESTPLATE
		// at tier 10 is 109,940,000 maxed against 2,500,000 plain.
		assertTrue(widest >= 10.0d, "maxedness should be worth at least an order of magnitude "
				+ "somewhere, or the flag really is dead weight - widest was " + widest);
	}

	/** What production writes: a flag for a maxed roll, the floor tier exactly, both optional. */
	private static String shipped(int boost, int tier) {
		return term(boost >= MAX_STAT_BOOST ? "maxed" : "", tier);
	}

	private static String term(String boostTerm, int tier) {
		if (tier == NO_TIER) {
			return boostTerm;
		}

		return boostTerm.isEmpty() ? "tier=" + tier : boostTerm + ",tier=" + tier;
	}

	/** One way of turning a roll into a signature clause, or "" for no clause at all. */
	private interface Keying {
		String term(int boost, int tier);
	}

	/**
	 * The item's signature with the shipped quality clause taken back off, so every variant is
	 * measured against the same base key and only the quality term differs.
	 *
	 * <p>Safe as a suffix strip because {@code signature()} adds the clause last and its contents
	 * never contain the separator.
	 */
	private static String withoutQuality(DecodedItem item) {
		return item.signature().replaceFirst("\\|quality=[^|]*$", "");
	}

	/** @return {@code {boost, tier}} or null when this sale carries no roll */
	private static int[] qualityRoll(String itemBytes) {
		try {
			NbtCompound root = NbtReader.readItemBytes(itemBytes);

			if (!(root.list("i").stream().findFirst().orElse(null) instanceof NbtCompound item)) {
				return null;
			}

			NbtCompound extra = item.child("tag").child("ExtraAttributes");

			if (!extra.contains("baseStatBoostPercentage")) {
				return null;
			}

			return new int[] {extra.intOr("baseStatBoostPercentage", 0),
					extra.intOr("item_tier", NO_TIER)};
		} catch (Exception e) {
			return null;
		}
	}

	/**
	 * @param overvaluedByHalf sales the key valued at 2x or more of what they actually fetched -
	 *                         the fake snipes, and the reason this gap costs coins rather than
	 *                         merely being imprecise
	 */
	private record Scored(int priced, double median, double p90, int overvaluedByHalf) {
		@Override
		public String toString() {
			return String.format("%,4d priced, median |log err| %.3f, p90 %.3f, %,d overvalued 2x+",
					priced, median, p90, overvaluedByHalf);
		}
	}

	private static Scored score(List<Double> actuals, List<String> keys,
			Map<String, List<Double>> index) {
		List<Double> errors = new ArrayList<>();
		int overvalued = 0;

		for (int i = 0; i < actuals.size(); i++) {
			double actual = actuals.get(i);
			OptionalDouble estimate = median(index.get(keys.get(i)));

			if (estimate.isEmpty() || estimate.getAsDouble() <= 0.0d || actual <= 0.0d) {
				continue;
			}

			errors.add(Math.abs(Math.log(estimate.getAsDouble() / actual)));

			if (estimate.getAsDouble() >= 2.0d * actual) {
				overvalued++;
			}
		}

		List<Double> sorted = errors.stream().sorted().toList();

		if (sorted.isEmpty()) {
			return new Scored(0, Double.NaN, Double.NaN, overvalued);
		}

		return new Scored(sorted.size(), sorted.get(sorted.size() / 2),
				sorted.get(sorted.size() * 9 / 10), overvalued);
	}

	private static OptionalDouble median(List<Double> values) {
		if (values == null || values.size() < MIN_SAMPLES) {
			return OptionalDouble.empty();
		}

		List<Double> sorted = values.stream().sorted().toList();
		return OptionalDouble.of(sorted.get(sorted.size() / 2));
	}
}
