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

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What reading {@code ExtraAttributes.runes} is worth, measured. Opt-in, needs a recorded tape:
 * {@code ./gradlew test -PtapeBacktest --tests '*RuneSignatureBacktestTest'}.
 *
 * <p>Every rune shares the item id {@code RUNE}, so before the runes map reached
 * {@link DecodedItem#signature()} the whole rune market priced off five keys - one per rarity -
 * and a Gem rune worth a couple of thousand coins sat in the same median as a Music rune worth
 * millions. That is not a slightly noisy valuation; it is a machine for generating fake snipes,
 * because every cheap rune looks like it is listed far under "its" value.
 *
 * <p>This holds out the newest sales and prices them from the older ones both ways, which is the
 * only comparison that means anything: a key always fits the sales it was built from.
 *
 * <p>Splitting one key per rarity into one per rune leaves fewer sales behind each, so fewer
 * held-out runes get quoted at all - measured at 461 of 636 against 582. That is the trade being
 * made on purpose. Declining to quote a rune costs a flip that was never real; quoting it off a
 * different rune's sales costs coins.
 */
@EnabledIfSystemProperty(named = "skyblockflipper.tapeBacktest", matches = "true")
class RuneSignatureBacktestTest {
	private static final String DEFAULT_TAPE_DIR = "run/config/skyblock-flipper/tape";

	private static final long HOLDOUT_HOURS = 6L;
	private static final int ALL_DAYS = 365;
	private static final int MIN_SAMPLES = ValueEstimate.MIN_SAMPLES;

	@Test
	void readingTheRuneMakesRunesPriceable() throws Exception {
		SalesTape tape = new SalesTape(Path.of(
				System.getProperty("skyblockflipper.tapeDir", DEFAULT_TAPE_DIR)), 3650);

		List<Long> stamps = new ArrayList<>();
		tape.forEachRecent(ALL_DAYS, sale -> stamps.add(sale.timestamp()));
		assertTrue(!stamps.isEmpty(), "no sales on the tape at " + DEFAULT_TAPE_DIR);

		long newest = stamps.stream().mapToLong(Long::longValue).max().orElseThrow();
		long cutoff = newest - HOLDOUT_HOURS * 3_600_000L;

		Map<String, List<Double>> byRarity = new HashMap<>();
		Map<String, List<Double>> bySignature = new HashMap<>();
		List<double[]> holdout = new ArrayList<>();
		List<String> holdoutRarityKeys = new ArrayList<>();
		List<String> holdoutSignatures = new ArrayList<>();

		tape.forEachRecent(ALL_DAYS, sale -> {
			if (!sale.bin() || sale.price() <= 0L) {
				return;
			}

			ItemDecoder.decode(sale.itemBytes()).ifPresent(item -> {
				if (item.runes().isEmpty()) {
					return;
				}

				double unitPrice = (double) sale.price() / Math.max(1, item.count());
				// The key the signature used to produce: identity dropped, rarity only.
				String rarityKey = item.skyblockId() + "|" + item.rarity().name();

				if (sale.timestamp() < cutoff) {
					byRarity.computeIfAbsent(rarityKey, k -> new ArrayList<>()).add(unitPrice);
					bySignature.computeIfAbsent(item.signature(), k -> new ArrayList<>()).add(unitPrice);
				} else {
					holdout.add(new double[] {unitPrice});
					holdoutRarityKeys.add(rarityKey);
					holdoutSignatures.add(item.signature());
				}
			});
		});

		List<Double> rarityErrors = new ArrayList<>();
		List<Double> signatureErrors = new ArrayList<>();

		for (int i = 0; i < holdout.size(); i++) {
			double actual = holdout.get(i)[0];
			error(median(byRarity.get(holdoutRarityKeys.get(i))), actual).ifPresent(rarityErrors::add);
			error(median(bySignature.get(holdoutSignatures.get(i))), actual).ifPresent(signatureErrors::add);
		}

		double withoutRune = medianError(rarityErrors);
		double withRune = medianError(signatureErrors);

		System.out.printf("%nrune signature backtest: %,d held-out rune sales in the newest %dh%n",
				holdout.size(), HOLDOUT_HOURS);
		System.out.printf("  keyed on id and rarity alone: %,d priced, median |log err| %.3f%n",
				rarityErrors.size(), withoutRune);
		System.out.printf("  keyed on the rune and tier:   %,d priced, median |log err| %.3f%n",
				signatureErrors.size(), withRune);

		// A log error of 1.0 is being wrong by a factor of e; the rarity-only key runs far past that,
		// which is what pricing a Gem rune off Music rune sales looks like.
		assertTrue(withRune < withoutRune / 2.0d, "reading the rune should at least halve the error, "
				+ "but it went from " + withoutRune + " to " + withRune);
	}

	/** Empty when the key had too few sales for the model to quote it at all. */
	private static java.util.OptionalDouble error(java.util.OptionalDouble estimate, double actual) {
		if (estimate.isEmpty() || estimate.getAsDouble() <= 0.0d || actual <= 0.0d) {
			return java.util.OptionalDouble.empty();
		}

		return java.util.OptionalDouble.of(Math.abs(Math.log(estimate.getAsDouble() / actual)));
	}

	private static java.util.OptionalDouble median(List<Double> values) {
		if (values == null || values.size() < MIN_SAMPLES) {
			return java.util.OptionalDouble.empty();
		}

		List<Double> sorted = values.stream().sorted().toList();
		return java.util.OptionalDouble.of(sorted.get(sorted.size() / 2));
	}

	private static double medianError(List<Double> errors) {
		List<Double> sorted = errors.stream().sorted().toList();
		return sorted.isEmpty() ? Double.NaN : sorted.get(sorted.size() / 2);
	}
}
