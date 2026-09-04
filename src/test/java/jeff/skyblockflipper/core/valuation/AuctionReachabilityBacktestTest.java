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
import jeff.skyblockflipper.core.tape.TimedAuctionTape;
import jeff.skyblockflipper.core.valuation.backtest.TapeFixture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 0b of the timed-auction bidding investigation (docs/auction-bidding-plan.md): the one thing
 * Phase 0a could not measure - <b>reachability</b>. Opt-in:
 * <pre>
 * ./gradlew test -PtapeBacktest -PtapeDir=&lt;sales tape&gt; -PtimedTapeDir=&lt;timed-auction tape&gt; \
 *     --tests '*AuctionReachabilityBacktestTest'
 * </pre>
 *
 * <p>0a proved the surplus is real: timed auctions clear below BIN value and resell well. But its
 * data was the <i>final</i> price of each auction, so "703 ended cheap a day" could never become
 * "703 you could have won" - you compete for those same auctions with whoever actually won them,
 * often a bot. This test answers that, by joining the new active-trajectory tape
 * ({@link TimedAuctionTape}, gathered 24/7 by the collector) to the ended-sales tape.
 *
 * <p>The measurement, per the plan:
 * <ol>
 *   <li>Group every active-listing sample by auction id into a trajectory: its signature, starting
 *       bid, stack size, and the top bid at the last sample before it ended.</li>
 *   <li>"Flagged undervalued" = the starting bid is >= {@value #FLAG_MIN_DISCOUNT_PCT}% under the
 *       BIN median of the signature (the reachable price is the starting bid: what an uncontested
 *       lead costs). The median is the pooled BIN sale price, out of the training window - the same
 *       "BIN median" the plan names, never resold at the model's own quote.</li>
 *   <li>"Reachable" = the top bid never rose off the starting bid through the last sample before
 *       {@code end}: winnable by presence. This is read straight from the trajectory, <b>not</b>
 *       from the join, on purpose - a zero-bid auction returns to the seller and leaves no ended
 *       record, yet it is the purest reachable flip, so requiring a join would silently drop exactly
 *       the auctions the strategy would win most easily. The two kinds are reported apart:
 *       <i>sold-at-start</i> (one bid, at the opening price - a real competed sale you'd have raced
 *       one bidder for) and <i>no-bid</i> (returned unsold - reachable if you'd shown up, but maybe
 *       nobody wanted it).</li>
 *   <li>For <b>contested</b> auctions the join supplies the final price: how many 2.5% steps the war
 *       ran, and whether it still landed under the BIN median.</li>
 * </ol>
 *
 * <p>The decision gate (plan): build Phase 1 only if a meaningful share (target 20-30%) of flagged
 * timed auctions end reachably cheap, at a per-day rate and per-flip value worth the clicks.
 *
 * <p>Until the collector has run for days there is no trajectory tape, so this prints that and
 * returns green: the reachability number is a wait after the collection is deployed, not something
 * this run can conjure.
 */
@EnabledIfSystemProperty(named = "skyblockflipper.tapeBacktest", matches = "true")
class AuctionReachabilityBacktestTest {
	private static final Duration TRAIN_WINDOW = Duration.ofDays(2);
	private static final double FLAG_MIN_DISCOUNT = 0.15d;
	private static final int FLAG_MIN_DISCOUNT_PCT = 15;
	private static final double DAY_MS = 86_400_000.0d;
	/** A resale median needs this many BIN sales of the signature to price against, matching 0a. */
	private static final int MIN_COMPS = ValueEstimate.MIN_SAMPLES;

	private static final String DEFAULT_TIMED_DIR = "run/config/skyblock-flipper/timed-auction-tape";

	/** One auction's trajectory, reduced as its samples stream in (never all held at once). */
	private static final class Traj {
		private String uuid;
		private String signature;
		private int count = 1;
		private long startingBid;
		private long lastSampledAt = Long.MIN_VALUE;
		private long lastHighestBid;
		private long lastEnd;
		private int samples;

		/** The top bid never rose off the opening bid: winnable at the starting bid by presence. */
		private boolean reachable() {
			return lastHighestBid <= startingBid;
		}

		/** No bid at all - the auction returns to the seller and never reaches the ended tape. */
		private boolean noBid() {
			return lastHighestBid == 0L;
		}
	}

	@Test
	void reachabilityOfFlaggedTimedAuctions() throws Exception {
		Map<String, Traj> byId = new HashMap<>();
		long[] span = {Long.MAX_VALUE, Long.MIN_VALUE};

		TimedAuctionTape timedTape = new TimedAuctionTape(Path.of(timedTapeDir()), 3650);
		int totalSamples = timedTape.forEachRecent(365, s -> {
			Traj t = byId.computeIfAbsent(s.uuid(), k -> new Traj());
			t.uuid = s.uuid();
			t.samples++;
			t.startingBid = s.startingBid();
			t.count = Math.max(1, s.count());
			if (s.sampledAt() >= t.lastSampledAt) {
				t.lastSampledAt = s.sampledAt();
				t.signature = s.signature();
				t.lastHighestBid = s.highestBidAmount();
				t.lastEnd = s.end();
			}
			span[0] = Math.min(span[0], s.sampledAt());
			span[1] = Math.max(span[1], s.sampledAt());
		});

		System.out.printf("%n=== timed-auction reachability backtest (Phase 0b) ===%n");
		System.out.printf("timed tape: %s%n", timedTapeDir());
		System.out.printf("%,d samples over %,d distinct auctions%n", totalSamples, byId.size());

		if (byId.isEmpty()) {
			System.out.printf("%nNo trajectories on the timed tape. Deploy the collection "
					+ "(timedAuctionTapeEnabled=true, scanAuctions on) on the 24/7 collector and let it "
					+ "run several days, then re-run this against its tape. The reachability number is a "
					+ "wait after deploy - it cannot be measured from ended data alone, which is the whole "
					+ "reason Phase 0b exists.%n");
			return;
		}

		long newestSample = span[1];
		double coverageDays = Math.max(1.0d, (span[1] - span[0]) / DAY_MS);
		System.out.printf("coverage: %.2f days of sampling%n", coverageDays);

		// One pass over the ended tape: BIN medians per signature (out of the training window) and
		// the realized final price of every trajectory that sold, joined on the auction id.
		long cutoff = TapeFixture.newestTimestamp();
		long trainStart = cutoff - TRAIN_WINDOW.toMillis();
		Map<String, List<Double>> binBySig = new HashMap<>();
		Map<String, Long> finalPriceById = new HashMap<>();
		Set<String> ids = byId.keySet();

		TapeFixture.tape().forEachRecent(TapeFixture.ALL_DAYS, sale -> {
			if (sale.price() <= 0L) {
				return;
			}
			if (sale.bin()) {
				if (sale.timestamp() >= trainStart && sale.timestamp() < cutoff) {
					decode(sale.itemBytes()).ifPresent(item -> binBySig
							.computeIfAbsent(item.signature(), k -> new ArrayList<>())
							.add((double) sale.price() / Math.max(1, item.count())));
				}
			} else if (sale.auctionId() != null && ids.contains(sale.auctionId())) {
				finalPriceById.put(sale.auctionId(), sale.price());
			}
		});

		// Only auctions that have actually ended (their clock ran out at or before our newest sample)
		// can be judged reachable-to-end; the still-open tail near the newest edge is excluded.
		int ended = 0;
		int priceable = 0;
		int flagged = 0;
		int reachable = 0;
		int reachableNoBid = 0;
		int reachableSold = 0;
		int contested = 0;
		int joined = 0;
		int joinSaneOk = 0;
		List<Integer> steps = new ArrayList<>();
		int contestedFinalPriced = 0;
		int contestedFinalBelowMedian = 0;

		for (Traj t : byId.values()) {
			if (t.signature == null || t.lastEnd > newestSample) {
				continue; // still open, or never resolved a last sample
			}
			ended++;

			OptionalDouble median = TapeFixture.median(binBySig.get(t.signature), MIN_COMPS);
			if (median.isEmpty()) {
				continue; // no liquid BIN market: item-level adverse selection, unpriceable (0a's 23.6%)
			}
			priceable++;

			double m = median.getAsDouble();
			double startUnit = (double) t.startingBid / t.count;
			double discountStart = 1.0d - startUnit / m;
			if (discountStart < FLAG_MIN_DISCOUNT) {
				continue;
			}
			flagged++;

			Long finalWhole = finalPriceById.get(t.uuid);
			if (finalWhole != null) {
				joined++;
				if (finalWhole >= t.lastHighestBid) {
					joinSaneOk++; // the realized price is at or above the last top bid we saw: id join holds
				}
			}

			if (t.reachable()) {
				reachable++;
				if (t.noBid()) {
					reachableNoBid++;
				} else {
					reachableSold++;
				}
			} else {
				contested++;
				long finalPrice = finalWhole != null ? finalWhole : t.lastHighestBid;
				if (finalPrice > t.startingBid && t.startingBid > 0L) {
					steps.add((int) Math.round(
							Math.log((double) finalPrice / t.startingBid) / Math.log(1.025d)));
				}
				if (finalWhole != null) {
					contestedFinalPriced++;
					if ((double) finalWhole / t.count < m) {
						contestedFinalBelowMedian++;
					}
				}
			}
		}

		System.out.printf("%n--- population (ended, ending-soon auctions only) ---%n");
		System.out.printf("ended: %,d   priceable (BIN comps >=%d): %,d   unpriceable: %,d%n",
				ended, MIN_COMPS, priceable, ended - priceable);
		System.out.printf("join to ended sales: %,d of %,d flagged (%.1f%%), id-sane %,d/%,d%n",
				joined, flagged, pct(joined, flagged), joinSaneOk, joined);

		System.out.printf("%n--- of flagged undervalued (start bid >=%d%% under BIN median): is it reachable? ---%n",
				FLAG_MIN_DISCOUNT_PCT);
		System.out.printf("flagged: %,d (%.0f/day paper, cf. 0a's 164-703/day)%n",
				flagged, flagged / coverageDays);
		System.out.printf("REACHABLE (uncontested to end): %,d (%.1f%% of flagged)   %.0f/day%n",
				reachable, pct(reachable, flagged), reachable / coverageDays);
		System.out.printf("  of which sold-at-start (one bidder, real sale): %,d (%.0f/day)%n",
				reachableSold, reachableSold / coverageDays);
		System.out.printf("  of which no-bid (returned unsold, ceiling):     %,d (%.0f/day)%n",
				reachableNoBid, reachableNoBid / coverageDays);
		System.out.printf("CONTESTED (a war ratcheted the price): %,d (%.1f%% of flagged)%n",
				contested, pct(contested, flagged));

		if (!steps.isEmpty()) {
			List<Integer> sorted = steps.stream().sorted().toList();
			System.out.printf("%n--- contested wars: how far the +2.5%% ladder ran ---%n");
			System.out.printf("steps: median %d / p90 %d   final under BIN median: %,d of %,d (%.1f%%)%n",
					sorted.get(sorted.size() / 2), sorted.get((int) (sorted.size() * 0.9)),
					contestedFinalBelowMedian, contestedFinalPriced,
					pct(contestedFinalBelowMedian, contestedFinalPriced));
		}

		System.out.printf("%n--- verdict cut (plan's decision gate: >=20-30%% of flagged reachable) ---%n");
		System.out.printf("reachable share of flagged: %.1f%%   sold-at-start share: %.1f%%%n",
				pct(reachable, flagged), pct(reachableSold, flagged));
		System.out.printf("Reachable is read from the trajectory, not resold at any quote. Contested final "
				+ "prices come from the joined ended sale. Blind to signature misses (the BIN median pools "
				+ "the same blind sales) - see docs/auction-bidding-plan.md.%n");

		assertTrue(ended > 0, "no ended trajectories - the tape may be all still-open listings");
	}

	private static Optional<DecodedItem> decode(String itemBytes) {
		try {
			return ItemDecoder.decode(itemBytes);
		} catch (RuntimeException e) {
			return Optional.empty();
		}
	}

	private static double pct(long part, long whole) {
		return whole == 0L ? 0.0d : 100.0d * part / whole;
	}

	private static String timedTapeDir() {
		return System.getProperty("skyblockflipper.timedTapeDir", DEFAULT_TIMED_DIR);
	}
}
