package jeff.skyblockflipper.core.model;

import jeff.skyblockflipper.core.item.Rarity;

/**
 * One buy-it-now listing currently on the auction house.
 *
 * <p>{@code itemName} and {@code rarity} come straight off the listing without touching
 * {@code item_bytes}, which is the whole point: there are ~46,000 live BINs and decoding all of
 * them every minute to find the handful that are mispriced would cost more than the flips are
 * worth. The name already carries the reforge and the stars, so it is enough to look up a rough
 * value and throw away everything that is obviously priced correctly.
 *
 * @param price     the BIN price. Hypixel reports it as {@code starting_bid}; a BIN never has bids
 * @param itemBytes the raw blob, decoded only for the few listings worth a closer look
 */
public record ActiveListing(
		String uuid,
		String itemName,
		Rarity rarity,
		long price,
		String itemBytes
) {
	/**
	 * The grouping key that can be computed without decoding anything. Coarser than a decoded
	 * signature - it cannot see enchantments, gemstones or hot potato books - so it is only ever
	 * used to prune, never to justify a purchase.
	 */
	public String coarseKey() {
		return coarseKey(itemName, rarity);
	}

	public static String coarseKey(String itemName, Rarity rarity) {
		return itemName + "|" + rarity.name();
	}

	/** Drops the blob once a listing is known not to be interesting, so a sweep is not 70MB of heap. */
	public ActiveListing withoutBlob() {
		return new ActiveListing(uuid, itemName, rarity, price, "");
	}
}
