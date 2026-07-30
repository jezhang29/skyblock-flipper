package jeff.skyblockflipper.core.model;

import com.google.gson.annotations.SerializedName;

/**
 * One item configuration's realized prices for one completed UTC day, derived from the sales tape.
 *
 * <p>The same argument as {@link BazaarDailyStat}, on the tape where it matters far more. A day of
 * raw sales is a quarter of a gigabyte because every line carries the item blob it was decoded
 * from, and retention is therefore a disk budget: whatever falls off the back of the window is
 * gone, and {@code auctions_ended} cannot be asked about the past. Rolled up, the same day is one
 * line per configuration that traded - small enough to keep for as long as the mod exists, which
 * is what makes "what did this sell for last month?" a question with an answer.
 *
 * <p>What it cannot do is replace the raw tape for model work: the rollup fixes the key at
 * {@link jeff.skyblockflipper.core.item.DecodedItem#signature()}, so a change to what a signature
 * contains can only be measured against days whose blobs are still on disk. It is a long-horizon
 * price reference, not an archive.
 *
 * <p>Written once per day per signature and never updated, so a day already in the index is skipped
 * rather than recomputed, which makes the rollup safe to re-run after a crash.
 *
 * @param signature the decoded item signature these sales shared
 * @param day       the UTC day summarised, as {@code yyyy-MM-dd}
 * @param median    median unit price. A median for the same reason the valuation model uses one:
 *                  a single whale purchase should not move a day's reference price
 * @param low       lowest unit price seen
 * @param high      highest unit price seen
 * @param samples   how many BIN sales backed it, so a thin day can be discounted
 */
public record SaleDailyStat(
		@SerializedName("s") String signature,
		@SerializedName("d") String day,
		@SerializedName("m") double median,
		@SerializedName("lo") double low,
		@SerializedName("hi") double high,
		@SerializedName("n") int samples
) {
	/** How far the day's prices spread relative to the median; a crude disagreement measure. */
	public double range() {
		return median <= 0.0d ? 0.0d : (high - low) / median;
	}
}
