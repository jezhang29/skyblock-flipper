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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Why {@code power_ability_scroll} stays unread, measured. Opt-in, needs a recorded tape:
 * {@code ./gradlew test -PtapeBacktest --tests '*PowerScrollBacktestTest'}.
 *
 * <p>It was the top of the unread-attribute list by coins - 554 taped BIN sales carrying 185.8
 * billion, a single enumerable string with six values, and 5x to 600x within one item id. Every
 * one of those numbers is real and the term still measures out, for the reason the raw
 * {@code color} triple did: <b>the pooled key is already right about it.</b>
 *
 * <p>The 600x is the item id talking, which is now four for four. {@code RAGNAROCK_AXE} sells at
 * 509,990,000 with a Jasper scroll against 844,000 without, and {@code ASPECT_OF_THE_END} at
 * 249,950,000 against 435,000. Against {@link DecodedItem#signature()}, which is the key production
 * prices from, only 30 signatures hold more than one scroll variant or a scroll-less sale at all,
 * and their medians run 1.0x to 1.6x apart. A scrolled item is a starred, recombobulated, enchanted
 * one; the investment terms did the separating before the scroll was ever considered.
 *
 * <p>What is left is a term that would cost coverage without buying accuracy, which is the shape
 * the dye and the maxed flag were explicitly not. See the two tests below.
 */
@EnabledIfSystemProperty(named = "skyblockflipper.tapeBacktest", matches = "true")
class PowerScrollBacktestTest {
	private static final String DEFAULT_TAPE_DIR = "run/config/skyblock-flipper/tape";

	private static final String SCROLL = "power_ability_scroll";
	private static final long HOLDOUT_HOURS = 24L;
	private static final int ALL_DAYS = 365;

	/**
	 * The pooled key values a scrolled sale correctly, and keying the scroll would stop it.
	 *
	 * <p>Trains on everything older than the newest 24 hours and scores the sales in it, restricted
	 * to item ids that ever carry a scroll - the rest of the market cannot move either way, and
	 * leaving it in would bury the effect under 170,000 sales that are not about scrolls.
	 *
	 * <p>The result is lopsided. Today's key prices 19 of the held-out scrolled sales and is within
	 * 1.5x on 17 of them, including four {@code HYPERION} sales priced to within 2% at 1.2 billion
	 * coins. Adding {@code scroll=} to the signature and to the bare guard prices <b>2</b>, fixes one
	 * overvaluation, and changes the aggregate median and p90 not at all.
	 *
	 * <p>The mechanism behind "pooling is right" is worth stating, because it is not luck: on the
	 * items where a scroll is common the scrolled sales <b>dominate their own signature</b> - nine of
	 * the ten sales under one Hyperion key carry a Sapphire scroll - so the median they are priced
	 * against is a scrolled median. Splitting it out leaves a cell of nine and a cell of one, and
	 * {@link ValueEstimate#MIN_SAMPLES} rejects the second.
	 */
	@Test
	void keyingTheScrollCostsMoreValuationsThanItFixes() throws Exception {
		Set<String> scrolledIds = idsThatEverCarryAScroll();
		long cutoff = newestTimestamp() - HOLDOUT_HOURS * 3_600_000L;

		Scored pooled = score(scrolledIds, cutoff, false);
		Scored keyed = score(scrolledIds, cutoff, true);

		System.out.printf("%n%,d item ids ever carry a power scroll%n", scrolledIds.size());
		System.out.printf("held-out sales of those ids:%n  %-30s %s%n  %-30s %s%n",
				"today (scroll unread)", pooled, "with scroll= keyed", keyed);

		// The cost. Scrolled sales lose their valuation twice over: their exact key splits below
		// MIN_SAMPLES, and the bare guard drops the otherwise-bare ones off the coarse index too.
		assertTrue(keyed.scrolledPriced() * 5 < pooled.scrolledPriced(),
				"keying the scroll should cost most scrolled sales their valuation, but it went "
						+ "from " + pooled.scrolledPriced() + " to " + keyed.scrolledPriced());

		// The benefit, or the lack of one. Anything the split fixes has to show up here.
		assertTrue(pooled.scrolledPriced() - keyed.scrolledPriced()
						> 5 * (pooled.overvalued() - keyed.overvalued()),
				"the split is only worth its coverage if it fixes a comparable number of "
						+ "overvaluations, and it fixed " + (pooled.overvalued() - keyed.overvalued())
						+ " while costing " + (pooled.scrolledPriced() - keyed.scrolledPriced())
						+ " valuations");

		// And the pooled key is not merely no worse on average - it is actively right about the
		// sales in question. If this ever fails, scrolled items have stopped dominating their own
		// signatures and the whole argument has to be re-measured.
		assertTrue(pooled.scrolledWithinHalf() * 4 > pooled.scrolledPriced() * 3,
				"the pooled key is expected to value most scrolled sales within 1.5x of what they "
						+ "fetched, but only " + pooled.scrolledWithinHalf() + " of "
						+ pooled.scrolledPriced() + " landed there");
	}

	/**
	 * And the mixed pools do not overvalue the plain items in them, which is the direction that
	 * costs coins.
	 *
	 * <p>Only 30 production signatures pool a scroll with anything else at all, and this walks every
	 * one of them: the pooled median each sale is priced against today, against what each variant
	 * inside it actually fetched. The whole table is printed, because it is the case against the
	 * 600x headline - measured at the signature rather than at the item id, the two sides of a mixed
	 * pool agree to within 1.6x everywhere except two keys.
	 *
	 * <p>Both of those exceptions are on the scrolled side, and they are opposite errors. The pool
	 * that quotes a Sapphire {@code ASPECT_OF_THE_END} at the plain price undervalues it 9.4x, which
	 * costs nothing - an item quoted below its worth is one nobody buys. The pool that quotes three
	 * Sapphire {@code ASPECT_OF_THE_VOID} sales above what they fetched overvalues them 3.5x, and
	 * that one is a real fake snipe. It is also the entire measured harm: three sales in six days,
	 * one of which reached the holdout in the first test.
	 *
	 * <p><b>No plain variant is overvalued anywhere</b>, which is the assertion that matters. The
	 * plain sale is the one a flip would be bought on, and where a scroll dominates its own key -
	 * nine Sapphire {@code HYPERION} sales against one plain - the two medians are 1,208,000,000 and
	 * 1,209,000,000. The scroll is priced into the same item at the same time by the same market.
	 */
	@Test
	void mixedPoolsDoNotOvervalueTheirPlainSales() throws Exception {
		Map<String, Map<String, List<Double>>> perSignature = new HashMap<>();

		forEachSale((item, extra, timestamp, unitPrice) -> perSignature
				.computeIfAbsent(item.signature(), k -> new TreeMap<>())
				.computeIfAbsent(extra.string(SCROLL).orElse("(none)"), k -> new ArrayList<>())
				.add(unitPrice));

		int mixed = 0;
		int plainOvervalued = 0;
		int scrolledOvervalued = 0;
		List<String> table = new ArrayList<>();

		for (Map.Entry<String, Map<String, List<Double>>> entry : perSignature.entrySet()) {
			Map<String, List<Double>> variants = entry.getValue();

			if (variants.size() < 2) {
				continue;
			}

			mixed++;
			// What the key quotes today: one median over every sale under it, scroll or no scroll.
			double pooled = median(variants.values().stream().flatMap(List::stream).toList(), 1)
					.orElseThrow();
			StringBuilder detail = new StringBuilder();
			double worst = 1.0d;

			for (Map.Entry<String, List<Double>> variant : variants.entrySet()) {
				boolean plain = variant.getKey().equals("(none)");
				double med = median(variant.getValue(), 1).orElseThrow();
				double ratio = pooled / med;
				worst = Math.max(worst, Math.max(ratio, 1.0d / ratio));

				if (ratio >= 2.0d) {
					if (plain) {
						plainOvervalued++;
					} else {
						scrolledOvervalued++;
					}
				}

				detail.append(String.format("%s n=%d med %,.0f; ",
						variant.getKey().replace("_POWER_SCROLL", ""), variant.getValue().size(), med));
			}

			table.add(String.format("  %5.1fx  quoted %,15.0f  %-50s %s",
					worst, pooled, trim(entry.getKey()), detail));
		}

		System.out.printf("%n%,d production signatures hold more than one scroll variant, or a "
				+ "scroll variant and a plain sale:%n", mixed);
		table.stream().sorted().forEach(System.out::println);
		System.out.printf("  variants the pooled median overvalues 2x+: %,d plain, %,d scrolled%n",
				plainOvervalued, scrolledOvervalued);

		assertTrue(mixed < 40, "the scroll is expected to pool with a plain sale at only a handful of "
				+ "production signatures - if it now pools at many, the item-id ratios have started "
				+ "reaching the real key and this should be re-measured, but there were " + mixed);

		// The load-bearing one. A plain item quoted at a scrolled item's price is the fake snipe the
		// signature exists to prevent, and no mixed pool produces one. If this ever fires, the scroll
		// has started moving medians and belongs in the key.
		assertEquals(0, plainOvervalued, "no mixed pool should quote a plain sale at 2x or more of "
				+ "what plain sales of it fetch, since that is the error a flip would be acted on");

		assertTrue(scrolledOvervalued <= 2, "the scroll's own overvaluation is expected to be a "
				+ "handful of keys, not the rule, but " + scrolledOvervalued + " of " + mixed
				+ " pools quoted a scrolled sale at 2x or more of what it fetched");
	}

	/**
	 * @param scrolledPriced     of those, the ones carrying a scroll - what the term would cost
	 * @param scrolledWithinHalf scrolled sales valued between 0.67x and 1.5x of what they fetched
	 * @param overvalued         sales valued at 2x or more of what they fetched, the fake snipes
	 */
	private record Scored(int priced, int scrolledPriced, int scrolledWithinHalf, int overvalued,
			double median, double p90) {
		@Override
		public String toString() {
			return String.format("%,5d priced (%,d scrolled, %,d of them within 1.5x), %,d over 2x, "
					+ "median |log err| %.3f, p90 %.3f",
					priced, scrolledPriced, scrolledWithinHalf, overvalued, median, p90);
		}
	}

	/**
	 * Replays the tape into the two indices production builds, then prices the holdout from them.
	 *
	 * @param keyed whether the scroll reaches the signature and the bare guard, i.e. the change
	 */
	private static Scored score(Set<String> scrolledIds, long cutoff, boolean keyed)
			throws Exception {
		Map<String, List<Double>> exact = new HashMap<>();
		Map<String, List<Double>> coarse = new HashMap<>();
		List<Held> holdout = new ArrayList<>();

		forEachSale((item, extra, timestamp, unitPrice) -> {
			if (!scrolledIds.contains(item.skyblockId())) {
				return;
			}

			String scroll = extra.string(SCROLL).orElse("");
			String key = keyed && !scroll.isEmpty()
					? item.signature() + "|scroll=" + scroll : item.signature();
			// The bare guard: with the scroll keyed, a scrolled item is no longer an unmodified one,
			// so it leaves the coarse index the way a dyed or starred item does.
			boolean bare = bareApartFromScroll(item) && !(keyed && !scroll.isEmpty());
			String coarseKey = ActiveListing.coarseKey(item.displayName(), item.rarity());

			if (timestamp >= cutoff) {
				holdout.add(new Held(unitPrice, key, bare ? coarseKey : "", scroll));
				return;
			}

			exact.computeIfAbsent(key, k -> new ArrayList<>()).add(unitPrice);

			if (bare) {
				coarse.computeIfAbsent(coarseKey, k -> new ArrayList<>()).add(unitPrice);
			}
		});

		List<Double> errors = new ArrayList<>();
		int scrolledPriced = 0;
		int scrolledWithinHalf = 0;
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

			if (!sale.scroll().isEmpty()) {
				scrolledPriced++;

				if (ratio > 2.0d / 3.0d && ratio < 1.5d) {
					scrolledWithinHalf++;
				}
			}
		}

		List<Double> sorted = errors.stream().sorted().toList();

		return new Scored(sorted.size(), scrolledPriced, scrolledWithinHalf, overvalued,
				sorted.isEmpty() ? Double.NaN : sorted.get(sorted.size() / 2),
				sorted.isEmpty() ? Double.NaN : sorted.get(sorted.size() * 9 / 10));
	}

	/** A held-out sale, the key it would be priced under, and the coarse key if it is eligible. */
	private record Held(double price, String key, String coarseKey, String scroll) {
	}

	/**
	 * {@link FairValueModel}'s admission test for the coarse index, minus the scroll itself - the
	 * quantity the coverage-cost argument is about. 24 of the 554 taped scroll sales pass it, which
	 * is what makes this term unlike the dye, where the answer was zero and the term was free.
	 */
	private static boolean bareApartFromScroll(DecodedItem item) {
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

		forEachSale((item, extra, timestamp, unitPrice) -> extra.string(SCROLL).ifPresent(scroll -> {
			ids.add(item.skyblockId());
			byScroll.merge(scroll, 1, Integer::sum);
			coins[0] += (long) unitPrice;
		}));

		System.out.printf("%n%,d scroll sales carrying %,d coins: %s%n",
				byScroll.values().stream().mapToInt(Integer::intValue).sum(), coins[0], byScroll);

		assertTrue(ids.size() > 1, "no power scroll sales on the tape at " + tapeDir());
		return ids;
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
		return key.length() <= 50 ? key : key.substring(0, 47) + "...";
	}
}
