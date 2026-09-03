package jeff.skyblockflipper.core.valuation;

import jeff.skyblockflipper.core.item.DecodedItem;
import jeff.skyblockflipper.core.pricing.Fees;
import jeff.skyblockflipper.core.valuation.backtest.TapeFixture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Not "is the quote accurate" but "would sniping it have made money". Opt-in, needs a recorded tape:
 * {@code ./gradlew test -PtapeBacktest --tests '*SnipeProfitBacktestTest'}.
 *
 * <p>Every other valuation backtest scores the model's quote against what a sale fetched - coverage,
 * log error, fake snipes. None of them close the loop the auction sniper actually runs: flag a live
 * listing priced under the model, buy it, relist, and see what comes back after fees. This one does,
 * against the shipped gate stack ({@code snipeMinDiscount 0.15}, {@code minConfidence 0.6},
 * {@code minProfitPerFlip 50k}) and the shipped valuation window (2 days).
 *
 * <p>The simulation. Hold out the newest {@code HOLDOUT_HOURS}; train the real {@link FairValueModel}
 * under {@link Keying#PRODUCTION} on the two days before the cutoff. Each held-out BIN sale is a
 * listing that existed and cleared at its price - proxy enough for a listing the sniper could have
 * seen. Run the shipped gate on it with its own sale price as the ask. When it flags, we "buy" at
 * that price and resell not at the model's quote but at <b>M</b>, the leave-one-out median of the
 * <i>other</i> held-out sales of the same signature - a price real sellers actually got during the
 * resale window, not one the model hoped for. Realized profit is {@code binRoundTripProfit(buy, M)}.
 *
 * <p>What this measures and what it cannot. It measures adverse selection and staleness <i>within a
 * signature</i>: a snipe flagged because the model's two-day-old median sits above where the item now
 * trades resells at M and loses, and that shows up here as it would in the purse. It is blind to a
 * signature <i>miss</i> - if a term the model does not read makes the item genuinely cheap, M pools
 * the same blind sales the quote did and both agree on a wrong number. Those are the
 * {@code UnreadAttributeProbeTest} / per-attribute-holdout department; the gemstone-slot bug lived
 * there. So a clean run here is necessary, not sufficient, and the population fake-snipe rate is
 * printed alongside as the standing proxy for the miss risk.
 */
@EnabledIfSystemProperty(named = "skyblockflipper.tapeBacktest", matches = "true")
class SnipeProfitBacktestTest {
	private static final long HOLDOUT_HOURS = 24L;

	/** What the shipped model prices from: {@code valuationWindowDays = 2}. */
	private static final Duration WINDOW = Duration.ofDays(2);

	/**
	 * The shipped coarse floor. A raise to 0.25 was reverted: {@code SnipeGateReconcileBacktestTest}
	 * showed the losing 0.15-0.25 band splits by trust, not depth, so a blanket floor is the wrong lever.
	 */
	private static final double SNIPE_MIN_DISCOUNT = 0.15d;
	private static final double MIN_CONFIDENCE = 0.6d;
	private static final long MIN_PROFIT_PER_FLIP = 50_000L;

	/** No AH fee multiplier: Derpy off is the base case; a Derpy-on count is reported beside it. */
	private static final Fees FEES = new Fees(0, false);
	private static final Fees FEES_DERPY = new Fees(0, true);

	/** A resale median needs this many other holdout sales of the signature to be trusted. */
	private static final int MIN_RESALE_COMPS = 4;

	/** The lowest discount the sweep visits, so the candidate pool is built once and subset after. */
	private static final double POOL_DISCOUNT = 0.10d;

	/** Per-flip capital ceilings to report: uncapped, then 10M and 250M bankrolls at the 0.25 share. */
	private static final long UNCAPPED = Long.MAX_VALUE;
	private static final long CAP_10M = 2_500_000L;
	private static final long CAP_250M = 62_500_000L;

	@Test
	void sniperProfitAndLossOnHeldOutListings() throws Exception {
		long cutoff = TapeFixture.newestTimestamp() - HOLDOUT_HOURS * 3_600_000L;

		FairValueModel.Builder builder =
				FairValueModel.builder(Instant.ofEpochMilli(cutoff), WINDOW, Keying.PRODUCTION);
		List<Held> held = new ArrayList<>();
		Map<String, List<Double>> holdoutBySignature = new HashMap<>();

		// One pass. Pre-cutoff sales train the model; held-out sales are both the listings we test the
		// sniper on and, pooled by signature, the resale comps.
		TapeFixture.forEachSale((item, extra, timestamp, unitPrice) -> {
			if (unitPrice <= 0.0d) {
				return;
			}

			if (timestamp >= cutoff) {
				held.add(new Held(item, unitPrice));
				holdoutBySignature.computeIfAbsent(item.signature(), k -> new ArrayList<>()).add(unitPrice);
			} else {
				builder.add(item, unitPrice, timestamp);
			}
		});

		FairValueModel model = builder.build();

		// Population accuracy over every quotable held-out sale, and the candidate pool the sniper acts
		// on, in one walk of the holdout.
		List<Double> logErrors = new ArrayList<>();
		int quoted = 0;
		int overvalued2x = 0;
		long seenCoins = 0L;
		long quotedCoins = 0L;
		List<Cand> pool = new ArrayList<>();

		for (Held sale : held) {
			seenCoins += Math.round(sale.unitPrice());
			Optional<ValueEstimate> estimate = model.valueOf(sale.item());

			if (estimate.isEmpty() || estimate.get().median() <= 0.0d) {
				continue;
			}

			ValueEstimate value = estimate.get();
			double v = value.median();
			long price = Math.round(sale.unitPrice());

			quoted++;
			quotedCoins += price;
			logErrors.add(Math.abs(Math.log(v / sale.unitPrice())));

			if (v >= 2.0d * sale.unitPrice()) {
				overvalued2x++;
			}

			// The threshold-independent half of the shipped gate. The discount gate itself is applied
			// per row in the sweep, so the pool is everything with at least POOL_DISCOUNT of headroom.
			double discount = 1.0d - sale.unitPrice() / v;

			if (discount < POOL_DISCOUNT || value.confidence() < MIN_CONFIDENCE) {
				continue;
			}

			long quotedNet = FEES.binRoundTripProfit(price, Math.round(v));

			if (quotedNet < MIN_PROFIT_PER_FLIP) {
				continue;
			}

			Long realizedNet = resaleMedian(holdoutBySignature.get(sale.item().signature()), sale.unitPrice())
					.stream().mapToObj(m -> FEES.binRoundTripProfit(price, Math.round(m)))
					.findFirst().orElse(null);
			boolean derpyStillProfitable = FEES_DERPY.binRoundTripProfit(price, Math.round(v)) >= MIN_PROFIT_PER_FLIP;

			pool.add(new Cand(sale.item().skyblockId(), price, v, discount, value.confidence(),
					value.hoursToSell(), value.basis(), quotedNet, realizedNet, derpyStillProfitable));
		}

		System.out.printf("%n=== snipe P&L backtest: newest %dh held out, trained on the %d-day window ===%n",
				HOLDOUT_HOURS, WINDOW.toDays());
		System.out.printf("held-out BIN sales: %,d seen, %,d quotable (%.1f%% by count, %.1f%% by coins)%n",
				held.size(), quoted, pct(quoted, held.size()), pct(quotedCoins, seenCoins));
		System.out.printf("population accuracy: median |log err| %.3f, sales the model overvalues 2x+: "
						+ "%,d of %,d quotable (%.2f%%) - the standing fake-snipe rate%n",
				median(logErrors), overvalued2x, quoted, pct(overvalued2x, quoted));

		// The base case: the shipped 0.15 discount, uncapped, so the number is about the model and not
		// the bankroll. The band table below is what showed a higher blanket floor to be the wrong lever.
		report("SHIPPED GATE (discount 0.15, uncapped)", pool, SNIPE_MIN_DISCOUNT, UNCAPPED);

		System.out.printf("%n--- the same gate under a per-flip capital cap ---%n");
		report("10M bankroll  (cap 2.5M)", pool, SNIPE_MIN_DISCOUNT, CAP_10M);
		report("250M bankroll (cap 62.5M)", pool, SNIPE_MIN_DISCOUNT, CAP_250M);

		System.out.printf("%n--- discount-threshold sweep (uncapped) ---%n");
		System.out.printf("%-9s %8s %10s %12s %14s %14s %9s %8s%n",
				"discount", "flags", "resale-n", "loss-rate", "Σ quoted", "Σ realized", "realized/", "median");
		System.out.printf("%-9s %8s %10s %12s %14s %14s %9s %8s%n",
				"floor", "", "", "", "profit", "profit", "quoted", "per flip");
		for (double d : new double[] {0.10d, 0.15d, 0.20d, 0.30d, 0.40d, 0.50d}) {
			sweepRow(pool, d);
		}

		System.out.printf("%n--- realized outcome by discount band (uncapped, the gate's other floors applied) ---%n");
		System.out.printf("%-12s %8s %10s %10s %10s %13s %10s%n",
				"band", "flags", "resale-n", "loss-rate", "survives", "median/flip", "loss>5M");
		double[] edges = {0.15d, 0.25d, 0.40d, 0.60d, 0.80d, 1.01d};
		for (int i = 0; i < edges.length - 1; i++) {
			bandRow(pool, edges[i], edges[i + 1]);
		}
		System.out.printf("A within-signature snipe resells near M, so a deep band that still loses is one "
				+ "the resale median cannot vouch for - where a signature MISS would hide, counted here as a "
				+ "win it is not.%n");

		System.out.printf("%n\"realized\" resells at the concurrent median of the signature's other held-out "
				+ "sales, not at the model's quote. \"loss-rate\" is flags whose realized round trip is "
				+ "negative. Blind to signature misses - see the class comment.%n");

		assertTrue(!pool.isEmpty(), "no listings cleared the shipped snipe gate on the tape at "
				+ TapeFixture.tapeDir() + " - is the holdout empty or the tape stale?");
	}

	/** One line of the by-cap / base report, with the distribution and concentration a verdict needs. */
	private static void report(String label, List<Cand> pool, double minDiscount, long cap) {
		List<Cand> flagged = pool.stream()
				.filter(c -> c.discount() >= minDiscount && c.price() <= cap)
				.toList();

		List<Cand> measured = flagged.stream().filter(c -> c.realizedNet() != null).toList();
		List<Long> realized = measured.stream().map(Cand::realizedNet).sorted().toList();

		long quotedSum = flagged.stream().mapToLong(Cand::quotedNet).sum();
		long realizedSum = realized.stream().mapToLong(Long::longValue).sum();
		long realizedQuotedSum = measured.stream().mapToLong(Cand::quotedNet).sum();
		long losses = realized.stream().filter(n -> n < 0L).count();
		long derpyOk = flagged.stream().filter(Cand::derpyStillProfitable).count();

		long illiquid = flagged.size() - measured.size();
		long illiquidQuoted = flagged.stream().filter(c -> c.realizedNet() == null)
				.mapToLong(Cand::quotedNet).sum();

		System.out.printf("%n%s%n", label);
		System.out.printf("  flags: %,d (%,d/day)   quoted profit: %s   avg quoted/flip: %s%n",
				flagged.size(), flagged.size() * 24L / HOLDOUT_HOURS, coins(quotedSum),
				coins(flagged.isEmpty() ? 0L : quotedSum / flagged.size()));
		System.out.printf("  resale-verifiable: %,d   illiquid (no comps): %,d worth %s of quoted hope%n",
				measured.size(), illiquid, coins(illiquidQuoted));

		if (realized.isEmpty()) {
			System.out.printf("  no resale-verifiable flags%n");
			return;
		}

		System.out.printf("  REALIZED profit on the verifiable set: %s vs %s quoted  ->  %.0f%% of the quote "
						+ "survives%n",
				coins(realizedSum), coins(realizedQuotedSum),
				realizedQuotedSum == 0L ? 0.0d : 100.0d * realizedSum / realizedQuotedSum);
		System.out.printf("  loss-rate: %,d of %,d flags resell at a loss (%.1f%%)%n",
				losses, realized.size(), pct(losses, realized.size()));
		System.out.printf("  per-flip realized: min %s / p10 %s / median %s / p90 %s / max %s%n",
				coins(realized.getFirst()), coins(percentile(realized, 0.10d)),
				coins(percentile(realized, 0.50d)), coins(percentile(realized, 0.90d)),
				coins(realized.getLast()));
		System.out.printf("  concentration: the best 5 flags are %.0f%% of realized profit%n",
				concentrationTop5(realized, realizedSum));
		System.out.printf("  under Derpy (AH fees x4): %,d of %,d flags still clear min profit%n",
				derpyOk, flagged.size());
	}

	private static void sweepRow(List<Cand> pool, double minDiscount) {
		List<Cand> flagged = pool.stream().filter(c -> c.discount() >= minDiscount).toList();
		List<Long> realized = flagged.stream()
				.map(Cand::realizedNet).filter(java.util.Objects::nonNull).sorted().toList();

		long quotedSum = flagged.stream().mapToLong(Cand::quotedNet).sum();
		long realizedSum = realized.stream().mapToLong(Long::longValue).sum();
		long realizedQuoted = flagged.stream().filter(c -> c.realizedNet() != null)
				.mapToLong(Cand::quotedNet).sum();
		long losses = realized.stream().filter(n -> n < 0L).count();

		System.out.printf("%-8.2f  %,8d %,10d %11.1f%% %14s %14s %8.0f%% %8s%n",
				minDiscount, flagged.size(), realized.size(), pct(losses, realized.size()),
				coins(quotedSum), coins(realizedSum),
				realizedQuoted == 0L ? 0.0d : 100.0d * realizedSum / realizedQuoted,
				coins(realized.isEmpty() ? 0L : percentile(realized, 0.50d)));
	}

	/** One discount band's realized outcome: where in the discount range the losses actually sit. */
	private static void bandRow(List<Cand> pool, double lo, double hi) {
		List<Cand> band = pool.stream().filter(c -> c.discount() >= lo && c.discount() < hi).toList();
		List<Long> realized = band.stream()
				.map(Cand::realizedNet).filter(java.util.Objects::nonNull).sorted().toList();

		long realizedSum = realized.stream().mapToLong(Long::longValue).sum();
		long quotedR = band.stream().filter(c -> c.realizedNet() != null).mapToLong(Cand::quotedNet).sum();
		long losses = realized.stream().filter(n -> n < 0L).count();
		long bigLosses = realized.stream().filter(n -> n < -5_000_000L).count();

		System.out.printf("%.2f-%-6.2f  %,8d %,10d %9.1f%% %9.0f%% %13s %,10d%n",
				lo, hi, band.size(), realized.size(), pct(losses, realized.size()),
				quotedR == 0L ? 0.0d : 100.0d * realizedSum / quotedR,
				coins(realized.isEmpty() ? 0L : percentile(realized, 0.50d)), bigLosses);
	}

	/** Median of the signature's held-out sales with one instance of the flagged price removed. */
	private static OptionalDouble resaleMedian(List<Double> comps, double own) {
		if (comps == null) {
			return OptionalDouble.empty();
		}

		List<Double> others = new ArrayList<>(comps);
		others.remove(own);
		return TapeFixture.median(others, MIN_RESALE_COMPS);
	}

	private static double concentrationTop5(List<Long> ascending, long total) {
		if (total <= 0L || ascending.isEmpty()) {
			return 0.0d;
		}

		long top5 = ascending.stream().sorted(Comparator.reverseOrder()).limit(5).mapToLong(Long::longValue).sum();
		return 100.0d * top5 / total;
	}

	private static long percentile(List<Long> sorted, double fraction) {
		int index = (int) Math.round(fraction * (sorted.size() - 1));
		return sorted.get(Math.clamp(index, 0, sorted.size() - 1));
	}

	private static double median(List<Double> values) {
		if (values.isEmpty()) {
			return Double.NaN;
		}

		List<Double> sorted = values.stream().sorted().toList();
		return sorted.get(sorted.size() / 2);
	}

	private static double pct(long part, long whole) {
		return whole == 0L ? 0.0d : 100.0d * part / whole;
	}

	private static String coins(long value) {
		double m = value / 1_000_000.0d;
		return String.format("%,.2fM", m);
	}

	/** A held-out sale: the decoded item and its per-unit price. */
	private record Held(DecodedItem item, double unitPrice) {
	}

	/**
	 * A listing that cleared the threshold-independent gates, carrying everything the sweep and the
	 * report need without re-pricing.
	 */
	private record Cand(String id, long price, double value, double discount, double conf, double hours,
			ValueEstimate.Basis basis, long quotedNet, Long realizedNet, boolean derpyStillProfitable) {
	}
}
