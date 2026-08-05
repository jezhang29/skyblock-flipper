package jeff.skyblockflipper.core.valuation;

import jeff.skyblockflipper.core.item.DecodedItem;
import jeff.skyblockflipper.core.valuation.backtest.Backtest;
import jeff.skyblockflipper.core.valuation.backtest.CounterfactualKeying;
import jeff.skyblockflipper.core.valuation.backtest.TapeFixture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.time.Duration;
import java.time.Instant;
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
 * What {@code winning_bid} is worth to the model, measured. Opt-in, needs a recorded tape:
 * {@code ./gradlew test -PtapeBacktest --tests '*MidasBidBacktestTest'}.
 *
 * <p>It is the largest entry {@code UnreadAttributeProbeTest} has ever printed and the one the
 * roadmap kept setting aside, because it is a different shape from every signature term so far. On a
 * Midas weapon the bid <b>is</b> the value: the item's stats scale with the coins burned at the Dark
 * Auction, so the attribute is a continuous number rather than a bit or an enumeration. Keying it
 * would make one cell per bid, which is the drill parts' failure mode with 500 values instead of 69.
 *
 * <p>So the candidate here is not a key term. It is a second way to compute an estimate: pool the
 * <b>ratio</b> of sale price to bid over the same signature production already uses, and quote
 * {@code medianRatio * thisItem'sBid}. That pools across bids instead of splitting on them, so it
 * costs no coverage at all, and it is only right if the market actually prices these things
 * proportionally to the bid - which is what the first test measures before the second one scores it.
 *
 * <p>Re-measured against the model that ships rather than the hand-built copy the finding was taken
 * on. Four of the five arms are now a {@link Keying} handed to the real model; the fifth - flooring
 * the ratio quote at the pooled median - is a rule production has no way to express, so it is
 * reconstructed by joining the two arms' rows on {@link Backtest.Priced#saleKey()}.
 */
@EnabledIfSystemProperty(named = "skyblockflipper.tapeBacktest", matches = "true")
class MidasBidBacktestTest {
	private static final long HOLDOUT_HOURS = 24L;

	/** Longer than any tape, so training is the unbounded replay these findings were measured on. */
	private static final Duration WHOLE_TAPE = Duration.ofDays(3650);

	/**
	 * Does the market price these proportionally to the bid at all?
	 *
	 * <p>The whole candidate rests on this. If the sale price is a roughly fixed multiple of the bid
	 * then one pooled ratio prices every bid; if it is flat in the bid, or if the ratio is as spread
	 * out as the prices themselves, then the attribute is unreadable by this route too and the answer
	 * is to leave it alone.
	 */
	@Test
	void reportsHowSalePriceTracksTheBid() throws Exception {
		Map<String, List<double[]>> byId = new TreeMap<>();
		Map<String, List<Double>> bySignature = new HashMap<>();
		Map<String, Long> perDay = new TreeMap<>();
		int[] bare = {0};

		TapeFixture.forEachSale((item, extra, timestamp, unitPrice) -> {
			if (!item.hasWinningBid()) {
				return;
			}

			byId.computeIfAbsent(item.skyblockId(), k -> new ArrayList<>())
					.add(new double[] {unitPrice, item.winningBid()});
			bySignature.computeIfAbsent(item.signature(), k -> new ArrayList<>()).add(unitPrice);
			perDay.merge(Instant.ofEpochMilli(timestamp).toString().substring(0, 10), 1L, Long::sum);

			// What the coarse guard would cost if it ever fired: the bid-carrying sales that carry
			// nothing else, and so would otherwise be priced off the pool of every bid on that name.
			if (CounterfactualKeying.withTheBidUnread(false).isBare(item)) {
				bare[0]++;
			}
		});

		int carrying = byId.values().stream().mapToInt(List::size).sum();

		System.out.printf("%n%,d sales carry a Dark Auction bid, %,d of them otherwise bare%n",
				carrying, bare[0]);
		System.out.printf("sales per UTC day: %s%n", perDay);
		System.out.printf("%n%-28s %6s %16s %6s %14s %14s %7s %7s %7s%n",
				"item id", "sales", "coins", "bids", "median bid", "median price",
				"ratio", "p10", "p90");

		byId.forEach((id, forId) -> {
			List<Double> ratios = forId.stream().map(sale -> sale[0] / sale[1]).toList();
			List<Double> bids = forId.stream().map(sale -> sale[1]).toList();
			List<Double> prices = forId.stream().map(sale -> sale[0]).toList();

			System.out.printf("%-28s %6d %16d %6d %14.0f %14.0f %7.3f %7.3f %7.3f%n",
					id, forId.size(),
					forId.stream().mapToLong(sale -> (long) sale[0]).sum(),
					bids.stream().distinct().count(),
					TapeFixture.median(bids, 1).orElse(0.0d),
					TapeFixture.median(prices, 1).orElse(0.0d),
					TapeFixture.median(ratios, 1).orElse(0.0d),
					percentile(ratios, 0.1d), percentile(ratios, 0.9d));
		});

		assertTrue(!byId.isEmpty(), "no sales carrying a Dark Auction bid on the tape at "
				+ TapeFixture.tapeDir());

		// How wrong pooling is in the direction money is lost in: a signature holding several bids
		// quotes one median for all of them, so the low-bid sales in it are quoted high.
		int overvalued = 0;

		for (Map.Entry<String, List<Double>> pool : bySignature.entrySet()) {
			OptionalDouble quoted = Backtest.quotableMedian(pool.getValue());

			if (quoted.isEmpty()) {
				continue;
			}

			for (double price : pool.getValue()) {
				if (quoted.getAsDouble() >= 2.0d * price) {
					overvalued++;
				}
			}
		}

		System.out.printf("%npooling every bid on a signature into one median values %,d of those "
				+ "sales at 2x+ of what they fetched%n", overvalued);
	}

	/**
	 * Whether the ratio quote beats the pooled median on held-out sales, and what banding would cost.
	 *
	 * <p>Trains on everything older than the newest 24 hours, scores what is in it, over the item ids
	 * that ever carry a bid. Repeated at 48 and 72 hours, because the tape is lopsided in time and one
	 * slice of it is not a finding.
	 */
	@Test
	void scoresTheBidAgainstThePooledMedian() throws Exception {
		Set<String> ids = idsThatEverCarryABid();
		long cutoff = TapeFixture.newestTimestamp() - HOLDOUT_HOURS * 3_600_000L;

		Backtest.Result pooled = arm(CounterfactualKeying.withTheBidUnread(false), ids, cutoff);
		Backtest.Result banded = arm(CounterfactualKeying.withExtraTermInsteadOfTheBidRatio(
				MidasBidBacktestTest::bidBand), ids, cutoff);
		Backtest.Result ratio = arm(CounterfactualKeying.withTheBidUnread(true), ids, cutoff);
		Backtest.Result guarded = arm(Keying.PRODUCTION, ids, cutoff);

		System.out.printf("%nheld-out sales of the %,d bid-carrying ids (newest %dh):%n"
						+ "  %-30s %s%n  %-30s %s%n  %-30s %s%n  %-30s %s%n  %-30s %s%n",
				ids.size(), HOLDOUT_HOURS,
				"today (bid unread)", pooled,
				"bid banded into the key", banded,
				"bid as a ratio quote", ratio,
				"ratio, floored at pooled", floored(pooled, ratio),
				"ratio + coarse guard (shipped)", guarded);

		for (long hours : new long[] {48L, 72L}) {
			long earlier = TapeFixture.newestTimestamp() - hours * 3_600_000L;
			System.out.printf("%n  newest %dh:%n    %-28s %s%n    %-28s %s%n    %-28s %s%n", hours,
					"today (bid unread)", arm(CounterfactualKeying.withTheBidUnread(false), ids, earlier),
					"bid as a ratio quote", arm(CounterfactualKeying.withTheBidUnread(true), ids, earlier),
					"ratio + coarse guard (shipped)", arm(Keying.PRODUCTION, ids, earlier));
		}

		// Banding is the version of this that follows the earlier branches' pattern, and it is here to
		// be rejected on measurement rather than by argument: a bid band splits a pool without pooling
		// anything back, so it can only cost coverage.
		assertTrue(banded.priced().size() <= pooled.priced().size(), "banding the bid is expected to "
				+ "cost coverage, but priced sales went from " + pooled.priced().size() + " to "
				+ banded.priced().size());
		assertTrue(ratio.overvaluedBy(2.0d) < banded.overvaluedBy(2.0d), "the ratio quote is expected "
				+ "to beat the band it replaces on fake snipes, but it scored "
				+ ratio.overvaluedBy(2.0d) + " against " + banded.overvaluedBy(2.0d));

		// The finding, and what production now does. Unlike every signature term measured before it,
		// this one is free: it is the same sales under the same key, asked a different question.
		assertTrue(ratio.priced().size() >= pooled.priced().size(), "the ratio quote is expected to "
				+ "cost no coverage at all, but priced sales went from " + pooled.priced().size()
				+ " to " + ratio.priced().size());
		assertTrue(ratio.overvaluedBy(2.0d) * 2 < pooled.overvaluedBy(2.0d), "the ratio quote is "
				+ "expected to remove most of the fake snipes, but they went from "
				+ pooled.overvaluedBy(2.0d) + " to " + ratio.overvaluedBy(2.0d));

		// Flooring it at the pooled median throws the finding away: the pooled median is the wrong
		// number, so taking the larger of the two keeps being wrong whenever it is wrong upward.
		assertTrue(floored(pooled, ratio).overvalued() > ratio.overvaluedBy(2.0d),
				"flooring the ratio quote at the pooled median is expected to reintroduce the pooled "
						+ "error");
	}

	private static Backtest.Result arm(Keying keying, Set<String> ids, long cutoff) throws Exception {
		return Backtest.holdout(keying, cutoff, WHOLE_TAPE, item -> ids.contains(item.skyblockId()));
	}

	/**
	 * The rejected variant, reconstructed from the two arms that ran.
	 *
	 * <p>"Quote the larger of the ratio estimate and the pooled median" is not a rule any
	 * {@link Keying} can express - it reads two indices and compares them - so rather than teach the
	 * model a rule it does not have, the two arms' rows are joined on the sale they priced and the
	 * maximum taken. Sales only one arm priced take that arm's quote, which is what a
	 * {@code max}-of-two-optionals rule would do.
	 */
	private static Floored floored(Backtest.Result pooled, Backtest.Result ratio) {
		Map<String, Backtest.Priced> pooledByKey = new HashMap<>();
		pooled.priced().forEach(priced -> pooledByKey.put(priced.saleKey(), priced));

		Set<String> seen = new HashSet<>();
		int priced = 0;
		int overvalued = 0;

		for (Backtest.Priced sale : ratio.priced()) {
			Backtest.Priced other = pooledByKey.get(sale.saleKey());
			double estimate = other == null ? sale.estimate()
					: Math.max(sale.estimate(), other.estimate());

			priced++;
			seen.add(sale.saleKey());
			overvalued += estimate >= 2.0d * sale.actual() ? 1 : 0;
		}

		for (Backtest.Priced sale : pooled.priced()) {
			if (seen.contains(sale.saleKey())) {
				continue;
			}

			priced++;
			overvalued += sale.overvaluedBy(2.0d) ? 1 : 0;
		}

		return new Floored(priced, overvalued);
	}

	private record Floored(int priced, int overvalued) {
		@Override
		public String toString() {
			return String.format("%,5d priced, %,d over 2x", priced, overvalued);
		}
	}

	/** The obvious wrong answer, measured: the bid banded by powers of two, in the key. */
	private static String bidBand(DecodedItem item) {
		if (!item.hasWinningBid()) {
			return "";
		}

		return "bid2^" + (63 - Long.numberOfLeadingZeros(Math.max(1L, item.winningBid())));
	}

	private static Set<String> idsThatEverCarryABid() throws Exception {
		Set<String> ids = new HashSet<>();

		TapeFixture.forEachSale((item, extra, timestamp, unitPrice) -> {
			if (item.hasWinningBid()) {
				ids.add(item.skyblockId());
			}
		});

		assertTrue(!ids.isEmpty(), "no bid-carrying sales on the tape at " + TapeFixture.tapeDir());
		return ids;
	}

	private static double percentile(List<Double> values, double fraction) {
		if (values.isEmpty()) {
			return Double.NaN;
		}

		List<Double> sorted = values.stream().sorted().toList();
		return sorted.get(Math.min(sorted.size() - 1, (int) (sorted.size() * fraction)));
	}
}
