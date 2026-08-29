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
package jeff.skyblockflipper.core.recovery;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecoveryComponentCatalogTest {
	@Test
	void mapsGemstonesToBazaarWithExplicitRemovalCost() {
		RecoveryAttachment attachment = new RecoveryAttachment(RecoveryComponentKind.GEMSTONE,
				"COMBAT_0", "FINE_RUBY_GEM", 1L);
		RecoveryComponentCatalog.Entry entry = RecoveryComponentCatalog.find(attachment).orElseThrow();

		assertEquals(RecoveryExitVenue.BAZAAR, entry.exitVenue());
		assertEquals(10_000L, entry.removalCost().orElseThrow());
		assertFalse(entry.evidenceVersion().isBlank());
	}

	@Test
	void knownDrillIdentityStillFailsClosedWithoutCapturedRemovalCost() {
		RecoveryAttachment attachment = new RecoveryAttachment(RecoveryComponentKind.DRILL_ENGINE,
				"engine", "MITHRIL_DRILL_ENGINE", 1L);

		assertTrue(RecoveryComponentCatalog.find(attachment).orElseThrow().removalCost().isEmpty());
		assertTrue(RecoveryComponentCatalog.uncredited(attachment).warnings()
				.contains(RecoveryWarning.UNKNOWN_REMOVAL_COST));
	}

	@Test
	void unknownAndKindMismatchedIdsAreNotGuessed() {
		RecoveryAttachment unknown = new RecoveryAttachment(RecoveryComponentKind.GEMSTONE,
				"UNIVERSAL_0", "PERFECT_MOONSTONE_GEM", 1L);
		RecoveryAttachment wrongKind = new RecoveryAttachment(RecoveryComponentKind.FISHING_HOOK,
				"hook", "FINE_RUBY_GEM", 1L);

		assertTrue(RecoveryComponentCatalog.find(unknown).isEmpty());
		assertTrue(RecoveryComponentCatalog.find(wrongKind).isEmpty());
		assertTrue(RecoveryComponentCatalog.uncredited(unknown).warnings()
				.contains(RecoveryWarning.UNKNOWN_MAPPING));
	}
}
