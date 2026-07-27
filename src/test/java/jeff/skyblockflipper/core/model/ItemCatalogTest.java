package jeff.skyblockflipper.core.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The melon problem, in miniature.
 *
 * <p>Hypixel renamed the melons to match modern Minecraft, so {@code ENCHANTED_MELON_BLOCK} is now
 * called "Enchanted Melon" and {@code ENCHANTED_MELON} is "Enchanted Melon Slice". One is a 51k
 * item, the other a 341 one, and the bazaar search for the shorter name returns both.
 */
class ItemCatalogTest {
	private static final ItemCatalog MELONS = new ItemCatalog(Map.of(
			"ENCHANTED_MELON_BLOCK", new ItemCatalog.Entry("ENCHANTED_MELON_BLOCK", "Enchanted Melon", 51_200.0d),
			"ENCHANTED_MELON", new ItemCatalog.Entry("ENCHANTED_MELON", "Enchanted Melon Slice", 320.0d),
			"ENCHANTED_DIAMOND", new ItemCatalog.Entry("ENCHANTED_DIAMOND", "Enchanted Diamond", null)));

	@Test
	void reportsANameThatAnotherItemExtends() {
		assertEquals("Enchanted Melon Slice",
				MELONS.shadowedBy("ENCHANTED_MELON_BLOCK").orElseThrow());
	}

	@Test
	void aLongerNameIsNotShadowedByTheShorterOne() {
		// The relationship is one-directional: searching "Enchanted Melon Slice" cannot return
		// "Enchanted Melon", so there is nothing to warn about.
		assertTrue(MELONS.shadowedBy("ENCHANTED_MELON").isEmpty());
	}

	@Test
	void anUnrelatedNameIsUnambiguous() {
		assertTrue(MELONS.shadowedBy("ENCHANTED_DIAMOND").isEmpty());
	}

	@Test
	void identityNotesAlwaysCarryTheIdAndWarnOnlyWhenAmbiguous() {
		assertEquals(2, MELONS.identityNotes("ENCHANTED_MELON_BLOCK").size());
		assertEquals(List.of("Item id ENCHANTED_DIAMOND"), MELONS.identityNotes("ENCHANTED_DIAMOND"));
	}

	@Test
	void anUnknownIdStillGetsAName() {
		assertEquals("MYSTERY_ITEM", MELONS.displayName("MYSTERY_ITEM"));
		assertTrue(MELONS.shadowedBy("MYSTERY_ITEM").isEmpty());
	}
}
