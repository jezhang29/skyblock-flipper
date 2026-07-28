package jeff.skyblockflipper.core.valuation;

import java.util.List;

/**
 * What an item configuration has actually been selling for.
 *
 * @param key         signature or coarse key this was computed for
 * @param median      the estimate itself. A median, never a mean: one troll listing or one
 *                    whale purchase moves a mean enough to invent an opportunity that is not there
 * @param samples     how many realized sales back it
 * @param dispersion  interquartile range over the median; how much the market disagrees with itself
 * @param salesPerHour observed sale rate for this configuration, which is how long your resale
 *                    will take rather than how long you would like it to take
 * @param basis       how completely the sales behind it describe the item
 */
public record ValueEstimate(
		String key,
		double median,
		int samples,
		double dispersion,
		double salesPerHour,
		Basis basis
) {
	/**
	 * How completely the sales behind an estimate describe the item, and what that costs it.
	 *
	 * <p>The multiplier is applied to {@link #confidence()}, so it decides which estimates can
	 * clear {@code minConfidence} at all rather than merely ranking them.
	 */
	public enum Basis {
		/** Matched the item's full decoded signature. Nothing about it is unaccounted for. */
		EXACT(1.0d),

		/**
		 * Matched a deliberately widened key: right item, approximately the right configuration.
		 * Today this is only a pet priced from a level band, or from sales at any level.
		 *
		 * <p>Penalised, but not out of reach of the default {@code minConfidence} of 0.6 the way
		 * {@link #COARSE} is. A widened key is still the right item with the right held item and
		 * only its level approximated, which on the recorded tape is worth discounting rather than
		 * discarding: walking the ladder beat pricing pets at any level by 0.134 against 0.146
		 * median absolute log error, so the rungs below the first are better than nothing and
		 * clearly worse than a full match.
		 */
		BANDED(0.85d),

		/**
		 * Matched only the name and rarity, so anything the name does not mention - enchantments,
		 * gemstones, hot potato books - is unaccounted for. Its ceiling is exactly 0.6, so under
		 * the default settings it never justifies a purchase on its own.
		 */
		COARSE(0.6d);

		private final double confidenceMultiplier;

		Basis(double confidenceMultiplier) {
			this.confidenceMultiplier = confidenceMultiplier;
		}

		public double confidenceMultiplier() {
			return confidenceMultiplier;
		}
	}

	/** Under this many sales, a median is an anecdote. */
	public static final int MIN_SAMPLES = 6;

	/** True only for a full signature match; a widened or coarse key is not the same item. */
	public boolean exact() {
		return basis == Basis.EXACT;
	}

	/**
	 * The same figures, re-labelled with how the item that asked for them actually matched.
	 *
	 * <p>An index entry does not know this on its own. One key can be the exact signature of one
	 * item and a deliberately widened match for another - a pet with no readable level and a
	 * level 100 pet of the same type both land on the levelless key - so the basis belongs to the
	 * lookup, not to the stored median.
	 */
	public ValueEstimate withBasis(Basis newBasis) {
		return basis == newBasis ? this
				: new ValueEstimate(key, median, samples, dispersion, salesPerHour, newBasis);
	}

	/**
	 * Confidence in the estimate, on the 0-1 scale the rest of the mod uses.
	 *
	 * <p>Rises with sample count and falls with disagreement between those samples, then pays
	 * whatever its {@link Basis} costs.
	 */
	public double confidence() {
		double sampleScore = Math.min(1.0d, samples / 20.0d);
		double tightness = Math.clamp(1.0d - dispersion, 0.0d, 1.0d);
		double confidence = 0.25d + 0.45d * sampleScore + 0.30d * tightness;

		return confidence * basis.confidenceMultiplier();
	}

	public boolean isUsable() {
		return samples >= MIN_SAMPLES;
	}

	/** Expected hours to resell one unit, from the observed rate. Bounded: this is an estimate. */
	public double hoursToSell() {
		if (salesPerHour <= 0.0d) {
			return MAX_HOURS_TO_SELL;
		}

		return Math.clamp(1.0d / salesPerHour, MIN_HOURS_TO_SELL, MAX_HOURS_TO_SELL);
	}

	/** Even the fastest-moving item needs finding, buying and relisting. */
	private static final double MIN_HOURS_TO_SELL = 0.25d;

	/** Past this, calling it a per-hour rate is a fiction. */
	private static final double MAX_HOURS_TO_SELL = 48.0d;

	/**
	 * Builds an estimate from realized unit prices.
	 *
	 * @param prices      unit prices, in any order
	 * @param windowHours how long the sales were collected over, for the sale rate
	 */
	public static ValueEstimate of(String key, List<Double> prices, double windowHours, Basis basis) {
		double[] sorted = prices.stream().mapToDouble(Double::doubleValue).sorted().toArray();

		double median = percentile(sorted, 0.5d);
		double spread = percentile(sorted, 0.75d) - percentile(sorted, 0.25d);

		return new ValueEstimate(
				key,
				median,
				sorted.length,
				median > 0.0d ? spread / median : 1.0d,
				windowHours > 0.0d ? sorted.length / windowHours : 0.0d,
				basis);
	}

	private static double percentile(double[] sorted, double fraction) {
		if (sorted.length == 0) {
			return 0.0d;
		}

		int index = (int) Math.round(fraction * (sorted.length - 1));
		return sorted[Math.clamp(index, 0, sorted.length - 1)];
	}
}
