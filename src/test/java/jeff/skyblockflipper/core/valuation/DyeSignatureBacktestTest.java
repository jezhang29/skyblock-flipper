package jeff.skyblockflipper.core.valuation;

import jeff.skyblockflipper.core.item.DecodedItem;
import jeff.skyblockflipper.core.nbt.NbtCompound;
import jeff.skyblockflipper.core.valuation.backtest.Backtest;
import jeff.skyblockflipper.core.valuation.backtest.CounterfactualKeying;
import jeff.skyblockflipper.core.valuation.backtest.SignatureTerms;
import jeff.skyblockflipper.core.valuation.backtest.TapeFixture;
import jeff.skyblockflipper.core.valuation.backtest.UnreadTerms;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What reading a leather item's paint is worth, measured. Opt-in, needs a recorded tape:
 * {@code ./gradlew test -PtapeBacktest --tests '*DyeSignatureBacktestTest'}.
 *
 * <p>Two attributes that look like one gap and are not. They are near-disjoint on the tape - of
 * 2,091 sales carrying either, 8 carry both - and they get opposite answers:
 *
 * <ul>
 *   <li>{@code dye_item} is a named dye somebody chose to apply, e.g. {@code DYE_PURE_BLACK}. It
 *       goes into the signature. See {@link #theNamedDyeSplitsKeysItCurrentlyPoolsWithThePlainItem}.
 *   <li>{@code color} is a raw {@code r:g:b} triple. It stays out, and the last two tests are the
 *       reason rather than an oversight.
 *   </ul>
 *
 * <p>The dye half is a pool-shape measurement rather than a holdout, and that is a limitation of the
 * tape rather than a choice: 674 dyed sales over six days leave 46 in a six-hour holdout, of which
 * the full signature prices one either way. The colour half does get a holdout, because the ids that
 * carry a colour trade often enough to fill one.
 */
@EnabledIfSystemProperty(named = "skyblockflipper.tapeBacktest", matches = "true")
class DyeSignatureBacktestTest {
	private static final long HOLDOUT_HOURS = 24L;

	/** Longer than any tape, so training is the unbounded replay these findings were measured on. */
	private static final Duration WHOLE_TAPE = Duration.ofDays(3650);

	private static final String DYE = "dye=";
	private static final String COLOUR = "color";

	/**
	 * Why the named dye ships: it costs nothing and it separates sales that are currently one key.
	 *
	 * <p>The cost side is the stronger half. Every dyed sale on the tape carries something else that
	 * already keeps it out of the coarse index - stars, enchantments, a reforge - so adding the dye
	 * to {@link DecodedItem#signature()} takes no item off a coarse valuation it has today. It only
	 * splits exact keys, and only ones that were pooling a dyed item with a plain one.
	 *
	 * <p><b>The benefit side is small, and it is small for an instructive reason.</b> Keyed on the
	 * item id alone a dye looks enormous - {@code SKELETON_MASTER_CHESTPLATE} sells at 199,990,000
	 * dyed against 240,000 plain, 833x - but against the signature production actually uses, 67 of
	 * 587 dyed keys hold an undyed sale and they run 0.9x to 2.1x. The gap between those two numbers
	 * is not the dye. It is that a dyed item is also a starred, recombobulated, enchanted one, so
	 * the investment terms were already doing the separating, exactly as they were for the maxed
	 * flag in {@link DungeonQualityBacktestTest} and exactly as the held item was for pet levels.
	 * <b>Measure a term against the key the model uses, not against the item id.</b>
	 *
	 * <p>So this ships on the same footing as the maxed flag: not because it is worth a lot, but
	 * because it is worth something, costs no coverage at all, and names a correlation that nothing
	 * enforces. The day somebody dyes a bare item, the term is what stops it pricing off the plain
	 * pool.
	 */
	@Test
	void theNamedDyeSplitsKeysItCurrentlyPoolsWithThePlainItem() throws Exception {
		Map<String, List<Double>> dyed = new HashMap<>();
		Map<String, List<Double>> plain = new HashMap<>();
		int[] counts = {0, 0};

		TapeFixture.forEachSale((item, extra, timestamp, unitPrice) -> {
			String base = SignatureTerms.without(item.signature(), DYE);

			if (item.isDyed()) {
				counts[0]++;

				// The claim the zero-cost argument rests on. A dyed item that were otherwise bare
				// would lose its coarse valuation the moment the signature names the dye, because
				// bareness is what admits an item to the coarse index at all.
				if (CounterfactualKeying.withoutTerm(DYE).isBare(item)) {
					counts[1]++;
				}

				dyed.computeIfAbsent(base, k -> new ArrayList<>()).add(unitPrice);
			} else if (colour(extra).isEmpty()) {
				plain.computeIfAbsent(base, k -> new ArrayList<>()).add(unitPrice);
			}
		});

		int mixed = 0;
		double widest = 1.0d;
		String widestKey = "";
		List<String> table = new ArrayList<>();

		for (Map.Entry<String, List<Double>> entry : dyed.entrySet()) {
			OptionalDouble withDye = TapeFixture.median(entry.getValue(), 1);
			OptionalDouble without = TapeFixture.median(plain.get(entry.getKey()), 1);

			if (withDye.isEmpty() || without.isEmpty() || without.getAsDouble() <= 0.0d) {
				continue;
			}

			mixed++;
			double ratio = withDye.getAsDouble() / without.getAsDouble();
			table.add(String.format("  %-58s dyed n=%3d med %,14.0f | plain n=%4d med %,13.0f (%.1fx)",
					trim(entry.getKey()), entry.getValue().size(), withDye.getAsDouble(),
					plain.get(entry.getKey()).size(), without.getAsDouble(), ratio));

			if (ratio > widest) {
				widest = ratio;
				widestKey = entry.getKey();
			}
		}

		System.out.printf("%n%,d sales carry a named dye_item, of which %,d are otherwise bare%n",
				counts[0], counts[1]);
		System.out.printf("%,d signatures carry a dye; %,d of those also hold an undyed sale:%n",
				dyed.size(), mixed);
		table.stream().sorted().forEach(System.out::println);
		System.out.printf("  widest: %s at %.1fx%n", trim(widestKey), widest);

		// The whole cost argument. If this ever fires, the dye term has started taking items off
		// the coarse index and it has to be re-argued on what the split is worth, not on being free.
		assertEquals(0, counts[1], "a dyed item that is otherwise bare would pay for the dye term "
				+ "with its coarse valuation, and the tape is expected to hold none");

		assertTrue(mixed > 0, "the dye term is only worth adding if some signature currently pools a "
				+ "dyed sale with an undyed one, and none does");

		// Deliberately well under the 2.1x measured, because the point of this bound is that the
		// split is worth *something* at the production key. If it ever falls to 1.0x the term has
		// become the pooled key wearing a longer name and should go.
		assertTrue(widest >= 1.5d, "the pooling the dye term breaks up should be worth at least 1.5x "
				+ "somewhere, or it is describing noise - widest was " + widest);
	}

	/**
	 * The raw colour is near-unique per sale, which is why no key can hold it.
	 *
	 * <p>It is a bigger pooling than the dye by any static measure - {@code GOBLIN_BOOTS} holds 466
	 * sales at 12,000 coins and two at 60,000,000 on one key, 5,000x - and it still cannot be keyed.
	 * An exact colour names a cell of one, and a coarser one cannot help either: an exotic colour is a
	 * one-off, so it never reaches {@link ValueEstimate#MIN_SAMPLES} under any key that distinguishes
	 * it from the ordinary ones.
	 */
	@Test
	void theRawColourShattersIntoCellsOfOne() throws Exception {
		Map<String, Map<String, Integer>> coloursPerItem = new HashMap<>();

		TapeFixture.forEachSale((item, extra, timestamp, unitPrice) -> {
			String colour = colour(extra);

			if (!colour.isEmpty()) {
				coloursPerItem.computeIfAbsent(item.skyblockId(), k -> new HashMap<>())
						.merge(colour, 1, Integer::sum);
			}
		});

		String densest = coloursPerItem.entrySet().stream()
				.max(Comparator.comparingInt(e -> e.getValue().values().stream()
						.mapToInt(Integer::intValue).sum()))
				.map(Map.Entry::getKey).orElseThrow();
		int distinct = coloursPerItem.get(densest).size();
		int sales = coloursPerItem.get(densest).values().stream().mapToInt(Integer::intValue).sum();

		System.out.printf("%ncolour is near-unique per sale where it is dense: %s carries %,d "
				+ "distinct colours across %,d sales%n", densest, distinct, sales);

		assertTrue(distinct > sales * 9 / 10, "the raw colour is expected to be near-unique per sale "
				+ "on the item that carries it most, but " + densest + " had " + distinct
				+ " colours across " + sales + " sales");
	}

	/**
	 * And keying it costs valuations without fixing anything, against the model that ships.
	 *
	 * <p>This arm replaces a hand-built pair of coarse pools the finding was first taken on. The
	 * question is the same - what happens to coloured and plain sales if the colour reaches the
	 * signature and the bare guard - but the model around it is now the real one, with its rung
	 * ladder, its 200-sample ring and its bid ratios.
	 *
	 * <p>On a 24h holdout of the 144 ids that ever carry a colour, keying it prices 10,221 sales
	 * against 11,264 unread and takes fake snipes from 868 to 840. <b>It costs 1,043 valuations to fix
	 * 28</b>, and coloured sales themselves go from 1,023 priced - 800 of them within 1.5x of what
	 * they fetched - to 5.
	 *
	 * <p>The lesson the colour is the source of: <b>keying an attribute converts a wrong number into
	 * no number, so check the wrong number is actually wrong first.</b> Here it mostly is not. The
	 * items carrying a colour densely are fashion items whose entire pool is coloured, so the pool is
	 * right about them, and the exotics that would justify a split are so rare that a median ignores
	 * them.
	 */
	@Test
	void keyingTheRawColourCostsMoreThanItFixes() throws Exception {
		Set<String> colouredIds = idsThatEverCarryAColour();
		long cutoff = TapeFixture.newestTimestamp() - HOLDOUT_HOURS * 3_600_000L;

		UnreadTerms pooledTerms = new UnreadTerms(DyeSignatureBacktestTest::colourTerm);
		UnreadTerms keyedTerms = new UnreadTerms(DyeSignatureBacktestTest::colourTerm);

		Backtest.Result pooled = Backtest.holdout(Keying.PRODUCTION, cutoff, WHOLE_TAPE,
				item -> colouredIds.contains(item.skyblockId()), pooledTerms);
		Backtest.Result keyed = Backtest.holdout(keyedTerms.keying(), cutoff, WHOLE_TAPE,
				item -> colouredIds.contains(item.skyblockId()), keyedTerms);

		System.out.printf("%n%,d item ids ever carry a raw colour%n", colouredIds.size());
		System.out.printf("held-out sales of those ids:%n  %-26s %s%n  %-26s %s%n",
				"today (colour unread)", pooled, "with colour= keyed", keyed);
		System.out.printf("  coloured sales priced: %,d unread (%,d within 1.5x), %,d keyed%n",
				pooled.count(pooledTerms::carries), pooled.within(1.5d, pooledTerms::carries),
				keyed.count(keyedTerms::carries));

		// The cost: an exact colour key is a cell of one, and the bare guard takes the coarse
		// fallback away as well, so a coloured sale ends up with nothing.
		assertTrue(keyed.count(keyedTerms::carries) * 5 < pooled.count(pooledTerms::carries),
				"keying the colour should cost most coloured sales their valuation, but priced "
						+ "coloured sales went from " + pooled.count(pooledTerms::carries) + " to "
						+ keyed.count(keyedTerms::carries));

		// The mixed pool is right about most of them, which is what makes that cost a loss.
		assertTrue(pooled.within(1.5d, pooledTerms::carries) * 4 > pooled.count(pooledTerms::carries) * 3,
				"the pooled key is expected to value most coloured sales within 1.5x of what they "
						+ "fetched, but only " + pooled.within(1.5d, pooledTerms::carries) + " of "
						+ pooled.count(pooledTerms::carries) + " landed there");

		// And the benefit side. If keying the colour ever starts removing fake snipes in bulk, the
		// exotics have begun moving medians and the call flips.
		assertTrue(pooled.overvaluedBy(2.0d) - keyed.overvaluedBy(2.0d)
						< pooled.priced().size() - keyed.priced().size(),
				"the colour term is kept out because it fixes fewer overvaluations than the "
						+ "valuations it costs, and this run fixed "
						+ (pooled.overvaluedBy(2.0d) - keyed.overvaluedBy(2.0d)) + " for "
						+ (pooled.priced().size() - keyed.priced().size()));
	}

	private static Set<String> idsThatEverCarryAColour() throws Exception {
		Set<String> ids = new HashSet<>();

		TapeFixture.forEachSale((item, extra, timestamp, unitPrice) -> {
			if (!colour(extra).isEmpty()) {
				ids.add(item.skyblockId());
			}
		});

		assertTrue(!ids.isEmpty(), "no coloured sales on the tape at " + TapeFixture.tapeDir());
		return ids;
	}

	/** The candidate term: the raw triple exactly as Hypixel writes it. */
	private static String colourTerm(DecodedItem item, NbtCompound extra) {
		String colour = colour(extra);
		return colour.isEmpty() ? "" : "color=" + colour;
	}

	private static String colour(NbtCompound extra) {
		return extra.string(COLOUR).orElse("");
	}

	private static String trim(String key) {
		return key.length() <= 58 ? key : key.substring(0, 55) + "...";
	}
}
