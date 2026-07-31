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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Why {@code ethermerge} belongs in the signature, measured. Opt-in, needs a recorded tape:
 * {@code ./gradlew test -PtapeBacktest --tests '*EthermergeBacktestTest'}.
 *
 * <p>It is the first attribute off {@code UnreadAttributeProbeTest}'s ranking rather than off the
 * spread ranking, and it is the first in four tries to survive the measurement. An Etherwarp
 * Conduit merged into an {@code ASPECT_OF_THE_VOID} is a permanent upgrade that costs a Transmission
 * Tuner and a conduit, and the market pays for it: under identical production signatures the merged
 * sales run several times the plain ones.
 *
 * <p>What makes it different from {@code power_ability_scroll} and the drill parts, which were bigger
 * markets and both measured out:
 *
 * <ul>
 * <li><b>It is one bit</b>, not an enumeration and not a near-identifier. {@code ethermerge} reads 1
 *     on every sale that has it, so splitting a pool splits it in two rather than into nine cells of
 *     one.
 * <li><b>Plain sales dominate the mixed pools</b>, the opposite of the scroll. A merged Aspect of the
 *     Void sits in a pool of unmerged ones and is priced by their median, so the pool is wrong about
 *     the merged item and stays right about the plain one - and there are enough plain sales left
 *     after the split for both cells to clear {@link ValueEstimate#MIN_SAMPLES}.
 * <li><b>It is nearly free.</b> Only 12 taped sales would lose their exact key, against 24 for the
 *     scroll and 405 configurations for the drill parts.
 * </ul>
 */
@EnabledIfSystemProperty(named = "skyblockflipper.tapeBacktest", matches = "true")
class EthermergeBacktestTest {
	private static final String DEFAULT_TAPE_DIR = "run/config/skyblock-flipper/tape";
	private static final int ALL_DAYS = 365;
	private static final long HOLDOUT_HOURS = 24L;

	private static final String ETHERMERGE = "ethermerge";
	private static final String TUNED = "tuned_transmission";
	private static final String NONE = "(none)";

	/** How the merge reaches the signature, if at all. */
	private enum Keying {
		/** Today: a merged Aspect of the Void is keyed exactly like an unmerged one. */
		POOLED,
		/** One bit, which is what ships. */
		MERGE,
		/** The bit plus the Transmission Tuner level, the finer term this rejects. */
		MERGE_AND_TUNER
	}

	/**
	 * The market, and which way its mixed pools are wrong.
	 *
	 * <p>The direction is the whole question. A pool that undervalues its merged sales costs nothing -
	 * an item quoted below its worth is one nobody buys - while a pool that quotes a plain Aspect of
	 * the Void at a merged one's price is a fake snipe, and that is the error money is lost on.
	 */
	@Test
	void mixedPoolsOvervalueTheirPlainSales() throws Exception {
		Map<String, Map<String, List<Double>>> perSignature = new HashMap<>();
		Map<String, int[]> byId = new TreeMap<>();
		long[] coins = {0L};
		int[] bare = {0};

		forEachSale((item, extra, timestamp, unitPrice) -> {
			boolean merged = extra.flag(ETHERMERGE);

			if (merged) {
				byId.computeIfAbsent(item.skyblockId(), k -> new int[1])[0]++;
				coins[0] += (long) unitPrice;

				if (bareApartFromMerge(item)) {
					bare[0]++;
				}
			}

			perSignature
					.computeIfAbsent(unmerged(item), k -> new TreeMap<>())
					.computeIfAbsent(merged ? term(extra) : NONE, k -> new ArrayList<>())
					.add(unitPrice);
		});

		int mixed = 0;
		int quotable = 0;
		int plainOvervalued = 0;
		int mergedOvervalued = 0;
		List<String> table = new ArrayList<>();

		for (Map.Entry<String, Map<String, List<Double>>> entry : perSignature.entrySet()) {
			Map<String, List<Double>> variants = entry.getValue();

			if (variants.size() < 2 || variants.keySet().stream().noneMatch(v -> !v.equals(NONE))) {
				continue;
			}

			mixed++;
			OptionalDouble quoted = median(
					variants.values().stream().flatMap(List::stream).toList(),
					ValueEstimate.MIN_SAMPLES);

			if (quoted.isEmpty()) {
				continue;
			}

			quotable++;
			StringBuilder detail = new StringBuilder();
			double worst = 1.0d;

			for (Map.Entry<String, List<Double>> variant : variants.entrySet()) {
				double med = median(variant.getValue(), 1).orElseThrow();
				double ratio = quoted.getAsDouble() / med;
				worst = Math.max(worst, Math.max(ratio, 1.0d / ratio));

				if (ratio >= 2.0d) {
					if (variant.getKey().equals(NONE)) {
						plainOvervalued++;
					} else {
						mergedOvervalued++;
					}
				}

				detail.append(String.format("%s n=%d med %,.0f; ",
						variant.getKey(), variant.getValue().size(), med));
			}

			table.add(String.format("  %5.1fx  quoted %,15.0f  %-46s %s",
					worst, quoted.getAsDouble(), trim(entry.getKey()), detail));
		}

		System.out.printf("%n%,d merged sales carrying %,d coins, %,d of them otherwise bare; ids: %s%n",
				byId.values().stream().mapToInt(c -> c[0]).sum(), coins[0], bare[0],
				byId.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()[0]).toList());
		System.out.printf("%n%,d production signatures mix merged and unmerged sales, %,d of them "
				+ "quotable:%n", mixed, quotable);
		table.stream().sorted().forEach(System.out::println);
		System.out.printf("  quotable variants the pooled median overvalues 2x+: %,d plain, %,d merged%n",
				plainOvervalued, mergedOvervalued);

		// The finding. Unlike the scroll and the drill parts, the mixed pools here are wrong in the
		// expensive direction: the plain sale is the one quoted high, and a plain sale is what a flip
		// would be bought on.
		assertTrue(plainOvervalued > 0, "ethermerge is only worth keying because its mixed pools quote "
				+ "plain sales above what plain sales fetch, and none did");
	}

	/**
	 * Keying it prices the merged sales and stops the fake snipes, at a cost of a dozen valuations.
	 *
	 * <p>Trains on everything older than the newest 24 hours and scores what is in it, restricted to
	 * the item ids that ever carry the attribute.
	 */
	@Test
	void keyingTheMergeFixesMoreThanItCosts() throws Exception {
		Set<String> ids = idsThatEverMerge();
		long cutoff = newestTimestamp() - HOLDOUT_HOURS * 3_600_000L;

		Scored pooled = score(ids, cutoff, Keying.POOLED);
		Scored keyed = score(ids, cutoff, Keying.MERGE);
		Scored tuned = score(ids, cutoff, Keying.MERGE_AND_TUNER);

		System.out.printf("%n%,d item ids ever carry an ethermerge%n", ids.size());
		System.out.printf("held-out sales of those ids:%n  %-26s %s%n  %-26s %s%n  %-26s %s%n",
				"today (merge unread)", pooled, "with the merge keyed", keyed,
				"with the tuner level too", tuned);

		// The tuner level rides along on a merged item and is worth 1.06x on top of it - 24,900,000
		// against 26,400,000 under the largest mixed pool - so it splits cells for a difference the
		// market barely prices. Keeping it out is what makes the term one bit.
		assertTrue(tuned.priced() <= keyed.priced(), "the tuner level is expected to cost coverage "
				+ "on top of the merge, since it splits merged sales again");
		assertTrue(tuned.overvalued() >= keyed.overvalued(), "the tuner level is expected to fix no "
				+ "overvaluation the merge alone does not");

		assertTrue(keyed.overvalued() < pooled.overvalued(), "keying the merge is expected to remove "
				+ "fake snipes, but overvaluations went from " + pooled.overvalued() + " to "
				+ keyed.overvalued());

		// Coverage is the price of every signature term, and this one is expected to be cheap.
		assertTrue(keyed.priced() * 10 > pooled.priced() * 9, "the merge is expected to cost few "
				+ "valuations, but priced sales went from " + pooled.priced() + " to " + keyed.priced());
	}

	/**
	 * @param mergedPriced held-out merged sales that got a valuation at all
	 * @param overvalued   sales valued at 2x or more of what they fetched, the fake snipes
	 */
	private record Scored(int priced, int mergedPriced, int overvalued, double median, double p90) {
		@Override
		public String toString() {
			return String.format("%,5d priced (%,d merged), %,d over 2x, median |log err| %.3f, "
					+ "p90 %.3f", priced, mergedPriced, overvalued, median, p90);
		}
	}

	/** Replays the tape into the two indices production builds, then prices the holdout from them. */
	private static Scored score(Set<String> ids, long cutoff, Keying keying) throws Exception {
		Map<String, List<Double>> exact = new HashMap<>();
		Map<String, List<Double>> coarse = new HashMap<>();
		List<Held> holdout = new ArrayList<>();

		forEachSale((item, extra, timestamp, unitPrice) -> {
			if (!ids.contains(item.skyblockId())) {
				return;
			}

			boolean merged = extra.flag(ETHERMERGE);
			String base = unmerged(item);
			String term = merged ? switch (keying) {
				case POOLED -> "";
				case MERGE -> "|ethermerge";
				case MERGE_AND_TUNER -> "|" + term(extra);
			} : "";
			String key = base + term;
			boolean bare = bareApartFromMerge(item) && term.isEmpty();
			String coarseKey = ActiveListing.coarseKey(item.displayName(), item.rarity());

			if (timestamp >= cutoff) {
				holdout.add(new Held(unitPrice, key, bare ? coarseKey : "", merged));
				return;
			}

			exact.computeIfAbsent(key, k -> new ArrayList<>()).add(unitPrice);

			if (bare) {
				coarse.computeIfAbsent(coarseKey, k -> new ArrayList<>()).add(unitPrice);
			}
		});

		List<Double> errors = new ArrayList<>();
		int mergedPriced = 0;
		int overvalued = 0;

		for (Held sale : holdout) {
			OptionalDouble estimate = median(exact.get(sale.key()), ValueEstimate.MIN_SAMPLES);

			if (estimate.isEmpty() && !sale.coarseKey().isEmpty()) {
				estimate = median(coarse.get(sale.coarseKey()), ValueEstimate.MIN_SAMPLES);
			}

			if (estimate.isEmpty() || estimate.getAsDouble() <= 0.0d || sale.price() <= 0.0d) {
				continue;
			}

			double ratio = estimate.getAsDouble() / sale.price();
			errors.add(Math.abs(Math.log(ratio)));

			if (ratio >= 2.0d) {
				overvalued++;
			}

			if (sale.merged()) {
				mergedPriced++;
			}
		}

		List<Double> sorted = errors.stream().sorted().toList();

		return new Scored(sorted.size(), mergedPriced, overvalued,
				sorted.isEmpty() ? Double.NaN : sorted.get(sorted.size() / 2),
				sorted.isEmpty() ? Double.NaN : sorted.get(sorted.size() * 9 / 10));
	}

	/** A held-out sale, the key it would be priced under, and the coarse key if it is eligible. */
	private record Held(double price, String key, String coarseKey, boolean merged) {
	}

	private static Set<String> idsThatEverMerge() throws Exception {
		Set<String> ids = new HashSet<>();

		forEachSale((item, extra, timestamp, unitPrice) -> {
			if (extra.flag(ETHERMERGE)) {
				ids.add(item.skyblockId());
			}
		});

		assertTrue(!ids.isEmpty(), "no ethermerged sales on the tape at " + tapeDir());
		return ids;
	}

	/**
	 * The candidate term: the merge, plus the tuning that rides on it.
	 *
	 * <p>{@code tuned_transmission} is the Transmission Tuner level and only ever appears on a merged
	 * item, so it is measured as part of the same term rather than as a competing one. Its own row in
	 * the probe shows no overvaluation at all, which is what one would expect from a term that never
	 * varies independently.
	 */
	private static String term(NbtCompound extra) {
		int tuned = extra.intOr(TUNED, 0);
		return tuned > 0 ? "ethermerge,tuned=" + tuned : "ethermerge";
	}

	/**
	 * The signature this item had before the merge was read, which is the baseline being measured.
	 *
	 * <p>Now that the term ships, {@link DecodedItem#signature()} already carries it, so the "today"
	 * arm has to take it back off. Safe as a strip because the clause is a fixed string and the only
	 * thing that can follow it is the dye clause.
	 */
	private static String unmerged(DecodedItem item) {
		return item.signature().replaceFirst("\\|ethermerge(?=\\||$)", "");
	}

	/** {@link FairValueModel}'s admission test for the coarse index, minus the merge itself. */
	private static boolean bareApartFromMerge(DecodedItem item) {
		return !item.isPet()
				&& !item.isPotion()
				&& !item.hasQuality()
				&& !item.isDyed()
				&& item.stars() == 0
				&& !item.recombobulated()
				&& item.hotPotatoBooks() == 0
				&& item.enchantments().isEmpty()
				&& item.gemstones().isEmpty()
				&& item.attributes().isEmpty()
				&& item.runes().isEmpty();
	}

	/** The decoded item, its raw {@code ExtraAttributes}, when it sold, and its unit price. */
	private interface SaleVisitor {
		void accept(DecodedItem item, NbtCompound extra, long timestamp, double unitPrice);
	}

	private static void forEachSale(SaleVisitor visitor) throws Exception {
		tape().forEachRecent(ALL_DAYS, sale -> {
			if (!sale.bin() || sale.price() <= 0L) {
				return;
			}

			NbtCompound root;

			try {
				root = NbtReader.readItemBytes(sale.itemBytes());
			} catch (Exception e) {
				return;
			}

			if (!(root.list("i").stream().findFirst().orElse(null) instanceof NbtCompound stack)) {
				return;
			}

			NbtCompound extra = stack.child("tag").child("ExtraAttributes");

			ItemDecoder.fromRoot(root).ifPresent(item -> visitor.accept(item, extra, sale.timestamp(),
					(double) sale.price() / Math.max(1, item.count())));
		});
	}

	private static long newestTimestamp() throws Exception {
		long[] newest = {0L};
		tape().forEachRecent(ALL_DAYS, sale -> newest[0] = Math.max(newest[0], sale.timestamp()));

		assertTrue(newest[0] > 0L, "no sales on the tape at " + tapeDir());
		return newest[0];
	}

	private static SalesTape tape() {
		return new SalesTape(Path.of(tapeDir()), 3650);
	}

	private static String tapeDir() {
		return System.getProperty("skyblockflipper.tapeDir", DEFAULT_TAPE_DIR);
	}

	private static OptionalDouble median(List<Double> values, int minSamples) {
		if (values == null || values.size() < minSamples) {
			return OptionalDouble.empty();
		}

		List<Double> sorted = values.stream().sorted().toList();
		return OptionalDouble.of(sorted.get(sorted.size() / 2));
	}

	private static String trim(String key) {
		return key.length() <= 46 ? key : key.substring(0, 43) + "...";
	}
}
