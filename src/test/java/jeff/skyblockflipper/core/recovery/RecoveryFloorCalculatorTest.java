package jeff.skyblockflipper.core.recovery;

import jeff.skyblockflipper.core.pricing.Fees;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecoveryFloorCalculatorTest {
	private static RecoveryLeg ah(RecoveryComponentKind kind, long gross, long removal) {
		return new RecoveryLeg(kind, kind.name(), kind.name(), 1L, RecoveryExitVenue.AH,
				gross, removal, 12, 6.0d, 60_000L, 0L, RecoveryConfidence.HIGH, true,
				Set.of());
	}

	@Test
	void buffersGrossBeforeFeesAndSubtractsFixedRemovalAfterward() {
		RecoveryComponentQuote quote = RecoveryFloorCalculator.quote(
				ah(RecoveryComponentKind.GEMSTONE, 10_000_001L, 500_000L), 0.15d,
				Fees.none()).orElseThrow();

		assertEquals(8_500_000L, quote.bufferedGross());
		assertEquals(170_000L, quote.fee());
		assertEquals(7_830_000L, quote.netContribution());
	}

	@Test
	void bazaarFeeRoundsAgainstTheBufferedGrossInTheConservativeDirection() {
		RecoveryLeg leg = new RecoveryLeg(RecoveryComponentKind.GEMSTONE, "FINE_RUBY_GEM",
				"Fine Ruby Gemstone", 1L, RecoveryExitVenue.BAZAAR, 101L, 2L, 0, 0.0d,
				0L, 1L, RecoveryConfidence.HIGH, true, Set.of());

		RecoveryComponentQuote quote = RecoveryFloorCalculator.quote(leg, 0.15d,
				Fees.none()).orElseThrow();

		assertEquals(85L, quote.bufferedGross());
		assertEquals(2L, quote.fee());
		assertEquals(81L, quote.netContribution());
	}

	@Test
	void anExplainedMissingLegStaysVisibleAndContributesZero() {
		RecoveryLeg leg = RecoveryLeg.uncredited(RecoveryComponentKind.LEGACY_SHARD,
				"mana_pool:4", "Mana Pool IV", 1L, RecoveryWarning.PREVIEW_REQUIRED);

		RecoveryComponentQuote quote = RecoveryFloorCalculator.quote(leg, 0.15d,
				Fees.none()).orElseThrow();

		assertFalse(quote.credited());
		assertEquals(0L, quote.netContribution());
		assertEquals(Set.of(RecoveryWarning.PREVIEW_REQUIRED), quote.warnings());
	}

	@Test
	void rejectsZeroPurchaseInvalidBufferAndMalformedCreditedLegs() {
		RecoveryComponentQuote host = RecoveryFloorCalculator.quote(
				ah(RecoveryComponentKind.HOST, 1_000_000L, 0L), 0.15d, Fees.none())
				.orElseThrow();

		assertTrue(RecoveryFloorCalculator.quote(ah(RecoveryComponentKind.HOST, 1L, 0L),
				1.0d, Fees.none()).isEmpty());
		assertTrue(RecoveryFloorCalculator.compose("uuid", "ITEM", "Item", 0L, Instant.EPOCH,
				host, List.of()).isEmpty());
		assertThrows(IllegalArgumentException.class, () -> new RecoveryLeg(
				RecoveryComponentKind.GEMSTONE, "id", "name", 0L, RecoveryExitVenue.AH,
				1L, 0L, 1, 1.0d, 1L, 0L, RecoveryConfidence.HIGH, true, Set.of()));
	}

	@Test
	void overflowFailsClosedInsteadOfWrappingIntoAProfit() {
		RecoveryComponentQuote host = RecoveryFloorCalculator.quote(
				ah(RecoveryComponentKind.HOST, Long.MAX_VALUE, 0L), 0.0d, Fees.none())
				.orElseThrow();
		RecoveryComponentQuote component = RecoveryFloorCalculator.quote(
				ah(RecoveryComponentKind.GEMSTONE, Long.MAX_VALUE, 0L), 0.0d, Fees.none())
				.orElseThrow();

		assertTrue(RecoveryFloorCalculator.compose("uuid", "ITEM", "Item", 1L, Instant.EPOCH,
				host, List.of(component)).isEmpty());
	}

	@Test
	void composeUsesEveryLegAndKeysIdentityByAuctionUuid() {
		RecoveryComponentQuote host = RecoveryFloorCalculator.quote(
				ah(RecoveryComponentKind.HOST, 2_000_000L, 0L), 0.15d, Fees.none())
				.orElseThrow();
		RecoveryComponentQuote component = RecoveryFloorCalculator.quote(
				ah(RecoveryComponentKind.DRILL_ENGINE, 1_000_000L, 100_000L), 0.15d,
				Fees.none()).orElseThrow();

		RecoveryOpportunity opportunity = RecoveryFloorCalculator.compose("auction-uuid", "DRILL",
				"Drill", 1_000_000L, Instant.EPOCH, host, List.of(component)).orElseThrow();

		assertEquals("auction-uuid", opportunity.auctionUuid());
		assertEquals(host.netContribution() + component.netContribution(),
				opportunity.conservativeFloor());
		assertEquals(opportunity.conservativeFloor() - 1_000_000L,
				opportunity.expectedProfit());
		assertEquals(opportunity.expectedProfit() / 1_000_000.0d, opportunity.margin());
		assertEquals(32, opportunity.fingerprint().length());
	}
}
