package jeff.skyblockflipper.core.valuation;

import jeff.skyblockflipper.core.item.DecodedItem;
import jeff.skyblockflipper.core.item.ItemDecoder;
import jeff.skyblockflipper.core.model.ActiveListing;
import jeff.skyblockflipper.core.nbt.NbtCompound;
import jeff.skyblockflipper.core.nbt.NbtReader;
import jeff.skyblockflipper.core.tape.SalesTape;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

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
 *   <li>{@code color} is a raw {@code r:g:b} triple. It stays out, and the second test is the
 *       reason rather than an oversight. See {@link #theRawColourCannotBeKeyedOnWithoutLosingMore}.
 *   </ul>
 *
 * <p>Neither is measured by holding out six hours and scoring key variants, the way the potion and
 * dungeon-quality splits were. That was tried first and has no power here: 674 dyed sales over six
 * days leave 46 in the holdout, of which the full signature prices <b>one</b>, with or without a dye
 * term. Painted items are rare enough that the question is not which key scores better on a holdout
 * but whether the key pools things it should not, so both tests below measure that directly.
 */
@EnabledIfSystemProperty(named = "skyblockflipper.tapeBacktest", matches = "true")
class DyeSignatureBacktestTest {
	private static final String DEFAULT_TAPE_DIR = "run/config/skyblock-flipper/tape";

	private static final long HOLDOUT_HOURS = 6L;
	private static final int ALL_DAYS = 365;

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

		forEachSale((item, timestamp, paint, unitPrice) -> {
			String base = withoutPaint(item);

			if (!paint[0].isEmpty()) {
				counts[0]++;

				// The claim the zero-cost argument rests on. A dyed item that were otherwise bare
				// would lose its coarse valuation the moment the signature names the dye, because
				// bareness is what admits an item to the coarse index at all.
				if (bareApartFromPaint(item)) {
					counts[1]++;
				}

				dyed.computeIfAbsent(base, k -> new ArrayList<>()).add(unitPrice);
			} else if (paint[1].isEmpty()) {
				plain.computeIfAbsent(base, k -> new ArrayList<>()).add(unitPrice);
			}
		});

		int mixed = 0;
		double widest = 1.0d;
		String widestKey = "";
		List<String> table = new ArrayList<>();

		for (Map.Entry<String, List<Double>> entry : dyed.entrySet()) {
			OptionalDouble withDye = median(entry.getValue(), 1);
			OptionalDouble without = median(plain.get(entry.getKey()), 1);

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
	 * Why the raw colour stays out, which is the more interesting half.
	 *
	 * <p>It is a bigger pooling than the dye by any static measure - {@code GOBLIN_BOOTS} holds 466
	 * sales at 12,000 coins and two at 60,000,000 on one key, 5,000x - and keying it still loses.
	 * Three measured reasons, all checked below:
	 *
	 * <ol>
	 *   <li><b>It shatters.</b> On the items that carry it densely it is near-unique per sale, so an
	 *       exact colour key names a cell of one and prices nothing. Nor could a coarser one help:
	 *       an exotic colour is a one-off, so it never reaches {@link ValueEstimate#MIN_SAMPLES}
	 *       under any key that distinguishes it. Keying it turns a wrong number into no number.
	 *   <li><b>The wrong number is mostly right.</b> The items where colour is dense are fashion
	 *       items whose every sale is coloured, so their coarse pool is entirely coloured and prices
	 *       them correctly. Excluding coloured items from that pool - which is what a signature term
	 *       plus the {@code isBare} guard would do - costs most of them their valuation to fix a
	 *       handful of overvaluations.
	 *   <li><b>It contaminates nothing.</b> The two 60,000,000 coin exotics sit in a pool of 466 at
	 *       12,000, and the estimate is a median, so they do not move it. Plain items priced from
	 *       the mixed pool score identically to plain items priced from a pool with every coloured
	 *       sale removed.
	 * </ol>
	 */
	@Test
	void theRawColourCannotBeKeyedOnWithoutLosingMore() throws Exception {
		Map<String, Map<String, Integer>> coloursPerItem = new HashMap<>();
		Map<String, List<Double>> mixedPool = new HashMap<>();
		Map<String, List<Double>> uncolouredPool = new HashMap<>();
		List<Held> heldColoured = new ArrayList<>();
		List<Held> heldPlain = new ArrayList<>();

		long cutoff = newestTimestamp() - HOLDOUT_HOURS * 3_600_000L;

		forEachSale((item, timestamp, paint, unitPrice) -> {
			boolean coloured = !paint[1].isEmpty();

			if (coloured) {
				coloursPerItem.computeIfAbsent(item.skyblockId(), k -> new HashMap<>())
						.merge(paint[1], 1, Integer::sum);
			}

			// Only bare items reach the coarse index, so only they are affected either way.
			if (!bareApartFromPaint(item)) {
				return;
			}

			String coarse = ActiveListing.coarseKey(item.displayName(), item.rarity());

			if (timestamp >= cutoff) {
				(coloured ? heldColoured : heldPlain).add(new Held(unitPrice, coarse));
				return;
			}

			mixedPool.computeIfAbsent(coarse, k -> new ArrayList<>()).add(unitPrice);

			if (!coloured) {
				uncolouredPool.computeIfAbsent(coarse, k -> new ArrayList<>()).add(unitPrice);
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

		Scored colouredToday = score(heldColoured, mixedPool);
		Scored colouredExcluded = score(heldColoured, uncolouredPool);
		Scored plainToday = score(heldPlain, mixedPool);
		Scored plainCleaned = score(heldPlain, uncolouredPool);

		System.out.printf("held-out coloured sales, priced coarsely:%n  %-34s %s%n  %-34s %s%n",
				"today (mixed pool)", colouredToday, "if colour keyed out", colouredExcluded);
		System.out.printf("held-out plain sales, priced coarsely:%n  %-34s %s%n  %-34s %s%n",
				"today (mixed pool)", plainToday, "with coloured sales removed", plainCleaned);

		// 1. It shatters. Nearly one colour per sale, so an exact colour key prices nothing.
		assertTrue(distinct > sales * 9 / 10, "the raw colour is expected to be near-unique per sale "
				+ "on the item that carries it most, but " + densest + " had " + distinct
				+ " colours across " + sales + " sales");

		// 2. Keying it out costs most coloured sales their valuation, and they are mostly correct
		// today. If this ever inverts - if the coloured population stops being dominated by items
		// whose whole pool is coloured - the call flips and colour should go into the signature.
		assertTrue(colouredExcluded.priced() * 5 < colouredToday.priced(),
				"keying the colour out should cost most coloured sales their coarse valuation, "
						+ "but it went from " + colouredToday.priced() + " to "
						+ colouredExcluded.priced());

		assertTrue(colouredToday.overvaluedByHalf() * 10 < colouredToday.priced(),
				"the mixed coarse pool is expected to be right about most coloured sales, but it "
						+ "overvalued " + colouredToday.overvaluedByHalf() + " of "
						+ colouredToday.priced());

		// 3. And leaving them in poisons nothing, because the estimate is a median.
		assertEquals(plainToday.overvaluedByHalf(), plainCleaned.overvaluedByHalf(),
				"coloured sales sitting in the coarse pool are expected to move no plain valuation, "
						+ "since a handful of outliers cannot shift a median");
	}

	/** A held-out sale and the coarse key it would be priced under. */
	private record Held(double price, String key) {
	}

	/**
	 * @param overvaluedByHalf sales the key valued at 2x or more of what they actually fetched -
	 *                         the fake snipes, and the only direction of error that costs coins
	 */
	private record Scored(int priced, double median, double p90, int overvaluedByHalf) {
		@Override
		public String toString() {
			return String.format("%,5d priced, median |log err| %.3f, p90 %.3f, %,d overvalued 2x+",
					priced, median, p90, overvaluedByHalf);
		}
	}

	private static Scored score(List<Held> held, Map<String, List<Double>> index) {
		List<Double> errors = new ArrayList<>();
		int overvalued = 0;

		for (Held sale : held) {
			OptionalDouble estimate = median(index.get(sale.key()), ValueEstimate.MIN_SAMPLES);

			if (estimate.isEmpty() || estimate.getAsDouble() <= 0.0d || sale.price() <= 0.0d) {
				continue;
			}

			errors.add(Math.abs(Math.log(estimate.getAsDouble() / sale.price())));

			if (estimate.getAsDouble() >= 2.0d * sale.price()) {
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

	/**
	 * {@link FairValueModel}'s admission test for the coarse index, minus the paint itself.
	 *
	 * <p>Deliberately not delegating to production: once the dye reaches the signature, production
	 * says a dyed item is not bare, and what both tests need to know is what it was apart from the
	 * paint. That is the quantity the zero-cost argument is about.
	 */
	private static boolean bareApartFromPaint(DecodedItem item) {
		return !item.isPet()
				&& !item.isPotion()
				&& !item.hasQuality()
				&& item.stars() == 0
				&& !item.recombobulated()
				&& item.hotPotatoBooks() == 0
				&& item.enchantments().isEmpty()
				&& item.gemstones().isEmpty()
				&& item.attributes().isEmpty()
				&& item.runes().isEmpty();
	}

	/**
	 * The signature with the shipped dye clause taken back off, so a dyed sale and a plain one are
	 * compared on the key they share rather than on the one the split just gave them.
	 *
	 * <p>Safe as a suffix strip because {@code signature()} adds the clause last and its contents
	 * never contain the separator.
	 */
	private static String withoutPaint(DecodedItem item) {
		return item.signature().replaceFirst("\\|dye=[^|]*$", "");
	}

	/** What the callers all need per sale: the decoded item, its paint, and its unit price. */
	private interface SaleVisitor {
		void accept(DecodedItem item, long timestamp, String[] paint, double unitPrice);
	}

	private static void forEachSale(SaleVisitor visitor) throws Exception {
		SalesTape tape = new SalesTape(Path.of(
				System.getProperty("skyblockflipper.tapeDir", DEFAULT_TAPE_DIR)), 3650);

		tape.forEachRecent(ALL_DAYS, sale -> {
			if (!sale.bin() || sale.price() <= 0L) {
				return;
			}

			NbtCompound root;

			try {
				root = NbtReader.readItemBytes(sale.itemBytes());
			} catch (Exception e) {
				return;
			}

			// Every sale, painted or not: the plain pools are what the painted ones are compared
			// against, so leaving them out would measure the comparison against nothing.
			ItemDecoder.fromRoot(root).ifPresent(item -> visitor.accept(
					item, sale.timestamp(), paint(root),
					(double) sale.price() / Math.max(1, item.count())));
		});
	}

	private static long newestTimestamp() throws Exception {
		SalesTape tape = new SalesTape(Path.of(
				System.getProperty("skyblockflipper.tapeDir", DEFAULT_TAPE_DIR)), 3650);
		long[] newest = {0L};
		tape.forEachRecent(ALL_DAYS, sale -> newest[0] = Math.max(newest[0], sale.timestamp()));

		assertTrue(newest[0] > 0L, "no sales on the tape at " + DEFAULT_TAPE_DIR);
		return newest[0];
	}

	/** @return {@code {dye_item, color}}, either or both possibly "" */
	private static String[] paint(NbtCompound root) {
		if (!(root.list("i").stream().findFirst().orElse(null) instanceof NbtCompound item)) {
			return new String[] {"", ""};
		}

		NbtCompound extra = item.child("tag").child("ExtraAttributes");

		return new String[] {
				extra.string("dye_item").orElse(""),
				extra.string("color").orElse("")};
	}

	private static OptionalDouble median(List<Double> values, int minSamples) {
		if (values == null || values.size() < minSamples) {
			return OptionalDouble.empty();
		}

		List<Double> sorted = values.stream().sorted().toList();
		return OptionalDouble.of(sorted.get(sorted.size() / 2));
	}

	private static String trim(String key) {
		return key.length() <= 58 ? key : key.substring(0, 55) + "...";
	}
}
