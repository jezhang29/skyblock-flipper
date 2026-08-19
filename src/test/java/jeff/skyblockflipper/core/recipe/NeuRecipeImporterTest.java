package jeff.skyblockflipper.core.recipe;

import jeff.skyblockflipper.core.model.UpgradeCost;

import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the five NEU schema traps, each of which produces a table that parses cleanly and prices
 * wrongly.
 *
 * <p>Every case here is a shape measured in the live repo on 2026-08-17 rather than an invented
 * one, and each has a count in {@link NeuRecipeImporter}'s javadoc saying how much of the table it
 * covers. The failure mode they all share is silence: a recipe read with a missing ingredient or a
 * quantity off by a factor of five still ranks, still shows a margin, and the margin is wrong.
 */
class NeuRecipeImporterTest {
	private final NeuRecipeImporter importer = new NeuRecipeImporter();

	private List<Recipe> read(String json) {
		return importer.readItem(new StringReader(json));
	}

	private static Map<String, Integer> bill(Recipe recipe) {
		return recipe.ingredients().stream().collect(Collectors.toMap(
				UpgradeCost.Ingredient::productId, UpgradeCost.Ingredient::amount));
	}

	/**
	 * Trap 3, the expensive one. Nine cells of the same ingredient is nine units, and reading one
	 * cell as the total understates a bill by up to nine times.
	 */
	@Test
	void sumsQuantitiesAcrossGridCells() {
		List<Recipe> recipes = read("""
				{
				  "internalname": "ENCHANTED_DIAMOND_BLOCK",
				  "recipe": {
				    "A1": "ENCHANTED_DIAMOND:32", "A2": "ENCHANTED_DIAMOND:32", "A3": "ENCHANTED_DIAMOND:32",
				    "B1": "ENCHANTED_DIAMOND:32", "B2": "ENCHANTED_DIAMOND:32", "B3": "",
				    "C1": "", "C2": "", "C3": ""
				  }
				}""");

		assertEquals(1, recipes.size());
		assertEquals(Map.of("ENCHANTED_DIAMOND", 160), bill(recipes.getFirst()));
	}

	/** Trap 3 again: a cell with no colon means one unit, not zero, and not a skip. */
	@Test
	void readsAColonlessCellAsOneUnit() {
		List<Recipe> recipes = read("""
				{
				  "internalname": "ENCHANTED_COMPOST",
				  "recipe": {"A1": "YELLOW_FLOWER", "A2": "ENCHANTED_BREAD:4", "B2": "YELLOW_FLOWER"}
				}""");

		assertEquals(Map.of("YELLOW_FLOWER", 2, "ENCHANTED_BREAD", 4), bill(recipes.getFirst()));
	}

	/**
	 * Trap 5. NEU spells a legacy damage value with a hyphen and the bazaar trades it with a colon,
	 * so an unrewritten id prices against a product that does not exist and the recipe is dropped.
	 */
	@Test
	void rewritesLegacyDamageIdsToTheBazaarSpelling() {
		assertEquals("INK_SACK:3", importer.normalise("INK_SACK-3"));
		assertEquals("RAW_FISH:1", importer.normalise("RAW_FISH-1"));

		// The id itself ends in a digit here, so only the trailing group is the damage value.
		assertEquals("LOG_2:1", importer.normalise("LOG_2-1"));
		assertEquals("LEAVES_2:1", importer.normalise("LEAVES_2-1"));
	}

	/** No id in the repo carries a hyphen that is not a damage value, and none may be invented. */
	@Test
	void leavesOrdinaryIdsAlone() {
		assertEquals("ENCHANTED_DIAMOND", importer.normalise("ENCHANTED_DIAMOND"));
		assertEquals("SUPER_COMPACTOR_3000", importer.normalise("SUPER_COMPACTOR_3000"));
	}

	/**
	 * Trap 2. A {@code drops} entry read as a craft prices an item at the cost of nothing, which is
	 * an infinite margin that ranks first.
	 */
	@Test
	void readsOnlyCraftingEntriesFromThePluralForm() {
		List<Recipe> recipes = read("""
				{
				  "internalname": "TARANTULA_SILK",
				  "recipes": [
				    {"type": "drops", "A1": "TARANTULA_WEB:1"},
				    {"type": "npc_shop", "cost": ["SKYBLOCK_COIN:12"], "result": "TARANTULA_SILK"},
				    {"type": "crafting", "A1": "TARANTULA_WEB:32", "B2": "TARANTULA_WEB:32"}
				  ]
				}""");

		assertEquals(1, recipes.size());
		assertEquals(Map.of("TARANTULA_WEB", 64), bill(recipes.getFirst()));
	}

	/** Trap 1. Six files carry both forms, and each is a real recipe with its own bill. */
	@Test
	void readsBothSchemaShapesFromOneFile() {
		List<Recipe> recipes = read("""
				{
				  "internalname": "ENCHANTED_DIAMOND",
				  "recipe": {"A1": "DIAMOND:160"},
				  "recipes": [{"type": "crafting", "A1": "ENCHANTED_DIAMOND_BLOCK:1", "count": 160}]
				}""");

		assertEquals(2, recipes.size());
		assertTrue(recipes.stream().anyMatch(r -> r.uses("DIAMOND") && r.outputCount() == 1));
		assertTrue(recipes.stream().anyMatch(r -> r.uses("ENCHANTED_DIAMOND_BLOCK")
				&& r.outputCount() == 160));
	}

	/** Trap 4. 43 entries write {@code 1.0}; an integer parse throws on the whole payload. */
	@Test
	void readsAFractionallyWrittenCount() {
		assertEquals(160, read("""
				{"internalname": "X", "recipe": {"A1": "Y:1", "count": 160.0}}""")
				.getFirst().outputCount());

		assertEquals(1, read("""
				{"internalname": "X", "recipe": {"A1": "Y:1", "count": 1.0}}""")
				.getFirst().outputCount());
	}

	/** Trap 4 again: {@code count} is not exclusive to the plural form. 159 singular objects carry one. */
	@Test
	void readsCountFromTheSingularForm() {
		assertEquals(8, read("""
				{"internalname": "X", "recipe": {"A1": "Y:1", "count": 8}}""")
				.getFirst().outputCount());
	}

	/** Absent means one. */
	@Test
	void defaultsCountToOne() {
		assertEquals(1, read("""
				{"internalname": "X", "recipe": {"A1": "Y:1"}}""").getFirst().outputCount());
	}

	/** Present 631 times in the repo and never in disagreement, so it must not change an answer. */
	@Test
	void honoursOverrideOutputId() {
		assertEquals("REAL_OUTPUT", read("""
				{"internalname": "SOME_FILE", "recipe": {"A1": "Y:1", "overrideOutputId": "REAL_OUTPUT"}}""")
				.getFirst().outputId());
	}

	/**
	 * A recipe with nothing in its grid is a file this parser did not understand. Keeping it would
	 * price an output at zero cost, which ranks above every real flip.
	 */
	@Test
	void dropsARecipeWithAnEmptyGrid() {
		assertTrue(read("""
				{"internalname": "X", "recipe": {"A1": "", "B2": " "}}""").isEmpty());
		assertTrue(read("""
				{"internalname": "X", "recipes": [{"type": "crafting"}]}""").isEmpty());
	}

	/** {@code crafttext} is advisory, and carrying it is what lets the UI say why a craft is unavailable. */
	@Test
	void carriesTheUnlockText() {
		assertEquals("Requires: Iron Ingot IX", read("""
				{"internalname": "ENCHANTED_HOPPER", "crafttext": "Requires: Iron Ingot IX",
				 "recipe": {"A1": "ENCHANTED_IRON:1"}}""").getFirst().unlockText());
	}

	/** An item file with no recipe at all is the common case, not an error. */
	@Test
	void ignoresAnItemWithNoRecipe() {
		assertTrue(read("""
				{"internalname": "DIAMOND_SWORD", "displayname": "Diamond Sword"}""").isEmpty());
		assertTrue(read("[]").isEmpty());
	}
}
