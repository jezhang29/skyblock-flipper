package jeff.skyblockflipper.core.valuation;

import jeff.skyblockflipper.core.item.DecodedItem;
import jeff.skyblockflipper.core.item.ItemDecoder;
import jeff.skyblockflipper.core.tape.SalesTape;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What reading the potion behind {@code POTION} is worth, measured. Opt-in, needs a recorded tape:
 * {@code ./gradlew test -PtapeBacktest --tests '*PotionSignatureBacktestTest'}.
 *
 * <p>The third market to hide behind a shared item id, after {@code PET} and {@code RUNE}, and the
 * most expensive of the three: 2,758 BIN sales worth 2.1 billion coins in two days, all keyed as
 * {@code POTION|<rarity>}. Because Stinky Cheese and Harvest Harbinger are 70% of the count, that
 * one median sat at 918,000 coins, and every cheap potion in the game - splash Healing at 25,000,
 * Combat XP Boost at 500 - read as a large discount on itself. That is not a noisy valuation, it is
 * a generator of fake snipes, which is the same thing runes were.
 *
 * <p>Held out newest-first and priced from the older sales both ways, because a key always fits the
 * sales it was built from.
 */
@EnabledIfSystemProperty(named = "skyblockflipper.tapeBacktest", matches = "true")
class PotionSignatureBacktestTest {
	private static final String DEFAULT_TAPE_DIR = "run/config/skyblock-flipper/tape";

	private static final long HOLDOUT_HOURS = 6L;
	private static final int ALL_DAYS = 365;
	private static final int MIN_SAMPLES = ValueEstimate.MIN_SAMPLES;

	@Test
	void readingThePotionMakesPotionsPriceable() throws Exception {
		SalesTape tape = new SalesTape(Path.of(
				System.getProperty("skyblockflipper.tapeDir", DEFAULT_TAPE_DIR)), 3650);

		List<Long> stamps = new ArrayList<>();
		tape.forEachRecent(ALL_DAYS, sale -> stamps.add(sale.timestamp()));
		assertTrue(!stamps.isEmpty(), "no sales on the tape at " + DEFAULT_TAPE_DIR);

		long newest = stamps.stream().mapToLong(Long::longValue).max().orElseThrow();
		long cutoff = newest - HOLDOUT_HOURS * 3_600_000L;

		Map<String, List<Double>> byRarity = new HashMap<>();
		Map<String, List<Double>> bySignature = new HashMap<>();
		List<Double> holdoutPrices = new ArrayList<>();
		List<String> holdoutRarityKeys = new ArrayList<>();
		List<String> holdoutSignatures = new ArrayList<>();

		tape.forEachRecent(ALL_DAYS, sale -> {
			if (!sale.bin() || sale.price() <= 0L) {
				return;
			}

			ItemDecoder.decode(sale.itemBytes()).ifPresent(item -> {
				if (!item.isPotion()) {
					return;
				}

				double unitPrice = (double) sale.price() / Math.max(1, item.count());
				// The key the signature used to produce: the potion dropped, rarity only.
				String rarityKey = item.skyblockId() + "|" + item.rarity().name();

				if (sale.timestamp() < cutoff) {
					byRarity.computeIfAbsent(rarityKey, k -> new ArrayList<>()).add(unitPrice);
					bySignature.computeIfAbsent(item.signature(), k -> new ArrayList<>()).add(unitPrice);
				} else {
					holdoutPrices.add(unitPrice);
					holdoutRarityKeys.add(rarityKey);
					holdoutSignatures.add(item.signature());
				}
			});
		});

		Scored withoutPotion = score(holdoutPrices, holdoutRarityKeys, byRarity);
		Scored withPotion = score(holdoutPrices, holdoutSignatures, bySignature);

		System.out.printf("%npotion signature backtest: %,d held-out potion sales in the newest %dh%n",
				holdoutPrices.size(), HOLDOUT_HOURS);
		System.out.println("  keyed on id and rarity alone:  " + withoutPotion);
		System.out.println("  keyed on the potion and perks: " + withPotion);

		// Deliberately not asserted on the median error. The median sale is a Stinky Cheese or a
		// Harvest Harbinger - together 70% of the count - and both sit near the pooled median, so
		// pooling scores *better* there (0.091 against 0.104) by being accidentally right about the
		// two items that drown out the rest. The damage is entirely in the tail, and the tail is
		// what this model is for: a valuation is only ever acted on when it sits far above the
		// asking price, so an overvaluation is a flip taken and an undervaluation is one skipped.
		assertTrue(withPotion.overvaluedByHalf() < withoutPotion.overvaluedByHalf() / 4.0d,
				"pooling every potion into one median should invent far more fake snipes than "
						+ "keying on the potion, but it went from " + withoutPotion.overvaluedByHalf()
						+ " to " + withPotion.overvaluedByHalf());

		assertTrue(withPotion.p90() < withoutPotion.p90() / 2.0d, "reading the potion should at "
				+ "least halve the p90 error, but it went from " + withoutPotion.p90() + " to "
				+ withPotion.p90());
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
