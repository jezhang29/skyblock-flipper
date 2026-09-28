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
package jeff.skyblockflipper.core.track;

import java.util.List;

/**
 * One item in a Hypixel menu, reduced to the parts a tracker could read.
 *
 * <p>{@code itemId} is the whole reason menu capture is worth doing: Hypixel stamps its own item id
 * into the stack's custom data, so a menu says {@code ENCHANTED_MELON_BLOCK} where chat only says
 * "Enchanted Melon". Chat's display names are ambiguous - 187 of 5549 item names are a strict prefix
 * of another - and this side-steps that entirely.
 *
 * @param index      slot position in the menu, which is how a parser will tell a row of orders from
 *                   the decoration around it
 * @param name       hover name with formatting codes stripped
 * @param lore       tooltip lines, stripped the same way; where the fill state of an order lives
 * @param customData the whole custom-data compound as SNBT, kept raw because step 0 does not yet
 *                   know which key matters and re-capturing costs another play session
 */
public record CapturedSlot(int index, String name, List<String> lore, String itemId, int count,
		String customData) {
	public CapturedSlot {
		lore = List.copyOf(lore);
	}
}
