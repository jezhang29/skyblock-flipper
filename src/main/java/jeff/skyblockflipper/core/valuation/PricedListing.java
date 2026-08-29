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
