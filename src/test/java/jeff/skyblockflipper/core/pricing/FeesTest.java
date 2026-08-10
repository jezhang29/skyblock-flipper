package jeff.skyblockflipper.core.pricing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeesTest {
	@Test
	void bazaarTaxStartsAt125BasisPointsAndFallsPerPerkLevel() {
		assertEquals(0.0125d, new Fees(0, false).bazaarTaxRate(), 1e-9);
		assertEquals(0.01125d, new Fees(1, false).bazaarTaxRate(), 1e-9);
		assertEquals(0.0100d, new Fees(2, false).bazaarTaxRate(), 1e-9);
	}

	@Test
	void bazaarTaxNeverFallsBelowOnePercent() {
		// Maxing the perk lands exactly on the floor, so the floor is not what stops it going
		// lower - the two-level clamp is. Both have to hold for a margin to be quoted correctly.
		assertEquals(0.01d, new Fees(Fees.MAX_BAZAAR_FLIPPER_LEVEL, false).bazaarTaxRate(), 1e-9);
	}

	@Test
	void perkLevelIsClampedToTheRealRange() {
		assertEquals(0.01d, new Fees(99, false).bazaarTaxRate(), 1e-9);
		assertEquals(0.0125d, new Fees(-5, false).bazaarTaxRate(), 1e-9);
	}

	@Test
	void orderSlotsStartAt14AndGain7PerPerkLevel() {
		assertEquals(14, new Fees(0, false).bazaarOrderSlots());
		assertEquals(21, new Fees(1, false).bazaarOrderSlots());
		assertEquals(28, new Fees(2, false).bazaarOrderSlots());
	}

	@Test
	void orderSlotsCannotExceedTheGameMaximum() {
		// The reason the perk level had to stop at 2. Unlike the tax, this has no floor to hide
		// an out-of-range level behind: a level of 6 would read 56 slots against a real 28.
		assertEquals(28, new Fees(99, false).bazaarOrderSlots());
		assertEquals(14, new Fees(-5, false).bazaarOrderSlots());
	}

	@Test
	void bazaarRoundTripIsUnprofitableInsideTheTax() {
		Fees fees = new Fees(0, false);

		// Buy at 100, sell at 101: the 1.25% tax on the sale eats the whole coin of spread.
		assertTrue(fees.bazaarRoundTripProfit(100.0d, 101.0d) < 0.0d);

		// A 2% gross spread clears it.
		assertTrue(fees.bazaarRoundTripProfit(100.0d, 102.0d) > 0.0d);
	}

	@Test
	void binListingFeeStepsUpAtTenAndOneHundredMillion() {
		Fees fees = new Fees(0, false);

		assertEquals(90_000L, fees.binListingFee(9_000_000L));
		assertEquals(200_000L, fees.binListingFee(10_000_000L));
		assertEquals(2_500_000L, fees.binListingFee(100_000_000L));
	}

	@Test
	void claimTaxOnlyAppliesAboveOneMillion() {
		Fees fees = new Fees(0, false);

		assertEquals(0L, fees.claimTax(999_999L));
		assertEquals(0L, fees.claimTax(1_000_000L));
		assertEquals(20_000L, fees.claimTax(2_000_000L));
	}

	@Test
	void claimTaxCannotDragAPayoutBelowTheThreshold() {
		// A sale just over 1M must not net less than a sale at exactly 1M.
		Fees fees = new Fees(0, false);
		long price = 1_005_000L;

		assertTrue(price - fees.claimTax(price) >= 1_000_000L);
	}

	@Test
	void derpyQuadruplesAuctionFeesButNotBazaarTax() {
		Fees normal = new Fees(0, false);
		Fees derpy = new Fees(0, true);

		assertEquals(normal.binListingFee(5_000_000L) * 4, derpy.binListingFee(5_000_000L));
		assertEquals(normal.claimTax(5_000_000L) * 4, derpy.claimTax(5_000_000L));

		// Derpy is an auction house effect; bazaar tax is untouched.
		assertEquals(normal.bazaarTaxRate(), derpy.bazaarTaxRate(), 1e-9);
	}

	@Test
	void derpyTurnsAThinAuctionFlipIntoALoss() {
		long buy = 50_000_000L;
		long sell = 54_000_000L;

		// 8% gross clears normal fees comfortably...
		assertTrue(new Fees(0, false).binRoundTripProfit(buy, sell) > 0L);

		// ...but 4x listing fee plus 4x claim tax is roughly 12%, which swallows it.
		assertTrue(new Fees(0, true).binRoundTripProfit(buy, sell) < 0L);
	}
}
