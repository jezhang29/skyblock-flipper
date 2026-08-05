package jeff.skyblockflipper.core.valuation;

import jeff.skyblockflipper.core.item.DecodedItem;
import jeff.skyblockflipper.core.item.ItemDecoder;
import jeff.skyblockflipper.core.item.Rarity;
import jeff.skyblockflipper.core.model.ActiveListing;
import jeff.skyblockflipper.core.model.EndedAuction;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What items are worth, learned from sales that actually happened.
 *
 * <p>Built from the sales tape rather than from active listings, and that is the load-bearing
 * choice. Active listings are contaminated by exactly the mispricings this is meant to find:
 * fitting to them teaches the model to agree with the mistake, and then the mistake looks correctly
 * priced. Only realized sales are evidence.
 *
 * <p>Buy-it-now sales only. An auction that ended on one uncontested bid says what nobody else was
 * awake for, not what the item is worth.
 *
 * <p>Two indices, because the two questions have different costs. The exact index is keyed on the
 * full decoded signature and is the only thing allowed to justify a purchase. The coarse index is
 * keyed on name and rarity, which can be read off a live listing without decoding it, and exists
 * purely to throw away the ~46,000 listings that are nowhere near mispriced before anything
 * expensive happens.
 */
public final class FairValueModel {
	private final Map<String, ValueEstimate> exact;
	private final Map<String, ValueEstimate> coarse;
	private final Map<String, ValueEstimate> perBid;
	private final int salesConsidered;
	private final Duration window;
	private final Keying keying;

	private FairValueModel(Map<String, ValueEstimate> exact, Map<String, ValueEstimate> coarse,
			Map<String, ValueEstimate> perBid, int salesConsidered, Duration window, Keying keying) {
		this.exact = Map.copyOf(exact);
		this.coarse = Map.copyOf(coarse);
		this.perBid = Map.copyOf(perBid);
		this.salesConsidered = salesConsidered;
		this.window = window;
		this.keying = keying;
	}

	public static FairValueModel empty() {
		return new FairValueModel(Map.of(), Map.of(), Map.of(), 0, Duration.ZERO, Keying.PRODUCTION);
	}

	/**
	 * @param sales realized sales, typically a replay of the tape
	 * @param window how far back to look. Long enough for sample counts to mean something, short
	 *               enough that a price move a week ago is not still being averaged in
	 */
	public static FairValueModel from(List<EndedAuction> sales, Instant now, Duration window) {
		Builder builder = builder(now, window);
		sales.forEach(builder::add);
		return builder.build();
	}

	public static Builder builder(Instant now, Duration window) {
		return new Builder(now, window, Keying.PRODUCTION);
	}

	/**
	 * Trains under a keying other than the one that ships.
	 *
	 * <p>Only a backtest has any business calling this. The model it returns must be read back
	 * through {@link #valueOf(DecodedItem)}, which uses the same keying it was built with - pricing a
	 * holdout under one keying against an index built under another measures nothing.
	 */
	public static Builder builder(Instant now, Duration window, Keying keying) {
		return new Builder(now, window, keying);
	}

	/**
	 * Accumulates sales one at a time.
	 *
	 * <p>The tape holds a few hundred thousand sales over a couple of days, each carrying a
	 * kilobyte and a half of raw blob. Reading them into a list first would mean several hundred
	 * megabytes on the heap to end up with a few thousand medians, so the tape is streamed through
	 * here instead and each sale is decoded and dropped.
	 */
	public static final class Builder {
		/**
		 * Recent sales per configuration to keep. Well past the point of diminishing returns for a
		 * median, and it bounds the memory a single popular item can take.
		 */
		private static final int MAX_SAMPLES_PER_KEY = 200;

		private final Map<String, List<Double>> bySignature = new HashMap<>();
		private final Map<String, List<Double>> byCoarseKey = new HashMap<>();
		private final Map<String, List<Double>> bidRatios = new HashMap<>();
		private final long cutoff;
		private final Duration window;
		private final Keying keying;

		private int considered;

		private Builder(Instant now, Duration window, Keying keying) {
			this.cutoff = now.minus(window).toEpochMilli();
			this.window = window;
			this.keying = keying;
		}

		public void add(EndedAuction sale) {
			if (sale == null || !sale.bin() || sale.timestamp() < cutoff) {
				return;
			}

			Optional<DecodedItem> decoded = ItemDecoder.decode(sale.itemBytes());

			if (decoded.isEmpty()) {
				return;
			}

			DecodedItem item = decoded.get();
			// Stacked sales are priced for the stack; everything downstream works per unit.
			add(item, (double) sale.price() / Math.max(1, item.count()), sale.timestamp());
		}

		/**
		 * Accounts for a sale already decoded and already reduced to a unit price.
		 *
		 * <p>Split out for the one caller that has to decode a sale before it knows whether to train
		 * on it - a backtest filtering to the item ids that carry the attribute under measurement.
		 * Feeding it the raw sale instead would decode every blob twice, and a decode is the
		 * expensive part of a tape replay.
		 */
		public void add(DecodedItem item, double unitPrice, long timestamp) {
			if (item == null || timestamp < cutoff) {
				return;
			}

			// Every rung, not just the signature. A sale is evidence about the exact configuration
			// that sold and about every wider description of it, and the wider ones are what stop
			// a thinly-traded configuration having no valuation at all.
			keying.keys(item).forEach(key -> record(bySignature, key, unitPrice));

			// What this sale says about the item per coin bid for it, which is a statement about
			// every other bid on the same configuration.
			if (item.hasWinningBid()) {
				keying.bidRatioKey(item)
						.ifPresent(key -> record(bidRatios, key, unitPrice / item.winningBid()));
			}

			record(byCoarseKey, ActiveListing.coarseKey(item.displayName(), item.rarity()), unitPrice);
			considered++;
		}

		public FairValueModel build() {
			double windowHours = window.toMillis() / 3_600_000.0d;

			// EXACT here is provisional; valueOf re-labels each hit with how it was actually
			// matched, because the same key can be an exact signature for one item and a widened
			// one for another.
			return new FairValueModel(
					estimates(bySignature, windowHours, ValueEstimate.Basis.EXACT),
					estimates(byCoarseKey, windowHours, ValueEstimate.Basis.COARSE),
					estimates(bidRatios, windowHours, ValueEstimate.Basis.EXACT),
					considered,
					window,
					keying);
		}

		private static void record(Map<String, List<Double>> index, String key, double price) {
			List<Double> prices = index.computeIfAbsent(key, k -> new ArrayList<>());

			// Oldest out: a stale price should not outvote a current one on a busy item.
			if (prices.size() >= MAX_SAMPLES_PER_KEY) {
				prices.removeFirst();
			}

			prices.add(price);
		}
	}

	/**
	 * The estimate that may be used to justify buying this item.
	 *
	 * <p>Walks the item's keys from most specific to least, taking the first with enough sales
	 * behind it. For everything but a pet that is one key, the exact signature, and the walk ends
	 * where it always did. A pet tries its own level first, then its level band, then any level -
	 * each rung a real widening, each labelled as such so the confidence it earns is discounted.
	 *
	 * <p>Before any of that, the one item whose price is a number written on it rather than a pool
	 * of sales: a Midas weapon, whose stats scale with the coins burned at the Dark Auction. Its
	 * signature says nothing about the bid, so the pooled median quotes a 3,000,000 coin staff and a
	 * 100,000,000 coin one the same. The ratio index answers the question the pool cannot -
	 * <b>what does this configuration fetch per coin bid</b> - and multiplying that by this item's own
	 * bid costs no coverage at all, because it is the same sales under the same key. On a 24h holdout
	 * of the item ids that carry a bid it took sales valued at 2x or more of what they fetched from
	 * 142 in 512 to 11, and the median absolute log error from 0.588 to 0.242, at identical coverage.
	 *
	 * <p>Then one exception, unchanged: an item carrying no attributes at all has nothing the
	 * coarse key could have missed, so name and rarity describe it completely. Without that rule
	 * the coarse index would happily price a five-star recombobulated helmet off sales of the bare
	 * one and call the difference profit. That test, and the clause-by-clause evidence behind it,
	 * lives in {@link Keying#PRODUCTION}.
	 */
	public Optional<ValueEstimate> valueOf(DecodedItem item) {
		if (item.hasWinningBid()) {
			ValueEstimate perCoin = keying.bidRatioKey(item).map(perBid::get).orElse(null);

			if (perCoin != null && perCoin.isUsable()) {
				return Optional.of(perCoin.scaledBy(item.winningBid()));
			}
		}

		List<String> keys = keying.keys(item);

		for (int rung = 0; rung < keys.size(); rung++) {
			ValueEstimate match = exact.get(keys.get(rung));

			if (match != null && match.isUsable()) {
				boolean fullMatch = rung == 0 && item.isFullyDescribed();

				return Optional.of(match.withBasis(
						fullMatch ? ValueEstimate.Basis.EXACT : ValueEstimate.Basis.BANDED));
			}
		}

		if (!keying.isBare(item)) {
			return Optional.empty();
		}

		return Optional.ofNullable(coarse.get(ActiveListing.coarseKey(item.displayName(), item.rarity())))
				.filter(ValueEstimate::isUsable);
	}

	/** The rough value of a listing, for pruning before anything is decoded. */
	public Optional<ValueEstimate> roughValueOf(String itemName, Rarity rarity) {
		return Optional.ofNullable(coarse.get(ActiveListing.coarseKey(itemName, rarity)))
				.filter(ValueEstimate::isUsable);
	}

	public boolean isEmpty() {
		return coarse.isEmpty();
	}

	public int salesConsidered() {
		return salesConsidered;
	}

	public int pricedConfigurations() {
		return exact.size();
	}

	public Duration window() {
		return window;
	}

	private static Map<String, ValueEstimate> estimates(Map<String, List<Double>> prices,
			double windowHours, ValueEstimate.Basis basis) {
		Map<String, ValueEstimate> out = new HashMap<>();

		prices.forEach((key, values) -> {
			if (values.size() >= ValueEstimate.MIN_SAMPLES) {
				out.put(key, ValueEstimate.of(key, values, windowHours, basis));
			}
		});

		return out;
	}
}
