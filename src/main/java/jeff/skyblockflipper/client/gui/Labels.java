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
package jeff.skyblockflipper.client.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Fitting text to a space that is measured in pixels rather than characters.
 *
 * <p>A character budget is the wrong unit for both of these: "Whipped Magma Cream" and
 * "IIIIIIIIIIIIIIIIIII" are the same length and nothing like the same width, and the flip screen
 * draws under a zoom factor that is chosen from the window size, so nothing here can be tuned to a
 * character count that holds at one GUI scale.
 *
 * <p>Cutting is the answer for a name, which has no shorter form. Dropping whole clauses is the
 * answer for a hint, because half a sentence ending in "explains every c" is not a shorter hint,
 * it is a bug on screen.
 */
final class Labels {
	private static final String ELLIPSIS = "...";

	private Labels() {
	}

	/**
	 * @return {@code text}, or as much of it as fits followed by an ellipsis
	 */
	static String fit(Font font, String text, int available) {
		if (font.width(Component.literal(text)) <= available) {
			return text;
		}

		int budget = available - font.width(Component.literal(ELLIPSIS));

		if (budget <= 0) {
			return "";
		}

		int end = text.length();

		while (end > 0 && font.width(Component.literal(text.substring(0, end))) > budget) {
			end--;
		}

		return text.substring(0, end) + ELLIPSIS;
	}

	/**
	 * Joins as many clauses as fit, in order, and drops the rest.
	 *
	 * <p>Ordered most useful first by the caller, so what survives on a cramped screen is the part
	 * worth keeping. The first clause is kept whether it fits or not - cut to fit if it has to be -
	 * because a hint row that renders empty looks like something failed.
	 */
	static String join(Font font, List<String> clauses, int available) {
		if (clauses.isEmpty()) {
			return "";
		}

		StringBuilder out = new StringBuilder(fit(font, clauses.getFirst(), available));

		for (String clause : clauses.subList(1, clauses.size())) {
			String candidate = out + " " + clause;

			if (font.width(Component.literal(candidate)) > available) {
				break;
			}

			out = new StringBuilder(candidate);
		}

		return out.toString();
	}
}
