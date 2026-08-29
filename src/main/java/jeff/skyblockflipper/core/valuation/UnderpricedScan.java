package jeff.skyblockflipper.core.valuation;

import jeff.skyblockflipper.core.api.ListingSink;
import jeff.skyblockflipper.core.item.DecodedItem;
import jeff.skyblockflipper.core.item.ItemDecoder;
import jeff.skyblockflipper.core.model.ActiveListing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Finds live listings priced below what their configuration actually sells for.
 *
 * <p>Two stages, because the expensive step has to be earned. Every listing is first checked
 * against the coarse name-and-rarity value, which needs no decoding; almost everything fails there
 * and its blob is dropped immediately. Only survivors get decoded, and then they have to clear the
 * bar a second time against the value of their exact signature.
 *
 * <p>That second check is the one that matters. The coarse value mixes every version of an item
 * together, so a bare helmet looks like a bargain next to sales of five-star recombobulated ones -
 * and that false positive is exactly the shape of a snipe that loses money. A listing that cannot
 * be matched to its exact configuration is dropped rather than guessed at.
 */
public final class UnderpricedScan implements ListingSink {
	/** A sweep that somehow found thousands of bargains has found a bug, not a market. */
	private static final int MAX_RESULTS = 200;

	private final FairValueModel model;
	private final double minDiscount;
	private final long maxPrice;

	private final List<PricedListing> found = new ArrayList<>();

	private int listingsSeen;
	private int decoded;
	private int rejectedOnExactValue;

	/**
	 * @param minDiscount how far under fair value a listing has to be before it is worth a look
	 * @param maxPrice    listings above this are not actionable, so not worth decoding either
	 */
	public UnderpricedScan(FairValueModel model, double minDiscount, long maxPrice) {
		this.model = model;
		this.minDiscount = minDiscount;
		this.maxPrice = maxPrice;
	}

	@Override
	public void offer(ActiveListing listing) {
		offerDecoded(listing, () -> ItemDecoder.decode(listing.itemBytes()));
	}

	/**
	 * Runs the unchanged ordinary scan while allowing a composed sweep to share one decoded blob.
	 * The supplier stays lazy, so the existing coarse rejection still avoids parsing almost every
	 * listing.
	 */
	public void offerDecoded(ActiveListing listing,
			Supplier<Optional<DecodedItem>> decodedItem) {
		listingsSeen++;

		if (listing.price() > maxPrice || found.size() >= MAX_RESULTS) {
			return;
		}

		Optional<ValueEstimate> rough = model.roughValueOf(listing.itemName(), listing.rarity());

		// Never sold in the window: there is nothing to call it cheap against.
		if (rough.isEmpty() || !isDiscounted(listing.price(), rough.get())) {
			return;
		}

		Optional<DecodedItem> item = decodedItem.get();

		if (item.isEmpty()) {
			return;
		}

		decoded++;
		Optional<ValueEstimate> exact = model.valueOf(item.get());

		if (exact.isEmpty() || !isDiscounted(listing.price(), exact.get())) {
			// The coarse hit was an illusion: this configuration is not actually cheap, or we
			// cannot price it at all.
			rejectedOnExactValue++;
			return;
		}

		found.add(new PricedListing(listing.withoutBlob(), item.get(), exact.get()));
	}

	/** Cheapest relative to value first. */
	public List<PricedListing> results() {
		return found.stream()
				.sorted(Comparator.comparingDouble(PricedListing::discount).reversed())
				.toList();
	}

	public int listingsSeen() {
		return listingsSeen;
	}

	/** How many blobs the sweep actually had to parse, which is the cost this design manages. */
	public int decoded() {
		return decoded;
	}

	/** Coarse hits that the exact check threw out - the false positives this avoids acting on. */
	public int rejectedOnExactValue() {
		return rejectedOnExactValue;
	}

	private boolean isDiscounted(long price, ValueEstimate value) {
		return price <= value.median() * (1.0d - minDiscount);
	}
}
