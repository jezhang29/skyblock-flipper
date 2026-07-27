package jeff.skyblockflipper.core.model;

import com.google.gson.annotations.SerializedName;

/**
 * One product's top of book at one instant, as stored on the bazaar tape.
 *
 * <p><b>The component names are book sides, not order types, and that is deliberate.</b> Hypixel
 * names its two summaries from the perspective of the order you would place, which reads backwards
 * - see {@link jeff.skyblockflipper.core.model.dto.BazaarDto} for the full trap. That translation
 * happens exactly once, on the way in from the wire, and this record is built from
 * {@link BazaarProduct#instantBuyPrice()} and {@link BazaarProduct#instantSellPrice()} which have
 * already been through it. Never populate these from a DTO directly: a tape written with the sides
 * swapped looks entirely plausible and silently inverts every trend computed from it afterwards.
 *
 * <p>Serialized with one-and-two-letter keys because there is one line per product per sample and
 * a few hundred thousand of them a day; spelling the keys out would roughly double the tape for no
 * reader's benefit. The keys bind through {@code @SerializedName} on record components, which this
 * project already relies on for {@link EndedAuction} and covers in {@code SalesTapeTest}.
 *
 * <p>Only {@code quick_status}-grade data is kept, never book depth. Depth is where the size
 * explodes, and none of the trend indicators can use it.
 *
 * @param productId  bazaar product id, e.g. {@code ENCHANTED_DIAMOND}
 * @param timestamp  epoch millis of the snapshot Hypixel generated, not of our fetch
 * @param askPrice   best sell offer: what you pay to instant-buy one unit
 * @param bidPrice   best buy order: what you receive by instant-selling one unit
 * @param boughtWeek units instantly bought over the trailing week
 * @param soldWeek   units instantly sold over the trailing week
 */
public record BazaarSample(
		@SerializedName("p") String productId,
		@SerializedName("t") long timestamp,
		@SerializedName("a") double askPrice,
		@SerializedName("b") double bidPrice,
		@SerializedName("bv") long boughtWeek,
		@SerializedName("sv") long soldWeek
) {
	/**
	 * The single price the trend indicators run on.
	 *
	 * <p>Midpoint rather than either side alone: a book whose spread widens while the true price
	 * holds still moves both the ask and the bid, and tracking one side would report that as a
	 * price move. The midpoint is the part that only changes when the market actually reprices.
	 */
	public double mid() {
		return (askPrice + bidPrice) / 2.0d;
	}

	/**
	 * The thinner of the two weekly volumes, matching
	 * {@link BazaarProduct#bottleneckWeeklyVolume()} so a liquidity judgement made against the tape
	 * means the same thing as one made against the live book.
	 */
	public long bottleneckWeeklyVolume() {
		return Math.min(boughtWeek, soldWeek);
	}

	/** True when both sides had a resting order, i.e. the midpoint means something. */
	public boolean isTwoSided() {
		return askPrice > 0.0d && bidPrice > 0.0d;
	}
}
