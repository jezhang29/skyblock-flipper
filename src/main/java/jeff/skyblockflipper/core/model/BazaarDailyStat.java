package jeff.skyblockflipper.core.model;

import com.google.gson.annotations.SerializedName;

/**
 * One product's summary for one completed UTC day, derived from the raw bazaar tape.
 *
 * <p>The raw tape is written for fidelity and is far too large to read repeatedly: two weeks of it
 * is millions of lines. This index is what actually gets read - one line per product per day, so a
 * fortnight is tens of thousands of lines and loads instantly. It exists so that a long-horizon
 * question ("is this above or below its usual price?") never costs a full tape scan.
 *
 * <p>Written once per day per product and never updated, so a day already present in the index is
 * skipped rather than recomputed. That makes the rollup safe to re-run after a crash.
 *
 * @param productId bazaar product id
 * @param day       the UTC day summarised, as {@code yyyy-MM-dd}
 * @param medianMid median midpoint across the day. A median, matching the valuation model's
 *                  reasoning: one bad tick should not move a whole day's reference price
 * @param minMid    lowest midpoint seen
 * @param maxMid    highest midpoint seen
 * @param samples   how many samples backed it, so a thin day can be discounted
 */
public record BazaarDailyStat(
		@SerializedName("p") String productId,
		@SerializedName("d") String day,
		@SerializedName("m") double medianMid,
		@SerializedName("lo") double minMid,
		@SerializedName("hi") double maxMid,
		@SerializedName("n") int samples
) {
	/** How far the day's range spans relative to its median; a crude daily volatility. */
	public double range() {
		return medianMid <= 0.0d ? 0.0d : (maxMid - minMid) / medianMid;
	}
}
