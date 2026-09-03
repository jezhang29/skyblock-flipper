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
import jeff.skyblockflipper.core.item.ItemDecoder;
import jeff.skyblockflipper.core.pricing.Fees;
import jeff.skyblockflipper.core.tape.SalesTape;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Measures what the exact gate's tighter discount actually buys, on recorded sales.
 * Run with {@code ./gradlew test -PtapeBacktest --tests '*ExactMarginBacktestTest'}.
 *
 * <p>The exact gate ({@link UnderpricedScan}) fired only below {@code snipeMinDiscount} (15%). Item 3
 * added a second, smaller margin ({@code exactMinDiscount}, 5%) that fires when the exact-signature
 * estimate is well-backed - confidence &gt; 0.80, samples &gt;= 15, dispersion &lt; 0.20. This asks
 * two things of the tape: how many more listings the 5% margin flags, and whether those extra flags
 * would actually have resold for a profit.
 *
 * <p>Method. Train a {@link FairValueModel} on the 48 hours before a split point - what the mod would
 * have known. Take the 6 hours after the split as a stream of listings, each priced at its realized
 * sale, and as the resale-truth market for the same window. A listing is scored a real opportunity
 * only if buying it at its price and reselling at the out-of-sample median of its exact signature
 * clears {@link Fees#binRoundTripProfit} - so a flag against a stale, too-high training median, where
 * the item then resold at about what the listing paid, is counted a false positive, not a win.
 *
 * <p>Caveat. This measures the exact gate directly. In the ordinary sweep some of these listings
 * still need the unchanged coarse gate to admit the decode first, so the extra flags here are the
 * upper bound of what the change surfaces (the recovery-forced-decode path reaches the exact gate
 * without the coarse margin, and takes all of them). A held-out completed sale stands in for a live
 * listing, the same proxy the prefilter backtest uses.
 */
@EnabledIfSystemProperty(named = "skyblockflipper.tapeBacktest", matches = "true")
class ExactMarginBacktestTest {
	private static final int DAYS_TO_READ = 4;
	private static final Duration TRAINING_WINDOW = Duration.ofHours(48);
	private static final Duration HOLDOUT = Duration.ofHours(6);
	private static final double COARSE_MARGIN = 0.15d;

	// The exact estimate has to be this well-backed before the tighter margin fires. Kept in step
	// with UnderpricedScan's own guard constants; the dispersion cap is swept below.
	private static final double MIN_CONFIDENCE = 0.80d;
	private static final int MIN_SAMPLES = 15;

	// A resale truth needs a few realized sales behind it; one is an anecdote either way. Raise it
	// with -Dskyblockflipper.minResaleSamples to check the false-positive rate is not just resale
	// noise on thin signatures.
	private static final int MIN_RESALE_SAMPLES =
			Integer.getInteger("skyblockflipper.minResaleSamples", 3);

	private static final String DEFAULT_TAPE_DIR = "run/config/skyblock-flipper/tape";

	@Test
	void measuresTheTighterExactMargin() throws Exception {
		SalesTape tape = new SalesTape(tapeDir(), Integer.MAX_VALUE);
		long[] newest = {0L};
		tape.forEachRecent(DAYS_TO_READ, sale -> newest[0] = Math.max(newest[0], sale.timestamp()));

		long splitAt = newest[0] - HOLDOUT.toMillis();
		long trainStart = splitAt - TRAINING_WINDOW.toMillis();
		FairValueModel.Builder model = FairValueModel.builder(Instant.ofEpochMilli(splitAt),
				TRAINING_WINDOW);
		Map<String, List<Double>> resaleTruth = new HashMap<>();
		List<HeldOut> holdout = new ArrayList<>();

		tape.forEachRecent(DAYS_TO_READ, sale -> {
			if (!sale.bin() || sale.price() <= 0L) {
				return;
			}
			ItemDecoder.decode(sale.itemBytes()).ifPresent(item -> {
				double price = (double) sale.price() / Math.max(1, item.count());
				if (sale.timestamp() >= splitAt) {
					holdout.add(new HeldOut(item, price));
					resaleTruth.computeIfAbsent(item.signature(), ignored -> new ArrayList<>())
							.add(price);
				} else if (sale.timestamp() >= trainStart) {
					model.add(item, price, sale.timestamp());
				}
			});
		});

		assertFalse(holdout.isEmpty(), "no held-out BIN sales on tape at " + tapeDir());
		FairValueModel trained = model.build();
		Fees fees = new Fees(0, false);

		// The bar the shipped exact gate held before item 3: a listing 15% under its exact median.
		// Its quality is the reference the tighter margin has to reach to be worth its extra flags.
		Arm baseline = new Arm();
		for (HeldOut sale : holdout) {
			trained.valueOf(sale.item).ifPresent(value -> {
				long price = Math.round(sale.price);
				if (price <= value.median() * (1.0d - COARSE_MARGIN)) {
					baseline.record(sale.item, price, fees, resaleTruth);
				}
			});
		}

		System.out.printf("%nexact-margin backtest: %,d held-out BIN sales, %dh train, %dh holdout, "
				+ "resale truth >= %d sales%n",
				holdout.size(), TRAINING_WINDOW.toHours(), HOLDOUT.toHours(), MIN_RESALE_SAMPLES);
		System.out.printf("%-22s %7s %11s %11s %13s %16s %13s%n",
				"arm", "flags", "measurable", "true opps", "false-pos", "net coins", "net/flag");
		printArm("baseline 15%", baseline);

		// Sweep the tighter margin and the dispersion cap it fires under. For each, only the listings
		// the margin ADDS over the 15% baseline are scored: those are the whole question, and their
		// false-positive rate against the 26% baseline is what decides whether the added spend earns
		// its clicks. A tighter dispersion cap should trade flags for a lower false-positive rate.
		double[] margins = {0.05d, 0.07d, 0.10d, 0.12d};
		double[] dispersionCaps = {0.20d, 0.12d, 0.08d};
		for (double dispersionCap : dispersionCaps) {
			for (double margin : margins) {
				Arm added = new Arm();
				for (HeldOut sale : holdout) {
					trained.valueOf(sale.item).ifPresent(value -> {
						long price = Math.round(sale.price);
						boolean flagged15 = price <= value.median() * (1.0d - COARSE_MARGIN);
						boolean trusted = value.confidence() > MIN_CONFIDENCE
								&& value.samples() >= MIN_SAMPLES
								&& value.dispersion() < dispersionCap;
						boolean flaggedTight = trusted
								&& price <= value.median() * (1.0d - margin);
						if (flaggedTight && !flagged15) {
							added.record(sale.item, price, fees, resaleTruth);
						}
					});
				}
				printArm(String.format("+%.0f%% disp<%.2f", margin * 100.0d, dispersionCap), added);
			}
		}
	}

	private static void printArm(String label, Arm arm) {
		int measurable = arm.truePositives + arm.falsePositives;
		long netPerFlag = arm.flags == 0 ? 0L : arm.netCoins / arm.flags;
		System.out.printf("%-22s %7d %11d %11d %12.1f%% %,16d %,13d%n",
				label, arm.flags, measurable, arm.truePositives,
				100.0d * arm.falsePositives / Math.max(1, measurable), arm.netCoins, netPerFlag);
	}

	private static double percentile(List<Double> values, double fraction) {
		double[] sorted = values.stream().mapToDouble(Double::doubleValue).sorted().toArray();
		int index = (int) Math.round(fraction * (sorted.length - 1));
		return sorted[Math.clamp(index, 0, sorted.length - 1)];
	}

	private record HeldOut(DecodedItem item, double price) {}

	private static final class Arm {
		private int flags;
		private int truePositives;
		private int falsePositives;
		private long netCoins;

		private void record(DecodedItem item, long buyPrice, Fees fees,
				Map<String, List<Double>> resaleTruth) {
			flags++;
			List<Double> resales = resaleTruth.get(item.signature());
			if (resales == null || resales.size() < MIN_RESALE_SAMPLES) {
				// No out-of-sample resale to judge against; count the flag, not a win or a loss.
				return;
			}
			long resale = Math.round(percentile(resales, 0.50d));
			long profit = fees.binRoundTripProfit(buyPrice, resale);
			netCoins += profit;
			if (profit > 0L) {
				truePositives++;
			} else {
				falsePositives++;
			}
		}
	}

	private static Path tapeDir() {
		return Path.of(System.getProperty("skyblockflipper.tapeDir", DEFAULT_TAPE_DIR));
	}
}
