package jeff.skyblockflipper.core.valuation;

import jeff.skyblockflipper.core.item.DecodedItem;
import jeff.skyblockflipper.core.pricing.Fees;
import jeff.skyblockflipper.core.valuation.backtest.TapeFixture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Settles the one auction-sniper knob two branches tuned in opposite directions.
 *
 * <p>This branch raised the global discount floor ({@code snipeMinDiscount} 0.15 -> 0.25) after
 * {@code SnipeProfitBacktestTest} found the 0.15-0.25 band the worst of the book. The
 * {@code auction-overlay} branch went the other way: it left the floor at 0.15 and <i>lowered</i> a
 * separate exact-signature gate to {@code exactMinDiscount} 0.12, but only for a well-backed quote -
 * EXACT basis, confidence &gt; 0.80, samples &gt;= 15, dispersion &lt; 0.20.
 *
 * <p>The two are not measuring the same thing. Both resell a flagged listing at the out-of-sample
 * median of its exact signature's holdout sales - not at the model's quote, which was the premise
 * that turned out wrong - so the disagreement is not about resale truth. It is about population: a
 * blanket 0.25 floor cannot see that a 0.18-discount flag is a trusted EXACT quote with 40 samples
 * and a tight book, while a coarse 0.18 quote off a mixed name-and-rarity pool is a coin toss. The
 * floor throws both away together. codex's gate keeps the first and still drops the second.
 *
 * <p>So the question this run answers, on one holdout and one realized-resale truth: <b>do trusted
 * EXACT flags in the disputed 0.12-0.25 discount range resell at a profit as reliably as the deeper
 * flags the 0.25 floor keeps?</b> If they do, the blanket floor is discarding money the trust gate
 * would keep, and codex's direction is right. If even trusted low-discount flags lose, the floor is.
 *
 * <p>Opt-in, needs a recorded tape:
 * {@code ./gradlew test -PtapeBacktest -PtapeDir=<tape> --tests '*SnipeGateReconcile*'}.
 */
@EnabledIfSystemProperty(named = "skyblockflipper.tapeBacktest", matches = "true")
class SnipeGateReconcileBacktestTest {
	private static final long HOLDOUT_HOURS = 24L;

	/** The shipped valuation window, so the model prices exactly as it would in play. */
	private static final Duration WINDOW = Duration.ofDays(2);

	/** Derpy off is the base case, the same as {@code SnipeProfitBacktestTest}. */
	private static final Fees FEES = new Fees(0, false);
	private static final long MIN_PROFIT_PER_FLIP = 50_000L;

	/** The shipped confidence floor every gate here shares. */
	private static final double MIN_CONFIDENCE = 0.6d;

	/** A realized resale median needs this many other holdout sales of the signature to be trusted. */
	private static final int MIN_RESALE_COMPS = 4;

	/** Below every floor and margin compared here, so the pool is built once and every gate subsets it. */
	private static final double POOL_DISCOUNT = 0.08d;

	// codex's exact-gate trust conditions, held in step with UnderpricedScan's guard.
	private static final double TRUST_CONFIDENCE = 0.80d;
	private static final int TRUST_SAMPLES = 15;
	private static final double TRUST_DISPERSION = 0.20d;

	/** The floor this branch shipped, and codex's exact margin, the two the run adjudicates. */
	private static final double RAISED_FLOOR = 0.25d;
	private static final double EXACT_MARGIN = 0.12d;
	private static final double OLD_FLOOR = 0.15d;

	@Test
	void doesTheTrustGateRescueLowDiscountFlagsTheRaisedFloorDiscards() throws Exception {
		long cutoff = TapeFixture.newestTimestamp() - HOLDOUT_HOURS * 3_600_000L;

		FairValueModel.Builder builder =
				FairValueModel.builder(Instant.ofEpochMilli(cutoff), WINDOW, Keying.PRODUCTION);
		List<Held> held = new ArrayList<>();
		Map<String, List<Double>> holdoutBySignature = new HashMap<>();

		// One pass, exactly as SnipeProfitBacktestTest: pre-cutoff sales train the shipped model,
		// held-out sales are both the listings under test and, pooled by signature, the resale comps.
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
		List<Cand> pool = new ArrayList<>();

		for (Held sale : held) {
			Optional<ValueEstimate> estimate = model.valueOf(sale.item());

			if (estimate.isEmpty() || estimate.get().median() <= 0.0d) {
				continue;
			}

			ValueEstimate value = estimate.get();
			double median = value.median();
			long price = Math.round(sale.unitPrice());
			double discount = 1.0d - sale.unitPrice() / median;

			if (discount < POOL_DISCOUNT || value.confidence() < MIN_CONFIDENCE) {
				continue;
			}

			long quotedNet = FEES.binRoundTripProfit(price, Math.round(median));

			if (quotedNet < MIN_PROFIT_PER_FLIP) {
				continue;
			}

			// The realized resale: the leave-one-out median of the signature's OTHER holdout sales,
			// the same truth both branches score against. Null when the signature is too thin to judge.
			Long realizedNet = resaleMedian(holdoutBySignature.get(sale.item().signature()), sale.unitPrice())
					.stream().mapToObj(m -> FEES.binRoundTripProfit(price, Math.round(m)))
					.findFirst().orElse(null);

			boolean trusted = value.basis() == ValueEstimate.Basis.EXACT
					&& value.confidence() > TRUST_CONFIDENCE
					&& value.samples() >= TRUST_SAMPLES
					&& value.dispersion() < TRUST_DISPERSION;

			pool.add(new Cand(price, discount, value.basis(), trusted, quotedNet, realizedNet));
		}

		assertFalse(pool.isEmpty(), "no listings cleared the shared gate on the tape at "
				+ TapeFixture.tapeDir() + " - is the holdout empty or the tape stale?");

		System.out.printf("%n=== snipe-gate reconciliation: newest %dh held out, %d-day train window ===%n",
				HOLDOUT_HOURS, WINDOW.toDays());
		System.out.printf("pool: %,d flags past %.0f%% discount, conf >= %.2f, quoted profit >= %s%n",
				pool.size(), POOL_DISCOUNT * 100.0d, MIN_CONFIDENCE, coins(MIN_PROFIT_PER_FLIP));
		System.out.printf("resale truth: leave-one-out median of a signature's other held-out sales "
				+ "(>= %d comps). Same truth both branches use.%n", MIN_RESALE_COMPS);

		System.out.printf("%n--- the four gates, each admitted set resold at realized M ---%n");
		System.out.printf("%-34s %8s %10s %10s %13s %11s %10s%n",
				"gate", "flags", "measured", "loss-rate", "net/flag", "survives", "Σ realized");
		gate("this branch: floor 0.25 (all bases)", pool, c -> c.discount() >= RAISED_FLOOR);
		gate("old: floor 0.15 (all bases)", pool, c -> c.discount() >= OLD_FLOOR);
		gate("codex: trusted EXACT, margin 0.12", pool, c -> c.trusted() && c.discount() >= EXACT_MARGIN);
		gate("codex + coarse 0.15 (their sweep base)", pool,
				c -> c.discount() >= OLD_FLOOR || (c.trusted() && c.discount() >= EXACT_MARGIN));

		System.out.printf("%n--- realized loss-rate by discount band x trust (the crux) ---%n");
		System.out.printf("%-13s %-14s %8s %10s %10s %13s %11s%n",
				"band", "population", "flags", "measured", "loss-rate", "net/flag", "survives");
		double[] edges = {0.08d, 0.12d, 0.15d, 0.25d, 0.40d, 0.60d, 1.01d};
		for (int i = 0; i < edges.length - 1; i++) {
			bandRow(pool, edges[i], edges[i + 1], true);
			bandRow(pool, edges[i], edges[i + 1], false);
		}

		System.out.printf("%n--- the disputed set: trusted EXACT flags in [0.12, 0.25) ---%n");
		System.out.printf("These are exactly the flags codex's gate keeps and this branch's 0.25 floor drops.%n");
		gate("  trusted EXACT, 0.12 <= discount < 0.25", pool,
				c -> c.trusted() && c.discount() >= EXACT_MARGIN && c.discount() < RAISED_FLOOR);
		gate("  untrusted, 0.12 <= discount < 0.25 (floor is right to drop these)", pool,
				c -> !c.trusted() && c.discount() >= EXACT_MARGIN && c.discount() < RAISED_FLOOR);

		System.out.printf("%nRead: if the disputed trusted set loses far less often than the untrusted one and "
				+ "resells at a profit, a blanket 0.25 floor is discarding money a trust gate keeps - "
				+ "codex's direction. If it loses too, the floor stands.%n");
	}

	/** One gate's admitted set, scored on realized resale. */
	private static void gate(String label, List<Cand> pool, java.util.function.Predicate<Cand> admits) {
		List<Cand> flagged = pool.stream().filter(admits).toList();
		List<Long> realized = flagged.stream()
				.map(Cand::realizedNet).filter(Objects::nonNull).sorted().toList();

		long losses = realized.stream().filter(n -> n < 0L).count();
		long realizedSum = realized.stream().mapToLong(Long::longValue).sum();
		long quotedOnMeasured = flagged.stream().filter(c -> c.realizedNet() != null)
				.mapToLong(Cand::quotedNet).sum();
		long netPerFlag = realized.isEmpty() ? 0L : realizedSum / realized.size();

		System.out.printf("%-34s %8d %10d %9.1f%% %13s %10s %10s%n",
				label, flagged.size(), realized.size(), pct(losses, realized.size()),
				coins(netPerFlag),
				quotedOnMeasured == 0L ? "-" : String.format("%.0f%%", 100.0d * realizedSum / quotedOnMeasured),
				coins(realizedSum));
	}

	/** One band, split by whether the quote clears codex's trust gate. */
	private static void bandRow(List<Cand> pool, double lo, double hi, boolean trustedOnly) {
		List<Cand> band = pool.stream()
				.filter(c -> c.discount() >= lo && c.discount() < hi && c.trusted() == trustedOnly)
				.toList();
		List<Long> realized = band.stream()
				.map(Cand::realizedNet).filter(Objects::nonNull).sorted().toList();

		long losses = realized.stream().filter(n -> n < 0L).count();
		long realizedSum = realized.stream().mapToLong(Long::longValue).sum();
		long quotedOnMeasured = band.stream().filter(c -> c.realizedNet() != null)
				.mapToLong(Cand::quotedNet).sum();
		long netPerFlag = realized.isEmpty() ? 0L : realizedSum / realized.size();

		System.out.printf("%.2f-%-8.2f %-14s %8d %10d %9.1f%% %13s %10s%n",
				lo, hi, trustedOnly ? "trusted EXACT" : "everything else",
				band.size(), realized.size(), pct(losses, realized.size()), coins(netPerFlag),
				quotedOnMeasured == 0L ? "-" : String.format("%.0f%%", 100.0d * realizedSum / quotedOnMeasured));
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

	private static double pct(long part, long whole) {
		return whole == 0L ? 0.0d : 100.0d * part / whole;
	}

	private static String coins(long value) {
		return String.format("%,.2fM", value / 1_000_000.0d);
	}

	private record Held(DecodedItem item, double unitPrice) {
	}

	/** A pooled flag: its discount, whether the quote is trusted, and both resale outcomes. */
	private record Cand(long price, double discount, ValueEstimate.Basis basis, boolean trusted,
			long quotedNet, Long realizedNet) {
	}
}
