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
package jeff.skyblockflipper.core.model.dto;

import jeff.skyblockflipper.core.model.MayorInfo;

import java.util.ArrayList;
import java.util.List;

/** Wire format of {@code /v2/resources/skyblock/election}. */
public final class MayorDto {
	public boolean success;
	public MayorEntry mayor;

	public static final class MayorEntry {
		public String key;
		public String name;
		public List<Perk> perks;
		public Minister minister;
	}

	public static final class Perk {
		public String name;
	}

	public static final class Minister {
		public String name;
	}

	public MayorInfo toMayorInfo() {
		if (mayor == null || mayor.key == null) {
			return MayorInfo.unknown();
		}

		List<String> perkNames = new ArrayList<>();

		if (mayor.perks != null) {
			for (Perk perk : mayor.perks) {
				if (perk != null && perk.name != null) {
					perkNames.add(perk.name);
				}
			}
		}

		String minister = mayor.minister != null && mayor.minister.name != null ? mayor.minister.name : "";

		return new MayorInfo(mayor.key, mayor.name != null ? mayor.name : mayor.key, perkNames, minister);
	}
}
