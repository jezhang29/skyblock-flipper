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
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P2 rolling release gates for the Wither-scroll comparable-sales repair. */
@EnabledIfSystemProperty(named = "skyblockflipper.tapeBacktest", matches = "true")
class WitherScrollReleaseBacktestTest {
	private static final Duration TRAINING_WINDOW = Duration.ofHours(48);
	private static final int[] HOLDOUT_HOURS = {6, 12, 24};
	private static final String ABILITY_SCROLL_TERM = "abilityScrolls=";

	@Test
	void correctedSignaturePassesEveryRollingWitherBladeHoldout() throws Exception {
		Map<LocalDate, Long> dayEnds = TapeFixture.retainedDayEnds();
		assertFalse(dayEnds.isEmpty(), "no retained tape days at " + TapeFixture.tapeDir());

		List<Backtest.Period> periods = rollingPeriods(dayEnds);
		Predicate<DecodedItem> witherBlade = DecodedItem::isScrollCapableBlade;
		Map<Backtest.Period, Backtest.Result> preFix = Backtest.holdout(
				CounterfactualKeying.withoutTerm(ABILITY_SCROLL_TERM), periods,
				TRAINING_WINDOW, witherBlade);
		Map<Backtest.Period, Backtest.Result> corrected = Backtest.holdout(
				Keying.PRODUCTION, periods, TRAINING_WINDOW, witherBlade);

		System.out.printf("%nWither-scroll rolling holdouts: %d retained UTC days, %d periods, "
				+ "48h shipped training window%n", dayEnds.size(), periods.size());
		System.out.printf("%-16s %7s %7s %9s %9s %8s %8s%n",
				"period", "held", "trained", "pre priced", "fix priced", "pre >=2x", "fix >=2x");

		for (Backtest.Period period : periods) {
			Backtest.Result old = preFix.get(period);
			Backtest.Result fixed = corrected.get(period);
			int oldDanger = dangerousUnscrolled(old.priced()).size();
			int fixedDanger = dangerousUnscrolled(fixed.priced()).size();

			System.out.printf("%-16s %,7d %,7d %,9d %,9d %,8d %,8d%n",
					period.label(), fixed.heldOut(), fixed.trained(), old.priced().size(),
					fixed.priced().size(), oldDanger, fixedDanger);
			assertTrue(fixedDanger == 0, period.label() + " still has " + fixedDanger
					+ " unscrolled Wither-blade quotes at 2x or more of realized price");
		}

		System.out.printf("%n%-8s %-9s %10s %10s %10s %10s %14s %14s %10s %10s%n",
				"holdout", "arm", "priced", "held", "coverage", "p90", ">=2x exposure",
				"upward excess", "sales/h", "rate delta");

		for (int hours : HOLDOUT_HOURS) {
			List<Backtest.Period> horizon = periods.stream()
					.filter(period -> period.label().endsWith("/" + hours + "h")).toList();
			Score old = score(horizon, preFix);
			Score fixed = score(horizon, corrected);
			printScore(hours, "pre-fix", old, old, fixed);
			printScore(hours, "corrected", fixed, old, fixed);
			System.out.printf("  variants held/priced pre/fix: %s / %s / %s%n",
					old.observedByVariant, old.pricedByVariant, fixed.pricedByVariant);
			System.out.printf("  median backing samples pre/fix: %s / %s%n",
					old.samplesByVariant, fixed.samplesByVariant);
			System.out.printf("  median resale sales/h pre/fix: %s / %s%n",
					old.rateByVariant, fixed.rateByVariant);

			assertTrue(fixed.dangerousExposure == 0L,
					hours + "h corrected arm retained dangerous unscrolled exposure");
		}

		Score allOld = score(periods, preFix);
		Score allFixed = score(periods, corrected);
		assertTrue(allOld.dangerousExposure > 0L,
				"the ability-scroll-unread counterfactual no longer reproduces the incident");
		assertTrue(allFixed.dangerousExposure < allOld.dangerousExposure,
				"the corrected signature removed no dangerous coin exposure");
	}

	@Test
	void measuresButDoesNotShipAHighTicketQuarantine() throws Exception {
		long newest = TapeFixture.newestTimestamp();
		Backtest.Result market = Backtest.holdout(Keying.PRODUCTION,
				newest - Duration.ofHours(24).toMillis(), TRAINING_WINDOW, item -> true);
		assertFalse(market.priced().isEmpty(), "the newest 24h whole-market holdout priced nothing");

		// Market-relative, not an invented coin cap. The other boundaries are already the warnings
		// AuctionValueStrategy shows: fewer than 12 comparables, >40% IQR/median disagreement, or a
		// widened/coarse match that does not describe the full configuration.
		double highTicket = percentile(market.priced().stream()
				.map(Backtest.Priced::estimate).toList(), 0.90d);
		Predicate<Backtest.Priced> risky = sale -> sale.estimate() >= highTicket
				&& (sale.samples() < 12 || sale.dispersion() > 0.4d
				|| sale.basis() != ValueEstimate.Basis.EXACT);

		List<Backtest.Priced> quarantined = market.priced().stream().filter(risky).toList();
		List<Backtest.Priced> kept = market.priced().stream().filter(risky.negate()).toList();
		MarketScore before = marketScore(market.priced(), market.observed());
		MarketScore after = marketScore(kept, market.observed());
		MarketScore removed = marketScore(quarantined, quarantined.stream()
				.map(sale -> new Backtest.Observed(sale.saleKey(), sale.item(), sale.timestamp(),
						sale.actual())).toList());

		System.out.printf("%nwhole-market 24h high-ticket quarantine experiment%n");
		System.out.printf("  high-ticket boundary: top quote decile, %,.0f coins on this holdout%n",
				highTicket);
		System.out.printf("  quarantined: %,d of %,d priced sales (%,.2f%%); %,d were >=2x upward%n",
				quarantined.size(), market.priced().size(),
				100.0d * quarantined.size() / market.priced().size(), removed.over2x);
		System.out.printf("  before: p90 %.3f, coverage %.2f%% by count / %.2f%% by coins, "
				+ "%,d >=2x, %,d exposure%n", before.p90, before.countCoverage * 100.0d,
				before.coinCoverage * 100.0d, before.over2x, before.dangerousExposure);
		System.out.printf("  after:  p90 %.3f, coverage %.2f%% by count / %.2f%% by coins, "
				+ "%,d >=2x, %,d exposure%n", after.p90, after.countCoverage * 100.0d,
				after.coinCoverage * 100.0d, after.over2x, after.dangerousExposure);
		System.out.printf("  delta:  p90 %+.3f, coverage %+.2fpp by count / %+.2fpp by coins, "
				+ "%+,d >=2x, %,d exposure%n", after.p90 - before.p90,
				100.0d * (after.countCoverage - before.countCoverage),
				100.0d * (after.coinCoverage - before.coinCoverage),
				after.over2x - before.over2x,
				after.dangerousExposure - before.dangerousExposure);

		assertFalse(quarantined.isEmpty(), "the quarantine experiment selected no estimates");
	}

	private static List<Backtest.Period> rollingPeriods(Map<LocalDate, Long> dayEnds) {
		List<Backtest.Period> periods = new ArrayList<>();
		LocalDate newestDay = dayEnds.keySet().stream().max(Comparator.naturalOrder()).orElseThrow();

		for (Map.Entry<LocalDate, Long> entry : dayEnds.entrySet()) {
			long nextMidnight = entry.getKey().plusDays(1).atStartOfDay(ZoneOffset.UTC)
					.toInstant().toEpochMilli();
			long endExclusive = entry.getKey().equals(newestDay)
					? entry.getValue() + 1L : nextMidnight;

			for (int hours : HOLDOUT_HOURS) {
				periods.add(new Backtest.Period(entry.getKey() + "/" + hours + "h",
						endExclusive - Duration.ofHours(hours).toMillis(), endExclusive));
			}
		}
		return List.copyOf(periods);
	}

	private static void printScore(int hours, String arm, Score score, Score old, Score fixed) {
		double delta = old.resaleRate == 0.0d ? Double.NaN
				: 100.0d * (fixed.resaleRate / old.resaleRate - 1.0d);
		System.out.printf("%4dh    %-9s %,10d %,10d %9.2f%% %10.3f %,14d %,14d %10.3f %9.1f%%%n",
				hours, arm, score.priced, score.observed, score.coverage() * 100.0d, score.p90,
				score.dangerousExposure, score.upwardExcess, score.resaleRate, delta);
	}

	private static Score score(Collection<Backtest.Period> periods,
			Map<Backtest.Period, Backtest.Result> results) {
		List<Backtest.Priced> priced = periods.stream()
				.flatMap(period -> results.get(period).priced().stream()).toList();
		List<Backtest.Observed> observed = periods.stream()
				.flatMap(period -> results.get(period).observed().stream()).toList();
		return new Score(priced, observed);
	}

	private static List<Backtest.Priced> dangerousUnscrolled(List<Backtest.Priced> priced) {
		return priced.stream().filter(sale -> sale.item().abilityScrolls().isEmpty())
				.filter(sale -> sale.overvaluedBy(2.0d)).toList();
	}

	private static String variant(DecodedItem item) {
		return item.abilityScrolls().isEmpty()
				? "none" : String.join("+", item.abilityScrolls());
	}

	private static double percentile(List<Double> values, double fraction) {
		if (values.isEmpty()) {
			return Double.NaN;
		}
		List<Double> sorted = values.stream().sorted().toList();
		return sorted.get(Math.clamp((int) Math.floor(fraction * sorted.size()), 0,
				sorted.size() - 1));
	}

	private static final class Score {
		private final int priced;
		private final int observed;
		private final double p90;
		private final long dangerousExposure;
		private final long upwardExcess;
		private final double resaleRate;
		private final Map<String, Long> observedByVariant;
		private final Map<String, Long> pricedByVariant;
		private final Map<String, Integer> samplesByVariant;
		private final Map<String, Double> rateByVariant;

		private Score(List<Backtest.Priced> pricedSales, List<Backtest.Observed> observedSales) {
			priced = pricedSales.size();
			observed = observedSales.size();
			p90 = percentile(pricedSales.stream().map(Backtest.Priced::logError).toList(), 0.90d);
			dangerousExposure = dangerousUnscrolled(pricedSales).stream()
					.mapToLong(sale -> Math.round(sale.estimate() - sale.actual())).sum();
			upwardExcess = pricedSales.stream()
					.mapToLong(sale -> Math.max(0L, Math.round(sale.estimate() - sale.actual()))).sum();
			resaleRate = percentile(pricedSales.stream()
					.map(Backtest.Priced::salesPerHour).toList(), 0.50d);
			observedByVariant = counts(observedSales.stream()
					.map(sale -> variant(sale.item())).toList());
			pricedByVariant = counts(pricedSales.stream()
					.map(sale -> variant(sale.item())).toList());
			samplesByVariant = mediansByVariant(pricedSales, sale -> (double) sale.samples()).entrySet()
					.stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,
							entry -> (int) Math.round(entry.getValue()), (a, b) -> a, TreeMap::new));
			rateByVariant = mediansByVariant(pricedSales, Backtest.Priced::salesPerHour);
		}

		private double coverage() {
			return observed == 0 ? 0.0d : priced / (double) observed;
		}
	}

	private static Map<String, Long> counts(List<String> variants) {
		Map<String, Long> counts = new TreeMap<>();
		variants.forEach(variant -> counts.merge(variant, 1L, Long::sum));
		return Map.copyOf(counts);
	}

	private static Map<String, Double> mediansByVariant(List<Backtest.Priced> sales,
			java.util.function.ToDoubleFunction<Backtest.Priced> value) {
		Map<String, List<Double>> values = new TreeMap<>();
		sales.forEach(sale -> values.computeIfAbsent(variant(sale.item()), key -> new ArrayList<>())
				.add(value.applyAsDouble(sale)));
		Map<String, Double> medians = new LinkedHashMap<>();
		values.forEach((variant, samples) -> medians.put(variant, percentile(samples, 0.50d)));
		return Map.copyOf(medians);
	}

	private static MarketScore marketScore(List<Backtest.Priced> priced,
			List<Backtest.Observed> observed) {
		double observedCoins = observed.stream().mapToDouble(Backtest.Observed::actual).sum();
		double pricedCoins = priced.stream().mapToDouble(Backtest.Priced::actual).sum();
		long exposure = priced.stream().filter(sale -> sale.overvaluedBy(2.0d))
				.mapToLong(sale -> Math.round(sale.estimate() - sale.actual())).sum();
		return new MarketScore(percentile(priced.stream().map(Backtest.Priced::logError).toList(),
				0.90d), observed.isEmpty() ? 0.0d : priced.size() / (double) observed.size(),
				observedCoins == 0.0d ? 0.0d : pricedCoins / observedCoins,
				(int) priced.stream().filter(sale -> sale.overvaluedBy(2.0d)).count(), exposure);
	}

	private record MarketScore(double p90, double countCoverage, double coinCoverage, int over2x,
			long dangerousExposure) {
	}
}
