package jeff.skyblockflipper.core.strategy;

import jeff.skyblockflipper.core.model.BazaarProduct;
import jeff.skyblockflipper.core.model.BazaarSnapshot;
import jeff.skyblockflipper.core.model.ItemCatalog;
import jeff.skyblockflipper.core.model.OrderLevel;
import jeff.skyblockflipper.core.pricing.Fees;
import jeff.skyblockflipper.core.recipe.FusionImporter;
import jeff.skyblockflipper.core.recipe.FusionTable;
import jeff.skyblockflipper.core.valuation.TrendSnapshot;

import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the seam between {@link jeff.skyblockflipper.core.pricing.FusionQuote} and the shared
 * candidate shape: a profitable fusion becomes one candidate with its buy, fuse and sell steps, the
 * per-click cost lands in the notes, and turning fusion off empties the list.
 */
class FusionFlipStrategyTest {
	private static final Duration HOUR = Duration.ofHours(1);
	private static final long PLENTY = 1_000_000_000L;

	private static final String GRAPH = """
			{"shards":{
			  "A":{"name":"In One","family":"Bug Family","rarity":"common","fuse_amount":5,"internal_id":"SHARD_IN1"},
			  "B":{"name":"In Two","family":"Bug Family","rarity":"common","fuse_amount":5,"internal_id":"SHARD_IN2"},
			  "O":{"name":"Out","family":"Bug Family","rarity":"rare","fuse_amount":5,"internal_id":"SHARD_OUT"}},
			 "recipes":{"O":{"2":[["A","B"]]}}}""";

	private final Map<String, BazaarProduct> products = new HashMap<>();

	private void askOnly(String id, double ask, int askOrders, long weeklyBought) {
		products.put(id, new BazaarProduct(id,
				List.of(new OrderLevel(ask, 1_000_000L, askOrders)),
				List.of(),
				new BazaarProduct.MovingWeek(weeklyBought, 0L)));
	}

	private StrategyContext context(boolean fusionOn) {
		return new StrategyContext(new BazaarSnapshot(Instant.now(), Map.copyOf(products)),
				ItemCatalog.empty(), List.of(), TrendSnapshot.empty(), Fees.none(), PLENTY, 0L, 0.0d,
				0.0d, HOUR, 1.0d, NpcContext.unlimited(), CraftContext.off(), CombineContext.off(),
				fusionOn ? FusionContext.defaults() : FusionContext.off());
	}

	private FusionTable table() {
		return FusionImporter.parse(new StringReader(GRAPH));
	}

	@Test
	void turnsAProfitableFusionIntoOneCandidate() {
		askOnly("SHARD_IN1", 100.0d, 20, 168_000L);
		askOnly("SHARD_IN2", 200.0d, 20, 168_000L);
		askOnly("SHARD_OUT", 1000.0d, 20, 168_000L);

		List<FlipCandidate> found =
				new FusionFlipStrategy(table()).findCandidates(context(true));

		assertEquals(1, found.size());

		FlipCandidate candidate = found.getFirst();
		assertEquals(StrategyKind.FUSION, candidate.kind());
		assertEquals("SHARD_OUT", candidate.itemId());
		assertTrue(candidate.steps().stream().anyMatch(s -> s.startsWith("Fuse:")),
				"the fusion step must be shown");
		assertTrue(candidate.steps().stream().anyMatch(s -> s.startsWith("Sell Offer:")),
				"the exit offer must be shown");
		assertTrue(candidate.notes().stream().anyMatch(s -> s.contains("per fusion click")),
				"net per fusion click is the honest axis and must be a note");
	}

	@Test
	void producesNothingWhenFusionIsOff() {
		askOnly("SHARD_IN1", 100.0d, 20, 168_000L);
		askOnly("SHARD_IN2", 200.0d, 20, 168_000L);
		askOnly("SHARD_OUT", 1000.0d, 20, 168_000L);

		assertTrue(new FusionFlipStrategy(table()).findCandidates(context(false)).isEmpty());
	}

	@Test
	void theShippedGraphLoadsAndTheStrategyRanksAgainstAnEmptyBookWithoutThrowing() {
		// The real strategy against an empty book must return nothing, not fail: a fresh client has no
		// bazaar yet, and the 130k-route graph must not choke the ranking.
		StrategyContext empty = new StrategyContext(BazaarSnapshot.empty(), ItemCatalog.empty(),
				List.of(), TrendSnapshot.empty(), Fees.none(), PLENTY, 0L, 0.0d, 0.0d, HOUR, 1.0d,
				NpcContext.unlimited(), CraftContext.off(), CombineContext.off(), FusionContext.defaults());

		assertTrue(new FusionFlipStrategy().findCandidates(empty).isEmpty());
	}
}
