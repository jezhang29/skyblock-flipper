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

import org.junit.jupiter.api.Test;

import java.util.List;

import static jeff.skyblockflipper.core.item.WitherScrollNbtFixtures.IMPLOSION;
import static jeff.skyblockflipper.core.item.WitherScrollNbtFixtures.SHADOW_WARP;
import static jeff.skyblockflipper.core.item.WitherScrollNbtFixtures.WITHER_SHIELD;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WitherScrollItemDecoderTest {
	private static DecodedItem decode(List<String> scrolls) {
		return ItemDecoder.fromRoot(WitherScrollNbtFixtures.hyperionWith(scrolls)).orElseThrow();
	}

	@Test
	void realisticNbtDistinguishesNoPartialAndCompleteSets() {
		DecodedItem none = ItemDecoder.decode(
				WitherScrollNbtFixtures.captured("none")).orElseThrow();
		DecodedItem one = ItemDecoder.decode(
				WitherScrollNbtFixtures.captured("one")).orElseThrow();
		DecodedItem two = decode(List.of(IMPLOSION, WITHER_SHIELD));
		DecodedItem full = ItemDecoder.decode(
				WitherScrollNbtFixtures.captured("full")).orElseThrow();

		assertEquals(List.of(), none.abilityScrolls());
		assertEquals(List.of(IMPLOSION), one.abilityScrolls());
		assertEquals(List.of(IMPLOSION, WITHER_SHIELD), two.abilityScrolls());
		assertTrue(full.hasCompleteAbilityScrollSet());
		assertFalse(two.hasCompleteAbilityScrollSet());

		assertTrue(none.signature().contains("abilityScrolls=none"));
		assertTrue(one.signature().contains("abilityScrolls=IMPLOSION_SCROLL"));
		assertTrue(two.signature().contains(
				"abilityScrolls=IMPLOSION_SCROLL,WITHER_SHIELD_SCROLL"));
		assertTrue(full.signature().contains("abilityScrolls=IMPLOSION_SCROLL,"
				+ "SHADOW_WARP_SCROLL,WITHER_SHIELD_SCROLL"));
		assertNotEquals(none.signature(), full.signature());
	}

	@Test
	void orderingDoesNotChangeTheNormalizedCollectionOrSignature() {
		DecodedItem first = decode(List.of(WITHER_SHIELD, IMPLOSION, SHADOW_WARP));
		DecodedItem second = decode(List.of(SHADOW_WARP, WITHER_SHIELD, IMPLOSION));

		assertEquals(List.of(IMPLOSION, SHADOW_WARP, WITHER_SHIELD), first.abilityScrolls());
		assertEquals(first.abilityScrolls(), second.abilityScrolls());
		assertEquals(first.signature(), second.signature());
		assertThrows(UnsupportedOperationException.class,
				() -> first.abilityScrolls().add(IMPLOSION));
	}

	@Test
	void emptyListAndAbsentFieldBothMeanNoScrolls() {
		DecodedItem absent = ItemDecoder.fromRoot(
				WitherScrollNbtFixtures.unscrolledHyperion()).orElseThrow();
		DecodedItem empty = decode(List.of());

		assertEquals(absent.abilityScrolls(), empty.abilityScrolls());
		assertEquals(absent.signature(), empty.signature());
	}

	@Test
	void malformedDuplicateAndUnknownValuesFailClosed() {
		assertTrue(ItemDecoder.fromRoot(WitherScrollNbtFixtures.hyperionWith("IMPLOSION_SCROLL"))
				.isEmpty());
		assertTrue(ItemDecoder.fromRoot(WitherScrollNbtFixtures.hyperionWith(List.of(1, 2)))
				.isEmpty());
		assertTrue(ItemDecoder.fromRoot(
				WitherScrollNbtFixtures.hyperionWith(List.of(IMPLOSION, IMPLOSION))).isEmpty());
		assertTrue(ItemDecoder.fromRoot(WitherScrollNbtFixtures.hyperionWith(
				List.of(IMPLOSION, "FUTURE_SCROLL"))).isEmpty());
	}
}
