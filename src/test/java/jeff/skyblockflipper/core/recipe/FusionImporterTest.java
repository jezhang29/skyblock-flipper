package jeff.skyblockflipper.core.recipe;

import jeff.skyblockflipper.core.model.BazaarProduct;
import jeff.skyblockflipper.core.model.BazaarSnapshot;
import jeff.skyblockflipper.core.model.OrderLevel;
import jeff.skyblockflipper.core.recipe.FusionTable.Route;
import jeff.skyblockflipper.core.recipe.FusionTable.Shard;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the shape of the bundled fusion graph and the two game rules baked into it at load.
 *
 * <p>The graph is copied from an upstream repo whose workflow rewrites the file, so these counts are
 * how a schema change announces itself: a drop in shard or route count, a {@code fuse_amount} that
 * stopped matching a family, or an output quantity outside {@code {1, 2}} all mean the bundled file
 * has drifted from what {@link FusionImporter} expects. See {@code docs/fusion-flipping.md}.
 */
class FusionImporterTest {
	private final FusionTable table = FusionTable.bundled();

	@Test
	void loadsTheWholeGraphKeyedOnBazaarIds() {
		// 321 shards, every id a SHARD_* bazaar id, and the graph is not accidentally empty.
		assertFalse(table.isEmpty());
		assertEquals(321, table.shardIds().size());

		for (String id : table.shardIds()) {
			assertTrue(id.startsWith("SHARD_"), () -> id + " is not a bazaar shard id");
		}

		// Recipes are keyed by the output shard's bazaar id, not by an internal code.
		assertTrue(table.outputs().contains("SHARD_GROVE"));
		assertFalse(table.recipesFor("SHARD_GROVE").isEmpty());

		// The pre-expanded graph is large: well over 100k input-pair routes across all outputs.
		assertTrue(table.recipeCount() > 100_000,
				() -> "only " + table.recipeCount() + " routes; the graph looks truncated");
	}

	@Test
	void fuseAmountFollowsTheFamily() {
		// 2 for the Reptile/Amphibian/Elemental families, 1 for the lone Chameleon shard, 5 otherwise.
		int ones = 0;
		int twos = 0;
		int fives = 0;

		for (Shard shard : table.shards()) {
			switch (shard.fuseAmount()) {
				case 1 -> ones++;
				case 2 -> twos++;
				case 5 -> fives++;
				default -> throw new AssertionError(
						shard.id() + " has an unexpected fuse_amount " + shard.fuseAmount());
			}
		}

		assertEquals(1, ones, "exactly one shard (Chameleon) consumes one per fusion");
		assertEquals(60, twos);
		assertEquals(260, fives);

		// Grove is Elemental Family, so two per fusion.
		assertEquals(2, table.shard("SHARD_GROVE").orElseThrow().fuseAmount());
	}

	@Test
	void outputQuantityIsOneOrTwoAndReptileIsJudgedOnInputs() {
		for (String output : table.outputs()) {
			for (Route route : table.recipesFor(output)) {
				assertTrue(route.outputQty() == 1 || route.outputQty() == 2,
						() -> output + " has output quantity " + route.outputQty());

				// The reptile flag must equal "either input's family contains Reptile", which is what
				// earns the crocodile double-output perk in the reference tool.
				boolean expected = familyOf(route.inputA()).contains("Reptile")
						|| familyOf(route.inputB()).contains("Reptile");
				assertEquals(expected, route.reptile(),
						() -> output + " reptile flag disagrees with its inputs");
			}
		}
	}

	@Test
	void existenceValidationDropsOnlyRainbugAgainstAFullBook() {
		// A bazaar that lists every shard the graph names except SHARD_RAINBUG, which is the one shard
		// with no live product. The existence check should report exactly that gap.
		Map<String, BazaarProduct> products = new HashMap<>();

		for (String id : table.shardIds()) {
			if (!id.equals("SHARD_RAINBUG")) {
				products.put(id, product(id));
			}
		}

		BazaarSnapshot bazaar = new BazaarSnapshot(Instant.now(), Map.copyOf(products));

		assertEquals(Set.of("SHARD_RAINBUG"), table.missingProducts(bazaar));
	}

	@Test
	void missingProductsFlagsAWholeStaleGraph() {
		// An empty book means every shard is missing: the alarm for a graph that no longer matches the
		// game, rather than a single removed id.
		assertEquals(table.shardIds().size(),
				table.missingProducts(BazaarSnapshot.empty()).size());
	}

	private String familyOf(String id) {
		String family = table.shard(id).orElseThrow().family();

		return family == null ? "" : family;
	}

	private static BazaarProduct product(String id) {
		return new BazaarProduct(id,
				List.of(new OrderLevel(100.0d, 1_000L, 20)),
				List.of(new OrderLevel(50.0d, 1_000L, 20)),
				new BazaarProduct.MovingWeek(10_000L, 10_000L));
	}
}
