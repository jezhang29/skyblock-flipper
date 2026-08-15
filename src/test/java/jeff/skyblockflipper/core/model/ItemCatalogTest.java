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

	/**
	 * The catalog that made this necessary: a player reading the bazaar sees "Nether Wart
	 * Distillate" and no amount of care turns that into {@code NETHER_STALK_DISTILLATE}.
	 */
	private static final ItemCatalog WARTS = new ItemCatalog(Map.of(
			"NETHER_STALK_DISTILLATE",
			new ItemCatalog.Entry("NETHER_STALK_DISTILLATE", "Nether Wart Distillate", null),
			"BLAZE_ROD_DISTILLATE",
			new ItemCatalog.Entry("BLAZE_ROD_DISTILLATE", "Blaze Rod Distillate", null),
			"REVENANT_CATALYST",
			new ItemCatalog.Entry("REVENANT_CATALYST", "Revenant Catalyst", null)));

	@Test
	void findsAnItemByTheNameInTheGameRatherThanTheIdBehindIt() {
		assertEquals("NETHER_STALK_DISTILLATE",
				WARTS.find("nether wart distillate").only().orElseThrow());
	}

	@Test
	void findsAnItemByItsIdWhateverTheCase() {
		assertEquals("REVENANT_CATALYST", WARTS.find("revenant_catalyst").only().orElseThrow());
		assertEquals("REVENANT_CATALYST", WARTS.find("Revenant Catalyst").only().orElseThrow());
	}

	@Test
	void findsAnItemFromPartOfItsName() {
		assertEquals("NETHER_STALK_DISTILLATE", WARTS.find("nether wart").only().orElseThrow());
	}

	@Test
	void wordsMayBeTypedInAnyOrder() {
		assertEquals("NETHER_STALK_DISTILLATE", WARTS.find("distillate wart").only().orElseThrow());
	}

	@Test
	void refusesToChooseBetweenTwoItemsAndOffersBoth() {
		ItemCatalog.Lookup found = WARTS.find("distillate");

		assertTrue(found.only().isEmpty());
		assertEquals(List.of("BLAZE_ROD_DISTILLATE", "NETHER_STALK_DISTILLATE"), found.candidates());
	}

	@Test
	void anExactNameWinsOverTheItemThatMerelyExtendsIt() {
		// The melon problem, asked the other way round: "Enchanted Melon" is one item's whole name
		// and another's prefix, and the whole name is the answer rather than a tie.
		assertEquals("ENCHANTED_MELON_BLOCK", MELONS.find("Enchanted Melon").only().orElseThrow());
	}

	@Test
	void aQueryThatOnlyMatchesLooselyResolvesToNothing() {
		assertTrue(MELONS.find("melon").only().isEmpty());
		assertEquals(2, MELONS.find("melon").candidates().size());
	}

	@Test
	void aRestrictedSearchSeesOnlyWhatTheCallerOffered() {
		assertEquals("BLAZE_ROD_DISTILLATE",
				WARTS.find("distillate", List.of("BLAZE_ROD_DISTILLATE")).only().orElseThrow());
	}

	@Test
	void searchesIdsThatTheItemResourceHasNeverHeardOf() {
		// The bazaar names products before the catalog is fetched, and a probe still has to work.
		ItemCatalog.Lookup found = ItemCatalog.empty()
				.find("catalyst", List.of("REVENANT_CATALYST", "ENCHANTED_DIAMOND"));

		assertEquals("REVENANT_CATALYST", found.only().orElseThrow());
	}

	@Test
	void nothingTypedFindsNothing() {
		assertTrue(WARTS.find("  ").isEmpty());
		assertTrue(WARTS.find(null).isEmpty());
		assertTrue(WARTS.find("wobbegong").isEmpty());
	}
}
