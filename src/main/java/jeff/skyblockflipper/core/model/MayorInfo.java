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

import java.util.List;

/**
 * The sitting mayor and minister.
 *
 * <p>Matters for two reasons: Derpy quadruples every auction house fee, which makes high-value
 * flips actively loss-making, and mayors shift demand across whole item categories on a schedule
 * that is known in advance.
 *
 * @param key  stable identifier, e.g. {@code derpy}, {@code pets}, {@code diana}
 * @param name display name, e.g. {@code Diana}
 */
public record MayorInfo(String key, String name, List<String> perks, String ministerName) {
	private static final String DERPY_KEY = "derpy";

	public MayorInfo {
		perks = List.copyOf(perks);
	}

	public static MayorInfo unknown() {
		return new MayorInfo("", "Unknown", List.of(), "");
	}

	/**
	 * True while Derpy is in office, which multiplies all auction house fees by four. Treat this
	 * as a hard stop on expensive auction flips rather than a margin adjustment.
	 *
	 * <p>Checks both key and display name deliberately. Mayor keys are theme-based rather than
	 * name-based - Diana's key is {@code pets}, not {@code diana} - and Derpy's key could not be
	 * confirmed against the live API while another mayor held office. Matching the name as well
	 * means this still fires correctly if the key turns out to be something else.
	 */
	public boolean isDerpy() {
		return DERPY_KEY.equalsIgnoreCase(key) || DERPY_KEY.equalsIgnoreCase(name);
	}

	public boolean isKnown() {
		return !key.isEmpty();
	}
}
