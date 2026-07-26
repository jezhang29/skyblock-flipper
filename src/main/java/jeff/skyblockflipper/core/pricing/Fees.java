package jeff.skyblockflipper.core.pricing;

/**
 * The fee stack, applied before any strategy is allowed to call something profitable.
 *
 * <p>This is deliberately the first thing every candidate passes through. A round trip costs
 * roughly 1-1.25% on the bazaar and 2-3.5% on the auction house, so any idea with a gross margin
 * under that is a coin shredder that looks like a business.
 *
 * @param bazaarFlipperLevel Bazaar Flipper perk level, 0-6
 * @param derpy              whether Derpy currently holds office, which quadruples every AH fee
 */
public record Fees(int bazaarFlipperLevel, boolean derpy) {
	/** Base bazaar sales tax before the Bazaar Flipper perk. */
	private static final double BAZAAR_BASE_TAX = 0.0125d;
	/** Each perk level removes this much tax... */
	private static final double BAZAAR_TAX_PER_LEVEL = 0.00125d;
	/** ...down to this floor. */
	private static final double BAZAAR_MIN_TAX = 0.01d;

	private static final double BIN_FEE_UNDER_10M = 0.01d;
	private static final double BIN_FEE_UNDER_100M = 0.02d;
	private static final double BIN_FEE_ABOVE_100M = 0.025d;

	/** Sales below this are not taxed on claim. */
	private static final long CLAIM_TAX_THRESHOLD = 1_000_000L;
	private static final double CLAIM_TAX_RATE = 0.01d;

	private static final int DERPY_MULTIPLIER = 4;

	public Fees {
		bazaarFlipperLevel = Math.clamp(bazaarFlipperLevel, 0, 6);
	}

	public static Fees none() {
		return new Fees(0, false);
	}

	/** Effective bazaar sales tax, as a fraction. */
	public double bazaarTaxRate() {
		return Math.max(BAZAAR_MIN_TAX, BAZAAR_BASE_TAX - BAZAAR_TAX_PER_LEVEL * bazaarFlipperLevel);
	}

	/** What actually lands in your purse from a bazaar sell offer of {@code gross} coins. */
	public double bazaarSaleProceeds(double gross) {
		return gross * (1.0d - bazaarTaxRate());
	}

	/**
	 * Net profit per unit for a bazaar round trip: buy at {@code buyPrice}, sell at
	 * {@code sellPrice}. Tax lands on the sale only; buying costs exactly what you pay.
	 */
	public double bazaarRoundTripProfit(double buyPrice, double sellPrice) {
		return bazaarSaleProceeds(sellPrice) - buyPrice;
	}

	/**
	 * Up-front fee to list a buy-it-now auction. Charged whether or not the item sells, which is
	 * what makes speculative listing of slow-moving items expensive.
	 */
	public long binListingFee(long price) {
		double rate;

		if (price < 10_000_000L) {
			rate = BIN_FEE_UNDER_10M;
		} else if (price < 100_000_000L) {
			rate = BIN_FEE_UNDER_100M;
		} else {
			rate = BIN_FEE_ABOVE_100M;
		}

		return Math.round(price * rate) * multiplier();
	}

	/**
	 * Tax deducted when claiming auction proceeds. Only applies above 1M, and is capped so it
	 * cannot drag a payout below the 1M threshold.
	 */
	public long claimTax(long price) {
		if (price <= CLAIM_TAX_THRESHOLD) {
			return 0L;
		}

		long tax = Math.round(price * CLAIM_TAX_RATE) * multiplier();

		return Math.min(tax, price - CLAIM_TAX_THRESHOLD);
	}

	/** Coins actually received from a BIN sale at {@code price}, after listing fee and claim tax. */
	public long binNetProceeds(long price) {
		return price - binListingFee(price) - claimTax(price);
	}

	/** Total round-trip auction cost of buying at {@code buyPrice} and reselling at {@code salePrice}. */
	public long binRoundTripProfit(long buyPrice, long salePrice) {
		return binNetProceeds(salePrice) - buyPrice;
	}

	private int multiplier() {
		return derpy ? DERPY_MULTIPLIER : 1;
	}
}
