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
package jeff.skyblockflipper.core.item;

import jeff.skyblockflipper.core.recovery.RecoveryMetadata;

import java.util.Objects;

/** Ordinary valuation decode and recovery metadata derived from the same parsed NBT tree. */
public record DetailedDecodedItem(DecodedItem item, RecoveryMetadata recovery) {
	public DetailedDecodedItem {
		Objects.requireNonNull(item, "item");
		Objects.requireNonNull(recovery, "recovery");
	}
}
