package jeff.skyblockflipper.core.valuation;

import jeff.skyblockflipper.core.item.DecodedItem;
import jeff.skyblockflipper.core.item.ItemDecoder;
import jeff.skyblockflipper.core.model.ActiveListing;
import jeff.skyblockflipper.core.nbt.NbtCompound;
import jeff.skyblockflipper.core.nbt.NbtReader;
import jeff.skyblockflipper.core.tape.SalesTape;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Path;
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
 */
@EnabledIfSystemProperty(named = "skyblockflipper.tapeBacktest", matches = "true")
class MidasBidBacktestTest {
	private static final String DEFAULT_TAPE_DIR = "run/config/skyblock-flipper/tape";
	private static final int ALL_DAYS = 365;
	private static final long HOLDOUT_HOURS = 24L;

	private static final String WINNING_BID = "winning_bid";

	/** Every taped sale of an item id that ever carries a bid, decoded down to what is scored. */
	private static List<Sale> sales;
	private static long newestTimestamp;

	/**
	 * A taped sale of a bid-carrying item id.
	 *
	 * @param bid the coins burned at the Dark Auction, or 0 for a sale that carries no bid
	 */
	private record Sale(long timestamp, double price, long bid, String signature, String coarseKey,
			boolean bare, String id) {
		private boolean hasBid() {
			return bid > 0L;
		}
	}

	/** How the bid reaches the estimate, if at all. */
	private enum Keying {
		/** Today: every bid on one signature shares one median. */
		POOLED,
		/** The bid banded by powers of two in the key - the obvious wrong answer, measured. */
		BANDED,
		/** The candidate: quote the pooled price-to-bid ratio times this item's bid. */
		RATIO,
		/**
		 * The ratio quote floored at the pooled median, in case the bid understates a floor price.
		 * A Midas Staff bought for 50,000 coins is still a Midas Staff.
		 */
		RATIO_FLOORED,
		/**
		 * The ratio quote, plus keeping bid-carrying items out of the coarse index the way every
		 * other unreadable-from-the-name attribute is kept out. A "Midas Staff" is named the same at
		 * any bid, so the coarse pool mixes every bid together and is the pooled error again.
		 */
		RATIO_NOT_BARE
	}

	@BeforeAll
	static void loadTape() throws Exception {
		Set<String> ids = new HashSet<>();
		long[] newest = {0L};

		forEachSale((item, extra, timestamp, unitPrice) -> {
			newest[0] = Math.max(newest[0], timestamp);

			if (bid(extra) > 0L) {
				ids.add(item.skyblockId());
			}
		});

		assertTrue(!ids.isEmpty(), "no sales carrying " + WINNING_BID + " on the tape at " + tapeDir());
		newestTimestamp = newest[0];

		List<Sale> loaded = new ArrayList<>();

		forEachSale((item, extra, timestamp, unitPrice) -> {
			if (!ids.contains(item.skyblockId())) {
				return;
			}

			loaded.add(new Sale(timestamp, unitPrice, bid(extra), item.signature(),
					ActiveListing.coarseKey(item.displayName(), item.rarity()), bare(item),
					item.skyblockId()));
		});

		sales = List.copyOf(loaded);
	}

	/**
	 * Does the market price these proportionally to the bid at all?
	 *
	 * <p>The whole candidate rests on this. If the sale price is a roughly fixed multiple of the bid
	 * then one pooled ratio prices every bid; if it is flat in the bid, or if the ratio is as spread
	 * out as the prices themselves, then the attribute is unreadable by this route too and the answer
	 * is to leave it alone.
	 */
	@Test
	void reportsHowSalePriceTracksTheBid() {
		Map<String, List<Sale>> byId = new TreeMap<>();
		sales.stream().filter(Sale::hasBid).forEach(sale ->
				byId.computeIfAbsent(sale.id(), k -> new ArrayList<>()).add(sale));

		System.out.printf("%n%,d sales of bid-carrying item ids, %,d of them carrying a bid, %,d of "
				+ "those otherwise bare%n", sales.size(), sales.stream().filter(Sale::hasBid).count(),
				sales.stream().filter(s -> s.hasBid() && s.bare()).count());

		// How the sales sit in time, because the holdout is a slice of it and a lopsided tape would
		// make a 24h holdout most of the market rather than a sample of it.
		Map<String, Long> perDay = new TreeMap<>();
		sales.forEach(sale -> perDay.merge(
				java.time.Instant.ofEpochMilli(sale.timestamp()).toString().substring(0, 10), 1L, Long::sum));
		System.out.printf("sales per UTC day: %s%n", perDay);
		System.out.printf("%n%-28s %6s %16s %6s %14s %14s %7s %7s %7s%n",
				"item id", "sales", "coins", "bids", "median bid", "median price",
				"ratio", "p10", "p90");

		for (Map.Entry<String, List<Sale>> entry : byId.entrySet()) {
			List<Sale> forId = entry.getValue();
			List<Double> ratios = forId.stream().map(s -> s.price() / s.bid()).toList();
			List<Double> bids = forId.stream().map(s -> (double) s.bid()).toList();
			List<Double> prices = forId.stream().map(Sale::price).toList();

			System.out.printf("%-28s %6d %16d %6d %14.0f %14.0f %7.3f %7.3f %7.3f%n",
					entry.getKey(), forId.size(),
					forId.stream().mapToLong(s -> (long) s.price()).sum(),
					forId.stream().map(Sale::bid).distinct().count(),
					median(bids, 1).orElse(0.0d), median(prices, 1).orElse(0.0d),
					median(ratios, 1).orElse(0.0d),
					percentile(ratios, 0.1d), percentile(ratios, 0.9d));
		}

		// How wrong today's pooling is, in the direction money is lost in: a signature holding several
		// bids quotes one median for all of them, so the low-bid sales in it are quoted high.
		Map<String, List<Sale>> bySignature = new HashMap<>();
		sales.stream().filter(Sale::hasBid).forEach(sale ->
				bySignature.computeIfAbsent(sale.signature(), k -> new ArrayList<>()).add(sale));

		int mixed = 0;
		int quotable = 0;
		int overvalued = 0;
		long overvaluedCoins = 0L;

		for (List<Sale> pool : bySignature.values()) {
			if (pool.stream().map(Sale::bid).distinct().count() < 2) {
				continue;
			}

			mixed++;
			OptionalDouble quoted = median(pool.stream().map(Sale::price).toList(),
					ValueEstimate.MIN_SAMPLES);

			if (quoted.isEmpty()) {
				continue;
			}

			quotable++;

			for (Sale sale : pool) {
				if (quoted.getAsDouble() >= 2.0d * sale.price()) {
					overvalued++;
					overvaluedCoins += (long) sale.price();
				}
			}
		}

		System.out.printf("%n%,d signatures hold more than one bid, %,d of them quotable; the pooled "
				+ "median values %,d of their sales at 2x+ of what they fetched (%,d coins)%n",
				mixed, quotable, overvalued, overvaluedCoins);

		assertTrue(!byId.isEmpty(), "no bid-carrying sales survived decoding");
	}

	/**
	 * Whether the ratio quote beats the pooled median on held-out sales, and what banding would cost.
	 *
	 * <p>Trains on everything older than the newest 24 hours, scores what is in it, over the item ids
	 * that ever carry a bid.
	 */
	@Test
	void scoresTheBidAgainstThePooledMedian() {
		long cutoff = newestTimestamp - HOLDOUT_HOURS * 3_600_000L;

		Scored pooled = score(cutoff, Keying.POOLED);
		Scored banded = score(cutoff, Keying.BANDED);
		Scored ratio = score(cutoff, Keying.RATIO);
		Scored floored = score(cutoff, Keying.RATIO_FLOORED);
		Scored guarded = score(cutoff, Keying.RATIO_NOT_BARE);

		System.out.printf("%nheld-out sales of bid-carrying ids (newest %dh):%n  %-24s %s%n  %-24s "
				+ "%s%n  %-24s %s%n  %-24s %s%n  %-24s %s%n", HOLDOUT_HOURS,
				"today (bid unread)", pooled, "bid banded into the key", banded,
				"bid as a ratio quote", ratio, "ratio, floored at pooled", floored,
				"ratio, not bare", guarded);

		explain(cutoff);

		// A second, longer holdout, because 24h of a six-day tape is one slice and the whole finding
		// rests on the ratio arm beating the pooled one on sales it was not trained on.
		for (long hours : new long[] {48L, 72L}) {
			long earlier = newestTimestamp - hours * 3_600_000L;
			System.out.printf("%n  newest %dh:%n    %-22s %s%n    %-22s %s%n    %-22s %s%n", hours,
					"today (bid unread)", score(earlier, Keying.POOLED),
					"bid as a ratio quote", score(earlier, Keying.RATIO),
					"ratio, not bare", score(earlier, Keying.RATIO_NOT_BARE));
		}

		// Banding is the version of this that follows the last five branches' pattern, and it is here
		// to be rejected on measurement rather than by argument: a bid band splits a pool without
		// pooling anything back, so it can only cost coverage.
		assertTrue(banded.priced() <= pooled.priced(), "banding the bid is expected to cost coverage, "
				+ "but priced sales went from " + pooled.priced() + " to " + banded.priced());
		assertTrue(ratio.overvalued() < banded.overvalued(), "the ratio quote is expected to beat the "
				+ "band it replaces on fake snipes, but it scored " + ratio.overvalued() + " against "
				+ banded.overvalued());

		// The finding, and what production now does. Unlike every signature term measured before it,
		// this one is free: it is the same sales under the same key, asked a different question.
		assertTrue(ratio.priced() >= pooled.priced(), "the ratio quote is expected to cost no "
				+ "coverage at all, but priced sales went from " + pooled.priced() + " to "
				+ ratio.priced());
		assertTrue(ratio.overvalued() * 5 < pooled.overvalued(), "the ratio quote is expected to "
				+ "remove most of the fake snipes, but they went from " + pooled.overvalued() + " to "
				+ ratio.overvalued());

		// Flooring it at the pooled median throws the finding away: the pooled median is the wrong
		// number, so taking the larger of the two keeps being wrong whenever it is wrong upward.
		assertTrue(floored.overvalued() > ratio.overvalued(), "flooring the ratio quote at the pooled "
				+ "median is expected to reintroduce the pooled error");

		// And the coarse guard costs nothing here, which is why it ships dormant rather than not at
		// all: no bid-carrying sale on this tape ever needed the coarse pool.
		assertTrue(guarded.priced() == ratio.priced() && guarded.overvalued() == ratio.overvalued(),
				"keeping bid-carrying items out of the coarse index scored differently from leaving "
						+ "them in, so its cost is no longer zero and needs re-measuring");
	}

	/**
	 * Where the two arms differ, per item id, so the headline is attributable to something.
	 *
	 * <p>Written because the in-sample pools look almost harmless - five quotable signatures, four
	 * sales overvalued - while the holdout says the pooled median is out by a factor of 8. Both can
	 * be true only if the harm is in the spread of bids over time rather than in any single pool, and
	 * that claim is worth seeing rather than assuming.
	 */
	private static void explain(long cutoff) {
		Map<String, List<Double>> trained = new HashMap<>();
		Map<String, List<Double>> ratios = new HashMap<>();
		Map<String, int[]> counts = new TreeMap<>();

		for (Sale sale : sales) {
			if (sale.timestamp() < cutoff) {
				trained.computeIfAbsent(sale.signature(), k -> new ArrayList<>()).add(sale.price());

				if (sale.hasBid()) {
					ratios.computeIfAbsent(sale.signature(), k -> new ArrayList<>())
							.add(sale.price() / sale.bid());
				}
			}
		}

		System.out.printf("%n%-28s %6s %7s %10s %10s %14s %14s%n",
				"item id", "train", "held", "pooled 2x", "ratio 2x", "median price", "pooled quote");

		Map<String, List<Sale>> heldById = new TreeMap<>();
		sales.stream().filter(s -> s.timestamp() >= cutoff).forEach(sale ->
				heldById.computeIfAbsent(sale.id(), k -> new ArrayList<>()).add(sale));

		heldById.forEach((id, held) -> {
			int[] tally = counts.computeIfAbsent(id, k -> new int[2]);
			List<Double> quotes = new ArrayList<>();

			for (Sale sale : held) {
				OptionalDouble pooled = median(trained.get(sale.signature()), ValueEstimate.MIN_SAMPLES);
				OptionalDouble ratio = median(ratios.get(sale.signature()), ValueEstimate.MIN_SAMPLES)
						.stream().map(r -> r * sale.bid()).findFirst();

				pooled.ifPresent(quotes::add);

				if (pooled.isPresent() && pooled.getAsDouble() >= 2.0d * sale.price()) {
					tally[0]++;
				}

				if (ratio.isPresent() && ratio.getAsDouble() >= 2.0d * sale.price()) {
					tally[1]++;
				}
			}

			System.out.printf("%-28s %6d %7d %10d %10d %14.0f %14.0f%n", id,
					sales.stream().filter(s -> s.id().equals(id) && s.timestamp() < cutoff).count(),
					held.size(), tally[0], tally[1],
					median(held.stream().map(Sale::price).toList(), 1).orElse(Double.NaN),
					median(quotes, 1).orElse(Double.NaN));
		});
	}

	/**
	 * @param bidPriced held-out sales carrying a bid that got a valuation at all
	 * @param overvalued sales valued at 2x or more of what they fetched, the fake snipes
	 */
	private record Scored(int priced, int bidPriced, int overvalued, double median, double p90) {
		@Override
		public String toString() {
			return String.format("%,5d priced (%,d with a bid), %,d over 2x, median |log err| %.3f, "
					+ "p90 %.3f", priced, bidPriced, overvalued, median, p90);
		}
	}

	/** Builds the indices production would build under one keying, then prices the holdout. */
	private static Scored score(long cutoff, Keying keying) {
		Map<String, List<Double>> exact = new HashMap<>();
		Map<String, List<Double>> coarse = new HashMap<>();
		Map<String, List<Double>> ratios = new HashMap<>();
		List<Sale> holdout = new ArrayList<>();

		for (Sale sale : sales) {
			if (sale.timestamp() >= cutoff) {
				holdout.add(sale);
				continue;
			}

			exact.computeIfAbsent(key(sale, keying), k -> new ArrayList<>()).add(sale.price());

			if (sale.bare() && !(keying == Keying.RATIO_NOT_BARE && sale.hasBid())) {
				coarse.computeIfAbsent(sale.coarseKey(), k -> new ArrayList<>()).add(sale.price());
			}

			if (sale.hasBid()) {
				ratios.computeIfAbsent(sale.signature(), k -> new ArrayList<>())
						.add(sale.price() / sale.bid());
			}
		}

		List<Double> errors = new ArrayList<>();
		int bidPriced = 0;
		int overvalued = 0;

		for (Sale sale : holdout) {
			OptionalDouble estimate = OptionalDouble.empty();
			boolean ratioArm = keying != Keying.POOLED && keying != Keying.BANDED;

			// The ratio quote takes precedence over the pooled median for a sale that carries a bid,
			// because it is the more specific statement about the same pool.
			if (ratioArm && sale.hasBid()) {
				estimate = median(ratios.get(sale.signature()), ValueEstimate.MIN_SAMPLES)
						.stream().map(r -> r * sale.bid()).findFirst();
			}

			OptionalDouble pooledMedian = median(exact.get(key(sale, keying)), ValueEstimate.MIN_SAMPLES);

			if (keying == Keying.RATIO_FLOORED && estimate.isPresent() && pooledMedian.isPresent()) {
				estimate = OptionalDouble.of(Math.max(estimate.getAsDouble(), pooledMedian.getAsDouble()));
			}

			if (estimate.isEmpty()) {
				estimate = pooledMedian;
			}

			boolean coarseEligible = sale.bare()
					&& !(keying == Keying.RATIO_NOT_BARE && sale.hasBid());

			if (estimate.isEmpty() && coarseEligible) {
				estimate = median(coarse.get(sale.coarseKey()), ValueEstimate.MIN_SAMPLES);
			}

			if (estimate.isEmpty() || estimate.getAsDouble() <= 0.0d || sale.price() <= 0.0d) {
				continue;
			}

			double ratio = estimate.getAsDouble() / sale.price();
			errors.add(Math.abs(Math.log(ratio)));

			if (ratio >= 2.0d) {
				overvalued++;
			}

			if (sale.hasBid()) {
				bidPriced++;
			}
		}

		List<Double> sorted = errors.stream().sorted().toList();

		return new Scored(sorted.size(), bidPriced, overvalued,
				sorted.isEmpty() ? Double.NaN : sorted.get(sorted.size() / 2),
				sorted.isEmpty() ? Double.NaN : sorted.get(sorted.size() * 9 / 10));
	}

	private static String key(Sale sale, Keying keying) {
		if (keying != Keying.BANDED || !sale.hasBid()) {
			return sale.signature();
		}

		return sale.signature() + "|bid=2^" + (63 - Long.numberOfLeadingZeros(sale.bid()));
	}

	private static long bid(NbtCompound extra) {
		return (long) extra.number(WINNING_BID).orElse(0.0d);
	}

	/** {@link FairValueModel}'s admission test for the coarse index. */
	private static boolean bare(DecodedItem item) {
		return !item.isPet()
				&& !item.isPotion()
				&& !item.hasQuality()
				&& !item.isDyed()
				&& !item.ethermerged()
				&& item.stars() == 0
				&& !item.recombobulated()
				&& item.hotPotatoBooks() == 0
				&& item.enchantments().isEmpty()
				&& item.gemstones().isEmpty()
				&& item.attributes().isEmpty()
				&& item.runes().isEmpty();
	}

	/** The decoded item, its raw {@code ExtraAttributes}, when it sold, and its unit price. */
	private interface SaleVisitor {
		void accept(DecodedItem item, NbtCompound extra, long timestamp, double unitPrice);
	}

	private static void forEachSale(SaleVisitor visitor) throws Exception {
		tape().forEachRecent(ALL_DAYS, sale -> {
			if (!sale.bin() || sale.price() <= 0L) {
				return;
			}

			NbtCompound root;

			try {
				root = NbtReader.readItemBytes(sale.itemBytes());
			} catch (Exception e) {
				return;
			}

			if (!(root.list("i").stream().findFirst().orElse(null) instanceof NbtCompound stack)) {
				return;
			}

			NbtCompound extra = stack.child("tag").child("ExtraAttributes");

			ItemDecoder.fromRoot(root).ifPresent(item -> visitor.accept(item, extra, sale.timestamp(),
					(double) sale.price() / Math.max(1, item.count())));
		});
	}

	private static SalesTape tape() {
		return new SalesTape(Path.of(tapeDir()), 3650);
	}

	private static String tapeDir() {
		return System.getProperty("skyblockflipper.tapeDir", DEFAULT_TAPE_DIR);
	}

	private static OptionalDouble median(List<Double> values, int minSamples) {
		if (values == null || values.size() < minSamples) {
			return OptionalDouble.empty();
		}

		List<Double> sorted = values.stream().sorted().toList();
		return OptionalDouble.of(sorted.get(sorted.size() / 2));
	}

	private static double percentile(List<Double> values, double fraction) {
		if (values.isEmpty()) {
			return Double.NaN;
		}

		List<Double> sorted = values.stream().sorted().toList();
		int index = (int) Math.round(fraction * (sorted.size() - 1));
		return sorted.get(Math.clamp(index, 0, sorted.size() - 1));
	}
}
