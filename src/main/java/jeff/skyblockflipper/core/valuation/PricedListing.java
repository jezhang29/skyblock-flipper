package jeff.skyblockflipper.core.valuation;

import jeff.skyblockflipper.core.item.DecodedItem;
import jeff.skyblockflipper.core.model.ActiveListing;

/**
 * A live listing that survived being checked against what its exact configuration actually sells
 * for.
 *
 * <p>The blob is dropped by this point: it has already been decoded into {@code item}, and keeping
 * it would hold a kilobyte and a half per candidate for no reason.
 */
public record PricedListing(ActiveListing listing, DecodedItem item, ValueEstimate value) {
	/** How far below fair value it is listed, as a fraction. */
	public double discount() {
		return value.median() <= 0.0d ? 0.0d : 1.0d - listing.price() / value.median();
	}
}
