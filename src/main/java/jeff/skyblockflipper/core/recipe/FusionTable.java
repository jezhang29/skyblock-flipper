package jeff.skyblockflipper.core.recipe;

import jeff.skyblockflipper.core.model.BazaarSnapshot;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * The attribute-shard fusion recipe graph, indexed by the bazaar id of what a fusion produces.
 *
 * <p>This is to fusion what {@link RecipeBook} is to crafting: game data the Hypixel API does not
 * carry, bundled rather than fetched so the strategy ranks on its first poll instead of waiting on a
 * download. The graph is copied from {@code Campionnn/SkyShards} {@code public/fusion-data.json},
 * pinned at a commit and parsed by {@link FusionImporter}. See {@code docs/fusion-flipping.md}.
 *
 * <p>Every id here is a bazaar id ({@code SHARD_GROVE}). The source file keys shards by an internal
 * code ({@code C1}) and lists recipes by those codes; {@link FusionImporter} translates both sides
 * to bazaar ids at load so nothing downstream ever sees a code.
 *
 * <p><b>Combinability is a game-rule claim, not a price observation</b> - the same discipline
 * {@link jeff.skyblockflipper.core.strategy.CombineTable} carries. A stale graph after a Skyblock
 * update prices fusions the game no longer allows, silently. {@link #missingProducts} is the
 * existence check: on the shipped graph exactly one shard ({@code SHARD_RAINBUG}) has no bazaar
 * product, and a larger gap means the graph has drifted from the game.
 *
 * <p>Immutable once built and safe to share across threads.
 */
public final class FusionTable {
	/** Where the bundled graph lives on the classpath. */
	static final String RESOURCE = "/data/skyblock-flipper/fusion-data.json";

	private static volatile FusionTable bundled;

	private final Map<String, Shard> shards;
	private final Map<String, List<Route>> recipesByOutput;

	FusionTable(Map<String, Shard> shards, Map<String, List<Route>> recipesByOutput) {
		this.shards = Map.copyOf(shards);

		Map<String, List<Route>> index = new java.util.HashMap<>(recipesByOutput.size());

		for (Map.Entry<String, List<Route>> entry : recipesByOutput.entrySet()) {
			index.put(entry.getKey(), List.copyOf(entry.getValue()));
		}

		this.recipesByOutput = Map.copyOf(index);
	}

	/**
	 * One attribute shard.
	 *
	 * @param id          the bazaar product id, e.g. {@code SHARD_GROVE}
	 * @param name        its display name, e.g. {@code Grove}
	 * @param family      the fusion family string, e.g. {@code Reptile and Serpent Family}. A fusion
	 *                    counts as reptile when either input's family contains {@code Reptile}, which
	 *                    is why {@link Route#reptile()} is pre-computed from this
	 * @param rarity      common..legendary, carried for display only
	 * @param fuseAmount  how many of this shard one fusion consumes: 2 for the Elemental/Amphibian/
	 *                    Reptile families, 1 for Chameleon, 5 for everything else. A property of the
	 *                    <b>input</b>, so the two inputs of a fusion can differ
	 */
	public record Shard(String id, String name, String family, String rarity, int fuseAmount) {
	}

	/**
	 * One way to fuse an output: a pair of input shards and the quantity produced.
	 *
	 * <p>The source file lists up to 130k of these; a single output can be made by many pairs and at
	 * two different quantities (a shard appears under both the {@code "1"} and {@code "2"} keys), so
	 * the min-cost solver in {@code FusionQuote} scans them all and keeps the cheapest per output
	 * unit.
	 *
	 * @param inputA     bazaar id of the first input
	 * @param inputB     bazaar id of the second input
	 * @param outputQty  units produced per fusion click: 1 or 2
	 * @param reptile    whether either input is of a Reptile family, which earns the crocodile
	 *                   double-output perk. Pre-computed from the inputs, matching SkyShards'
	 *                   {@code isReptile = inputs.some(i => family.includes("Reptile"))}
	 */
	public record Route(String inputA, String inputB, int outputQty, boolean reptile) {
	}

	/**
	 * The shipped graph, parsed once and cached.
	 *
	 * <p>Returns an empty table rather than throwing when the resource is missing, so a jar built
	 * without it loses the fusion strategy instead of failing to start - {@link #isEmpty()} is how a
	 * caller reports that honestly.
	 */
	public static FusionTable bundled() {
		FusionTable local = bundled;

		if (local == null) {
			synchronized (FusionTable.class) {
				local = bundled;

				if (local == null) {
					local = loadResource();
					bundled = local;
				}
			}
		}

		return local;
	}

	private static FusionTable loadResource() {
		try (InputStream in = FusionTable.class.getResourceAsStream(RESOURCE)) {
			if (in == null) {
				return empty();
			}

			try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
				return FusionImporter.parse(reader);
			}
		} catch (IOException e) {
			throw new UncheckedIOException("reading " + RESOURCE, e);
		}
	}

	public static FusionTable empty() {
		return new FusionTable(Map.of(), Map.of());
	}

	/** The shard with this bazaar id, or empty if the graph does not carry it. */
	public Optional<Shard> shard(String id) {
		return Optional.ofNullable(shards.get(id));
	}

	public Collection<Shard> shards() {
		return shards.values();
	}

	/** Every way to fuse {@code outputId}; empty if the graph knows none. */
	public List<Route> recipesFor(String outputId) {
		return recipesByOutput.getOrDefault(outputId, List.of());
	}

	/** Every shard id that is the output of at least one fusion. */
	public Set<String> outputs() {
		return recipesByOutput.keySet();
	}

	/** Shards the graph carries, output or input alike. */
	public Set<String> shardIds() {
		return shards.keySet();
	}

	public int recipeCount() {
		int total = 0;

		for (List<Route> routes : recipesByOutput.values()) {
			total += routes.size();
		}

		return total;
	}

	public boolean isEmpty() {
		return shards.isEmpty();
	}

	/**
	 * Shard ids the graph names but the live bazaar does not list as a product, sorted.
	 *
	 * <p>The existence check. A shard with no product cannot be sourced or sold, so the solver skips
	 * it structurally; this reports the gap for logging and for the test that pins it. On the shipped
	 * graph the only absentee is {@code SHARD_RAINBUG}. A larger set after a Skyblock update is the
	 * signal the bundled graph has gone stale, the same silent-wrong-number risk the combine and NEU
	 * tables carry.
	 */
	public Set<String> missingProducts(BazaarSnapshot bazaar) {
		Set<String> missing = new TreeSet<>();

		if (bazaar == null) {
			return missing;
		}

		for (String id : shards.keySet()) {
			if (bazaar.product(id).isEmpty()) {
				missing.add(id);
			}
		}

		return missing;
	}
}
