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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which unread attribute is worth a branch next, ranked by the coins its pooling misprices upward.
 * Opt-in, needs a recorded tape:
 * {@code ./gradlew test -PtapeBacktest --tests '*UnreadAttributeProbeTest'}.
 *
 * <p>{@code SignatureGapProbeTest} ranks by the p10-p90 spread of a signature's sales and lists what
 * nothing reads. That found the shared item ids, and then it kept being right about the size of a gap
 * and wrong about whether closing it helps: the raw {@code color} triple, {@code power_ability_scroll}
 * and the drill parts each topped that list, each was worth 100x or more at the item id, and each
 * measured flat at the key production actually prices from. Three branches to reach three no-ops.
 *
 * <p>So this ranks the thing that decides the answer instead. For every unread attribute it walks the
 * production signatures where it appears, keeps only the pools with {@link ValueEstimate#MIN_SAMPLES}
 * sales - a pool below that is quoted by nothing, so whatever it disagrees about costs nothing - and
 * counts the <b>sales the pooled median values at 2x or more of what their own configuration
 * fetched</b>. That is the error a flip is acted on: a plain item wearing a built item's price.
 *
 * <p>Against it, the cost of reading the attribute: the sales whose configuration has too few
 * examples to be quoted once split, plus the ones that would leave the coarse index because reading
 * an attribute makes an item no longer bare.
 *
 * <p>This is a screen and not a verdict. It measures pools in sample, over the whole tape, which
 * flatters a split - a cell of six that exists only because the same six sales trained it will not
 * price a seventh. A term that ranks high here still has to earn its place on a holdout, the way
 * {@code DrillPartBacktestTest} and {@code PowerScrollBacktestTest} made theirs do.
 */
@EnabledIfSystemProperty(named = "skyblockflipper.tapeBacktest", matches = "true")
class UnreadAttributeProbeTest {
	private static final String DEFAULT_TAPE_DIR = "run/config/skyblock-flipper/tape";
	private static final int ALL_DAYS = 365;

	/** Sales carrying an attribute before it is worth measuring at all. */
	private static final int MIN_SALES = 20;

	/** Per configuration, matching production's cap, so one busy item cannot eat the heap. */
	private static final int MAX_SAMPLES = 200;

	private static final String NONE = "(none)";

	/**
	 * Everything the decoder already reads, plus the per-item identity that could never be a key.
	 *
	 * <p>Kept in step with {@code SignatureGapProbeTest} by hand, which is a small duplication and the
	 * honest one: these two probes ask different questions and a shared list would be a third thing to
	 * keep in step with both.
	 */
	private static final Set<String> READ = Set.of(
			"id", "modifier", "upgrade_level", "dungeon_item_level", "rarity_upgrades",
			"hot_potato_count", "enchantments", "gems", "attributes", "petInfo", "runes",
			"potion", "potion_level", "splash", "enhanced", "extended",
			"baseStatBoostPercentage", "item_tier", "ethermerge", "dye_item", "winning_bid",
			"uuid", "timestamp", "originTag", "donated_museum");

	@Test
	void ranksUnreadAttributesByTheCoinsTheirPoolingMisprices() throws Exception {
		Map<String, Set<String>> idsByAttribute = candidates();
		Map<String, Harm> harm = new TreeMap<>();

		idsByAttribute.forEach((attribute, ids) -> harm.put(attribute, new Harm(attribute, ids)));

		// One pass for all of them. Each accumulator only takes sales of the item ids its own
		// attribute ever appears on, which is what keeps this from being the whole tape N times over.
		forEachSale((item, extra, unitPrice) ->
				harm.values().forEach(h -> h.add(item, extra, unitPrice)));

		harm.values().forEach(Harm::score);

		List<Harm> ranked = harm.values().stream()
				.sorted((a, b) -> Long.compare(b.overvaluedCoins, a.overvaluedCoins))
				.toList();

		System.out.printf("%n%-26s %7s %16s %6s %8s %9s %10s %16s %9s%n",
				"unread attribute", "sales", "coins", "values", "mixed", "quotable", "overvalued",
				"their coins", "cost");

		for (Harm h : ranked) {
			System.out.println(h);
		}

		System.out.printf("%n\"overvalued\" is sales in a quotable mixed pool that the pooled median "
				+ "values at 2x+ of what their own configuration fetched.%n\"cost\" is sales whose "
				+ "configuration would fall below MIN_SAMPLES once split, and so would be priced by "
				+ "nothing.%n");

		assertTrue(!ranked.isEmpty(), "no unread attributes on the tape at " + tapeDir());

		// The finding this probe exists to state, and the one that would change the plan if it broke.
		//
		// Run before ethermerge was read, this ranking put it first at 1,250,000,000 coins over 207
		// sales, an order of magnitude clear of everything else, and it went on to earn its place on a
		// holdout. With it read, the list drops to eman_kills at 45,500,000 over 26 sales - and
		// eman_kills is a kill counter with 979 distinct values on 1,905 sales, so keying it would
		// cost 988 valuations to fix 26. Everything below it is smaller and the same shape: a counter,
		// a timestamp, or a per-item identifier.
		//
		// So there is no further shared-id-shaped gap on this tape, and this threshold is the alarm
		// for a new one arriving - a Skyblock update that makes some new upgrade both common and
		// invisible would show up here first.
		Harm worst = ranked.getFirst();
		assertTrue(worst.overvaluedCoins < 100_000_000L, "an unread attribute now misprices real coins "
				+ "upward and may be worth a branch: " + worst.attribute + " at "
				+ worst.overvaluedCoins + " coins over " + worst.overvalued + " sales");
	}

	/** One unread attribute's pools, accumulated over the tape and then scored. */
	private static final class Harm {
		private final String attribute;
		private final Set<String> ids;
		private final Map<String, Map<String, List<Double>>> pools = new HashMap<>();
		private final Set<String> values = new HashSet<>();

		private int sales;
		private long coins;
		private int bare;
		private int mixed;
		private int quotable;
		private int overvalued;
		private long overvaluedCoins;
		private int cost;
		private boolean scored;

		private Harm(String attribute, Set<String> ids) {
			this.attribute = attribute;
			this.ids = ids;
		}

		private void add(DecodedItem item, NbtCompound extra, double unitPrice) {
			if (!ids.contains(item.skyblockId())) {
				return;
			}

			String value = value(extra, attribute);

			if (!value.equals(NONE)) {
				sales++;
				coins += (long) unitPrice;
				values.add(value);

				if (bare(item)) {
					bare++;
				}
			}

			List<Double> prices = pools
					.computeIfAbsent(item.signature(), k -> new TreeMap<>())
					.computeIfAbsent(value, k -> new ArrayList<>());

			if (prices.size() < MAX_SAMPLES) {
				prices.add(unitPrice);
			}
		}

		/** Scores the pools once they are all in. Run once, before the ranking reads the counters. */
		private void score() {
			if (scored) {
				return;
			}

			scored = true;

			for (Map<String, List<Double>> variants : pools.values()) {
				if (variants.size() < 2) {
					continue;
				}

				mixed++;
				List<Double> all = variants.values().stream().flatMap(List::stream).toList();
				OptionalDouble quoted = median(all, ValueEstimate.MIN_SAMPLES);

				if (quoted.isEmpty()) {
					// Below MIN_SAMPLES the pool prices nothing, so it cannot misprice anything.
					variants.forEach((value, prices) -> {
						if (!value.equals(NONE)) {
							cost += prices.size();
						}
					});
					continue;
				}

				quotable++;

				variants.forEach((value, prices) -> {
					double med = median(prices, 1).orElseThrow();

					if (quoted.getAsDouble() >= 2.0d * med) {
						overvalued += prices.size();
						overvaluedCoins += (long) (prices.size() * med);
					}

					// What splitting would cost: this cell is priced today by the pool it sits in and
					// would be priced by nothing on its own.
					if (!value.equals(NONE) && prices.size() < ValueEstimate.MIN_SAMPLES) {
						cost += prices.size();
					}
				});
			}
		}

		@Override
		public String toString() {
			return String.format("%-26s %7d %16d %6d %8d %9d %10d %16d %9d (%d bare)",
					attribute, sales, coins, values.size(), mixed, quotable, overvalued,
					overvaluedCoins, cost, bare);
		}
	}

	/**
	 * The attributes worth measuring, and the item ids each one ever appears on.
	 *
	 * <p>A first pass, so the second can hold pools for the ids that matter rather than for the whole
	 * tape. The id set is what bounds the memory: an attribute on four item ids accumulates four
	 * items' sales, not 452,000.
	 */
	private static Map<String, Set<String>> candidates() throws Exception {
		Map<String, Set<String>> idsByAttribute = new TreeMap<>();
		Map<String, Integer> counts = new TreeMap<>();

		forEachSale((item, extra, unitPrice) -> extra.keys().forEach(key -> {
			if (READ.contains(key)) {
				return;
			}

			counts.merge(key, 1, Integer::sum);
			idsByAttribute.computeIfAbsent(key, k -> new HashSet<>()).add(item.skyblockId());
		}));

		idsByAttribute.keySet().removeIf(key -> counts.get(key) < MIN_SALES);
		System.out.printf("%n%,d unread attributes carried by at least %d sales%n",
				idsByAttribute.size(), MIN_SALES);
		return idsByAttribute;
	}

	/**
	 * The attribute as one string, whatever NBT shape it arrived in.
	 *
	 * <p>A compound is rendered by its keys rather than its contents - the drill's {@code engine} is a
	 * compound holding a part id and a fuel count, and the fuel count is not a price term. This is a
	 * screen, so a coarse rendering that groups too much is the safe direction: it can understate a
	 * gap, never invent one.
	 */
	private static String value(NbtCompound extra, String key) {
		if (!extra.contains(key)) {
			return NONE;
		}

		return extra.string(key)
				.or(() -> extra.compound(key).map(NbtCompound::toString))
				.orElseGet(() -> String.valueOf(extra.intOr(key, -1)));
	}

	/** {@link FairValueModel}'s admission test for the coarse index. */
	private static boolean bare(DecodedItem item) {
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

	/** The decoded item, its raw {@code ExtraAttributes}, and its unit price. */
	private interface SaleVisitor {
		void accept(DecodedItem item, NbtCompound extra, double unitPrice);
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

			ItemDecoder.fromRoot(root).ifPresent(item -> visitor.accept(item, extra,
					(double) sale.price() / Math.max(1, item.count())));
		});
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
}
