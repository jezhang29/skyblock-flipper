/*
 * Skyblock Flipper - a Hypixel Skyblock flipping advisor mod.
 * Copyright (C) 2026 SoupChugger
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package jeff.skyblockflipper.core.valuation;

import jeff.skyblockflipper.core.item.DecodedItem;
import jeff.skyblockflipper.core.valuation.backtest.Backtest;
import jeff.skyblockflipper.core.valuation.backtest.TapeFixture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Does reading the pet level actually price pets better? Opt-in, needs a recorded tape:
 * {@code ./gradlew test -PtapeBacktest --tests '*PetLevelBacktestTest'}.
 *
 * <p>What it measures is the claim the whole feature rests on. The tape is split by time, a model is
 * built from the older sales only, and the newer sales are then priced by it and compared with what
 * they really sold for - so this is out-of-sample, not a fit reported back to itself. Each pet is
 * priced twice: once under production's ladder, and once under a keying that offers only the
 * levelless rung, which reproduces exactly how pets were priced before.
 *
 * <p>On the tape this was developed against - 32,805 sales over three days - pricing pets without
 * the level gave a median absolute log error of 0.146, and with it 0.134, at identical coverage.
 * <b>That is a modest gain, and smaller than it first appeared.</b> An early prototype measured
 * 0.291 against 0.155, but its baseline keyed pets on type and tier alone while this model has
 * always keyed on the held item too - and the held item is strongly correlated with the level, so
 * it was already separating fresh pets from maxed ones.
 *
 * <p>Re-measured against the model that ships on six days of tape, and it holds up better than that:
 * on a 24h holdout of every pet sale, median |log err| 0.129 without the level against 0.105 with
 * it, p90 0.799 against 0.531, fake snipes 542 against 473, at <b>identical coverage</b> - 12,021
 * priced either way, which is the ladder doing its job. The old note that the level is within noise
 * above 10M is superseded: on 3,470 held-out sales over 10M coins it runs 0.103 without against
 * 0.071 with.
 *
 * <p>The assertions below are therefore deliberately weak: they check the direction holds overall
 * and that coverage is not traded away for it, not that any particular figure is reproduced. This
 * is somebody's real market data, and it will not be the same market next month.
 *
 * <p>This used to read the whole tape into a list through {@code SalesTape.readAll} and split it
 * 70/30 by count. That was affordable at three days and is not at six - the tape is 692MB of blob -
 * so it now streams through {@link Backtest} like every other backtest here, holding out by time.
 */
@EnabledIfSystemProperty(named = "skyblockflipper.tapeBacktest", matches = "true")
class PetLevelBacktestTest {
	private static final long HOLDOUT_HOURS = 24L;

	/** Longer than any tape, so training is the unbounded replay these findings were measured on. */
	private static final Duration WHOLE_TAPE = Duration.ofDays(3650);

	private static final double DEAR = 10_000_000.0d;

	/**
	 * Pets priced the way they were before the level was read: one key, every level pooled.
	 *
	 * <p>Not expressible as a {@link jeff.skyblockflipper.core.valuation.backtest.CounterfactualKeying}
	 * arm, because a pet's rungs are not signature terms - they are separate keys the ladder walks -
	 * so there is no term to drop. The levelless rung is the last one production offers, which is why
	 * this reads it off {@link DecodedItem#valuationKeys()} rather than rebuilding a pet key.
	 */
	private static final Keying LEVELLESS = new Keying() {
		@Override
		public List<String> keys(DecodedItem item) {
			List<String> ladder = Keying.PRODUCTION.keys(item);
			return item.isPet() ? List.of(ladder.getLast()) : ladder;
		}

		@Override
		public Optional<String> bidRatioKey(DecodedItem item) {
			return Keying.PRODUCTION.bidRatioKey(item);
		}

		@Override
		public boolean isBare(DecodedItem item) {
			return Keying.PRODUCTION.isBare(item);
		}
	};

	@Test
	void readingTheLevelPricesPetsCloserToWhatTheyActuallySoldFor() throws Exception {
		long cutoff = TapeFixture.newestTimestamp() - HOLDOUT_HOURS * 3_600_000L;

		Backtest.Result withoutLevel =
				Backtest.holdout(LEVELLESS, cutoff, WHOLE_TAPE, DecodedItem::isPet);
		Backtest.Result withLevel =
				Backtest.holdout(Keying.PRODUCTION, cutoff, WHOLE_TAPE, DecodedItem::isPet);

		System.out.printf("%nheld-out pet sales:%n  %-34s %s%n  %-34s %s%n",
				"no level (how pets used to be priced)", withoutLevel, "with level", withLevel);
		System.out.printf("  sales over 10M coins: median |log err| %.3f without the level, %.3f "
						+ "with it, over %,d and %,d sales%n",
				medianErrorOfDearSales(withoutLevel), medianErrorOfDearSales(withLevel),
				countDear(withoutLevel), countDear(withLevel));

		assertTrue(withLevel.priced().size() >= 20, "only " + withLevel.priced().size()
				+ " holdout pets could be priced at all - too few to conclude anything from; record a "
				+ "longer tape");

		// Coverage must not be bought back with accuracy: the ladder exists so that reading the
		// level costs nothing when the level's own sales are thin.
		assertTrue(withLevel.priced().size() >= withoutLevel.priced().size(),
				"reading the level priced fewer pets (" + withLevel.priced().size()
						+ ") than ignoring it (" + withoutLevel.priced().size()
						+ "), which the fallback ladder is supposed to prevent");

		assertTrue(withLevel.medianLogError() < withoutLevel.medianLogError(),
				"reading the pet level did not improve out-of-sample accuracy: "
						+ withLevel.medianLogError() + " with it against "
						+ withoutLevel.medianLogError() + " without. That is the premise of the "
						+ "feature, so it failing means either the tape is too thin or the market "
						+ "changed shape.");
	}

	/** The dear half, where the level has never separated much - printed, deliberately not asserted. */
	private static double medianErrorOfDearSales(Backtest.Result result) {
		List<Double> errors = result.priced().stream()
				.filter(dear())
				.map(Backtest.Priced::logError)
				.sorted()
				.toList();

		return errors.isEmpty() ? Double.NaN : errors.get(errors.size() / 2);
	}

	private static int countDear(Backtest.Result result) {
		return (int) result.priced().stream().filter(dear()).count();
	}

	private static Predicate<Backtest.Priced> dear() {
		return priced -> priced.actual() > DEAR;
	}
}
