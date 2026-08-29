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

import java.util.Locale;

/**
 * Skyblock item rarity.
 *
 * <p>Note that the rarity an item reports is its <em>current</em> rarity, already including a
 * recombobulator bump. {@link DecodedItem#recombobulated()} is what tells you whether it was born
 * there or bought its way up, and the two are worth different amounts.
 */
public enum Rarity {
	COMMON,
	UNCOMMON,
	RARE,
	EPIC,
	LEGENDARY,
	MYTHIC,
	DIVINE,
	SUPREME,
	SPECIAL,
	VERY_SPECIAL,
	/** Nothing in the blob said, which is a fact about the item, not an error. */
	UNKNOWN;

	/**
	 * Reads {@code components["minecraft:tooltip_style"]}, which looks like
	 * {@code hypixel_skyblock:mythic}.
	 */
	public static Rarity fromTooltipStyle(String style) {
		if (style == null) {
			return UNKNOWN;
		}

		int colon = style.indexOf(':');
		return fromName(colon < 0 ? style : style.substring(colon + 1));
	}

	/** Matches a bare rarity name, as pet tiers are written. */
	public static Rarity fromName(String name) {
		for (Rarity rarity : values()) {
			if (rarity != UNKNOWN && rarity.name().equalsIgnoreCase(name)) {
				return rarity;
			}
		}

		return UNKNOWN;
	}

	/**
	 * Reads the rarity out of the last line of an item's lore, for the items that carry no tooltip
	 * style. The line reads like {@code EPIC DUNGEON HELMET}, and on mythics arrives wrapped in
	 * obfuscation codes, so the rarity word is looked for anywhere in the line rather than at the
	 * front. Colour codes must already be stripped.
	 */
	public static Rarity fromLoreLine(String line) {
		if (line == null || line.isBlank()) {
			return UNKNOWN;
		}

		String upper = line.toUpperCase(Locale.ROOT);

		// Checked first: "SPECIAL" is a prefix of the same line that says "VERY SPECIAL".
		if (upper.contains("VERY SPECIAL")) {
			return VERY_SPECIAL;
		}

		for (String word : upper.split("[^A-Z_]+")) {
			Rarity rarity = fromName(word);

			if (rarity != UNKNOWN) {
				return rarity;
			}
		}

		return UNKNOWN;
	}
}
