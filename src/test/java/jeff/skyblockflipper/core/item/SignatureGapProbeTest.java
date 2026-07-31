package jeff.skyblockflipper.core.item;

import jeff.skyblockflipper.core.nbt.NbtCompound;
import jeff.skyblockflipper.core.nbt.NbtReader;
import jeff.skyblockflipper.core.tape.SalesTape;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which signatures pool items that are not the same thing? Opt-in, needs a recorded tape:
 * {@code ./gradlew test -PtapeBacktest --tests '*SignatureGapProbeTest'}.
 *
 * <p>A signature earns its place by grouping items worth about the same. Where a day of realized
 * sales under one signature spans orders of magnitude, the signature is missing something the
 * market is pricing, and every valuation on that key is a median of two different items.
 */
@EnabledIfSystemProperty(named = "skyblockflipper.tapeBacktest", matches = "true")
class SignatureGapProbeTest {
	private static final String DEFAULT_TAPE_DIR = "run/config/skyblock-flipper/tape";

	private static final int WELL_TRADED = 20;
	private static final double POOLED = 10.0d;

	/** Attributes the decoder reads, plus the per-item identity that could never be a key. */
	private static final Set<String> CONSUMED = Set.of(
			"id", "modifier", "upgrade_level", "dungeon_item_level", "rarity_upgrades",
			"hot_potato_count", "enchantments", "gems", "attributes", "petInfo", "runes",
			"potion", "potion_level", "splash", "enhanced", "extended",
			"baseStatBoostPercentage", "item_tier", "dye_item", "ethermerge",
			"uuid", "timestamp", "originTag", "donated_museum");

	private record Gap(String signature, int samples, double p10, double median, double p90,
			double coins, Map<String, Integer> unread) {
		/**
		 * The tenth to ninetieth percentile, not the full range. Every one of these keys has some
		 * sales at a few coins - misclicks and giveaways are a constant of the auction house - so a
		 * min-to-max ratio ranks by how much junk a key attracted rather than by how badly it pools.
		 */
		double spread() {
			return p90 / Math.max(1.0d, p10);
		}

		@Override
		public String toString() {
			return String.format("%-52s n=%-5d p10 %,12.0f  median %,12.0f  p90 %,12.0f  (%,.0fx)"
							+ "  %,15.0f coins%n      unread: %s",
					signature, samples, p10, median, p90, spread(), coins, unread);
		}
	}

	@Test
	void reportsWhichSignaturesPoolItemsOfDifferentValue() throws Exception {
		SalesTape tape = new SalesTape(Path.of(
				System.getProperty("skyblockflipper.tapeDir", DEFAULT_TAPE_DIR)), 3650);

		Map<String, List<Double>> pricesBySignature = new HashMap<>();
		Map<String, Map<String, Integer>> unreadBySignature = new HashMap<>();
		int[] decoded = {0};

		tape.forEachRecent(2, sale -> {
			if (!sale.bin() || sale.price() <= 0L) {
				return;
			}

			ItemDecoder.decode(sale.itemBytes()).ifPresent(item -> {
				decoded[0]++;
				String signature = item.signature();
				pricesBySignature.computeIfAbsent(signature, k -> new ArrayList<>())
						.add((double) sale.price() / Math.max(1, item.count()));

				Map<String, Integer> counts =
						unreadBySignature.computeIfAbsent(signature, k -> new HashMap<>());

				for (String key : unreadAttributes(sale.itemBytes())) {
					counts.merge(key, 1, Integer::sum);
				}
			});
		});

		List<Gap> gaps = new ArrayList<>();
		double totalCoins = 0.0d;

		for (Map.Entry<String, List<Double>> entry : pricesBySignature.entrySet()) {
			List<Double> prices = entry.getValue().stream().sorted().toList();
			double coins = prices.stream().mapToDouble(Double::doubleValue).sum();
			totalCoins += coins;

			if (prices.size() >= WELL_TRADED) {
				gaps.add(new Gap(entry.getKey(), prices.size(),
						prices.get(prices.size() / 10), prices.get(prices.size() / 2),
						prices.get(prices.size() * 9 / 10), coins,
						unreadBySignature.getOrDefault(entry.getKey(), Map.of())));
			}
		}

		System.out.printf("%n%,d BIN sales decoded into %,d signatures, %,.0f coins%n",
				decoded[0], pricesBySignature.size(), totalCoins);

		System.out.printf("%nwidest well-traded signatures (n>=%d), by spread:%n", WELL_TRADED);
		gaps.stream().sorted(Comparator.comparingDouble(Gap::spread).reversed()).limit(15)
				.forEach(gap -> System.out.println("  " + gap));

		System.out.printf("%nby coins at stake where the spread is >=%.0fx:%n", POOLED);
		gaps.stream().filter(gap -> gap.spread() >= POOLED)
				.sorted(Comparator.comparingDouble(Gap::coins).reversed()).limit(15)
				.forEach(gap -> System.out.println("  " + gap));

		double pooled = gaps.stream().filter(gap -> gap.spread() >= POOLED)
				.mapToDouble(Gap::coins).sum();

		System.out.printf("%n%,.0f coins (%.1f%% of the tape) traded under a well-traded signature "
				+ "spanning %.0fx or more%n", pooled, 100.0d * pooled / totalCoins, POOLED);
	}

	/** ExtraAttributes keys present on the blob that nothing in {@link DecodedItem} reads. */
	private static Set<String> unreadAttributes(String itemBytes) {
		try {
			NbtCompound root = NbtReader.readItemBytes(itemBytes);

			if (!(root.list("i").stream().findFirst().orElse(null) instanceof NbtCompound item)) {
				return Set.of();
			}

			Set<String> keys = new HashSet<>(item.child("tag").child("ExtraAttributes").keys());
			keys.removeAll(CONSUMED);
			return keys;
		} catch (Exception e) {
			return Set.of();
		}
	}
}
