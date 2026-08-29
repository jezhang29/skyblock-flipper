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

import jeff.skyblockflipper.core.model.BazaarProduct;
import jeff.skyblockflipper.core.model.BazaarSample;
import jeff.skyblockflipper.core.model.ItemCatalog;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Measures, from the bazaar tape, how durable each product's gap under its NPC price has been.
 *
 * <p>Filled by streaming {@code BazaarTape.forEachRecent} through {@link #append} and read once
 * through {@link #snapshot()}. Built for one pass and then discarded, unlike {@link PriceHistory},
 * which the poller owns for the life of the session: there is nothing incremental to keep, because
 * every figure is a statistic over a fixed window that the next pass recomputes from scratch.
 *
 * <p><b>Only the bid side is read, and only products an NPC buys are accumulated at all.</b> Where
 * {@link PriceHistory} needs both sides for a midpoint and skips one-sided books, this needs a bid
 * and nothing else - a product with no resting sell offers is still perfectly sellable to an NPC.
 * Restricting to the 816 NPC-priced products of the 2,123 on the bazaar is also what keeps the
 * memory bounded: the median needs the samples themselves, so a window of three days at the tape's
 * five-minute cadence holds roughly 800 doubles per product, on the order of six megabytes, for the
 * length of one maintenance pass.
 *
 * <p><b>Sample order is assumed, not enforced.</b> The tape is append-only and written a snapshot at
 * a time, so a product's samples arrive oldest first - except after a sync, which appends the
 * collector's lines to the end of a day file that already holds ours. The persistence fraction and
 * the median do not care about order. The drift does, and a jump backwards in time is skipped rather
 * than counted, so a merged tape loses one interval per discontinuity and never invents one. Same
 * rule and the same reasoning as {@link FillStats}.
 *
 * <p>Not thread-safe. The maintenance thread builds one, fills it and publishes the snapshot; the
 * tape is only ever read here, never written, which is what makes running it off the poller safe.
 */
public final class NpcEdgeHistory {
	private static final double MILLIS_PER_HOUR = 3_600_000.0d;

	/** Samples one product's buffer starts at, doubled as needed. */
	private static final int INITIAL_CAPACITY = 64;

	private final Map<String, Double> npcPrices;
	private final Map<String, Series> series = new HashMap<>();
	private final Duration window;
	private final long cutoff;

	private int samples;

	/**
	 * @param catalog the NPC prices to measure against. Products it does not price are skipped
	 *                entirely rather than accumulated and discarded later
	 * @param now     the instant the window is measured back from, supplied rather than read so a
	 *                test can replay a fixed tape
	 * @param window  how far back to look. Three days is what the parameters in
	 *                {@code docs/npc-flipping.md} were measured over, and is comfortably more than
	 *                the seventeen hours {@link NpcEdge#MIN_SAMPLES} needs
	 */
	public NpcEdgeHistory(ItemCatalog catalog, Instant now, Duration window) {
		this.window = window;
		this.cutoff = now.minus(window).toEpochMilli();
		this.npcPrices = npcPricesIn(catalog);
	}

	/**
	 * Folds one taped sample in, ignoring anything outside the window or without a usable bid.
	 *
	 * <p>A cutoff applied per sample rather than by evicting afterwards, like
	 * {@code FairValueModel.Builder}: the caller streams whole day files, so the oldest one always
	 * reaches back further than the window does.
	 */
	public void append(BazaarSample sample) {
		if (sample == null || sample.productId() == null || sample.bidPrice() <= 0.0d
				|| sample.timestamp() < cutoff) {
			return;
		}

		Series product = series.get(sample.productId());

		if (product == null) {
			Double npcPrice = npcPrices.get(sample.productId());

			// No NPC buys it, so there is no trade here to have a history of.
			if (npcPrice == null) {
				return;
			}

			product = new Series(npcPrice);
			series.put(sample.productId(), product);
		}

		product.add(sample.timestamp(), sample.bidPrice());
		samples++;
	}

	/** An immutable view for readers on other threads. Recomputed, so call it once. */
	public NpcEdgeSnapshot snapshot() {
		Map<String, NpcEdge> edges = new HashMap<>(series.size());

		for (Map.Entry<String, Series> entry : series.entrySet()) {
			edges.put(entry.getKey(), entry.getValue().toEdge(entry.getKey()));
		}

		return new NpcEdgeSnapshot(edges, window, samples, Instant.now());
	}

	/** NPC-priced products the tape has had anything for so far. */
	public int productsTracked() {
		return series.size();
	}

	/** Samples accepted, which is fewer than the caller streamed. */
	public int samplesRead() {
		return samples;
	}

	private static Map<String, Double> npcPricesIn(ItemCatalog catalog) {
		Map<String, Double> prices = new HashMap<>();

		for (ItemCatalog.Entry entry : catalog.items().values()) {
			entry.npcPrice()
					.filter(price -> price > 0.0d)
					.ifPresent(price -> prices.put(entry.id(), price));
		}

		return prices;
	}

	/**
	 * One product's bids, plus the running figures that cannot be recovered from them.
	 *
	 * <p>The bids are kept because a median needs them; everything else accumulates as it arrives.
	 * The drift in particular could not be recomputed from a sorted array at all - it is the one
	 * figure here that depends on the order the samples came in.
	 */
	private static final class Series {
		private final double npcPrice;

		private double[] bids = new double[INITIAL_CAPACITY];
		private int size;
		private int edgeSamples;

		private long lastTime;
		private double lastBid;
		private double driftCoins;
		private double hours;
		private int intervals;

		Series(double npcPrice) {
			this.npcPrice = npcPrice;
		}

		void add(long time, double bid) {
			// Where a buy order would actually be posted. You cannot buy at the bid; you outbid it by
			// one increment, which is what BazaarProduct.outbidBuyOrder does against the live book.
			if (bid + BazaarProduct.PRICE_INCREMENT < npcPrice) {
				edgeSamples++;
			}

			if (size == bids.length) {
				bids = Arrays.copyOf(bids, size * 2);
			}

			bids[size++] = bid;

			if (lastTime > 0L) {
				long elapsed = time - lastTime;

				// A gap this long is the client having been closed, and a negative one is a synced
				// block landing after the lines it predates. Counting either would put time in the
				// denominator that nobody was watching, or a price step across a discontinuity in
				// the numerator.
				if (elapsed > 0L && elapsed <= PriceHistory.MAX_OBSERVED_GAP_MILLIS) {
					intervals++;
					hours += elapsed / MILLIS_PER_HOUR;

					double rise = bid - lastBid;

					// Only upward steps. Chasing the book means repricing when someone gets in front
					// of you; a bid that falls costs nothing to not follow.
					if (rise > 0.0d) {
						driftCoins += rise;
					}
				}
			}

			// Advanced unconditionally, including across a backward jump. Holding the cursor at the
			// later timestamp instead would make every remaining sample of a synced block look like
			// it arrived out of order, and the whole block would contribute nothing.
			lastTime = time;
			lastBid = bid;
		}

		NpcEdge toEdge(String productId) {
			double[] sorted = Arrays.copyOf(bids, size);
			Arrays.sort(sorted);

			// Margin is a decreasing function of the bid, so the median bid gives the median margin.
			// On an even count this index takes the upper of the two middle bids, and so the lower of
			// the two middle margins - the conservative one.
			double medianBid = sorted[size / 2];
			double medianMarginRatio =
					(npcPrice - (medianBid + BazaarProduct.PRICE_INCREMENT)) / npcPrice;

			return new NpcEdge(
					productId,
					npcPrice,
					(double) edgeSamples / size,
					medianMarginRatio,
					hours > 0.0d ? driftCoins / hours : 0.0d,
					hours,
					intervals,
					size);
		}
	}
}
