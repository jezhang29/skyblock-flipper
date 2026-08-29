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
