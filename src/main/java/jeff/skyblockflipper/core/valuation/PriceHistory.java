package jeff.skyblockflipper.core.valuation;

import jeff.skyblockflipper.core.model.BazaarDailyStat;
import jeff.skyblockflipper.core.model.BazaarProduct;
import jeff.skyblockflipper.core.model.BazaarSample;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A rolling in-memory window of bazaar midpoints, one series per product.
 *
 * <p><b>This holds far less than the tape does, on purpose.</b> The tape keeps a fortnight so that
 * a longer-horizon question can be answered later; this keeps only what the indicators actually
 * read, because the whole fortnight resident in memory would be hundreds of megabytes of heap to
 * compute averages that look back a day. The long history lives on disk and is summarised into
 * {@link BazaarDailyStat}; the recent history lives here.
 *
 * <p>Stored as parallel primitive arrays rather than a list of samples. At a couple of thousand
 * products with a few hundred samples each, boxing every price would roughly triple the footprint
 * for no benefit - nothing here ever needs a sample as an object.
 *
 * <p>Each series keeps the two book sides alongside the midpoint, which is the one place this
 * structure pays for something the midpoint cannot answer: {@link FillStats} counts how often the
 * bid or the ask moved past where you would have posted, and a midpoint hides exactly that. Three
 * double arrays instead of one costs on the order of ten megabytes at a full day of history across
 * the whole bazaar, which is the price of the mod being able to say how fast an order fills rather
 * than assuming it.
 *
 * <p>Not thread-safe, and deliberately so: the poller owns it and appends from a single thread,
 * exactly like {@code SalesTape}. Readers on other threads get {@link #snapshot()}, which is
 * immutable. Nothing else should hold a reference to this.
 */
public final class PriceHistory {
	/**
	 * What the caller is expected to sample at, used only to size the ring. Sampling faster than
	 * this does not corrupt anything - the buffer simply holds proportionally less time.
	 */
	private static final int NOMINAL_SAMPLE_MINUTES = 5;

	/**
	 * The recent sub-window, as a fraction of the full one: 24 hours in gives 3 hours out.
	 *
	 * <p>Short enough to react within a session, long enough that a handful of ticks cannot make
	 * a trend out of noise.
	 */
	private static final int SHORT_WINDOW_DIVISOR = 8;

	private static final double MILLIS_PER_HOUR = 3_600_000.0d;

	/**
	 * Slack on the displacement test, to absorb floating-point representation error.
	 *
	 * <p>Prices arrive as JSON doubles and 0.1 is not exactly representable, so a bid that steps up
	 * by exactly one increment lands either side of it essentially at random. Without this, roughly
	 * half of the most common move on the book - someone matching the price you would have posted -
	 * counts as having got in front of you, and every displacement rate comes out inflated.
	 *
	 * <p>Absolute rather than relative: it only has to clear the error in a difference of two
	 * doubles, which stays around 1e-8 even at the tens of millions the priciest products trade at.
	 */
	private static final double DISPLACEMENT_TOLERANCE = 1e-6d;

	/**
	 * The longest gap between two samples that still counts as having watched the book.
	 *
	 * <p>Six times the nominal cadence, so an ordinary missed poll or a slow snapshot still counts
	 * and a closed client does not. Generous on purpose: a caller sampling slower than nominal
	 * should lose precision, not lose the measurement entirely.
	 */
	private static final long MAX_OBSERVED_GAP_MILLIS = NOMINAL_SAMPLE_MINUTES * 6L * 60_000L;

	private final Map<String, Series> series = new HashMap<>();
	private final Duration window;
	private final Duration shortWindow;
	private final int capacity;

	private Map<String, Double> dailyMedians = Map.of();
	private long newestTimestamp;

	public PriceHistory(Duration window) {
		this.window = window.isZero() || window.isNegative() ? Duration.ofHours(24) : window;
		this.shortWindow = this.window.dividedBy(SHORT_WINDOW_DIVISOR);
		this.capacity = (int) Math.max(16L, this.window.toMinutes() / NOMINAL_SAMPLE_MINUTES + 8L);
	}

	/**
	 * Adds one sample, dropping anything that has fallen out of the window.
	 *
	 * <p>Eviction keys off the newest timestamp seen rather than the wall clock, so replaying a
	 * tape at startup keeps the same window it would have kept live. One-sided books are ignored:
	 * a midpoint needs both sides.
	 */
	public void append(BazaarSample sample) {
		if (sample == null || sample.productId() == null || !sample.isTwoSided()) {
			return;
		}

		newestTimestamp = Math.max(newestTimestamp, sample.timestamp());

		Series product = series.computeIfAbsent(sample.productId(), key -> new Series(capacity));
		product.add(sample.timestamp(), sample.mid(), sample.bidPrice(), sample.askPrice());
		product.evictBefore(newestTimestamp - window.toMillis());
	}

	/**
	 * Forgets every sample, so a replay of the tape starts from nothing.
	 *
	 * <p>The ring deduplicates nothing - it cannot, since two genuine samples of a still book are
	 * identical - so replaying a tape that has already been replayed would count every sample
	 * twice and halve the apparent volatility. Anything that re-reads the tape clears first.
	 */
	public void clear() {
		series.clear();
		// Back to the value the constructor leaves it at, not a sentinel: eviction subtracts the
		// window from it, and a minimum long would underflow before the first sample raised it.
		newestTimestamp = 0L;
	}

	/**
	 * Replaces the multi-day reference prices, from the tape's daily rollup.
	 *
	 * <p>Median of the daily medians rather than a mean of everything: the point of a longer
	 * reference is to be unmoved by the very spike it is being used to detect.
	 */
	public void setDailyStats(List<BazaarDailyStat> stats) {
		Map<String, List<Double>> byProduct = new HashMap<>();

		for (BazaarDailyStat stat : stats) {
			if (stat != null && stat.productId() != null && stat.medianMid() > 0.0d) {
				byProduct.computeIfAbsent(stat.productId(), key -> new ArrayList<>())
						.add(stat.medianMid());
			}
		}

		Map<String, Double> medians = new HashMap<>();
		byProduct.forEach((productId, values) -> {
			double[] sorted = values.stream().mapToDouble(Double::doubleValue).sorted().toArray();
			medians.put(productId, sorted[sorted.length / 2]);
		});

		this.dailyMedians = medians;
	}

	/** An immutable view for readers on other threads. Recomputed, so call it once per update. */
	public TrendSnapshot snapshot() {
		long shortCutoff = newestTimestamp - shortWindow.toMillis();
		Map<String, PriceTrend> trends = new HashMap<>();
		Map<String, FillStats> fills = new HashMap<>();
		int samples = 0;

		for (Map.Entry<String, Series> entry : series.entrySet()) {
			PriceTrend trend = entry.getValue().toTrend(entry.getKey(), shortCutoff);

			if (trend != null) {
				trends.put(entry.getKey(), trend);
				fills.put(entry.getKey(), entry.getValue().toFillStats(entry.getKey()));
				samples += trend.samples();
			}
		}

		return new TrendSnapshot(trends, fills, dailyMedians, window, samples, Instant.now());
	}

	public Duration window() {
		return window;
	}

	public int productsTracked() {
		return series.size();
	}

	/** Total samples currently resident, for {@code /flip status}. */
	public int sampleCount() {
		int total = 0;

		for (Series product : series.values()) {
			total += product.size();
		}

		return total;
	}

	public boolean isEmpty() {
		return series.isEmpty();
	}

	/**
	 * One product's midpoints, as a fixed-capacity circular buffer.
	 *
	 * <p>Capacity is a hard bound on memory; the time window is the intended bound. Whichever
	 * binds first wins, which means a caller sampling faster than nominal silently gets a shorter
	 * history rather than an unbounded one.
	 */
	private static final class Series {
		private final double[] mids;
		private final double[] bids;
		private final double[] asks;
		private final long[] times;

		private int start;
		private int size;

		Series(int capacity) {
			this.mids = new double[capacity];
			this.bids = new double[capacity];
			this.asks = new double[capacity];
			this.times = new long[capacity];
		}

		void add(long time, double mid, double bid, double ask) {
			int index = (start + size) % mids.length;
			mids[index] = mid;
			bids[index] = bid;
			asks[index] = ask;
			times[index] = time;

			if (size == mids.length) {
				// The write above landed on the oldest entry; step past it.
				start = (start + 1) % mids.length;
			} else {
				size++;
			}
		}

		void evictBefore(long cutoff) {
			while (size > 0 && times[start] < cutoff) {
				start = (start + 1) % mids.length;
				size--;
			}
		}

		int size() {
			return size;
		}

		/**
		 * One pass computing every figure {@link PriceTrend} needs.
		 *
		 * <p>Volatility is the standard deviation of log returns between consecutive samples,
		 * which is scale-free: it compares a 3-coin material and a 50-million-coin one on the
		 * same axis, and a percentage move on either means the same thing.
		 */
		PriceTrend toTrend(String productId, long shortCutoff) {
			if (size == 0) {
				return null;
			}

			double longSum = 0.0d;
			double longSquareSum = 0.0d;
			double shortSum = 0.0d;
			int shortCount = 0;

			double returnSum = 0.0d;
			double returnSquareSum = 0.0d;
			int returns = 0;
			double previous = 0.0d;

			for (int i = 0; i < size; i++) {
				int index = (start + i) % mids.length;
				double mid = mids[index];

				longSum += mid;
				longSquareSum += mid * mid;

				if (times[index] >= shortCutoff) {
					shortSum += mid;
					shortCount++;
				}

				if (previous > 0.0d && mid > 0.0d) {
					double logReturn = Math.log(mid / previous);
					returnSum += logReturn;
					returnSquareSum += logReturn * logReturn;
					returns++;
				}

				previous = mid;
			}

			double longAverage = longSum / size;

			// Before the series outgrows the short sub-window both averages cover the same
			// samples, so drift is zero. That reads as "no signal", which is the honest answer,
			// rather than as a trend inferred from a handful of ticks.
			double shortAverage = shortCount > 0 ? shortSum / shortCount : longAverage;

			double volatility = 0.0d;

			if (returns >= 2) {
				double mean = returnSum / returns;
				volatility = Math.sqrt(Math.max(0.0d, returnSquareSum / returns - mean * mean));
			}

			// Spread of the levels, as a fraction of the mean. Scale-free like volatility, but
			// answering "how far has this ranged" rather than "how jumpy is it" - a steady climb
			// scores high here and near zero there.
			double dispersion = 0.0d;

			if (size >= 2 && longAverage > 0.0d) {
				double variance = Math.max(0.0d, longSquareSum / size - longAverage * longAverage);
				dispersion = Math.sqrt(variance) / longAverage;
			}

			return new PriceTrend(
					productId,
					mids[(start + size - 1) % mids.length],
					shortAverage,
					longAverage,
					volatility,
					dispersion,
					size);
		}

		/**
		 * How often the top of each side moved past where you would have posted.
		 *
		 * <p>Kept apart from {@link #toTrend} rather than folded into its pass: the two answer
		 * different questions off different columns, and a ring of a few hundred entries is not
		 * where this class spends anything worth saving.
		 *
		 * <p>A step is counted only when the book moved strictly further than
		 * {@link BazaarProduct#PRICE_INCREMENT}. Moving to exactly your price is someone matching
		 * you, and time priority means you are still in front of them.
		 */
		FillStats toFillStats(String productId) {
			double threshold = BazaarProduct.PRICE_INCREMENT + DISPLACEMENT_TOLERANCE;
			int bidLifts = 0;
			int askDrops = 0;
			int intervals = 0;
			double hours = 0.0d;

			for (int i = 1; i < size; i++) {
				int previous = (start + i - 1) % mids.length;
				int current = (start + i) % mids.length;
				long elapsed = times[current] - times[previous];

				// A gap this long is the client having been closed, not a book that stood still
				// for eleven hours. Counting it would put the whole gap in the denominator and
				// almost nothing in the numerator, which reports a contested book as a quiet one.
				if (elapsed <= 0L || elapsed > MAX_OBSERVED_GAP_MILLIS) {
					continue;
				}

				intervals++;
				hours += elapsed / MILLIS_PER_HOUR;

				if (bids[current] - bids[previous] > threshold) {
					bidLifts++;
				}

				if (asks[previous] - asks[current] > threshold) {
					askDrops++;
				}
			}

			// Nothing contiguous to measure over. isUsable() reads that as unmeasured, which sends
			// the caller to its stated fallback rather than to a rate divided by nearly zero.
			if (hours <= 0.0d) {
				return new FillStats(productId, 0.0d, 0.0d, 0.0d, 0);
			}

			return new FillStats(productId, bidLifts / hours, askDrops / hours, hours, intervals);
		}
	}
}
