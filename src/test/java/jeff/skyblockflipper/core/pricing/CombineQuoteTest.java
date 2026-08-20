package jeff.skyblockflipper.core.pricing;

import jeff.skyblockflipper.core.model.BazaarProduct;
import jeff.skyblockflipper.core.model.BazaarSnapshot;
import jeff.skyblockflipper.core.model.OrderLevel;
import jeff.skyblockflipper.core.pricing.CombineQuote.Bound;
import jeff.skyblockflipper.core.pricing.CombineQuote.SourceRoute;
import jeff.skyblockflipper.core.pricing.CraftQuote.FillHistory;
import jeff.skyblockflipper.core.strategy.CombineTable.Entry;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the combine arithmetic and every gate it refuses at.
 *
 * <p>The gates are the substance, and for the same reason craft's are: a combine quote that returns
 * a number where it should return nothing ranks first, because a fantasy ask price and a missing
 * source both flatter the flip. The exit gate is the one that differs from craft - it reads the
 * target's ask side only - and the source-tier scan is the one thing the arithmetic does that a
 * single-leg craft does not, so both get a test of their own.
 */
class CombineQuoteTest {
	private static final Duration HOUR = Duration.ofHours(1);
	private static final long PLENTY = 1_000_000_000L;
	private static final double TAX_KEPT = 0.9875d; // Fees.none(): 1.25% bazaar tax

	private final Map<String, BazaarProduct> products = new HashMap<>();

	/** A two-sided book, deep enough on both sides to rest an order against. */
	private void book(String id, double ask, double bid, long weeklyBought, long weeklySold) {
		products.put(id, new BazaarProduct(id,
				List.of(new OrderLevel(ask, 1_000_000L, 20)),
				List.of(new OrderLevel(bid, 1_000_000L, 20)),
				new BazaarProduct.MovingWeek(weeklyBought, weeklySold)));
	}

	/** A target with a deep ask side and no bid side at all, which is what the best targets are. */
	private void askOnly(String id, double ask, int askOrders, long weeklyBought) {
		products.put(id, new BazaarProduct(id,
				List.of(new OrderLevel(ask, 1_000_000L, askOrders)),
				List.of(),
				new BazaarProduct.MovingWeek(weeklyBought, 0L)));
	}

	private BazaarSnapshot bazaar() {
		return new BazaarSnapshot(Instant.now(), Map.copyOf(products));
	}

	private Optional<CombineQuote> quote(Entry entry) {
		return CombineQuote.quote(entry, bazaar(), Fees.none(), FillHistory.none(), HOUR, PLENTY);
	}

	@Test
	void pricesTheOfferExitAndTheRestingSourceLeg() {
		// TEST 2->3: one source tier, two books an output, one combine.
		Entry entry = new Entry("TEST", 2, 3);
		askOnly("ENCHANTMENT_TEST_3", 1000.0d, 20, 168_000L);      // demand 1000/h
		book("ENCHANTMENT_TEST_2", 150.0d, 100.0d, 168_000L, 168_000L); // dumps 1000/h

		CombineQuote q = quote(entry).orElseThrow();

		assertEquals(SourceRoute.BUY_ORDER, q.route());
		assertEquals(2, q.sourceTier());
		// Sell one increment under the 1000 ask, taxed; buy two source books at bid + 0.1.
		assertEquals(999.9d, q.unitSellPrice(), 1e-9);
		assertEquals(2 * 100.1d, q.sourceCostPerOutput(), 1e-9);
		assertEquals(999.9d * TAX_KEPT - 200.2d, q.netPerOutput(), 1e-6);
		// One combine an output, so net per combine is net per output.
		assertEquals(q.netPerOutput(), q.netPerCombine(), 1e-9);
	}

	@Test
	void rejectsATargetRestingFewerThanFifteenAskOrders() {
		Entry entry = new Entry("TEST", 2, 3);
		book("ENCHANTMENT_TEST_2", 150.0d, 100.0d, 168_000L, 168_000L);

		askOnly("ENCHANTMENT_TEST_3", 1000.0d, 14, 168_000L);
		assertTrue(quote(entry).isEmpty(), "14 ask orders is a fantasy price and must be refused");

		askOnly("ENCHANTMENT_TEST_3", 1000.0d, 15, 168_000L);
		assertTrue(quote(entry).isPresent(), "15 ask orders is a real book");
	}

	@Test
	void scansSourceTiersAndKeepsTheCheapest() {
		// TEST 1->3: tier 1 needs four books an output, tier 2 needs two. Price tier 2 so that two of
		// it undercut four of tier 1, which is the real Rejuvenate shape: the bottom tier is not the
		// cheapest source.
		Entry entry = new Entry("TEST", 1, 3);
		askOnly("ENCHANTMENT_TEST_3", 100_000.0d, 20, 168_000L);
		book("ENCHANTMENT_TEST_1", 300.0d, 250.0d, 168_000L, 168_000L); // 4 x 250.1 = 1000.4
		book("ENCHANTMENT_TEST_2", 600.0d, 100.0d, 168_000L, 168_000L); // 2 x 100.1 =  200.2

		CombineQuote q = quote(entry).orElseThrow();

		assertEquals(2, q.sourceTier(), "tier 2 is the cheaper source per output");
		assertEquals(200.2d, q.sourceCostPerOutput(), 1e-9);
	}

	@Test
	void instantBuysASourceWithNoBidSide() {
		// Feather Falling 6 has a deep ask and no bid: it cannot be rested, only taken at the ask.
		Entry entry = new Entry("TEST", 2, 3);
		askOnly("ENCHANTMENT_TEST_3", 100_000.0d, 20, 168_000L);
		askOnly("ENCHANTMENT_TEST_2", 30.0d, 20, 168_000L); // ask only, no bid to outbid

		CombineQuote q = quote(entry).orElseThrow();

		assertEquals(SourceRoute.INSTANT_BUY, q.route());
		assertEquals(2 * 30.0d, q.sourceCostPerOutput(), 1e-9);
	}

	@Test
	void prefersTheCheaperRestingSourceEvenWhenInstantFillsFaster() {
		// The source dumps slowly (a slow resting fill) but is lifted fast (a fast instant fill), and
		// resting is a touch cheaper. Profit per hour would take the instant route for its speed; net
		// per combine takes the cheaper one, because this player is spending clicks, not hours.
		Entry entry = new Entry("TEST", 2, 3);
		askOnly("ENCHANTMENT_TEST_3", 100_000.0d, 20, 168_000_000L); // demand never binds
		book("ENCHANTMENT_TEST_2", 200.0d, 100.0d, 1_680_000L, 1_680L);

		CombineQuote q = quote(entry).orElseThrow();

		assertEquals(SourceRoute.BUY_ORDER, q.route(), "the cheaper resting source must win on net/click");
		assertEquals(2 * 100.1d, q.sourceCostPerOutput(), 1e-9);
	}

	@Test
	void refusesWhenTheSourceCostsMoreThanTheTaxedSale() {
		Entry entry = new Entry("TEST", 2, 3);
		askOnly("ENCHANTMENT_TEST_3", 1000.0d, 20, 168_000L);
		// Two source books at 600 each is 1200, over the ~987 the sale nets after tax.
		book("ENCHANTMENT_TEST_2", 700.0d, 600.0d, 168_000L, 168_000L);

		assertTrue(quote(entry).isEmpty(), "a bill over the taxed sale is a loss, not a flip");
	}

	@Test
	void theSlowerRestingLegBoundsTheRate() {
		// Target sells fast (huge demand); the source dumps slowly, so the source binds. The source
		// ask is high enough that instant-buying it is a loss, so the resting route is the one left
		// and its dump-limited rate is what the plan runs at.
		Entry entry = new Entry("TEST", 2, 3);
		askOnly("ENCHANTMENT_TEST_3", 1000.0d, 20, 16_800_000L);       // demand 100k/h
		book("ENCHANTMENT_TEST_2", 700.0d, 100.0d, 168_000L, 33_600L); // dumps 200/h, ask a loss

		CombineQuote q = quote(entry).orElseThrow();

		assertEquals(SourceRoute.BUY_ORDER, q.route());
		assertEquals(Bound.SOURCE_SUPPLY, q.bound());
		// 200 dumps/h at 5% share, two books an output: 5 outputs an hour.
		assertEquals(5.0d, q.outputsPerHour(), 1e-9);
	}
}
