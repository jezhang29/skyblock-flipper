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

			// Every rung, not just the signature. A sale is evidence about the exact configuration
			// that sold and about every wider description of it, and the wider ones are what stop
			// a thinly-traded configuration having no valuation at all.
			item.valuationKeys().forEach(key -> record(bySignature, key, unitPrice));
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
	 * <p>Walks the item's keys from most specific to least, taking the first with enough sales
	 * behind it. For everything but a pet that is one key, the exact signature, and the walk ends
	 * where it always did. A pet tries its own level first, then its level band, then any level -
	 * each rung a real widening, each labelled as such so the confidence it earns is discounted.
	 *
	 * <p>Then one exception, unchanged: an item carrying no attributes at all has nothing the
	 * coarse key could have missed, so name and rarity describe it completely. Without that rule
	 * the coarse index would happily price a five-star recombobulated helmet off sales of the bare
	 * one and call the difference profit.
	 */
	public Optional<ValueEstimate> valueOf(DecodedItem item) {
		List<String> keys = item.valuationKeys();

		for (int rung = 0; rung < keys.size(); rung++) {
			ValueEstimate match = exact.get(keys.get(rung));

			if (match != null && match.isUsable()) {
				boolean fullMatch = rung == 0 && item.isFullyDescribed();

				return Optional.of(match.withBasis(
						fullMatch ? ValueEstimate.Basis.EXACT : ValueEstimate.Basis.BANDED));
			}
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

	/**
	 * Nothing was added to this item, so there is nothing a name-and-rarity match could miss.
	 *
	 * <p>Attribute rolls count even though nobody added them by hand. An attributed item otherwise
	 * carries no attributes at all, so without this line it reads as bare and gets priced off the
	 * coarse pool - which for {@code CRIMSON_BOOTS} mixes 1.9M bare sales with 16M rolled ones and
	 * calls the gap a snipe.
	 *
	 * <p>A rune counts for the same reason. It costs a standalone rune nothing, because Hypixel
	 * writes the rune and its tier into the display name the coarse key is built from, so the two
	 * keys select the same sales anyway. What it stops is a runed sword falling back to a pool of
	 * bare ones.
	 *
	 * <p>A potion is excluded outright, like a pet, and unlike a rune it is not free to exclude. The
	 * display name the coarse key is built from does state the effect, the tier and whether it
	 * splashes, so most of the signature is recoverable from it - but it does not state the alchemy
	 * perks. An enhanced, extended Speed VIII potion is named exactly "Speed VIII Potion", and on the
	 * tape it sells for 82,525 coins against 58,999 for the plain one wearing the same name.
	 *
	 * <p>A dungeon quality roll is the attribute-roll bug again, and worse. Nothing about the drop's
	 * tier reaches its display name, so a maxed tier-10 {@code SKELETON_MASTER_CHESTPLATE} with no
	 * enchantments on it would read as bare and price off a pool whose median is a tier-7 at
	 * 2,000,000 coins - against the 113,000,000 the tier-10s actually fetch.
	 */
	private static boolean isBare(DecodedItem item) {
		return !item.isPet()
				&& !item.isPotion()
				&& !item.hasQuality()
				&& item.stars() == 0
				&& !item.recombobulated()
				&& item.hotPotatoBooks() == 0
				&& item.enchantments().isEmpty()
				&& item.gemstones().isEmpty()
				&& item.attributes().isEmpty()
				&& item.runes().isEmpty();
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
