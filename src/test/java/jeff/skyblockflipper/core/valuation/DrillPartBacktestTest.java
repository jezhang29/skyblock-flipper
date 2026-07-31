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
 * 314 production signatures, so a part term produces cells of one. On a 24 hour holdout the pooled
 * key prices 5 parted sales and is within 1.5x on 4 of them; keying the parts prices <b>zero</b>, and
 * fixes not one of the 10 overvaluations on the way.
 *
 * <p>The pooling is also not doing harm where it can be acted on. Of the 75 production signatures
 * that hold more than one drill configuration, 52 never reach {@link ValueEstimate#MIN_SAMPLES} and
 * are quoted by nothing at all. Among the 23 that are quoted, <b>not one values an unparted drill at
 * 2x or more of what unparted drills of it fetch</b> - the two pools that disagree by 2x or more do
 * it in the harmless direction, quoting a built drill below its worth.
 */
@EnabledIfSystemProperty(named = "skyblockflipper.tapeBacktest", matches = "true")
class DrillPartBacktestTest {
	private static final String DEFAULT_TAPE_DIR = "run/config/skyblock-flipper/tape";
	private static final int ALL_DAYS = 365;
	private static final long HOLDOUT_HOURS = 24L;

	private static final String NONE = "(none)";

	/** How a candidate part term reaches the signature, if at all. */
	private enum Keying {
		/** Today: the parts are not read, so a drill is keyed by its reforge, enchants and gems. */
		POOLED,
		/** Every part, the polarvoid count and the coating, the way a full attribute term would. */
		EXACT,
		/** One bit: something was installed. The cheapest term that could still split a pool. */
		FLAG
	}

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

		forEachSale((item, extra, timestamp, unitPrice) -> {
			String term = drillTerm(extra);
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

			if (bareApartFromParts(item)) {
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

		assertTrue(parted > 0, "no drill part sales on the tape at " + tapeDir());

		// The reason a part term cannot be priced: it is nearly an identifier. Sixty-nine
		// configurations over 405 sales is six sales each before the signature they sit under splits
		// them further, and MIN_SAMPLES is 6.
		assertTrue(byTerm.size() * 10 > parted, "the drill parts are expected to be near-unique per "
				+ "sale, which is why keying them prices nothing, but " + parted + " sales carried "
				+ "only " + byTerm.size() + " distinct configurations");
	}

	/**
	 * Keying the parts costs every parted valuation and fixes nothing.
	 *
	 * <p>Trains on everything older than the newest 24 hours and prices what is in it, restricted to
	 * the sixteen item ids that ever carry a part. Three arms: today's key, today's key plus the full
	 * part term, and today's key plus a single bit saying something was installed.
	 *
	 * <p>Today's key prices 5 parted sales and lands within 1.5x on 4. The exact term prices 0 - each
	 * held-out parted sale is the only one of its configuration - and the flag prices 0 too, which is
	 * the interesting half: even one bit is enough to shatter these pools, because the parted sales
	 * under a given drill signature are usually one or two.
	 *
	 * <p>Neither arm removes a single one of the 10 overvaluations, so there is nothing on the other
	 * side of the ledger to weigh the lost coverage against.
	 */
	@Test
	void keyingThePartsCostsEveryPartedValuationAndFixesNothing() throws Exception {
		Set<String> drillIds = idsThatEverCarryAPart();
		long cutoff = newestTimestamp() - HOLDOUT_HOURS * 3_600_000L;

		Scored pooled = score(drillIds, cutoff, Keying.POOLED);
		Scored exact = score(drillIds, cutoff, Keying.EXACT);
		Scored flag = score(drillIds, cutoff, Keying.FLAG);

		System.out.printf("%n%,d item ids ever carry a drill part%n", drillIds.size());
		System.out.printf("held-out sales of those ids:%n  %-26s %s%n  %-26s %s%n  %-26s %s%n",
				"today (parts unread)", pooled, "with the full part term", exact,
				"with a modified flag only", flag);

		assertEquals(0, exact.partedPriced(), "the exact part term is expected to price no parted "
				+ "sale at all, since each configuration is near-unique");

		// The flag is the cheapest term that could work, and it is measured rather than assumed away:
		// if the parted sales under a signature were many, one bit would split them cleanly.
		assertTrue(flag.partedPriced() < pooled.partedPriced(), "even a single modified bit is "
				+ "expected to cost parted valuations, but it priced " + flag.partedPriced()
				+ " against " + pooled.partedPriced());

		// The benefit side, which is empty. Any fake snipe either term removes would show up here.
		assertEquals(pooled.overvalued(), exact.overvalued(), "the exact part term is expected to fix "
				+ "no overvaluation, because no quotable pool overvalues an unparted drill");
		assertEquals(pooled.overvalued(), flag.overvalued(), "the modified flag is expected to fix no "
				+ "overvaluation either");

		// And the pooled key is actively right about the sales in question, not merely no worse.
		assertTrue(pooled.partedWithinHalf() * 4 > pooled.partedPriced() * 3,
				"the pooled key is expected to value most parted sales within 1.5x of what they "
						+ "fetched, but only " + pooled.partedWithinHalf() + " of "
						+ pooled.partedPriced() + " landed there");
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

		forEachSale((item, extra, timestamp, unitPrice) -> perSignature
				.computeIfAbsent(item.signature(), k -> new TreeMap<>())
				.computeIfAbsent(orNone(drillTerm(extra)), k -> new ArrayList<>())
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
			List<Double> all = variants.values().stream().flatMap(List::stream).toList();

			// The pool as production sees it: one median, and only if it clears MIN_SAMPLES.
			OptionalDouble quoted = median(all, ValueEstimate.MIN_SAMPLES);

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
	 * @param partedPriced     held-out sales carrying a part that got a valuation at all
	 * @param partedWithinHalf of those, the ones valued between 0.67x and 1.5x of what they fetched
	 * @param overvalued       sales valued at 2x or more of what they fetched, the fake snipes
	 */
	private record Scored(int priced, int partedPriced, int partedWithinHalf, int overvalued,
			double median, double p90) {
		@Override
		public String toString() {
			return String.format("%,5d priced (%,d parted, %,d of them within 1.5x), %,d over 2x, "
					+ "median |log err| %.3f, p90 %.3f",
					priced, partedPriced, partedWithinHalf, overvalued, median, p90);
		}
	}

	/** Replays the tape into the two indices production builds, then prices the holdout from them. */
	private static Scored score(Set<String> drillIds, long cutoff, Keying keying) throws Exception {
		Map<String, List<Double>> exact = new HashMap<>();
		Map<String, List<Double>> coarse = new HashMap<>();
		List<Held> holdout = new ArrayList<>();

		forEachSale((item, extra, timestamp, unitPrice) -> {
			if (!drillIds.contains(item.skyblockId())) {
				return;
			}

			String parts = drillTerm(extra);
			String term = switch (keying) {
				case POOLED -> "";
				case EXACT -> parts.isEmpty() ? "" : "|" + parts;
				case FLAG -> parts.isEmpty() ? "" : "|drill=modified";
			};
			// The bare guard: with a part term in the key, a built drill is no longer an unmodified
			// item, so it leaves the coarse index the way a starred or dyed one does.
			boolean bare = bareApartFromParts(item) && term.isEmpty();
			String coarseKey = ActiveListing.coarseKey(item.displayName(), item.rarity());

			if (timestamp >= cutoff) {
				holdout.add(new Held(unitPrice, item.signature() + term, bare ? coarseKey : "", parts));
				return;
			}

			exact.computeIfAbsent(item.signature() + term, k -> new ArrayList<>()).add(unitPrice);

			if (bare) {
				coarse.computeIfAbsent(coarseKey, k -> new ArrayList<>()).add(unitPrice);
			}
		});

		List<Double> errors = new ArrayList<>();
		int partedPriced = 0;
		int partedWithinHalf = 0;
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

			if (!sale.parts().isEmpty()) {
				partedPriced++;

				if (ratio > 2.0d / 3.0d && ratio < 1.5d) {
					partedWithinHalf++;
				}
			}
		}

		List<Double> sorted = errors.stream().sorted().toList();

		return new Scored(sorted.size(), partedPriced, partedWithinHalf, overvalued,
				sorted.isEmpty() ? Double.NaN : sorted.get(sorted.size() / 2),
				sorted.isEmpty() ? Double.NaN : sorted.get(sorted.size() * 9 / 10));
	}

	/** A held-out sale, the key it would be priced under, and the coarse key if it is eligible. */
	private record Held(double price, String key, String coarseKey, String parts) {
	}

	/**
	 * Which ids the question is even about.
	 *
	 * <p>A separate pass over the tape rather than a field on shared state: the tape is streamed a
	 * line at a time precisely so a day of it never sits in memory.
	 */
	private static Set<String> idsThatEverCarryAPart() throws Exception {
		Set<String> ids = new HashSet<>();

		forEachSale((item, extra, timestamp, unitPrice) -> {
			if (!drillTerm(extra).isEmpty()) {
				ids.add(item.skyblockId());
			}
		});

		assertTrue(!ids.isEmpty(), "no drill part sales on the tape at " + tapeDir());
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
	private static String drillTerm(NbtCompound extra) {
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

	/** {@link FairValueModel}'s admission test for the coarse index, minus the parts themselves. */
	private static boolean bareApartFromParts(DecodedItem item) {
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

	private static String spread(List<Double> prices) {
		if (prices == null || prices.isEmpty()) {
			return String.format("%34s", "-");
		}

		return String.format("%,10.0f %,11.0f %,11.0f",
				prices.stream().mapToDouble(Double::doubleValue).min().orElse(0),
				median(prices, 1).orElse(0),
				prices.stream().mapToDouble(Double::doubleValue).max().orElse(0));
	}

	private static String trim(String key) {
		return key.length() <= 46 ? key : key.substring(0, 43) + "...";
	}
}
