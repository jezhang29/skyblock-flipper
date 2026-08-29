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
import jeff.skyblockflipper.core.item.DungeonQuality;
import jeff.skyblockflipper.core.nbt.NbtCompound;
import jeff.skyblockflipper.core.valuation.backtest.Backtest;
import jeff.skyblockflipper.core.valuation.backtest.CounterfactualKeying;
import jeff.skyblockflipper.core.valuation.backtest.TapeFixture;
import jeff.skyblockflipper.core.valuation.backtest.UnreadTerms;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What reading a dungeon drop's quality roll is worth, measured. Opt-in, needs a recorded tape:
 * {@code ./gradlew test -PtapeBacktest --tests '*DungeonQualityBacktestTest'}.
 *
 * <p>Not a shared item id like {@code PET}, {@code RUNE} and {@code POTION} - these drops keep their
 * own ids - but the same pooling, and after those three the largest one left by coins. 9,759 of
 * 222,430 taped BIN sales carry {@code baseStatBoostPercentage}, and under one key
 * {@code SKELETON_MASTER_CHESTPLATE} spans 980,000 to 113,000,000 coins on the floor tier alone.
 *
 * <p>This also decides the shape of the key rather than assuming it, by scoring six ways of writing
 * the same two attributes down. The interesting comparison is not against the pooled key - that one
 * obviously loses - but between the variants, because {@code baseStatBoostPercentage} runs 1 to 50
 * and splitting on all fifty values would cost most of the coverage.
 *
 * <p>Every arm now runs the model that ships, differing only in the {@link Keying} it is built and
 * read back under. The arms used to be hand-built maps; see {@link Backtest} for what that copy got
 * wrong.
 *
 * <p><b>The re-measurement changed what this term is for.</b> On a 24h holdout of the 65 ids that
 * ever carry a roll, the tier term prices 2,588 of 7,578 held-out sales against pooling's 5,886 and
 * takes fake snipes from 1,105 to 467. Almost all of that is the coverage, not the quotes: on the
 * 2,588 sales both arms price they are indistinguishable - 424 against 467 overvalued 2x+, 157
 * against 154 wrong by 5x in either direction. The term earns its place on the sales pooling gets
 * catastrophically wrong rather than on an average, and those are visible only in the direction this
 * repo usually ignores. A tier-10 {@code SKELETON_MASTER_CHESTPLATE} that fetched 115,000,000 is
 * quoted at 1,800,000 pooled and 107,000,000 keyed, because its pooled key holds the tier-7 sales
 * too. An undervaluation costs no coins directly; it means the model has nothing to say about the
 * market where the coins are.
 *
 * <p>So the recorded figures for this split - fake snipes 137 to 99, p90 1.281 to 1.194 - are the
 * hand-built copy's and are superseded. The decision is unchanged.
 */
@EnabledIfSystemProperty(named = "skyblockflipper.tapeBacktest", matches = "true")
class DungeonQualityBacktestTest {
	private static final long HOLDOUT_HOURS = 24L;

	/** Longer than any tape, so training is the unbounded replay these findings were measured on. */
	private static final Duration WHOLE_TAPE = Duration.ofDays(3650);

	private static final String QUALITY = "quality=";
	private static final int MAX_STAT_BOOST = 50;
	private static final int NO_TIER = DungeonQuality.NO_TIER;

	private static final String POOLED = "pooled (no quality term)";
	private static final String SHIPPED = "maxed flag + tier (shipped)";
	private static final String TIER_ONLY = "tier only";
	private static final String EXACT_BOOST = "exact boost + tier";

	@Test
	void keyingOnTheQualityRollStopsDungeonDropsInventingSnipes() throws Exception {
		Set<String> ids = idsThatEverCarryARoll();
		long cutoff = TapeFixture.newestTimestamp() - HOLDOUT_HOURS * 3_600_000L;

		// Every way of writing the roll down that is worth scoring, from ignoring it to taking the
		// stat boost at face value.
		Map<String, Roll> variants = new LinkedHashMap<>();
		variants.put(TIER_ONLY, (boost, tier) -> tier == NO_TIER ? "" : "tier=" + tier);
		variants.put("maxed flag only", (boost, tier) -> boost >= MAX_STAT_BOOST ? "maxed" : "");
		variants.put("boost banded in tens + tier", (boost, tier) -> term(
				boost >= MAX_STAT_BOOST ? "maxed" : "boost" + (boost / 10) + "x", tier));
		variants.put(EXACT_BOOST, (boost, tier) -> term("boost=" + boost, tier));

		Map<String, Backtest.Result> scores = new LinkedHashMap<>();
		scores.put(POOLED, Backtest.holdout(CounterfactualKeying.withoutTerm(QUALITY), cutoff,
				WHOLE_TAPE, item -> ids.contains(item.skyblockId())));
		scores.put(SHIPPED, Backtest.holdout(Keying.PRODUCTION, cutoff, WHOLE_TAPE,
				item -> ids.contains(item.skyblockId())));

		for (Map.Entry<String, Roll> variant : variants.entrySet()) {
			UnreadTerms terms = new UnreadTerms((item, extra) -> quality(extra, variant.getValue()));
			scores.put(variant.getKey(), Backtest.holdout(terms.keyingInsteadOf(QUALITY), cutoff,
					WHOLE_TAPE, item -> ids.contains(item.skyblockId()), terms));
		}

		System.out.printf("%n%,d item ids ever carry a quality roll%n", ids.size());
		scores.forEach((name, scored) -> System.out.printf("  %-28s %s%n", name, scored));

		Backtest.Result pooled = scores.get(POOLED);
		Backtest.Result shipped = scores.get(SHIPPED);

		// Paired, because the two arms do not price the same sales. The tier term more than halves
		// coverage on these ids, and a whole-arm count of fake snipes cannot tell "quotes fewer sales"
		// from "quotes them better". This asks the narrower question the term has to answer: of the
		// sales both arms price, how many does each get badly wrong?
		Map<String, Backtest.Priced> byKey = new HashMap<>();
		shipped.priced().forEach(priced -> byKey.put(priced.saleKey(), priced));

		int common = 0;
		int pooledOver = 0;
		int shippedOver = 0;
		int pooledWayOff = 0;
		int shippedWayOff = 0;

		for (Backtest.Priced sale : pooled.priced()) {
			Backtest.Priced match = byKey.get(sale.saleKey());

			if (match == null) {
				continue;
			}

			common++;
			pooledOver += sale.overvaluedBy(2.0d) ? 1 : 0;
			shippedOver += match.overvaluedBy(2.0d) ? 1 : 0;
			pooledWayOff += wayOff(sale) ? 1 : 0;
			shippedWayOff += wayOff(match) ? 1 : 0;
		}

		// Both directions here, unlike everywhere else in this repo, and the reason is what the paired
		// comparison turned up. At 2x overvaluation the two arms are indistinguishable on the sales
		// they share (424 against 467), so on that metric alone the tier term looks like it buys
		// nothing but a halved coverage. What it actually fixes is the other direction: pooling quotes
		// a tier-10 SKELETON_MASTER_CHESTPLATE that fetched 100,000,000 at 9,800,000, because its key
		// holds the tier-7 sales too. That is not a fake snipe, it is the model being useless about
		// the market where the coins are, and it is invisible to an overvaluation count.
		System.out.printf("  on the %,d sales both arms price:%n"
						+ "    2x+ overvalued:      %,d pooled, %,d keyed%n"
						+ "    5x+ wrong either way: %,d pooled, %,d keyed%n",
				common, pooledOver, shippedOver, pooledWayOff, shippedWayOff);

		System.out.println("  dearest sales pooling gets 5x wrong and the tier term does not:");
		pooled.priced().stream()
				.filter(sale -> byKey.containsKey(sale.saleKey()))
				.filter(sale -> wayOff(sale) && !wayOff(byKey.get(sale.saleKey())))
				.sorted((a, b) -> Double.compare(b.actual(), a.actual()))
				.limit(5)
				.forEach(sale -> System.out.printf("    fetched %,12.0f  keyed %,12.0f  pooled %,12.0f"
								+ "  %s%n",
						sale.actual(), byKey.get(sale.saleKey()).estimate(), sale.estimate(),
						sale.item().signature()));

		// A tolerance rather than a strict improvement, because on the recorded tape this is 157
		// against 154 and three sales is not a finding. What is asserted is the claim that survives:
		// on the sales it still prices, the tier term does not make the model worse, and the case for
		// it rests on the coverage half below and on the tier-10 cases the print above shows.
		assertTrue(shippedWayOff * 10 <= pooledWayOff * 11, "on the sales both arms price, keying the "
				+ "quality roll should not leave materially more sales quoted 5x away from what they "
				+ "fetched, but it went from " + pooledWayOff + " to " + shippedWayOff);

		assertTrue(shipped.overvaluedBy(2.0d) < pooled.overvaluedBy(2.0d),
				"keying on the quality roll should invent fewer fake snipes overall too, but it "
						+ "went from " + pooled.overvaluedBy(2.0d) + " to " + shipped.overvaluedBy(2.0d));

		// And the reason the boost is a flag rather than a number: fifty values per item shatter the
		// key into cells too thin to price, so the exact variant buys its accuracy with coverage the
		// shipped one keeps. If this ever stops holding, the flag is the wrong call.
		assertTrue(scores.get(EXACT_BOOST).priced().size() < shipped.priced().size(),
				"splitting on the exact stat boost should price fewer sales than treating it as a "
						+ "maxed flag, but it priced " + scores.get(EXACT_BOOST).priced().size()
						+ " against " + shipped.priced().size());

		// The flag itself is inert against the full signature - see the second test below for why it
		// is shipped regardless. What matters here is that it is inert in the harmless direction:
		// carrying it costs no coverage at all, which is the whole reason keeping a dormant term is
		// affordable. If this stops holding, the flag has started splitting keys and the case for it
		// should be re-argued on what that split is worth rather than on it being free.
		assertEquals(scores.get(TIER_ONLY).priced().size(), shipped.priced().size(),
				"the maxed flag is expected to split no key at all, so adding it to the tier should "
						+ "price exactly as many sales");
	}

	/**
	 * Why the maxed flag ships even though the test above measures it changing nothing.
	 *
	 * <p>It is redundant, not worthless. Keyed coarsely on id, rarity and tier - dropping the
	 * investment terms that make the full signature so fine - maxedness is worth up to 44x, and the
	 * full signature only separates it by accident, because a maxed drop happens to be one somebody
	 * also starred and enchanted. This measures the size of the hole that correlation is covering.
	 */
	@Test
	void maxednessIsWorthKeepingBecauseNothingElseActuallyStatesIt() throws Exception {
		Map<String, List<Double>> maxed = new HashMap<>();
		Map<String, List<Double>> plain = new HashMap<>();

		TapeFixture.forEachSale((item, extra, timestamp, unitPrice) -> {
			if (!item.hasQuality()) {
				return;
			}

			int tier = item.quality().floorTier();
			String coarse = item.skyblockId() + "|" + item.rarity()
					+ (tier == NO_TIER ? "" : "|tier=" + tier);
			(item.quality().maxedStats() ? maxed : plain)
					.computeIfAbsent(coarse, k -> new ArrayList<>()).add(unitPrice);
		});

		double widest = 1.0d;
		String widestKey = "";

		System.out.printf("%nmaxed against unmaxed at the same id, rarity and tier:%n");

		for (Map.Entry<String, List<Double>> entry : maxed.entrySet()) {
			OptionalDouble withRoll = Backtest.quotableMedian(entry.getValue());
			OptionalDouble without = Backtest.quotableMedian(plain.get(entry.getKey()));

			if (withRoll.isEmpty() || without.isEmpty() || without.getAsDouble() <= 0.0d) {
				continue;
			}

			double ratio = withRoll.getAsDouble() / without.getAsDouble();
			System.out.printf("  %-52s maxed %,14.0f | plain %,14.0f  (%.2fx)%n",
					entry.getKey(), withRoll.getAsDouble(), without.getAsDouble(), ratio);

			if (ratio > widest) {
				widest = ratio;
				widestKey = entry.getKey();
			}
		}

		System.out.printf("  widest: %s at %.2fx%n", widestKey, widest);

		// Most of these cells sit at about 1x, which is the flatness that makes the flag inert. The
		// one that does not is the reason it ships: on the recorded tape SKELETON_MASTER_CHESTPLATE
		// at tier 10 is 109,940,000 maxed against 2,500,000 plain.
		assertTrue(widest >= 10.0d, "maxedness should be worth at least an order of magnitude "
				+ "somewhere, or the flag really is dead weight - widest was " + widest);
	}

	/** Quoted 5x away from what the sale fetched, in either direction. */
	private static boolean wayOff(Backtest.Priced sale) {
		return sale.estimate() >= 5.0d * sale.actual() || sale.actual() >= 5.0d * sale.estimate();
	}

	/** Which ids the question is about: every id that ever dropped with a roll on it. */
	private static Set<String> idsThatEverCarryARoll() throws Exception {
		Set<String> ids = new HashSet<>();

		TapeFixture.forEachSale((item, extra, timestamp, unitPrice) -> {
			if (item.hasQuality()) {
				ids.add(item.skyblockId());
			}
		});

		assertTrue(!ids.isEmpty(), "no quality rolls on the tape at " + TapeFixture.tapeDir());
		return ids;
	}

	/**
	 * One arm's spelling of a roll, read off the raw blob.
	 *
	 * <p>Off the blob rather than off {@link DecodedItem#quality()} because the exact and banded arms
	 * need the stat boost as a number, and the decoder deliberately keeps only the maxed bit - that
	 * being the finding this test produced.
	 */
	private static String quality(NbtCompound extra, Roll roll) {
		if (!extra.contains("baseStatBoostPercentage")) {
			return "";
		}

		String term = roll.term(extra.intOr("baseStatBoostPercentage", 0),
				extra.intOr("item_tier", NO_TIER));
		return term.isEmpty() ? "" : QUALITY + term;
	}

	private static String term(String boostTerm, int tier) {
		if (tier == NO_TIER) {
			return boostTerm;
		}

		return boostTerm.isEmpty() ? "tier=" + tier : boostTerm + ",tier=" + tier;
	}

	/** One way of turning a roll into a signature clause, or "" for no clause at all. */
	private interface Roll {
		String term(int boost, int tier);
	}
}
