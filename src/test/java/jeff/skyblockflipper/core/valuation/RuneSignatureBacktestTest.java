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
import jeff.skyblockflipper.core.valuation.backtest.CounterfactualKeying;
import jeff.skyblockflipper.core.valuation.backtest.TapeFixture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

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
 * <p>Splitting one key per rarity into one per rune and tier leaves fewer sales behind each, so fewer
 * held-out runes get quoted at all. That is the trade being made on purpose. Declining to quote a
 * rune costs a flip that was never real; quoting it off a different rune's sales costs coins.
 *
 * <p>Re-measured against the model that ships. The original version of this test scored a hand-built
 * pair of maps and asserted on the median error alone, which the potion split later showed to be the
 * wrong metric for a signature term - a pooled median is accidentally right about whatever dominates
 * its count. The tail is asserted first here.
 *
 * <p>On a 24h holdout of the 128 ids that ever carry a rune: fake snipes 857 unread against 496
 * keyed, p90 |log err| 0.981 against 0.693, median 0.155 against 0.143, and coverage 11,259 against
 * 11,143. Runed sales themselves go from 1,478 priced to 1,371. The gain is real and smaller than the
 * rune-sales-only figures the finding was first recorded with, which is what including the item ids'
 * whole market does to any per-attribute number.
 */
@EnabledIfSystemProperty(named = "skyblockflipper.tapeBacktest", matches = "true")
class RuneSignatureBacktestTest {
	private static final long HOLDOUT_HOURS = 24L;

	/** Longer than any tape, so training is the unbounded replay these findings were measured on. */
	private static final Duration WHOLE_TAPE = Duration.ofDays(3650);

	private static final String RUNES = "runes=";

	@Test
	void readingTheRuneMakesRunesPriceable() throws Exception {
		Set<String> ids = idsThatEverCarryARune();
		long cutoff = TapeFixture.newestTimestamp() - HOLDOUT_HOURS * 3_600_000L;

		Backtest.Result pooled = Backtest.holdout(CounterfactualKeying.withoutTerm(RUNES),
				cutoff, WHOLE_TAPE, item -> ids.contains(item.skyblockId()));
		Backtest.Result keyed = Backtest.holdout(Keying.PRODUCTION,
				cutoff, WHOLE_TAPE, item -> ids.contains(item.skyblockId()));

		System.out.printf("%n%,d item ids ever carry a rune%n", ids.size());
		System.out.printf("held-out sales of those ids:%n  %-26s %s%n  %-26s %s%n",
				"rune unread", pooled, "with the rune keyed", keyed);
		System.out.printf("  runed sales priced: %,d unread, %,d keyed%n",
				pooled.count(item -> !item.runes().isEmpty()),
				keyed.count(item -> !item.runes().isEmpty()));

		// The tail, which is what the term exists for: a Gem rune quoted off Music rune sales is a
		// fake snipe, and a fake snipe is the only error that costs coins.
		assertTrue(keyed.overvaluedBy(2.0d) * 4 < pooled.overvaluedBy(2.0d) * 3,
				"keying the rune is expected to remove a large share of the fake snipes, but sales "
						+ "quoted at 2x or more of what they fetched went from "
						+ pooled.overvaluedBy(2.0d) + " to " + keyed.overvaluedBy(2.0d));

		// And the coverage it costs, which is real and deliberate. This bound is loose: the point is
		// that the split does not cost the rune market its valuations wholesale.
		assertTrue(keyed.priced().size() * 2 > pooled.priced().size(), "the rune split is expected to "
				+ "cost coverage and not to halve it, but priced sales went from "
				+ pooled.priced().size() + " to " + keyed.priced().size());
	}

	private static Set<String> idsThatEverCarryARune() throws Exception {
		Set<String> ids = new HashSet<>();

		TapeFixture.forEachSale((item, extra, timestamp, unitPrice) -> {
			if (!item.runes().isEmpty()) {
				ids.add(item.skyblockId());
			}
		});

		assertTrue(!ids.isEmpty(), "no runed sales on the tape at " + TapeFixture.tapeDir());
		return ids;
	}
}
