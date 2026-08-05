package jeff.skyblockflipper.core.ledger;

import java.util.OptionalDouble;

/**
 * What the ledger says actually happened, as opposed to what the strategies promised.
 *
 * <p>This is the first thing in the mod that can measure adverse selection instead of warning
 * about it. Bazaar buy orders fill fastest exactly while the price is falling, so realized margin
 * runs below quoted margin as a rule, not as an accident. {@link #captureRate()} is that gap,
 * measured on your own fills.
 *
 * @param closed         positions closed with a quote to hold them against, which is the sample
 *                       the capture rate is computed on
 * @param unquoted       positions the tracker recorded that no strategy ever quoted. They count
 *                       toward the fill rate and are kept out of the capture rate, since a quote
 *                       of zero in the denominator would read as a total shortfall
 * @param quotedOnFilled what the plans promised, counted only on units that actually transacted
 * @param realized       what those same units actually paid
 */
public record LedgerStats(
		int closed,
		int abandoned,
		int unquoted,
		long unitsPlanned,
		long unitsFilled,
		double quotedOnFilled,
		double realized
) {
	/**
	 * Below this many closed positions, the capture rate is a story about two or three trades
	 * rather than a measurement, and acting on it would be worse than ignoring it.
	 */
	public static final int MIN_MEANINGFUL_SAMPLES = 5;

	public static LedgerStats empty() {
		return new LedgerStats(0, 0, 0, 0L, 0L, 0.0d, 0.0d);
	}

	/**
	 * Realized profit as a fraction of quoted, on filled units. 1.0 means the quotes were honest;
	 * 0.6 means four coins in ten never showed up.
	 */
	public OptionalDouble captureRate() {
		return quotedOnFilled == 0.0d ? OptionalDouble.empty() : OptionalDouble.of(realized / quotedOnFilled);
	}

	/**
	 * How much of the planned size actually transacted. A high capture rate on a fill rate of 0.2
	 * means the good prices were real but almost unreachable, which is its own answer.
	 */
	public OptionalDouble fillRate() {
		return unitsPlanned == 0L ? OptionalDouble.empty() : OptionalDouble.of((double) unitsFilled / unitsPlanned);
	}

	public boolean isMeaningful() {
		return closed >= MIN_MEANINGFUL_SAMPLES;
	}
}
