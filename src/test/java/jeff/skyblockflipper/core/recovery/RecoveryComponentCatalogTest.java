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
