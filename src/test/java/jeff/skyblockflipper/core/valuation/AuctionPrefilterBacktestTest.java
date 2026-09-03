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
import jeff.skyblockflipper.core.model.ActiveListing;
import jeff.skyblockflipper.core.tape.SalesTape;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Measures the recall/cost tradeoff of the cheap live-auction prefilter against recorded sales.
 * Run with {@code ./gradlew test -PtapeBacktest --tests '*AuctionPrefilterBacktestTest'}.
 *
 * <p>A family median is safe as a valuation fallback but incomplete as a decode gate. An upgraded
 * configuration can be worth much more than the median of items with the same visible name and
 * rarity. This test discounts each priceable held-out configuration by 20%, then asks whether a
	 * family statistic would let that synthetic exact-value bargain reach the decoder. It also
	 * applies the gate to held-out sales at their real prices as a proxy for the share of live
	 * listings that would be decoded.
 */
@EnabledIfSystemProperty(named = "skyblockflipper.tapeBacktest", matches = "true")
class AuctionPrefilterBacktestTest {
	private static final int DAYS_TO_READ = 4;
	private static final Duration TRAINING_WINDOW = Duration.ofHours(48);
	private static final Duration HOLDOUT = Duration.ofHours(6);
	private static final double SCAN_DISCOUNT = 0.15d;
	private static final double SYNTHETIC_DISCOUNT = 0.20d;
	private static final String DEFAULT_TAPE_DIR = "run/config/skyblock-flipper/tape";

	@Test
	void measuresFamilyCeilingsBeforeChangingTheDecodeGate() throws Exception {
		SalesTape tape = new SalesTape(tapeDir(), Integer.MAX_VALUE);
		long[] newest = {0L};
		tape.forEachRecent(DAYS_TO_READ,
				sale -> newest[0] = Math.max(newest[0], sale.timestamp()));

		long splitAt = newest[0] - HOLDOUT.toMillis();
		Instant trainEnd = Instant.ofEpochMilli(splitAt);
		FairValueModel.Builder model = FairValueModel.builder(trainEnd, TRAINING_WINDOW);
		Map<String, List<Double>> families = new HashMap<>();
		List<HeldOut> holdout = new ArrayList<>();

		tape.forEachRecent(DAYS_TO_READ, sale -> {
			if (!sale.bin() || sale.price() <= 0L) {
				return;
			}
			ItemDecoder.decode(sale.itemBytes()).ifPresent(item -> {
				double price = (double) sale.price() / Math.max(1, item.count());
				if (sale.timestamp() >= splitAt) {
					holdout.add(new HeldOut(item, price));
				} else if (sale.timestamp() >= splitAt - TRAINING_WINDOW.toMillis()) {
					model.add(item, price, sale.timestamp());
					families.computeIfAbsent(coarseKey(item), ignored -> new ArrayList<>())
							.add(price);
				}
			});
		});

		assertFalse(holdout.isEmpty(), "no held-out BIN sales on tape at " + tapeDir());
		FairValueModel trained = model.build();
		Map<String, Double> quantiles = new LinkedHashMap<>();
		quantiles.put("median", 0.50d);
		quantiles.put("p75", 0.75d);
		quantiles.put("p90", 0.90d);
		quantiles.put("p95", 0.95d);
		quantiles.put("p99", 0.99d);
		quantiles.put("maximum", 1.00d);

		System.out.printf("%nauction prefilter backtest: %,d held-out BIN sales, %dh train, %dh holdout%n",
				holdout.size(), TRAINING_WINDOW.toHours(), HOLDOUT.toHours());
		System.out.printf("%-9s %11s %13s%n", "ceiling", "snipe recall", "decode proxy");
		for (Map.Entry<String, Double> arm : quantiles.entrySet()) {
			Score score = score(trained, families, holdout, arm.getValue());
			System.out.printf("%-9s %10.1f%% %12.1f%%%n", arm.getKey(),
					100.0d * score.admittedBargains / Math.max(1, score.priceable),
					100.0d * score.decodedSales / holdout.size());
		}
	}

	private static Score score(FairValueModel model, Map<String, List<Double>> families,
			List<HeldOut> holdout, double quantile) {
		Score score = new Score();
		Map<String, Double> ceilings = new HashMap<>();
		families.forEach((key, values) -> ceilings.put(key, percentile(values, quantile)));

		for (HeldOut sale : holdout) {
			Double ceiling = ceilings.get(coarseKey(sale.item));
			if (ceiling == null) {
				continue;
			}
			if (sale.price <= ceiling * (1.0d - SCAN_DISCOUNT)) {
				score.decodedSales++;
			}
			var exact = model.valueOf(sale.item);
			if (exact.isEmpty() || exact.orElseThrow().confidence() < 0.6d) {
				continue;
			}
			score.priceable++;
			double bargain = exact.orElseThrow().median() * (1.0d - SYNTHETIC_DISCOUNT);
			if (bargain <= ceiling * (1.0d - SCAN_DISCOUNT)) {
				score.admittedBargains++;
			}
		}
		return score;
	}

	private static String coarseKey(DecodedItem item) {
		return ActiveListing.coarseKey(item.displayName(), item.rarity());
	}

	private static double percentile(List<Double> values, double fraction) {
		double[] sorted = values.stream().mapToDouble(Double::doubleValue).sorted().toArray();
		int index = (int) Math.round(fraction * (sorted.length - 1));
		return sorted[Math.clamp(index, 0, sorted.length - 1)];
	}

	private record HeldOut(DecodedItem item, double price) {}

	private static final class Score {
		private int priceable;
		private int admittedBargains;
		private int decodedSales;
	}

	private static Path tapeDir() {
		return Path.of(System.getProperty("skyblockflipper.tapeDir", DEFAULT_TAPE_DIR));
	}
}
