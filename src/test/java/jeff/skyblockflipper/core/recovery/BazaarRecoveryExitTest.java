package jeff.skyblockflipper.core.recovery;

import jeff.skyblockflipper.core.model.BazaarProduct;
import jeff.skyblockflipper.core.model.OrderLevel;
import jeff.skyblockflipper.core.pricing.Fees;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class BazaarRecoveryExitTest {
	private static final RecoveryAttachment FINE_RUBY = new RecoveryAttachment(
			RecoveryComponentKind.GEMSTONE, "COMBAT_0", "FINE_RUBY_GEM", 3L);

	@Test
	void walksBidDepthThenAppliesBufferTaxAndFixedRemovalCost() {
		BazaarProduct product = product(List.of(new OrderLevel(100_000.0d, 2L, 1),
				new OrderLevel(90_000.0d, 2L, 1)), 1_680L);

		RecoveryComponentQuote quote = BazaarRecoveryExit.quote(FINE_RUBY, product, 5.0d,
				0.10d, new Fees(0, false)).orElseThrow();

		assertEquals(290_000L, quote.grossQuickSale());
		assertEquals(261_000L, quote.bufferedGross());
		assertEquals(3_263L, quote.fee());
		assertEquals(30_000L, quote.removalCost());
		assertEquals(227_737L, quote.netContribution());
		assertEquals(3L, quote.quotedDepth());
	}

	@Test
	void neverUsesTheAskSideForAnInstantSeller() {
		BazaarProduct product = new BazaarProduct("FINE_RUBY_GEM",
				List.of(new OrderLevel(9_000_000.0d, 100L, 1)),
				List.of(new OrderLevel(100_000.0d, 100L, 1)),
				new BazaarProduct.MovingWeek(1L, 1_680L));

		assertEquals(300_000L, BazaarRecoveryExit.quote(FINE_RUBY, product, 0.0d, 0.0d,
				Fees.none()).orElseThrow().grossQuickSale());
	}

	@Test
	void insufficientDepthAndFlowReceiveExplainedZeroCredit() {
		RecoveryComponentQuote shallow = BazaarRecoveryExit.quote(FINE_RUBY,
				product(List.of(new OrderLevel(100_000.0d, 2L, 1)), 1_680L), 1.0d,
				0.15d, Fees.none()).orElseThrow();
		RecoveryComponentQuote illiquid = BazaarRecoveryExit.quote(FINE_RUBY,
				product(List.of(new OrderLevel(100_000.0d, 3L, 1)), 1L), 1.0d,
				0.15d, Fees.none()).orElseThrow();

		assertFalse(shallow.credited());
		assertEquals(true, shallow.warnings().contains(RecoveryWarning.INSUFFICIENT_DEPTH));
		assertFalse(illiquid.credited());
		assertEquals(true, illiquid.warnings().contains(RecoveryWarning.ILLIQUID));
	}

	private static BazaarProduct product(List<OrderLevel> bids, long instantSoldWeekly) {
		return new BazaarProduct("FINE_RUBY_GEM", List.of(), bids,
				new BazaarProduct.MovingWeek(1L, instantSoldWeekly));
	}
}
