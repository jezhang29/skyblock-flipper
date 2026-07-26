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
 * @param exact       true when the sales matched the item's full decoded signature, false when
 *                    they only matched its name and rarity
 */
public record ValueEstimate(
		String key,
		double median,
		int samples,
		double dispersion,
		double salesPerHour,
		boolean exact
) {
	/** Under this many sales, a median is an anecdote. */
	public static final int MIN_SAMPLES = 6;

	/**
	 * Confidence in the estimate, on the 0-1 scale the rest of the mod uses.
	 *
	 * <p>Rises with sample count and falls with disagreement between those samples. A coarse
	 * estimate is penalised hard: it matched only the name and rarity, so anything the name does
	 * not mention - enchantments, gemstones, hot potato books - is unaccounted for.
	 */
	public double confidence() {
		double sampleScore = Math.min(1.0d, samples / 20.0d);
		double tightness = Math.clamp(1.0d - dispersion, 0.0d, 1.0d);
		double confidence = 0.25d + 0.45d * sampleScore + 0.30d * tightness;

		return exact ? confidence : confidence * 0.6d;
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
	public static ValueEstimate of(String key, List<Double> prices, double windowHours, boolean exact) {
		double[] sorted = prices.stream().mapToDouble(Double::doubleValue).sorted().toArray();

		double median = percentile(sorted, 0.5d);
		double spread = percentile(sorted, 0.75d) - percentile(sorted, 0.25d);

		return new ValueEstimate(
				key,
				median,
				sorted.length,
				median > 0.0d ? spread / median : 1.0d,
				windowHours > 0.0d ? sorted.length / windowHours : 0.0d,
				exact);
	}

	private static double percentile(double[] sorted, double fraction) {
		if (sorted.length == 0) {
			return 0.0d;
		}

		int index = (int) Math.round(fraction * (sorted.length - 1));
		return sorted[Math.clamp(index, 0, sorted.length - 1)];
	}
}
