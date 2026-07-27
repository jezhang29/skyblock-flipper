package jeff.skyblockflipper.core.valuation;

/**
 * Which way one product's price has been moving, and how violently.
 *
 * <p>This exists to answer the question a single order book cannot: a wide spread looks identical
 * whether the item is simply illiquid or whether the price is falling out from under it, and those
 * two cases pay very differently. Market making into a falling market is the classic way a quoted
 * margin turns into a realized loss - your buy orders fill fastest exactly when people are dumping,
 * and your sell offers fill slowest at the same moment.
 *
 * <p>All figures are midpoints, never one side of the book. See {@code BazaarSample.mid()}.
 *
 * @param productId    bazaar product id
 * @param latest       most recent midpoint
 * @param shortAverage mean midpoint over the recent sub-window
 * @param longAverage  mean midpoint over the whole window
 * @param volatility   standard deviation of log returns between consecutive samples, as a
 *                     fraction. How much the price jitters from one sample to the next
 * @param dispersion   standard deviation of the prices themselves over the mean. How wide a range
 *                     the price has covered, which is a different question from how jumpy it is:
 *                     a smooth climb has a large dispersion and almost no volatility
 * @param samples      how many samples back this, so a thin series can be discounted
 */
public record PriceTrend(
		String productId,
		double latest,
		double shortAverage,
		double longAverage,
		double volatility,
		double dispersion,
		int samples
) {
	/**
	 * Under this many samples the averages are describing minutes, not a trend.
	 *
	 * <p>Set low deliberately. The degenerate early case is safe on its own: until the series is
	 * longer than the short sub-window, both averages cover the same samples and {@link #drift()}
	 * is zero, which reads as "no signal" rather than as a false one.
	 */
	public static final int MIN_SAMPLES = 12;

	/**
	 * Recent price relative to the longer average, as a signed fraction.
	 *
	 * <p>Negative means the item has been getting cheaper: buy orders placed now will fill into
	 * that decline. Positive means it has been getting more expensive, which helps a market maker
	 * holding inventory and hurts one who has not bought yet.
	 */
	public double drift() {
		return longAverage <= 0.0d ? 0.0d : (shortAverage - longAverage) / longAverage;
	}

	/** Falling faster than {@code threshold}, expressed as a positive fraction. */
	public boolean isFalling(double threshold) {
		return drift() < -Math.abs(threshold);
	}

	public boolean isRising(double threshold) {
		return drift() > Math.abs(threshold);
	}

	/**
	 * How far the latest print sits above the longer average, in standard deviations.
	 *
	 * <p>Measured against the series' own spread rather than a fixed percentage, because a 10%
	 * move means something entirely different on a stable material than on one that routinely
	 * swings 30% a day.
	 *
	 * <p>Scaled by {@link #dispersion()} and deliberately not by {@link #volatility()}. A steady
	 * climb produces almost identical returns sample to sample, so its return volatility is close
	 * to zero - dividing by that would report an enormous sigma for an item that has merely been
	 * drifting upward all day, which is the opposite of a spike.
	 */
	public double spikeSigma() {
		if (longAverage <= 0.0d || dispersion <= 0.0d) {
			return 0.0d;
		}

		return (latest - longAverage) / (dispersion * longAverage);
	}

	/**
	 * An abrupt move well outside this product's normal range.
	 *
	 * <p>On its own this is not evidence of manipulation - real news moves prices too. It only
	 * becomes a red flag combined with thin volume, which is why that half of the test lives in
	 * the strategy where the live order book is also visible.
	 */
	public boolean isSpiking(double sigma) {
		return spikeSigma() > Math.abs(sigma);
	}

	/** Enough samples for the numbers above to be worth reading. */
	public boolean isUsable() {
		return samples >= MIN_SAMPLES;
	}
}
