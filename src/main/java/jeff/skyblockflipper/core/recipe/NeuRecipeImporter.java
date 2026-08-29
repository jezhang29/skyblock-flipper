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
package jeff.skyblockflipper.core.recipe;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import jeff.skyblockflipper.core.model.UpgradeCost;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Turns the NotEnoughUpdates item dump into the recipe table {@link RecipeBook} ships.
 *
 * <p>Run offline, by hand, when NEU moves - not at build time and never at runtime. The output is
 * checked in, so a craft flip is ranked off a table that was reviewed rather than off whatever a
 * repository looked like the morning of a build.
 *
 * <pre>
 *   git clone --depth 1 https://github.com/NotEnoughUpdates/NotEnoughUpdates-REPO.git
 *   ./gradlew -q importRecipes --args="--repo NotEnoughUpdates-REPO"
 * </pre>
 *
 * <p>NEU is MIT licensed, so a derived table ships freely provided the notice goes with it; it is at
 * {@code src/main/resources/data/skyblock-flipper/NEU-LICENSE}.
 *
 * <p><b>The schema has five traps, all measured against the live repo on 2026-08-17 and all silent
 * if missed.</b> Each produces a table that parses cleanly and prices wrongly, which is the same
 * failure shape as the shared item ids in {@code ItemDecoder}. They are, with the count of files
 * that would be got wrong:
 *
 * <ol>
 * <li><b>Two schema shapes coexist.</b> A singular {@code recipe} object (2,011 files) and a plural
 *     {@code recipes} array (1,380 files); six files carry both. Reading only one form loses roughly
 *     half the table.</li>
 * <li><b>Most {@code recipes} entries are not crafts.</b> {@code npc_shop} 1093, {@code crafting}
 *     539, {@code drops} 429, {@code katgrade} 217, {@code forge} 120, {@code trade} 78. A
 *     {@code drops} entry read as a craft prices an item at the cost of nothing. The singular
 *     {@code recipe} form has no {@code type} and is always a craft.</li>
 * <li><b>Quantities are per grid cell.</b> {@code ENCHANTED_DIAMOND:32} in five of nine cells is
 *     160 units, so cells must be summed - see {@link Recipe.Ingredients}. 41 cells carry a bare id
 *     with no colon, meaning one; skipping those drops an ingredient silently.</li>
 * <li><b>Counts are not always integers.</b> 582 entries write {@code 1} and 43 write {@code 1.0},
 *     and {@code count} is not exclusive to the plural form - 159 singular {@code recipe} objects
 *     carry one. Parsing as an integer type throws on the payload.</li>
 * <li><b>Legacy damage ids use a hyphen where the bazaar uses a colon</b> - {@code INK_SACK-3}
 *     against {@code INK_SACK:3}. 815 grid cells and 151 recipe outputs, over 79 distinct ids -
 *     and every hyphenated id in the repo is of this shape, so there is nothing to false-positive
 *     on. Verified live: {@code RAW_FISH:1} is a real bazaar product.</li>
 * </ol>
 *
 * <p>{@code overrideOutputId} is honoured defensively and is expected to change nothing: it appears
 * 631 times and disagrees with {@code internalname} zero times.
 */
public final class NeuRecipeImporter {
	/** The nine crafting grid slots, in NEU's naming. */
	private static final List<String> CELLS = List.of(
			"A1", "A2", "A3", "B1", "B2", "B3", "C1", "C2", "C3");

	/**
	 * A trailing {@code -<digits>} is a legacy damage value, which the bazaar spells with a colon.
	 *
	 * <p>Anchored at the end and requiring digits because {@code LEAVES_2-1} and {@code LOG_2-1}
	 * carry a digit in the id itself, which only the trailing group may claim. Measured: 79 ids in
	 * the repo match this and 0 carry a hyphen that is not a damage value.
	 */
	private static final Pattern LEGACY_DAMAGE = Pattern.compile("^(.*)-(\\d+)$");

	private final Gson gson = new Gson();

	/** Counts worth printing, because a drop in any of them is how a schema change announces itself. */
	private int filesRead;
	private int filesFailed;
	private int singularForms;
	private int pluralCrafting;
	private int pluralSkipped;
	private int emptyGrids;
	private int legacyIdsRewritten;

	public static void main(String[] args) throws Exception {
		Options options;

		try {
			options = Options.parse(args);
		} catch (IllegalArgumentException e) {
			System.err.println(e.getMessage());
			System.err.println();
			System.err.println(Options.usage());
			System.exit(2);
			return;
		}

		if (options.help()) {
			System.out.println(Options.usage());
			return;
		}

		NeuRecipeImporter importer = new NeuRecipeImporter();
		List<Recipe> recipes = importer.readAll(options.itemsDir());

		if (recipes.isEmpty()) {
			System.err.println("No recipes found under " + options.itemsDir()
					+ ". Refusing to overwrite the table with an empty one.");
			System.exit(1);
			return;
		}

		importer.write(recipes, options.output());
		importer.report(recipes, options.output());
	}

	/**
	 * Reads every item file in {@code itemsDir}.
	 *
	 * <p>Sorted by output then by bill, so regenerating against an unchanged repo produces a
	 * byte-identical file and a real diff is legible in review.
	 */
	public List<Recipe> readAll(Path itemsDir) throws IOException {
		List<Recipe> recipes = new ArrayList<>();

		try (Stream<Path> files = Files.list(itemsDir)) {
			for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".json")).toList()) {
				filesRead++;

				try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
					recipes.addAll(readItem(reader));
				} catch (IOException | JsonParseException | IllegalStateException e) {
					filesFailed++;
				}
			}
		}

		recipes.sort(Comparator.comparing(Recipe::outputId)
				.thenComparing(r -> r.ingredients().size())
				.thenComparing(r -> r.ingredients().toString()));

		return recipes;
	}

	/** Every crafting recipe declared by one NEU item file. Package-private so the tests can aim at it. */
	List<Recipe> readItem(Reader reader) {
		JsonElement root = JsonParser.parseReader(reader);

		if (root == null || !root.isJsonObject()) {
			return List.of();
		}

		JsonObject item = root.getAsJsonObject();
		String internalName = string(item, "internalname");

		if (internalName == null) {
			return List.of();
		}

		String unlock = string(item, "crafttext");
		List<Recipe> recipes = new ArrayList<>();

		// The singular form carries no type field and is always a craft.
		JsonElement singular = item.get("recipe");

		if (singular != null && singular.isJsonObject()) {
			singularForms++;
			addRecipe(recipes, singular.getAsJsonObject(), internalName, unlock);
		}

		JsonElement plural = item.get("recipes");

		if (plural != null && plural.isJsonArray()) {
			for (JsonElement element : plural.getAsJsonArray()) {
				if (element == null || !element.isJsonObject()) {
					continue;
				}

				JsonObject candidate = element.getAsJsonObject();

				// Everything else in this array - npc_shop, drops, forge, katgrade, trade - is a
				// different acquisition route with a different cost, and none of them is a craft.
				if (!"crafting".equals(string(candidate, "type"))) {
					pluralSkipped++;
					continue;
				}

				pluralCrafting++;
				addRecipe(recipes, candidate, internalName, unlock);
			}
		}

		return recipes;
	}

	private void addRecipe(List<Recipe> into, JsonObject recipe, String internalName, String unlock) {
		Recipe.Ingredients ingredients = new Recipe.Ingredients();

		for (String cell : CELLS) {
			String value = string(recipe, cell);

			if (value == null || value.isBlank()) {
				continue;
			}

			addCell(ingredients, value.trim());
		}

		if (ingredients.isEmpty()) {
			// A shaped recipe with no inputs is not a free item, it is a file this parser did not
			// understand. Dropping it loses one recipe; keeping it prices an output at zero.
			emptyGrids++;
			return;
		}

		String override = string(recipe, "overrideOutputId");
		String output = normalise(override != null && !override.isBlank() ? override : internalName);
		int count = count(recipe);

		into.add(new Recipe(output, count, ingredients.toList(), unlock));
	}

	/** One grid cell: {@code ID:QTY}, or a bare {@code ID} meaning one. */
	private void addCell(Recipe.Ingredients into, String cell) {
		String id = cell;
		int amount = 1;
		int colon = cell.lastIndexOf(':');

		if (colon > 0 && colon < cell.length() - 1) {
			int parsed = number(cell.substring(colon + 1));

			// A colon this parser cannot read as a quantity is part of the id, not a broken count.
			if (parsed > 0) {
				id = cell.substring(0, colon);
				amount = parsed;
			}
		}

		into.add(normalise(id), amount);
	}

	/**
	 * Rewrites a legacy damage suffix into the bazaar's spelling: {@code INK_SACK-3} to
	 * {@code INK_SACK:3}. Package-private so {@code NeuRecipeImporterTest} can pin the rule.
	 */
	String normalise(String id) {
		String trimmed = id.trim();
		Matcher matcher = LEGACY_DAMAGE.matcher(trimmed);

		if (!matcher.matches()) {
			return trimmed;
		}

		legacyIdsRewritten++;

		return matcher.group(1) + ":" + matcher.group(2);
	}

	/** {@code count}, which is written as both {@code 1} and {@code 1.0}, and is often absent. */
	private int count(JsonObject recipe) {
		JsonElement element = recipe.get("count");

		if (element == null || !element.isJsonPrimitive()) {
			return 1;
		}

		int parsed = number(element.getAsString());

		return parsed > 0 ? parsed : 1;
	}

	/** @return the rounded value, or 0 for anything this cannot read as a positive number */
	private static int number(String text) {
		try {
			double value = Double.parseDouble(text.trim());

			return value >= 1.0d ? (int) Math.round(value) : 0;
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	private static String string(JsonObject object, String member) {
		JsonElement element = object.get(member);

		return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
	}

	public void write(List<Recipe> recipes, Path output) throws IOException {
		Path parent = output.getParent();

		if (parent != null) {
			Files.createDirectories(parent);
		}

		try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
			for (Recipe recipe : recipes) {
				writer.write(gson.toJson(toLine(recipe)));
				writer.newLine();
			}
		}
	}

	private static RecipeBook.RecipeLine toLine(Recipe recipe) {
		RecipeBook.RecipeLine line = new RecipeBook.RecipeLine();
		line.out = recipe.outputId();
		line.count = recipe.outputCount();
		line.unlock = recipe.unlockText().isEmpty() ? null : recipe.unlockText();
		line.ing = new ArrayList<>(recipe.ingredients().size());

		for (UpgradeCost.Ingredient ingredient : recipe.ingredients()) {
			line.ing.add(new RecipeBook.IngredientLine(ingredient.productId(), ingredient.amount()));
		}

		return line;
	}

	/**
	 * Prints what the run understood.
	 *
	 * <p>An import is unattended and a schema change does not throw - it quietly reads fewer files.
	 * These counts are the only thing that would show it, so they are printed next to the figures
	 * this file's javadoc records, ready to be compared.
	 */
	private void report(List<Recipe> recipes, Path output) throws IOException {
		long distinctOutputs = recipes.stream().map(Recipe::outputId).distinct().count();
		long multiVariant = recipes.stream().map(Recipe::outputId).distinct()
				.filter(id -> recipes.stream().filter(r -> r.outputId().equals(id)).count() > 1)
				.count();

		System.out.printf(Locale.ROOT, """
				Wrote %,d recipes to %s (%,d bytes)

				  item files read       %,7d  (expected ~8,745; %,d unreadable)
				  singular 'recipe'     %,7d  (expected ~2,011)
				  plural 'crafting'     %,7d  (expected ~539)
				  plural non-crafting   %,7d  skipped (expected ~1,937)
				  empty grids           %,7d  dropped
				  legacy ids rewritten  %,7d  (expected 966: 815 cells, 151 outputs)

				  distinct outputs      %,7d
				  outputs with variants %,7d

				A large drop in any of these means NEU's schema moved. See this class's javadoc for
				what each count was when the parser was written, and docs/craft-flipping.md for why.
				%n""",
				recipes.size(), output, Files.size(output),
				filesRead, filesFailed,
				singularForms, pluralCrafting, pluralSkipped, emptyGrids, legacyIdsRewritten,
				distinctOutputs, multiVariant);
	}

	/**
	 * @param itemsDir the NEU repo's {@code items} directory, or the repo root
	 * @param output   where to write the table
	 * @param help     whether the caller only asked what the flags are
	 */
	record Options(Path itemsDir, Path output, boolean help) {
		private static final Path DEFAULT_OUTPUT =
				Path.of("src/main/resources/data/skyblock-flipper/recipes.jsonl");

		static Options parse(String[] args) {
			Path repo = null;
			Path output = null;

			for (int i = 0; i < args.length; i++) {
				switch (args[i]) {
					case "--help", "-h" -> {
						return new Options(Path.of("."), DEFAULT_OUTPUT, true);
					}
					case "--repo" -> repo = Path.of(value(args, i++));
					case "--out" -> output = Path.of(value(args, i++));
					default -> throw new IllegalArgumentException("Unknown option: " + args[i]);
				}
			}

			if (repo == null) {
				throw new IllegalArgumentException("--repo is required");
			}

			// Accepting either the repo root or its items directory saves the caller remembering
			// which one this wants, and the wrong one reads zero files without saying why.
			Path resolved = repo.toAbsolutePath().normalize();
			Path items = Files.isDirectory(resolved.resolve("items")) ? resolved.resolve("items") : resolved;

			return new Options(items, (output != null ? output : DEFAULT_OUTPUT).toAbsolutePath()
					.normalize(), false);
		}

		private static String value(String[] args, int index) {
			if (index + 1 >= args.length) {
				throw new IllegalArgumentException(args[index] + " needs a value");
			}

			return args[index + 1];
		}

		static String usage() {
			return """
					Imports NotEnoughUpdates crafting recipes into the table RecipeBook ships.

					  --repo <path>  a NotEnoughUpdates-REPO checkout, or its items/ directory
					  --out <path>   where to write. Default:
					                 src/main/resources/data/skyblock-flipper/recipes.jsonl
					  --help         this text

					Run by hand when NEU moves, then review the diff and commit it. The counts
					printed at the end are how a schema change shows itself - compare them with
					the figures in NeuRecipeImporter's javadoc.""";
		}
	}
}
