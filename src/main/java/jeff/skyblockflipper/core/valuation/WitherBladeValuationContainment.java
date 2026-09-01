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

/**
 * Temporary loss containment while the Wither-scroll key passes its release gates.
 *
 * <p>Keep the gate in this one class so removing containment after the rolling holdouts is a small,
 * auditable change. Training continues under the corrected signature; only player-facing ordinary
 * and recovery auction valuations are suppressed.
 */
public final class WitherBladeValuationContainment {
	private static final boolean ACTIVE = true;

	private WitherBladeValuationContainment() {
	}

	public static boolean suppresses(DecodedItem item) {
		return ACTIVE && item != null && item.isScrollCapableBlade();
	}
}
