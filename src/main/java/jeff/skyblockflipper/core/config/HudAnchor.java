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
package jeff.skyblockflipper.core.config;

import java.util.Arrays;
import java.util.List;

/**
 * Which corner the HUD overlay hangs from.
 *
 * <p>Stored in the config as a string rather than as an enum-typed field: Gson turns an
 * unrecognised enum name into {@code null} without complaining, and a null here would NPE on
 * every frame rather than on load.
 */
public enum HudAnchor {
	TOP_LEFT,
	TOP_RIGHT,
	BOTTOM_LEFT,
	BOTTOM_RIGHT;

	/** Falls back to the top-left corner for anything unrecognised, including null. */
	public static HudAnchor parse(String name) {
		if (name == null) {
			return TOP_LEFT;
		}

		for (HudAnchor anchor : values()) {
			if (anchor.name().equalsIgnoreCase(name.trim())) {
				return anchor;
			}
		}

		return TOP_LEFT;
	}

	/** The choices a settings UI offers, in the order they are declared. */
	public static List<String> names() {
		return Arrays.stream(values()).map(HudAnchor::name).toList();
	}

	public boolean isRight() {
		return this == TOP_RIGHT || this == BOTTOM_RIGHT;
	}

	public boolean isBottom() {
		return this == BOTTOM_LEFT || this == BOTTOM_RIGHT;
	}
}
