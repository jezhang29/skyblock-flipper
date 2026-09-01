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

import jeff.skyblockflipper.core.nbt.NbtCompound;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UnreadAttributeProbeRenderingTest {
	@Test
	void listValuesNameTheCanonicalScrollSet() {
		NbtCompound first = new NbtCompound(Map.of("ability_scroll", List.of(
				"WITHER_SHIELD_SCROLL", "IMPLOSION_SCROLL", "SHADOW_WARP_SCROLL")));
		NbtCompound reordered = new NbtCompound(Map.of("ability_scroll", List.of(
				"SHADOW_WARP_SCROLL", "WITHER_SHIELD_SCROLL", "IMPLOSION_SCROLL")));

		String expected = "[IMPLOSION_SCROLL,SHADOW_WARP_SCROLL,WITHER_SHIELD_SCROLL]";
		assertEquals(expected, UnreadAttributeProbeTest.value(first, "ability_scroll"));
		assertEquals(expected, UnreadAttributeProbeTest.value(reordered, "ability_scroll"));
	}

	@Test
	void anEmptyListIsNotCollapsedToANumericFallback() {
		NbtCompound extra = new NbtCompound(Map.of("ability_scroll", List.of()));

		assertEquals("[]", UnreadAttributeProbeTest.value(extra, "ability_scroll"));
	}
}
