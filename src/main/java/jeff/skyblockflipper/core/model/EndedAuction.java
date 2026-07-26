package jeff.skyblockflipper.core.model;

import com.google.gson.annotations.SerializedName;

/**
 * One completed auction sale - a realized transaction, not a quote.
 *
 * <p>This is the ground truth the valuation model trains on. Active listings are contaminated by
 * exactly the mispricings we are hunting, so fitting to them teaches the model to agree with the
 * mistake.
 *
 * <p>{@code itemBytes} is kept as the raw base64 blob rather than decoded on ingest. Decoding is
 * the expensive part, the feature set we want to extract will change as the model improves, and
 * a stored blob can be re-parsed later. A stored decode cannot be un-decoded.
 *
 * @param auctionId unique id, used to de-duplicate overlapping polls
 * @param timestamp epoch millis when the sale completed
 * @param price     final sale price in coins
 * @param bin       true for buy-it-now, false for a bid auction
 */
public record EndedAuction(
		@SerializedName("auction_id") String auctionId,
		String seller,
		String buyer,
		long timestamp,
		long price,
		boolean bin,
		@SerializedName("item_bytes") String itemBytes
) {
}
