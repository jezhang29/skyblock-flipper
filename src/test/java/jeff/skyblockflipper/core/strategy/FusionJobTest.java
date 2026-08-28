package jeff.skyblockflipper.core.strategy;

import jeff.skyblockflipper.core.model.BazaarProduct;
import jeff.skyblockflipper.core.model.BazaarSnapshot;
import jeff.skyblockflipper.core.model.ItemCatalog;
import jeff.skyblockflipper.core.model.OrderLevel;
import jeff.skyblockflipper.core.pricing.CraftQuote.FillHistory;
import jeff.skyblockflipper.core.pricing.Fees;
import jeff.skyblockflipper.core.pricing.FusionQuote;
import jeff.skyblockflipper.core.recipe.FusionImporter;
import jeff.skyblockflipper.core.recipe.FusionTable;

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
 * Pins the flattening of a fusion tree into a {@link FusionJob} and its lift into a {@link WorkedJob}:
 * that base buys, the fusion clicks and the sell offer each become a row, that base shards are one
 * row per id, and that the transform rows are untracked while the two market legs are trackable.
 */
class FusionJobTest {
	private static final Duration HOUR = Duration.ofHours(1);
	private static final long PLENTY = 1_000_000_000L;

	private static final String GRAPH = """
			{"shards":{
			  "A":{"name":"In One","family":"Bug Family","rarity":"common","fuse_amount":5,"internal_id":"SHARD_IN1"},
			  "B":{"name":"In Two","family":"Bug Family","rarity":"common","fuse_amount":5,"internal_id":"SHARD_IN2"},
			  "M":{"name":"Mid","family":"Bug Family","rarity":"uncommon","fuse_amount":5,"internal_id":"SHARD_MID"},
			  "O":{"name":"Out","family":"Bug Family","rarity":"rare","fuse_amount":5,"internal_id":"SHARD_OUT"}},
			 "recipes":{
			   "M":{"1":[["A","B"]]},
			   "O":{"1":[["M","A"]]}}}""";

	private final Map<String, BazaarProduct> products = new HashMap<>();

	private void askOnly(String id, double ask, int askOrders, long weeklyBought) {
		products.put(id, new BazaarProduct(id,
				List.of(new OrderLevel(ask, 1_000_000L, askOrders)),
				List.of(),
				new BazaarProduct.MovingWeek(weeklyBought, 0L)));
	}

	private BazaarSnapshot bazaar() {
		return new BazaarSnapshot(Instant.now(), Map.copyOf(products));
	}

	@Test
	void flattensATreeIntoRowsAndAggregatesABaseShardUsedInTwoBranches() {
		// SHARD_IN1 is bought both directly (for OUT) and inside MID, so it must appear as one row.
		askOnly("SHARD_IN1", 100.0d, 20, 168_000L);
		askOnly("SHARD_IN2", 100.0d, 20, 168_000L);
		askOnly("SHARD_MID", 5_000_000.0d, 20, 168_000L); // dear, so the solver fuses it
		askOnly("SHARD_OUT", 500_000.0d, 20, 168_000L);

		FusionTable table = FusionImporter.parse(new StringReader(GRAPH));
		FusionQuote.Solver solver = FusionQuote.solver(table, bazaar(), 0);
		FusionQuote quote = FusionQuote.quote(solver, "SHARD_OUT", Fees.none(), FillHistory.none(),
				HOUR, PLENTY).orElseThrow();

		FusionJob job = FusionJob.of(quote, ItemCatalog.empty(), bazaar()).orElseThrow();

		// SHARD_IN1 appears once despite feeding two branches; SHARD_MID never appears as a buy.
		long in1Rows = job.rows().stream()
				.filter(r -> r.action() == FusionJob.Action.BUY_ORDER
						|| r.action() == FusionJob.Action.INSTANT_BUY)
				.filter(r -> r.itemId().equals("SHARD_IN1"))
				.count();
		assertEquals(1, in1Rows);
		assertTrue(job.rows().stream().noneMatch(r ->
				r.itemId().equals("SHARD_MID") && r.action() != FusionJob.Action.FUSE));

		// Two fusion rows, bottom-up, then one sell offer.
		List<FusionJob.Row> fuses = job.rows().stream()
				.filter(r -> r.action() == FusionJob.Action.FUSE).toList();
		assertEquals(2, fuses.size());
		assertEquals("SHARD_MID", fuses.get(0).itemId());
		assertEquals("SHARD_OUT", fuses.get(1).itemId());
		assertEquals(FusionJob.Action.SELL_OFFER, job.rows().getLast().action());
	}

	@Test
	void liftsIntoAWorkedJobWithTransformRowsUntracked() {
		askOnly("SHARD_IN1", 100.0d, 20, 168_000L);
		askOnly("SHARD_IN2", 100.0d, 20, 168_000L);
		askOnly("SHARD_MID", 5_000_000.0d, 20, 168_000L);
		askOnly("SHARD_OUT", 500_000.0d, 20, 168_000L);

		FusionTable table = FusionImporter.parse(new StringReader(GRAPH));
		FusionQuote.Solver solver = FusionQuote.solver(table, bazaar(), 0);
		FusionQuote quote = FusionQuote.quote(solver, "SHARD_OUT", Fees.none(), FillHistory.none(),
				HOUR, PLENTY).orElseThrow();

		FusionJob job = FusionJob.of(quote, ItemCatalog.empty(), bazaar()).orElseThrow();
		WorkedJob worked = WorkedJob.ofFusion("SHARD_OUT", "Out", job);

		assertEquals(StrategyKind.FUSION, worked.kind());
		// The two market legs (base buys + the sell offer) are trackable; the fusions are not.
		long buys = worked.steps().stream()
				.filter(s -> s.stage() == WorkedJob.Stage.BUY_ORDER
						|| s.stage() == WorkedJob.Stage.INSTANT_BUY)
				.count();
		long transforms = worked.steps().stream()
				.filter(s -> s.stage() == WorkedJob.Stage.TRANSFORM).count();
		assertEquals(2, transforms);
		assertEquals(buys + 1, worked.trackableCount()); // base buys plus the one sell offer
	}

	@Test
	void aStalledFusionKeepsItsNameAndSaysWhy() {
		WorkedJob stalled = WorkedJob.ofFusion("SHARD_OUT", "Molthorn", null);

		assertEquals(StrategyKind.FUSION, stalled.kind());
		assertEquals("Molthorn", stalled.displayName());
		assertTrue(stalled.steps().isEmpty());
		assertTrue(stalled.note().contains("no longer clears"));
	}
}
