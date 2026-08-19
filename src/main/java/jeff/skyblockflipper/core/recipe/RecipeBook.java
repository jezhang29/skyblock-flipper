package jeff.skyblockflipper.core.recipe;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import jeff.skyblockflipper.core.model.UpgradeCost;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Every crafting recipe the mod knows, indexed by what it produces.
 *
 * <p>Read from a bundled resource rather than fetched, because the source is a GitHub repository
 * and a strategy that cannot rank until a download finishes is a strategy that silently does
 * nothing on a flaky connection. Regenerate it with {@link NeuRecipeImporter} when NEU moves; the
 * table is a build input, not runtime state.
 *
 * <p><b>An output maps to a list, never to one recipe.</b> Several items are craftable more than one
 * way - {@code ENCHANTED_DIAMOND} has two - and each variant has its own bill and so its own margin.
 * Collapsing them to one would quietly pick a recipe by file order.
 *
 * <p>Immutable once built and safe to share across threads.
 */
public final class RecipeBook {
	/**
	 * Where the imported table lives on the classpath.
	 *
	 * <p>JSON Lines rather than one array: the importer appends, a malformed line costs one recipe
	 * instead of the whole table, and a regenerated file diffs by recipe in review.
	 */
	static final String RESOURCE = "/data/skyblock-flipper/recipes.jsonl";

	private static final Gson GSON = new Gson();

	private static volatile RecipeBook bundled;

	private final Map<String, List<Recipe>> byOutput;
	private final List<Recipe> all;

	private RecipeBook(List<Recipe> recipes) {
		Map<String, List<Recipe>> index = new HashMap<>();

		for (Recipe recipe : recipes) {
			index.computeIfAbsent(recipe.outputId(), id -> new ArrayList<>()).add(recipe);
		}

		index.replaceAll((id, list) -> List.copyOf(list));

		this.byOutput = Map.copyOf(index);
		this.all = List.copyOf(recipes);
	}

	public static RecipeBook of(List<Recipe> recipes) {
		return new RecipeBook(recipes);
	}

	public static RecipeBook empty() {
		return new RecipeBook(List.of());
	}

	/**
	 * The shipped table, parsed once and cached.
	 *
	 * <p>Returns an empty book rather than throwing when the resource is missing. A jar built
	 * without it should lose the craft strategy, not fail to start, and {@link #isEmpty()} is how a
	 * caller reports that honestly instead of showing an empty candidate list as "no flips found".
	 */
	public static RecipeBook bundled() {
		RecipeBook local = bundled;

		if (local == null) {
			synchronized (RecipeBook.class) {
				local = bundled;

				if (local == null) {
					local = loadResource();
					bundled = local;
				}
			}
		}

		return local;
	}

	private static RecipeBook loadResource() {
		try (InputStream in = RecipeBook.class.getResourceAsStream(RESOURCE)) {
			if (in == null) {
				return empty();
			}

			return read(new InputStreamReader(in, StandardCharsets.UTF_8));
		} catch (IOException e) {
			throw new UncheckedIOException("reading " + RESOURCE, e);
		}
	}

	public static RecipeBook read(Path file) throws IOException {
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			return read(reader);
		}
	}

	/**
	 * Parses the JSON Lines form.
	 *
	 * <p>A line that does not parse, or that describes a recipe this class would reject, is skipped.
	 * The table is a generated artifact and one broken line is a regeneration bug, not a reason to
	 * lose the other two and a half thousand recipes.
	 */
	public static RecipeBook read(Reader reader) throws IOException {
		List<Recipe> recipes = new ArrayList<>();

		try (BufferedReader lines = new BufferedReader(reader)) {
			String line;

			while ((line = lines.readLine()) != null) {
				if (line.isBlank()) {
					continue;
				}

				RecipeLine parsed;

				try {
					parsed = GSON.fromJson(line, RecipeLine.class);
				} catch (JsonSyntaxException e) {
					continue;
				}

				Recipe recipe = parsed == null ? null : parsed.toRecipe();

				if (recipe != null) {
					recipes.add(recipe);
				}
			}
		}

		return new RecipeBook(recipes);
	}

	/** Every way to craft {@code outputId}; empty if the book knows none. */
	public List<Recipe> forOutput(String outputId) {
		return byOutput.getOrDefault(outputId, List.of());
	}

	public Collection<Recipe> all() {
		return all;
	}

	public Set<String> outputs() {
		return byOutput.keySet();
	}

	public int size() {
		return all.size();
	}

	public boolean isEmpty() {
		return all.isEmpty();
	}

	/**
	 * Wire form of one line. Field names are short because the file is checked in and read by
	 * machine only; {@code i}/{@code n} for an ingredient mirrors {@code BazaarTape}'s line.
	 */
	static final class RecipeLine {
		String out;
		int count;
		List<IngredientLine> ing;
		String unlock;

		Recipe toRecipe() {
			if (out == null || out.isBlank() || ing == null || ing.isEmpty()) {
				return null;
			}

			List<UpgradeCost.Ingredient> ingredients = new ArrayList<>(ing.size());

			for (IngredientLine line : ing) {
				if (line == null || line.i == null || line.i.isBlank() || line.n < 1) {
					return null;
				}

				ingredients.add(new UpgradeCost.Ingredient(line.i, line.n));
			}

			return new Recipe(out, Math.max(1, count), ingredients, unlock);
		}
	}

	static final class IngredientLine {
		String i;
		int n;

		IngredientLine() {
		}

		IngredientLine(String productId, int amount) {
			this.i = productId;
			this.n = amount;
		}
	}
}
