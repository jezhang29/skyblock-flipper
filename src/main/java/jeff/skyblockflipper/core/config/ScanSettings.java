package jeff.skyblockflipper.core.config;

/**
 * The settings the background poller needs, as a plain snapshot.
 *
 * <p>Handed to the poller through a supplier rather than copied once at startup, so a
 * {@code /flip reload} changes what the next sweep does without restarting anything.
 *
 * @param scanAuctions            whether to sweep the auction house at all. It is roughly 70MB per
 *                                sweep, which is a real cost to somebody's connection
 * @param valuationWindowDays     how far back realized sales are trusted as evidence of current value
 * @param minDiscount             how far under fair value a listing must be to be worth decoding
 * @param maxPrice                listings above this cannot be acted on, so they are not examined
 * @param bazaarTapeEnabled       whether to record bazaar top-of-book to disk
 * @param bazaarTapeRetentionDays how many days of bazaar tape to keep
 * @param trendWindowHours        how far back the in-memory trend indicators look
 */
public record ScanSettings(
		boolean scanAuctions,
		int valuationWindowDays,
		double minDiscount,
		long maxPrice,
		boolean bazaarTapeEnabled,
		int bazaarTapeRetentionDays,
		int trendWindowHours
) {
}
