package jeff.skyblockflipper.core.pricing;

import jeff.skyblockflipper.core.model.BazaarProduct;
import jeff.skyblockflipper.core.model.BazaarSnapshot;
import jeff.skyblockflipper.core.model.OrderLevel;
import jeff.skyblockflipper.core.pricing.CraftQuote.FillHistory;
import jeff.skyblockflipper.core.pricing.FusionQuote.Fusion;
import jeff.skyblockflipper.core.pricing.FusionQuote.Leaf;
import jeff.skyblockflipper.core.pricing.FusionQuote.SourceRoute;
import jeff.skyblockflipper.core.recipe.FusionImporter;
import jeff.skyblockflipper.core.recipe.FusionTable;

import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the fusion arithmetic and the gates it refuses at.
 *
 * <p>The graph is hand-built here, not the shipped one, so the cost of a route is a number this test
 * can compute by hand: {@code (minCost(a)*fuseA + minCost(b)*fuseB) / effectiveOutputQty}. The three
 * things worth pinning are the min-cost recursion choosing to fuse an intermediate when that beats
 * buying it, the reptile double-output lowering the per-output cost, and the one-sided exit gate.
 */
class FusionQuoteTest {
	private static final Duration HOUR = Duration.ofHours(1);
	private static final long PLENTY = 1_000_000_000L;
	private static final double TAX_KEPT = 0.9875d; // Fees.none(): 1.25% bazaar tax

	private final Map<String, BazaarProduct> products = new HashMap<>();

	/** A shard with only an ask side, instant-bought at {@code ask}. */
	private void askOnly(String id, double ask, long weeklyBought) {
		products.put(id, new BazaarProduct(id,
				List.of(new OrderLevel(ask, 1_000_000L, 20)),
				List.of(),
				new BazaarProduct.MovingWeek(weeklyBought, 0L)));
	}

	/** A sell target: deep ask side, no bid side, which is what the best shards to sell into are. */
	private void target(String id, double ask, int askOrders, long weeklyBought) {
		products.put(id, new BazaarProduct(id,
				List.of(new OrderLevel(ask, 1_000_000L, askOrders)),
				List.of(),
				new BazaarProduct.MovingWeek(weeklyBought, 0L)));
	}

	private BazaarSnapshot bazaar() {
		return new BazaarSnapshot(Instant.now(), Map.copyOf(products));
	}

	private FusionTable table(String json) {
		return FusionImporter.parse(new StringReader(json));
	}

	private Optional<FusionQuote> quote(FusionTable table, String outputId, int croc) {
		FusionQuote.Solver solver = FusionQuote.solver(table, bazaar(), croc);

		return FusionQuote.quote(solver, outputId, Fees.none(), FillHistory.none(), HOUR, PLENTY);
	}

	@Test
	void pricesASingleStepFusionAndItsOfferExit() {
		// SHARD_OUT = SHARD_IN1 x5 + SHARD_IN2 x5 -> 2.
		String json = """
				{"shards":{
				  "A":{"name":"In One","family":"Bug Family","rarity":"common","fuse_amount":5,"internal_id":"SHARD_IN1"},
				  "B":{"name":"In Two","family":"Bug Family","rarity":"common","fuse_amount":5,"internal_id":"SHARD_IN2"},
				  "O":{"name":"Out","family":"Bug Family","rarity":"rare","fuse_amount":5,"internal_id":"SHARD_OUT"}},
				 "recipes":{"O":{"2":[["A","B"]]}}}""";

		askOnly("SHARD_IN1", 100.0d, 168_000L);
		askOnly("SHARD_IN2", 200.0d, 168_000L);
		target("SHARD_OUT", 1000.0d, 20, 168_000L);

		FusionQuote q = quote(table(json), "SHARD_OUT", 0).orElseThrow();

		// (100*5 + 200*5) / 2 = 750 per output.
		assertEquals(750.0d, q.sourceCostPerOutput(), 1e-6);
		assertEquals(999.9d, q.unitSellPrice(), 1e-6);
		assertEquals(999.9d * TAX_KEPT - 750.0d, q.netPerOutput(), 1e-6);

		// Two base shards bought instantly, one fusion click, and one click makes an output.
		assertEquals(2, q.leaves().size());
		assertEquals(1, q.fusions().size());
		Fusion fusion = q.fusions().get(0);
		assertEquals("SHARD_OUT", fusion.outputId());
		assertEquals(2, fusion.outputQuantity());
		assertEquals(0.5d, q.fusionsPerOutput(), 1e-9); // one click yields two outputs
		for (Leaf leaf : q.leaves()) {
			assertEquals(SourceRoute.INSTANT_BUY, leaf.route());
			assertEquals(2.5d, leaf.unitsPerOutput(), 1e-9); // 5 per click / 2 outputs per click
		}
	}

	@Test
	void fusesAnIntermediateWhenThatBeatsBuyingIt() {
		// SHARD_OUT = SHARD_MID + SHARD_IN3; SHARD_MID = SHARD_IN1 + SHARD_IN2. Buying MID is dear, so
		// the solver should fuse it instead and the leaves should be the three base shards.
		String json = """
				{"shards":{
				  "A":{"name":"In One","family":"Bug Family","rarity":"common","fuse_amount":5,"internal_id":"SHARD_IN1"},
				  "B":{"name":"In Two","family":"Bug Family","rarity":"common","fuse_amount":5,"internal_id":"SHARD_IN2"},
				  "C":{"name":"In Three","family":"Bug Family","rarity":"common","fuse_amount":5,"internal_id":"SHARD_IN3"},
				  "M":{"name":"Mid","family":"Bug Family","rarity":"uncommon","fuse_amount":5,"internal_id":"SHARD_MID"},
				  "O":{"name":"Out","family":"Bug Family","rarity":"rare","fuse_amount":5,"internal_id":"SHARD_OUT"}},
				 "recipes":{
				   "M":{"1":[["A","B"]]},
				   "O":{"1":[["M","C"]]}}}""";

		askOnly("SHARD_IN1", 100.0d, 168_000L);
		askOnly("SHARD_IN2", 100.0d, 168_000L);
		askOnly("SHARD_IN3", 100.0d, 168_000L);
		askOnly("SHARD_MID", 5_000_000.0d, 168_000L); // buying MID is far dearer than fusing it
		target("SHARD_OUT", 100_000.0d, 20, 168_000L);

		FusionQuote q = quote(table(json), "SHARD_OUT", 0).orElseThrow();

		// minCost(MID) = (100*5 + 100*5)/1 = 1000; minCost(OUT) = (1000*5 + 100*5)/1 = 5500.
		assertEquals(5500.0d, q.sourceCostPerOutput(), 1e-6);

		// Three base shards, MID never appears as a leaf, and two fusion clicks (make MID, then OUT).
		List<String> leafIds = q.leaves().stream().map(Leaf::shardId).sorted().toList();
		assertEquals(List.of("SHARD_IN1", "SHARD_IN2", "SHARD_IN3"), leafIds);
		assertEquals(2, q.fusions().size());
		// Bottom-up: the intermediate is fused before the output that consumes it.
		assertEquals("SHARD_MID", q.fusions().get(0).outputId());
		assertEquals("SHARD_OUT", q.fusions().get(1).outputId());
	}

	@Test
	void theReptilePerkLowersCostPerOutputOnlyWhenTheLevelIsSet() {
		// SHARD_OUT = SHARD_REP (Reptile) + SHARD_IN2 -> 2. Reptile earns the crocodile double-output.
		String json = """
				{"shards":{
				  "R":{"name":"Rep","family":"Reptile Family","rarity":"common","fuse_amount":2,"internal_id":"SHARD_REP"},
				  "B":{"name":"In Two","family":"Bug Family","rarity":"common","fuse_amount":5,"internal_id":"SHARD_IN2"},
				  "O":{"name":"Out","family":"Reptile Family","rarity":"rare","fuse_amount":5,"internal_id":"SHARD_OUT"}},
				 "recipes":{"O":{"2":[["R","B"]]}}}""";

		askOnly("SHARD_REP", 100.0d, 168_000L);
		askOnly("SHARD_IN2", 100.0d, 168_000L);
		target("SHARD_OUT", 100_000.0d, 20, 168_000L);

		// croc 0: effQty = 2, cost = (100*2 + 100*5)/2 = 350.
		FusionQuote off = quote(table(json), "SHARD_OUT", 0).orElseThrow();
		assertEquals(350.0d, off.sourceCostPerOutput(), 1e-6);
		assertTrue(off.fusions().get(0).reptile());

		// croc 10: crocMultiplier = 1.2, effQty = 2.4, cost = 700 / 2.4.
		FusionQuote on = quote(table(json), "SHARD_OUT", 10).orElseThrow();
		assertEquals(700.0d / 2.4d, on.sourceCostPerOutput(), 1e-6);
		assertTrue(on.sourceCostPerOutput() < off.sourceCostPerOutput());
	}

	@Test
	void refusesATargetUnderTheAskGate() {
		String json = """
				{"shards":{
				  "A":{"name":"In One","family":"Bug Family","rarity":"common","fuse_amount":5,"internal_id":"SHARD_IN1"},
				  "B":{"name":"In Two","family":"Bug Family","rarity":"common","fuse_amount":5,"internal_id":"SHARD_IN2"},
				  "O":{"name":"Out","family":"Bug Family","rarity":"rare","fuse_amount":5,"internal_id":"SHARD_OUT"}},
				 "recipes":{"O":{"2":[["A","B"]]}}}""";

		askOnly("SHARD_IN1", 100.0d, 168_000L);
		askOnly("SHARD_IN2", 200.0d, 168_000L);
		target("SHARD_OUT", 1000.0d, 10, 168_000L); // only 10 resting ask orders, under the 15 gate

		assertTrue(quote(table(json), "SHARD_OUT", 0).isEmpty());
	}

	@Test
	void refusesWhenABaseShardHasNoBazaarProduct() {
		// SHARD_IN2 is named by the recipe but never listed on the bazaar. It must be treated as
		// unbuyable (infinite cost at every depth), so the output is refused - not priced as if the
		// missing shard were free. Regression for a memo that left deep cost cells at 0.0.
		String json = """
				{"shards":{
				  "A":{"name":"In One","family":"Bug Family","rarity":"common","fuse_amount":5,"internal_id":"SHARD_IN1"},
				  "B":{"name":"In Two","family":"Bug Family","rarity":"common","fuse_amount":5,"internal_id":"SHARD_IN2"},
				  "O":{"name":"Out","family":"Bug Family","rarity":"rare","fuse_amount":5,"internal_id":"SHARD_OUT"}},
				 "recipes":{"O":{"2":[["A","B"]]}}}""";

		askOnly("SHARD_IN1", 100.0d, 168_000L);
		// SHARD_IN2 deliberately absent from the book.
		target("SHARD_OUT", 1000.0d, 20, 168_000L);

		assertTrue(quote(table(json), "SHARD_OUT", 0).isEmpty());
	}

	@Test
	void refusesWhenTheOutputWouldNotClearAfterTax() {
		String json = """
				{"shards":{
				  "A":{"name":"In One","family":"Bug Family","rarity":"common","fuse_amount":5,"internal_id":"SHARD_IN1"},
				  "B":{"name":"In Two","family":"Bug Family","rarity":"common","fuse_amount":5,"internal_id":"SHARD_IN2"},
				  "O":{"name":"Out","family":"Bug Family","rarity":"rare","fuse_amount":5,"internal_id":"SHARD_OUT"}},
				 "recipes":{"O":{"2":[["A","B"]]}}}""";

		askOnly("SHARD_IN1", 1000.0d, 168_000L);
		askOnly("SHARD_IN2", 1000.0d, 168_000L);
		target("SHARD_OUT", 5000.0d, 20, 168_000L); // make cost 5000, sale after tax under that

		assertFalse(5000.0d * TAX_KEPT - 5000.0d > 0.0d); // the flip loses money
		assertTrue(quote(table(json), "SHARD_OUT", 0).isEmpty());
	}
}
