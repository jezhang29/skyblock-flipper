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

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 0a of the timed-auction bidding investigation (docs/auction-bidding-plan.md): does a bid
 * auction end below the item's BIN value, often enough and in liquid enough items to be worth a flip?
 * Opt-in: {@code ./gradlew test -PtapeBacktest -PtapeDir=<tape> --tests '*AuctionBidProfitBacktestTest'}.
 *
 * <p>The shipped model and the production sniper both throw timed sales away - "what nobody else was
 * awake for". This test is the one place that looks at them, to settle whether that is leaving money
 * on the table. It changes no shipping code; it only reads the tape.
 *
 * <p>Method, mirrored from {@link SnipeProfitBacktestTest}, including its one rule that matters most:
 * <b>resell at a price real BIN sellers got, never at the model's own quote.</b> Hold out the newest
 * {@code HOLDOUT_HOURS}; train the real {@link FairValueModel} under {@link Keying#PRODUCTION} on the
 * BIN sales in the 2-day window before the cutoff. Then take each held-out <b>non-BIN</b> sale - a
 * timed auction that really ended at its winning bid - and:
 * <ul>
 *   <li>the <b>gate</b> sees only the model quote M: discount {@code 1 - bid/M}, and the profit the
 *       sniper would advertise, {@code binRoundTripProfit(bid, M)}. This is all the strategy could know
 *       when it decided to bid.</li>
 *   <li>the <b>truth</b> resells at R, the median of the <i>held-out BIN sales</i> of the same
 *       signature - out of sample, a price the market actually paid in the resale window. Realized is
 *       {@code binRoundTripProfit(bid, R)}. No holdout BIN comps (>= {@code MIN_RESALE_COMPS}) means
 *       illiquid and unverifiable, counted separately - never as a win.</li>
 * </ul>
 *
 * <p>What it cannot measure: reachability (the ended data has no bid history, so it cannot say whether
 * a cheap end was uncontested or the winner beat a war - Phase 0b), and signature misses (R pools the
 * same blind sales the quote did, so a term the model does not read fools both - the deep-discount
 * bands are where that hides, which is why they are reported apart).
 */
@EnabledIfSystemProperty(named = "skyblockflipper.tapeBacktest", matches = "true")
class AuctionBidProfitBacktestTest {
	private static final long HOLDOUT_HOURS = 48L;
	private static final Duration WINDOW = Duration.ofDays(2);

	/** The shipped sniper gate, applied to the winning-bid price to count "snipe-worthy" timed ends. */
	private static final double SNIPE_MIN_DISCOUNT = 0.15d;
	private static final double MIN_CONFIDENCE = 0.6d;
	private static final long MIN_PROFIT_PER_FLIP = 50_000L;

	/** A resale truth needs this many out-of-sample BIN sales of the signature to be trusted. */
	private static final int MIN_RESALE_COMPS = 4;

	private static final Fees FEES = new Fees(0, false);
	private static final Fees FEES_DERPY = new Fees(0, true);

	private static final double[] LIQ_EDGES = {0.0d, 0.05d, 0.2d, 1.0d, Double.MAX_VALUE};
	private static final String[] LIQ_LABELS = {"<0.05/h parked", "0.05-0.2/h slow", "0.2-1/h workable", ">1/h fast"};

	/** {@code realizedNet} is null when the signature has no out-of-sample BIN resale comps. */
	private record Bid(String id, long bidPrice, double median, double discount, double salesPerHour,
			double confidence, ValueEstimate.Basis basis, long quotedNet, Long realizedNet, boolean snipeWorthy) {
	}

	private record Held(DecodedItem item, double unitPrice) {
	}

	@Test
	void timedAuctionSurplusOnHeldOutSales() throws Exception {
		long newest = TapeFixture.newestTimestamp();
		long cutoff = newest - HOLDOUT_HOURS * 3_600_000L;
		long trainStart = cutoff - WINDOW.toMillis();

		FairValueModel.Builder builder =
				FairValueModel.builder(Instant.ofEpochMilli(cutoff), WINDOW, Keying.PRODUCTION);
		List<Held> held = new ArrayList<>();
		Map<String, List<Double>> resaleBySig = new HashMap<>(); // out-of-sample BIN holdout, per signature
		long[] totals = new long[2]; // [0] all sales, [1] non-BIN, for market-size context

		TapeFixture.tape().forEachRecent(TapeFixture.ALL_DAYS, sale -> {
			if (sale.price() <= 0L) {
				return;
			}
			totals[0]++;
			if (!sale.bin()) {
				totals[1]++;
			}

			if (sale.bin()) {
				if (sale.timestamp() >= trainStart && sale.timestamp() < cutoff) {
					decode(sale.itemBytes()).ifPresent(item -> builder.add(item,
							(double) sale.price() / Math.max(1, item.count()), sale.timestamp()));
				} else if (sale.timestamp() >= cutoff) {
					// Out-of-sample resale truth: BIN sales in the same window the timed flips resell into.
					decode(sale.itemBytes()).ifPresent(item -> resaleBySig
							.computeIfAbsent(item.signature(), k -> new ArrayList<>())
							.add((double) sale.price() / Math.max(1, item.count())));
				}
			} else if (sale.timestamp() >= cutoff) {
				decode(sale.itemBytes()).ifPresent(item ->
						held.add(new Held(item, (double) sale.price() / Math.max(1, item.count()))));
			}
		});

		FairValueModel model = builder.build();

		int priceable = 0;
		int unpriceable = 0;
		List<Bid> bids = new ArrayList<>();

		for (Held h : held) {
			Optional<ValueEstimate> estimate = model.valueOf(h.item());
			if (estimate.isEmpty() || estimate.get().median() <= 0.0d
					|| estimate.get().samples() < ValueEstimate.MIN_SAMPLES) {
				unpriceable++;
				continue;
			}
			priceable++;

			ValueEstimate v = estimate.get();
			long bid = Math.round(h.unitPrice());
			double median = v.median();
			double discount = 1.0d - h.unitPrice() / median;
			long quotedNet = FEES.binRoundTripProfit(bid, Math.round(median));

			OptionalDouble resale = TapeFixture.median(resaleBySig.get(h.item().signature()), MIN_RESALE_COMPS);
			Long realizedNet = resale.isPresent()
					? FEES.binRoundTripProfit(bid, Math.round(resale.getAsDouble())) : null;

			boolean snipeWorthy = discount >= SNIPE_MIN_DISCOUNT
					&& v.confidence() >= MIN_CONFIDENCE
					&& quotedNet >= MIN_PROFIT_PER_FLIP;

			bids.add(new Bid(h.item().skyblockId(), bid, median, discount, v.salesPerHour(),
					v.confidence(), v.basis(), quotedNet, realizedNet, snipeWorthy));
		}

		long verifiable = bids.stream().filter(b -> b.realizedNet() != null).count();

		System.out.printf("%n=== timed-auction (bid) surplus backtest ===%n");
		System.out.printf("tape market size (all retained days): %,d ended sales, %,d non-BIN (%.2f%%)%n",
				totals[0], totals[1], pct(totals[1], totals[0]));
		System.out.printf("holdout: newest %dh, model trained on the %d-day BIN window before it%n",
				HOLDOUT_HOURS, WINDOW.toDays());
		System.out.printf("held-out non-BIN: %,d decoded, %,d priceable (BIN comp, >=%d samples), "
						+ "%,d unpriceable (no liquid BIN market), %,d resale-verifiable (>=%d holdout BIN comps)%n",
				held.size(), priceable, ValueEstimate.MIN_SAMPLES, unpriceable, verifiable, MIN_RESALE_COMPS);

		if (bids.isEmpty()) {
			assertTrue(!held.isEmpty(), "no non-BIN sales decoded on the tape at " + TapeFixture.tapeDir());
			return;
		}

		List<Double> discounts = bids.stream().map(Bid::discount).sorted().toList();
		long belowValue = bids.stream().filter(b -> b.discount() > 0.0d).count();
		long deepEnough = bids.stream().filter(b -> b.discount() >= SNIPE_MIN_DISCOUNT).count();
		System.out.printf("%n--- surplus vs the model quote: how far under BIN value the winning bid landed ---%n");
		System.out.printf("bid below BIN value: %,d of %,d (%.1f%%)   >=15%% under: %,d (%.1f%%)%n",
				belowValue, bids.size(), pct(belowValue, bids.size()), deepEnough, pct(deepEnough, bids.size()));
		System.out.printf("discount: p10 %+.0f%% / p25 %+.0f%% / median %+.0f%% / p75 %+.0f%% / p90 %+.0f%%%n",
				100 * pctile(discounts, 0.10), 100 * pctile(discounts, 0.25), 100 * pctile(discounts, 0.50),
				100 * pctile(discounts, 0.75), 100 * pctile(discounts, 0.90));

		reportRealized("ALL priceable timed sales", bids);
		reportRealized("shipped sniper gate (>=15% + conf>=0.6 + quoted>=50k)",
				bids.stream().filter(Bid::snipeWorthy).toList());
		reportRealized("TRUSTWORTHY: EXACT basis, conf>0.80, discount 0.15-0.40 (strips misses + noise)",
				bids.stream().filter(b -> b.discount() >= 0.15d && b.discount() < 0.40d
						&& b.basis() == ValueEstimate.Basis.EXACT && b.confidence() > 0.80d).toList());

		long snipe = bids.stream().filter(Bid::snipeWorthy).count();
		long suspect = bids.stream().filter(Bid::snipeWorthy).filter(b -> b.discount() >= 0.60d).count();
		System.out.printf("%nof the %,d snipe-worthy, %,d (%.0f%%) are suspect-deep (>=60%% off) - the "
				+ "miss-prone tail the suspect guard would demote%n", snipe, suspect, pct(suspect, Math.max(1, snipe)));

		System.out.printf("%n--- snipe-worthy by valuation basis (misses hide in COARSE/BANDED) ---%n");
		for (ValueEstimate.Basis basis : ValueEstimate.Basis.values()) {
			long n = bids.stream().filter(Bid::snipeWorthy).filter(x -> x.basis() == basis).count();
			System.out.printf("  %-8s n %,6d%n", basis, n);
		}

		System.out.printf("%n--- snipe-worthy by item value (the click-budget cut) : realized on verifiable ---%n");
		double[] vEdges = {0, 100_000, 1_000_000, 10_000_000, Double.MAX_VALUE};
		String[] vLabels = {"<100k dust", "100k-1M", "1M-10M", ">10M"};
		for (int i = 0; i < vLabels.length; i++) {
			final double lo = vEdges[i];
			final double hi = vEdges[i + 1];
			List<Bid> b = bids.stream().filter(Bid::snipeWorthy)
					.filter(x -> x.median() >= lo && x.median() < hi).toList();
			List<Long> r = b.stream().map(Bid::realizedNet).filter(Objects::nonNull).sorted().toList();
			System.out.printf("  %-11s n %,6d   verifiable %,4d   median realized/flip %s%n",
					vLabels[i], b.size(), r.size(), coins(r.isEmpty() ? 0L : pctileL(r, 0.50)));
		}

		System.out.printf("%n--- by resale liquidity (the reachability-vs-adverse-selection cut) ---%n");
		System.out.printf("%-18s %8s %11s %14s %12s%n", "bucket", "n", "med disc", "med realized", "snipe-wthy");
		for (int i = 0; i < LIQ_LABELS.length; i++) {
			final double lo = LIQ_EDGES[i];
			final double hi = LIQ_EDGES[i + 1];
			List<Bid> b = bids.stream().filter(x -> x.salesPerHour() >= lo && x.salesPerHour() < hi).toList();
			if (b.isEmpty()) {
				System.out.printf("%-18s %,8d%n", LIQ_LABELS[i], 0);
				continue;
			}
			List<Double> d = b.stream().map(Bid::discount).sorted().toList();
			List<Long> r = b.stream().map(Bid::realizedNet).filter(Objects::nonNull).sorted().toList();
			long sw = b.stream().filter(Bid::snipeWorthy).count();
			System.out.printf("%-18s %,8d %+10.0f%% %14s %,12d%n",
					LIQ_LABELS[i], b.size(), 100 * pctile(d, 0.50),
					coins(r.isEmpty() ? 0L : pctileL(r, 0.50)), sw);
		}

		System.out.printf("%nRealized resells at the out-of-sample holdout BIN median, not the model quote. "
				+ "Blind to reachability and signature misses - see the class comment and "
				+ "docs/auction-bidding-plan.md.%n");

		assertTrue(priceable > 0, "no priceable timed sales - tape too thin or holdout empty");
	}

	/** Quoted vs out-of-sample realized for one subset, the SnipeProfit way. */
	private static void reportRealized(String label, List<Bid> subset) {
		long derpyOk = subset.stream()
				.filter(b -> FEES_DERPY.binRoundTripProfit(b.bidPrice(), Math.round(b.median())) >= MIN_PROFIT_PER_FLIP)
				.count();
		List<Bid> ver = subset.stream().filter(b -> b.realizedNet() != null).toList();
		List<Long> realized = ver.stream().map(Bid::realizedNet).sorted().toList();
		long quotedSum = subset.stream().mapToLong(Bid::quotedNet).sum();
		long realizedSum = realized.stream().mapToLong(Long::longValue).sum();
		long realizedQuoted = ver.stream().mapToLong(Bid::quotedNet).sum();
		long losses = realized.stream().filter(n -> n < 0L).count();

		System.out.printf("%n--- %s ---%n", label);
		System.out.printf("  flags: %,d (%,d/day)   quoted profit: %s   Derpy-safe: %,d%n",
				subset.size(), subset.size() * 24L / HOLDOUT_HOURS, coins(quotedSum), derpyOk);
		if (realized.isEmpty()) {
			System.out.printf("  no resale-verifiable flags (illiquid signatures)%n");
			return;
		}
		System.out.printf("  resale-verifiable: %,d   REALIZED %s vs %s quoted  ->  %.0f%% of quote survives%n",
				ver.size(), coins(realizedSum), coins(realizedQuoted),
				realizedQuoted == 0L ? 0.0d : 100.0d * realizedSum / realizedQuoted);
		System.out.printf("  loss-rate: %,d of %,d resell at a loss (%.1f%%)   per-flip: p10 %s / median %s / p90 %s%n",
				losses, realized.size(), pct(losses, realized.size()),
				coins(pctileL(realized, 0.10)), coins(pctileL(realized, 0.50)), coins(pctileL(realized, 0.90)));
	}

	private static Optional<DecodedItem> decode(String itemBytes) {
		try {
			return ItemDecoder.decode(itemBytes);
		} catch (RuntimeException e) {
			return Optional.empty();
		}
	}

	private static double pctile(List<Double> sorted, double f) {
		if (sorted.isEmpty()) {
			return 0.0d;
		}
		return sorted.get((int) Math.clamp(Math.round(f * (sorted.size() - 1)), 0, sorted.size() - 1));
	}

	private static long pctileL(List<Long> sorted, double f) {
		if (sorted.isEmpty()) {
			return 0L;
		}
		return sorted.get((int) Math.clamp(Math.round(f * (sorted.size() - 1)), 0, sorted.size() - 1));
	}

	private static double pct(long part, long whole) {
		return whole == 0L ? 0.0d : 100.0d * part / whole;
	}

	private static String coins(long c) {
		double a = Math.abs(c);
		if (a >= 1_000_000d) {
			return String.format("%.1fM", c / 1_000_000d);
		}
		if (a >= 1_000d) {
			return String.format("%.0fk", c / 1_000d);
		}
		return Long.toString(c);
	}
}
