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
	private final int salesConsidered;
	private final Duration window;

	private FairValueModel(Map<String, ValueEstimate> exact, Map<String, ValueEstimate> coarse,
			int salesConsidered, Duration window) {
		this.exact = Map.copyOf(exact);
		this.coarse = Map.copyOf(coarse);
		this.salesConsidered = salesConsidered;
		this.window = window;
	}

	public static FairValueModel empty() {
		return new FairValueModel(Map.of(), Map.of(), 0, Duration.ZERO);
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
		return new Builder(now, window);
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
		private final long cutoff;
		private final Duration window;

		private int considered;

		private Builder(Instant now, Duration window) {
			this.cutoff = now.minus(window).toEpochMilli();
			this.window = window;
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
			double unitPrice = (double) sale.price() / Math.max(1, item.count());

			record(bySignature, item.signature(), unitPrice);
			record(byCoarseKey, ActiveListing.coarseKey(item.displayName(), item.rarity()), unitPrice);
			considered++;
		}

		public FairValueModel build() {
			double windowHours = window.toMillis() / 3_600_000.0d;

			return new FairValueModel(
					estimates(bySignature, windowHours, true),
					estimates(byCoarseKey, windowHours, false),
					considered,
					window);
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
	 * <p>Exact signature matches only, with one exception: an item carrying no attributes at all
	 * has nothing the coarse key could have missed, so name and rarity describe it completely.
	 * Without that rule the coarse index would happily price a five-star recombobulated helmet off
	 * sales of the bare one and call the difference profit.
	 */
	public Optional<ValueEstimate> valueOf(DecodedItem item) {
		ValueEstimate exactMatch = exact.get(item.signature());

		if (exactMatch != null && exactMatch.isUsable()) {
			return Optional.of(exactMatch);
		}

		if (!isBare(item)) {
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

	/** Nothing was added to this item, so there is nothing a name-and-rarity match could miss. */
	private static boolean isBare(DecodedItem item) {
		return !item.isPet()
				&& item.stars() == 0
				&& !item.recombobulated()
				&& item.hotPotatoBooks() == 0
				&& item.enchantments().isEmpty()
				&& item.gemstones().isEmpty();
	}

	private static Map<String, ValueEstimate> estimates(Map<String, List<Double>> prices,
			double windowHours, boolean exact) {
		Map<String, ValueEstimate> out = new HashMap<>();

		prices.forEach((key, values) -> {
			if (values.size() >= ValueEstimate.MIN_SAMPLES) {
				out.put(key, ValueEstimate.of(key, values, windowHours, exact));
			}
		});

		return out;
	}
}
