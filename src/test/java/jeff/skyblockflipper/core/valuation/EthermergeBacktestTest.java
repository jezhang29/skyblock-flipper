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
import jeff.skyblockflipper.core.nbt.NbtCompound;
import jeff.skyblockflipper.core.valuation.backtest.Backtest;
import jeff.skyblockflipper.core.valuation.backtest.CounterfactualKeying;
import jeff.skyblockflipper.core.valuation.backtest.SignatureTerms;
import jeff.skyblockflipper.core.valuation.backtest.TapeFixture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.time.Duration;
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
 * Why {@code ethermerge} belongs in the signature, measured. Opt-in, needs a recorded tape:
 * {@code ./gradlew test -PtapeBacktest --tests '*EthermergeBacktestTest'}.
 *
 * <p>It is the first attribute off {@code UnreadAttributeProbeTest}'s ranking rather than off the
 * spread ranking, and it is the first in four tries to survive the measurement. An Etherwarp
 * Conduit merged into an {@code ASPECT_OF_THE_VOID} is a permanent upgrade that costs a Transmission
 * Tuner and a conduit, and the market pays for it: under identical production signatures the merged
 * sales run several times the plain ones.
 *
 * <p>What makes it different from {@code power_ability_scroll} and the drill parts, which were bigger
 * markets and both measured out:
 *
 * <ul>
 * <li><b>It is one bit</b>, not an enumeration and not a near-identifier. {@code ethermerge} reads 1
 *     on every sale that has it, so splitting a pool splits it in two rather than into nine cells of
 *     one.
 * <li><b>Plain sales dominate the mixed pools</b>, the opposite of the scroll. A merged Aspect of the
 *     Void sits in a pool of unmerged ones and is priced by their median, so the pool is wrong about
 *     the merged item and stays right about the plain one - and there are enough plain sales left
 *     after the split for both cells to clear {@link ValueEstimate#MIN_SAMPLES}.
 * <li><b>It is nearly free.</b> Only 12 taped sales would lose their exact key, against 24 for the
 *     scroll and 405 configurations for the drill parts.
 * </ul>
 *
 * <p>The holdout arms run the model that ships, under a {@link Keying} that unreads the term. They
 * used to run a hand-built pair of maps that resembled it; see {@link Backtest} for what that copy
 * got wrong. Re-measuring against the real model made the case for the term <b>stronger</b>: fake
 * snipes unread went from the copy's 13 to 175, and keying them away costs 7 valuations in 1,053.
 *
 * <p>The reason is the copy kept every sample where {@code FairValueModel.Builder} keeps the most
 * recent 200. On a key that pools plain and merged sales the ring fills with whichever population
 * sold lately, so the pooled median swings to the dearer one and every plain sale reads as a snipe -
 * an error the copy could not produce and therefore never counted.
 */
@EnabledIfSystemProperty(named = "skyblockflipper.tapeBacktest", matches = "true")
class EthermergeBacktestTest {
	private static final long HOLDOUT_HOURS = 24L;

	/** Longer than any tape, so training is the unbounded replay these findings were measured on. */
	private static final Duration WHOLE_TAPE = Duration.ofDays(3650);

	private static final String ETHERMERGE = "ethermerge";
	private static final String TUNED = "tuned_transmission";
	private static final String NONE = "(none)";

	/**
	 * The market, and which way its mixed pools are wrong.
	 *
	 * <p>The direction is the whole question. A pool that undervalues its merged sales costs nothing -
	 * an item quoted below its worth is one nobody buys - while a pool that quotes a plain Aspect of
	 * the Void at a merged one's price is a fake snipe, and that is the error money is lost on.
	 */
	@Test
	void mixedPoolsOvervalueTheirPlainSales() throws Exception {
		Map<String, Map<String, List<Double>>> perSignature = new HashMap<>();
		Map<String, int[]> byId = new TreeMap<>();
		long[] coins = {0L};
		int[] bare = {0};

		TapeFixture.forEachSale((item, extra, timestamp, unitPrice) -> {
			boolean merged = extra.flag(ETHERMERGE);

			if (merged) {
				byId.computeIfAbsent(item.skyblockId(), k -> new int[1])[0]++;
				coins[0] += (long) unitPrice;

				if (bareApartFromMerge(item)) {
					bare[0]++;
				}
			}

			perSignature
					.computeIfAbsent(unmerged(item), k -> new TreeMap<>())
					.computeIfAbsent(merged ? term(extra) : NONE, k -> new ArrayList<>())
					.add(unitPrice);
		});

		int mixed = 0;
		int quotable = 0;
		int plainOvervalued = 0;
		int mergedOvervalued = 0;
		List<String> table = new ArrayList<>();

		for (Map.Entry<String, Map<String, List<Double>>> entry : perSignature.entrySet()) {
			Map<String, List<Double>> variants = entry.getValue();

			if (variants.size() < 2 || variants.keySet().stream().noneMatch(v -> !v.equals(NONE))) {
				continue;
			}

			mixed++;
			OptionalDouble quoted = Backtest.quotableMedian(
					variants.values().stream().flatMap(List::stream).toList());

			if (quoted.isEmpty()) {
				continue;
			}

			quotable++;
			StringBuilder detail = new StringBuilder();
			double worst = 1.0d;

			for (Map.Entry<String, List<Double>> variant : variants.entrySet()) {
				double med = TapeFixture.median(variant.getValue(), 1).orElseThrow();
				double ratio = quoted.getAsDouble() / med;
				worst = Math.max(worst, Math.max(ratio, 1.0d / ratio));

				if (ratio >= 2.0d) {
					if (variant.getKey().equals(NONE)) {
						plainOvervalued++;
					} else {
						mergedOvervalued++;
					}
				}

				detail.append(String.format("%s n=%d med %,.0f; ",
						variant.getKey(), variant.getValue().size(), med));
			}

			table.add(String.format("  %5.1fx  quoted %,15.0f  %-46s %s",
					worst, quoted.getAsDouble(), trim(entry.getKey()), detail));
		}

		System.out.printf("%n%,d merged sales carrying %,d coins, %,d of them otherwise bare; ids: %s%n",
				byId.values().stream().mapToInt(c -> c[0]).sum(), coins[0], bare[0],
				byId.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()[0]).toList());
		System.out.printf("%n%,d production signatures mix merged and unmerged sales, %,d of them "
				+ "quotable:%n", mixed, quotable);
		table.stream().sorted().forEach(System.out::println);
		System.out.printf("  quotable variants the pooled median overvalues 2x+: %,d plain, %,d merged%n",
				plainOvervalued, mergedOvervalued);

		// The finding. Unlike the scroll and the drill parts, the mixed pools here are wrong in the
		// expensive direction: the plain sale is the one quoted high, and a plain sale is what a flip
		// would be bought on.
		assertTrue(plainOvervalued > 0, "ethermerge is only worth keying because its mixed pools quote "
				+ "plain sales above what plain sales fetch, and none did");
	}

	/**
	 * Keying it prices the merged sales and stops the fake snipes, at a cost of a dozen valuations.
	 *
	 * <p>Trains on everything older than the newest 24 hours and scores what is in it, restricted to
	 * the item ids that ever carry the attribute.
	 */
	@Test
	void keyingTheMergeFixesMoreThanItCosts() throws Exception {
		Set<String> ids = idsThatEverMerge();
		long cutoff = TapeFixture.newestTimestamp() - HOLDOUT_HOURS * 3_600_000L;

		Backtest.Result pooled = Backtest.holdout(CounterfactualKeying.withoutTerm(ETHERMERGE),
				cutoff, WHOLE_TAPE, item -> ids.contains(item.skyblockId()));
		Backtest.Result keyed = Backtest.holdout(Keying.PRODUCTION,
				cutoff, WHOLE_TAPE, item -> ids.contains(item.skyblockId()));

		System.out.printf("%n%,d item ids ever carry an ethermerge%n", ids.size());
		System.out.printf("held-out sales of those ids:%n  %-26s %s%n  %-26s %s%n",
				"today (merge unread)", pooled, "with the merge keyed", keyed);
		System.out.printf("  merged sales priced: %,d unread, %,d keyed%n",
				pooled.count(DecodedItem::ethermerged), keyed.count(DecodedItem::ethermerged));
		System.out.printf("  overvaluations by how the quote was matched:%n    unread %s%n    keyed  %s%n",
				overvaluedByBasis(pooled), overvaluedByBasis(keyed));

		assertTrue(keyed.overvaluedBy(2.0d) < pooled.overvaluedBy(2.0d), "keying the merge is expected "
				+ "to remove fake snipes, but overvaluations went from " + pooled.overvaluedBy(2.0d)
				+ " to " + keyed.overvaluedBy(2.0d));

		// Coverage is the price of every signature term, and this one is expected to be cheap.
		assertTrue(keyed.priced().size() * 10 > pooled.priced().size() * 9, "the merge is expected to "
				+ "cost few valuations, but priced sales went from " + pooled.priced().size() + " to "
				+ keyed.priced().size());
	}

	/**
	 * Why the Transmission Tuner level stays out of the term, measured without a model.
	 *
	 * <p>{@code tuned_transmission} rides along on a merged item and is worth about 1.06x on top of
	 * it, so splitting merged sales again buys a difference the market barely prices - and costs the
	 * cells that fall under {@link ValueEstimate#MIN_SAMPLES} their valuation entirely.
	 *
	 * <p>A pool-shape measurement rather than a holdout, because the tuner level is not a term
	 * {@link DecodedItem} reads: two sales differing only in tuner decode identically, so no
	 * {@link Keying} over a decoded item can tell them apart. Counting the cells the split would
	 * strand is the same claim, made where the evidence is.
	 */
	@Test
	void theTunerLevelSplitsCellsForADifferenceTheMarketBarelyPrices() throws Exception {
		Map<String, List<Double>> merged = new HashMap<>();
		Map<String, List<Double>> mergedAndTuned = new HashMap<>();

		TapeFixture.forEachSale((item, extra, timestamp, unitPrice) -> {
			if (!extra.flag(ETHERMERGE)) {
				return;
			}

			String base = unmerged(item);
			merged.computeIfAbsent(base, k -> new ArrayList<>()).add(unitPrice);
			mergedAndTuned.computeIfAbsent(base + "|" + term(extra), k -> new ArrayList<>())
					.add(unitPrice);
		});

		// Sales, not cells. A cell of 309 splitting into 288 and 21 leaves two quotable cells where
		// there was one, so counting cells reads as a gain; what the split actually costs is the
		// sales that land in a cell too small to quote, and those lose their valuation outright.
		int quotableSalesMerged = quotableSales(merged);
		int quotableSalesTuned = quotableSales(mergedAndTuned);
		int stranded = quotableSalesMerged - quotableSalesTuned;

		System.out.printf("%n%,d merged cells holding %,d quotable sales; split by tuner level: "
						+ "%,d cells holding %,d quotable sales, so %,d sales lose their valuation%n",
				merged.size(), quotableSalesMerged, mergedAndTuned.size(), quotableSalesTuned,
				stranded);

		assertTrue(mergedAndTuned.size() > merged.size(), "the tuner level is expected to split merged "
				+ "cells, and it split none - either no merged sale is tuned, or the attribute moved");
		assertTrue(stranded > 0, "the tuner level is kept out because splitting merged sales again "
				+ "strands some of them below the sample floor, and this split stranded none");
	}

	/**
	 * Which index produced each fake snipe.
	 *
	 * <p>Worth printing because the coarse index is the one that pools on name and rarity alone, and
	 * a run where the overvaluations are mostly {@code COARSE} is saying the fallback pool is
	 * contaminated rather than that the term under test is wrong.
	 */
	private static Map<ValueEstimate.Basis, Long> overvaluedByBasis(Backtest.Result result) {
		return result.priced().stream()
				.filter(priced -> priced.overvaluedBy(2.0d))
				.collect(java.util.stream.Collectors.groupingBy(Backtest.Priced::basis,
						TreeMap::new, java.util.stream.Collectors.counting()));
	}

	/** Sales sitting in a cell with enough company to be quoted at all. */
	private static int quotableSales(Map<String, List<Double>> cells) {
		return cells.values().stream()
				.filter(prices -> prices.size() >= ValueEstimate.MIN_SAMPLES)
				.mapToInt(List::size)
				.sum();
	}

	private static Set<String> idsThatEverMerge() throws Exception {
		Set<String> ids = new HashSet<>();

		TapeFixture.forEachSale((item, extra, timestamp, unitPrice) -> {
			if (extra.flag(ETHERMERGE)) {
				ids.add(item.skyblockId());
			}
		});

		assertTrue(!ids.isEmpty(), "no ethermerged sales on the tape at " + TapeFixture.tapeDir());
		return ids;
	}

	/**
	 * The candidate term: the merge, plus the tuning that rides on it.
	 *
	 * <p>{@code tuned_transmission} is the Transmission Tuner level and only ever appears on a merged
	 * item, so it is measured as part of the same term rather than as a competing one. Its own row in
	 * the probe shows no overvaluation at all, which is what one would expect from a term that never
	 * varies independently.
	 */
	private static String term(NbtCompound extra) {
		int tuned = extra.intOr(TUNED, 0);
		return tuned > 0 ? "ethermerge,tuned=" + tuned : "ethermerge";
	}

	/** The signature this item had before the merge was read, which is the baseline being measured. */
	private static String unmerged(DecodedItem item) {
		return SignatureTerms.without(item.signature(), ETHERMERGE);
	}

	/** Bare once the merge is unread - the sales that would join the coarse pool of plain ones. */
	private static boolean bareApartFromMerge(DecodedItem item) {
		return CounterfactualKeying.withoutTerm(ETHERMERGE).isBare(item);
	}

	private static String trim(String key) {
		return key.length() <= 46 ? key : key.substring(0, 43) + "...";
	}
}
