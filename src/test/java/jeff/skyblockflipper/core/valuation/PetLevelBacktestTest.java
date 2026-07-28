package jeff.skyblockflipper.core.valuation;

import jeff.skyblockflipper.core.item.DecodedItem;
import jeff.skyblockflipper.core.item.ItemDecoder;
import jeff.skyblockflipper.core.item.PetInfo;
import jeff.skyblockflipper.core.model.EndedAuction;
import jeff.skyblockflipper.core.tape.SalesTape;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Does reading the pet level actually price pets better? Run with
 * {@code ./gradlew test -PtapeBacktest}.
 *
 * <p>Disabled by default, for the same reason {@code LiveApiTest} is: it needs a recorded tape,
 * which is many megabytes of somebody's {@code run/} directory and is not in the repository. An
 * ordinary build must not depend on data it cannot have. Point it somewhere else with
 * {@code -Dskyblockflipper.tapeDir=...}.
 *
 * <p>What it measures is the claim the whole feature rests on. The tape is split by time, a model
 * is built from the older sales only, and the newer sales are then priced by it and compared with
 * what they really sold for - so this is out-of-sample, not a fit reported back to itself. Each
 * pet is priced twice: once as itself, and once with its level erased, which drives the valuation
 * ladder down to the levelless key and reproduces exactly how pets were priced before.
 *
 * <p>On the tape this was developed against - 32,805 sales over three days - pricing pets without
 * the level gave a median absolute log error of 0.146, and with it 0.134, at identical coverage.
 * <b>That is a modest gain, and smaller than it first appeared.</b> An early prototype measured
 * 0.291 against 0.155, but its baseline keyed pets on type and tier alone while this model has
 * always keyed on the held item too - and the held item is strongly correlated with the level, so
 * it was already separating fresh pets from maxed ones. Above 10M the two are within noise of each
 * other and the level has sometimes scored slightly worse, on fewer than a hundred sales.
 *
 * <p>The assertions below are therefore deliberately weak: they check the direction holds overall
 * and that coverage is not traded away for it, not that any particular figure is reproduced. This
 * is somebody's real market data, and it will not be the same market next month.
 */
@EnabledIfSystemProperty(named = "skyblockflipper.tapeBacktest", matches = "true")
class PetLevelBacktestTest {
	/** Fraction of the tape, oldest first, used to build the model. The rest is held out. */
	private static final double TRAIN_FRACTION = 0.7d;

	private static final String DEFAULT_TAPE_DIR = "run/config/skyblock-flipper/tape";

	@Test
	void readingTheLevelPricesPetsCloserToWhatTheyActuallySoldFor() throws Exception {
		List<EndedAuction> sales = taped();

		assertFalse(sales.isEmpty(), "no sales on the tape at " + tapeDir()
				+ " - point -Dskyblockflipper.tapeDir at a directory with day files in it");

		sales.sort(Comparator.comparingLong(EndedAuction::timestamp));
		int split = (int) (sales.size() * TRAIN_FRACTION);
		List<EndedAuction> train = sales.subList(0, split);
		List<EndedAuction> holdout = sales.subList(split, sales.size());

		// The window has to span the training sales, or the builder drops them as stale.
		Instant trainEnd = Instant.ofEpochMilli(train.getLast().timestamp());
		Duration window = Duration.ofMillis(
				train.getLast().timestamp() - train.getFirst().timestamp()).plusDays(1);

		FairValueModel model = FairValueModel.from(train, trainEnd, window);

		Errors withLevel = new Errors();
		Errors withoutLevel = new Errors();
		Errors withLevelDear = new Errors();
		Errors withoutLevelDear = new Errors();

		for (EndedAuction sale : holdout) {
			if (!sale.bin()) {
				continue;
			}

			Optional<DecodedItem> decoded = ItemDecoder.decode(sale.itemBytes());

			if (decoded.isEmpty() || !decoded.get().isPet() || sale.price() <= 0L) {
				continue;
			}

			DecodedItem pet = decoded.get();
			double actual = (double) sale.price() / Math.max(1, pet.count());

			record(model, pet, actual, withLevel, withLevelDear);
			record(model, levelErased(pet), actual, withoutLevel, withoutLevelDear);
		}

		System.out.printf("pet backtest over %d taped sales (%d train / %d holdout)%n",
				sales.size(), train.size(), holdout.size());
		withoutLevel.report("  no level (how pets used to be priced)");
		withLevel.report("  with level");
		withoutLevelDear.report("  no level, sales over 10M");
		withLevelDear.report("  with level, sales over 10M");

		assertTrue(withLevel.count() >= 20,
				"only " + withLevel.count() + " holdout pets could be priced at all - too few to "
						+ "conclude anything from; record a longer tape");

		// Coverage must not be bought back with accuracy: the ladder exists so that reading the
		// level costs nothing when the level's own sales are thin.
		assertTrue(withLevel.count() >= withoutLevel.count(),
				"reading the level priced fewer pets (" + withLevel.count() + ") than ignoring it ("
						+ withoutLevel.count() + "), which the fallback ladder is supposed to prevent");

		assertTrue(withLevel.medianError() < withoutLevel.medianError(),
				"reading the pet level did not improve out-of-sample accuracy: "
						+ withLevel.medianError() + " with it against " + withoutLevel.medianError()
						+ " without. That is the premise of the feature, so it failing means either "
						+ "the tape is too thin or the market changed shape.");
	}

	private static void record(FairValueModel model, DecodedItem pet, double actual, Errors all,
			Errors expensive) {
		Optional<ValueEstimate> estimate = model.valueOf(pet);

		if (estimate.isEmpty() || estimate.get().median() <= 0.0d) {
			return;
		}

		double error = Math.abs(Math.log(estimate.get().median() / actual));
		all.add(error);

		if (actual > 10_000_000.0d) {
			expensive.add(error);
		}
	}

	/**
	 * The same pet with its level forgotten, which is what every pet looked like before this.
	 *
	 * <p>Level 0 collapses the valuation ladder to its last rung, the levelless key, whose samples
	 * are every level of that pet pooled together.
	 */
	private static DecodedItem levelErased(DecodedItem pet) {
		PetInfo info = pet.petInfo().orElseThrow();

		return new DecodedItem(pet.skyblockId(), pet.displayName(), pet.count(), pet.rarity(),
				pet.reforge(), pet.stars(), pet.recombobulated(), pet.hotPotatoBooks(),
				Map.of(), List.of(), Map.of(),
				new PetInfo(info.type(), info.tier(), info.exp(), 0, info.heldItem(),
						info.candyUsed(), info.skin()));
	}

	private static List<EndedAuction> taped() throws Exception {
		SalesTape tape = new SalesTape(tapeDir(), Integer.MAX_VALUE);
		return new ArrayList<>(tape.readAll());
	}

	private static Path tapeDir() {
		return Path.of(System.getProperty("skyblockflipper.tapeDir", DEFAULT_TAPE_DIR));
	}

	/** Absolute log errors, which is the scale on which "twice the price" and "half" are equal. */
	private static final class Errors {
		private final List<Double> errors = new ArrayList<>();

		void add(double error) {
			errors.add(error);
		}

		int count() {
			return errors.size();
		}

		double medianError() {
			if (errors.isEmpty()) {
				return Double.MAX_VALUE;
			}

			List<Double> sorted = errors.stream().sorted().toList();
			return sorted.get(sorted.size() / 2);
		}

		double within(double tolerance) {
			double bound = Math.log(1.0d + tolerance);
			return errors.stream().filter(e -> e <= bound).count() / (double) Math.max(1, errors.size());
		}

		void report(String label) {
			System.out.printf("%-42s n=%5d  median |log err| %.3f  within +-20%% %5.1f%%%n",
					label, count(), medianError(), within(0.2d) * 100.0d);
		}
	}
}
