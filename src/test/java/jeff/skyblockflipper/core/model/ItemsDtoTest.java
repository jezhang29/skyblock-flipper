package jeff.skyblockflipper.core.model;

import com.google.gson.Gson;

import jeff.skyblockflipper.core.model.dto.ItemsDto;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the translation from the catalog's wire vocabulary to bazaar product ids.
 *
 * <p>The payload names an essence by bare type ({@code SPIDER}) while the bazaar trades it as
 * {@code ESSENCE_SPIDER}. Get that wrong and star costs do not come out wrong - they come out
 * missing, because the lookup finds no product, and a silently unpriceable star reads as an item
 * with no star cost at all.
 *
 * <p>The fixture is a trimmed capture of real entries, so it also fails if Hypixel restructures
 * {@code upgrade_costs}.
 */
class ItemsDtoTest {
	private static ItemCatalog catalog;

	@BeforeAll
	static void parseFixture() throws Exception {
		try (InputStream in = ItemsDtoTest.class.getResourceAsStream("/items-sample.json")) {
			ItemsDto dto = new Gson().fromJson(
					new InputStreamReader(in, StandardCharsets.UTF_8), ItemsDto.class);
			catalog = dto.toCatalog();
		}
	}

	@Test
	void keepsTheFieldsItAlreadyHad() {
		ItemCatalog.Entry melon = catalog.get("ENCHANTED_MELON_BLOCK").orElseThrow();

		assertEquals("Enchanted Melon", melon.name());
		assertEquals(51_200.0d, melon.npcPrice().orElseThrow(), 1e-9);
	}

	@Test
	void readsStackingSoATripCanBeSizedFromIt() {
		// Absent for almost everything, which has to read as stackable rather than as unknown.
		assertFalse(catalog.get("ENCHANTED_MELON_BLOCK").orElseThrow().unstackable());
		assertEquals(64, catalog.get("ENCHANTED_MELON_BLOCK").orElseThrow().stackSize());

		// Present and true for the 19 bazaar products with an NPC price that do not stack. An NPC
		// trip carries 36 of these against 2304 of the melons, which is the whole plan size.
		ItemCatalog.Entry tuner = catalog.get("TRANSMISSION_TUNER").orElseThrow();

		assertTrue(tuner.unstackable());
		assertEquals(1, tuner.stackSize());
		assertEquals(45_000.0d, tuner.npcPrice().orElseThrow(), 1e-9);
	}

	@Test
	void prefixesEssenceTypesIntoBazaarProductIds() {
		ItemCatalog.Entry arack = catalog.get("ARACK").orElseThrow();

		assertEquals(5, arack.maxStars());

		List<UpgradeCost.Ingredient> firstStar = arack.upgradeCosts().getFirst().ingredients();

		assertEquals(1, firstStar.size());
		assertEquals("ESSENCE_SPIDER", firstStar.getFirst().productId());
		assertEquals(15, firstStar.getFirst().amount());
	}

	@Test
	void carriesItemIngredientsThroughUnchanged() {
		// Hellfire Rod's first star wants two materials as well as essence, which is the case that
		// breaks any implementation assuming a star costs exactly one thing.
		List<UpgradeCost.Ingredient> firstStar = catalog.get("HELLFIRE_ROD").orElseThrow()
				.upgradeCosts().getFirst().ingredients();

		assertEquals(3, firstStar.size());
		assertTrue(firstStar.stream().anyMatch(i -> i.productId().equals("LUMP_OF_MAGMA")
						&& i.amount() == 20),
				"expected 20 LUMP_OF_MAGMA, got " + firstStar);
		assertTrue(firstStar.stream().anyMatch(i -> i.productId().equals("ESSENCE_CRIMSON")),
				"expected crimson essence alongside the materials, got " + firstStar);
	}

	@Test
	void readsMoreThanFiveStarsWhereMasterStarsAreDefined() {
		// The common case is five, so five is exactly what a wrong implementation would hard-code.
		assertEquals(10, catalog.get("HELLFIRE_ROD").orElseThrow().maxStars());
	}

	@Test
	void leavesUnstarrableItemsWithNoCosts() {
		assertEquals(0, catalog.get("ENCHANTED_MELON").orElseThrow().maxStars());
		assertTrue(catalog.get("ENCHANTED_MELON").orElseThrow().upgradeCosts().isEmpty());
	}
}
