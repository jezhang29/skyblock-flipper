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
	void readsTheStackingFlagWhereTheResourceSetsIt() {
		// Absent for almost everything, including items that really do not stack, which is why
		// Stacking asks the order book instead. See StackingTest.
		assertFalse(catalog.get("ENCHANTED_MELON_BLOCK").orElseThrow().unstackable());

		ItemCatalog.Entry tuner = catalog.get("TRANSMISSION_TUNER").orElseThrow();

		assertTrue(tuner.unstackable());
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

	@Test
	void namesEssencesNoItemUsesAsAStarIngredient() {
		// ESSENCE_SAFARI and ESSENCE_FOSSIL trade on the bazaar but appear in no upgrade_costs, so
		// per-item discovery never sees them. The bundled essence set names them anyway. The fixture
		// carries neither, so these entries can only have come from the bundle.
		assertEquals("Safari Essence", catalog.displayName("ESSENCE_SAFARI"));
		assertEquals("Fossil Essence", catalog.displayName("ESSENCE_FOSSIL"));
	}

	@Test
	void namesTheShardsTheEndpointOmits() {
		// The live resource lists none of the 320 SHARD_* the bazaar trades, so without the bundled
		// names every fusion view prints the raw id. The fixture carries no shards either, so these
		// entries can only have come from FusionTable.
		assertEquals("Grove", catalog.displayName("SHARD_GROVE"));
		assertEquals("Moltenfish", catalog.displayName("SHARD_MOLTENFISH"));
	}

	@Test
	void resolvesAShardByItsRealNameNotItsId() {
		// The point of naming them: a player reads "Grove" off the bazaar, not SHARD_GROVE.
		assertEquals("SHARD_GROVE", catalog.find("Grove").only().orElseThrow());
	}

	@Test
	void doesNotLetAShardNameShadowAnExistingItem() {
		// SHARD_KIWI is named "Kiwi", the same name the live catalog gives BUILDER_KIWI. Naming the
		// shard "Kiwi" too would put two ids in find()'s exact-name tier, so "Kiwi" would resolve to
		// neither and typing it off the bazaar would find nothing. The shard keeps its raw id instead.
		ItemsDto dto = new Gson().fromJson(
				"{\"items\":[{\"id\":\"BUILDER_KIWI\",\"name\":\"Kiwi\"}]}", ItemsDto.class);
		ItemCatalog withKiwi = dto.toCatalog();

		assertEquals("BUILDER_KIWI", withKiwi.find("Kiwi").only().orElseThrow());
		assertEquals("SHARD_KIWI", withKiwi.displayName("SHARD_KIWI"));
		// A shard whose name is free is still named, so the guard only bites on a real collision.
		assertEquals("Grove", withKiwi.displayName("SHARD_GROVE"));
	}
}
