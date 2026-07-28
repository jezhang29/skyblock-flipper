package jeff.skyblockflipper.core.valuation;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Every product's trend at one instant, frozen so readers never need a lock.
 *
 * <p>{@link PriceHistory} is a mutable ring that the poller thread appends to. Handing that
 * directly to a strategy running on the client thread would break the invariant the rest of the
 * data pipeline holds to - {@code MarketData} stores atomic references to immutable snapshots
 * precisely so no reader can catch a half-updated structure. This is the immutable half: the
 * poller recomputes it after each append and publishes it, the same way it rebuilds and publishes
 * a {@link FairValueModel}.
 *
 * @param trends       per-product trend over the in-memory window
 * @param fills        per-product rate at which a resting order at the top of the book gets
 *                     displaced, measured over the same window and from the same samples. Kept
 *                     beside the trends rather than inside them because it is a one-sided
 *                     measurement and every figure in a {@link PriceTrend} is a midpoint
 * @param dailyMedians per-product median of daily medians across the retained tape index. A much
 *                     longer reference than {@code trends} can offer, which matters because a
 *                     manipulation that has been running half a day is already inside the
 *                     24-hour average and no longer looks abnormal against it
 * @param window       how far back {@code trends} looks
 * @param samples      total samples resident behind these trends. Summed once here rather than on
 *                     demand, so {@code /flip status} can report depth without walking the map
 * @param builtAt      when this was computed
 */
public record TrendSnapshot(
		Map<String, PriceTrend> trends,
		Map<String, FillStats> fills,
		Map<String, Double> dailyMedians,
		Duration window,
		int samples,
		Instant builtAt
) {
	public TrendSnapshot {
		trends = Map.copyOf(trends);
		fills = Map.copyOf(fills);
		dailyMedians = Map.copyOf(dailyMedians);
	}

	public static TrendSnapshot empty() {
		return new TrendSnapshot(Map.of(), Map.of(), Map.of(), Duration.ZERO, 0, Instant.EPOCH);
	}

	/** Completed days behind {@link #dailyMedianFor}, for status reporting. */
	public int productsWithDailyHistory() {
		return dailyMedians.size();
	}

	/**
	 * Products whose fill rate is measured rather than assumed, for status reporting.
	 *
	 * <p>Worth reporting separately from {@link #size()}: a client that has just started has plenty
	 * of samples and no measured fills at all, because a rate needs an uninterrupted hour behind it.
	 */
	public int productsWithMeasuredFills() {
		return (int) fills.values().stream().filter(FillStats::isUsable).count();
	}

	/** The trend for a product, present only when enough samples back it. */
	public Optional<PriceTrend> trendFor(String productId) {
		return Optional.ofNullable(trends.get(productId)).filter(PriceTrend::isUsable);
	}

	/**
	 * How fast a resting order in this product gets displaced, present only when enough samples
	 * back it.
	 *
	 * <p>Filtered on {@link FillStats#isUsable()} for the same reason {@link #trendFor} is: a rate
	 * measured over three samples is not a rate, and a caller handed one would size a plan off it.
	 */
	public Optional<FillStats> fillStatsFor(String productId) {
		return Optional.ofNullable(fills.get(productId)).filter(FillStats::isUsable);
	}

	/** This product's multi-day reference price, if the tape has completed days for it. */
	public OptionalDouble dailyMedianFor(String productId) {
		Double median = dailyMedians.get(productId);
		return median == null || median <= 0.0d ? OptionalDouble.empty() : OptionalDouble.of(median);
	}

	public boolean isEmpty() {
		return trends.isEmpty();
	}

	public int size() {
		return trends.size();
	}
}
