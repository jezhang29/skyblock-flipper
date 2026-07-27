package jeff.skyblockflipper.client.gui;

import jeff.skyblockflipper.core.valuation.PriceTrend;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * A few pixels of price direction, drawn beside a row.
 *
 * <p>Deliberately not a chart. There is no room for one at row height, and the question a ranked
 * list has to answer is only "is this going up or down" - the detail panel carries the numbers.
 * Drawn from the three figures a {@link PriceTrend} already holds rather than from the raw series,
 * so nothing has to be read back off the tape to render a frame.
 *
 * <p>Colour, not just shape, because the whole point is to be readable at a glance while scanning
 * a column.
 */
final class Sparkline {
	private static final int RISING = 0xFF55FF55;
	private static final int FALLING = 0xFFFF5555;
	private static final int FLAT = 0xFF808080;

	/** Drift beyond this is worth colouring; inside it the item is simply steady. */
	private static final double FLAT_BAND = 0.005d;

	/** How far the line may deviate from the middle, in pixels. */
	private static final int AMPLITUDE = 3;

	private Sparkline() {
	}

	/**
	 * Draws a two-segment line: the long average on the left, the recent average in the middle,
	 * the latest print on the right.
	 *
	 * @param width how wide to draw, at least 6 pixels to be legible
	 */
	static void draw(GuiGraphicsExtractor graphics, PriceTrend trend, int x, int y, int width) {
		if (trend == null || width < 6) {
			return;
		}

		int colour = colourFor(trend);
		int middle = y + AMPLITUDE;

		int left = middle - offset(trend.longAverage(), trend);
		int centre = middle - offset(trend.shortAverage(), trend);
		int right = middle - offset(trend.latest(), trend);

		segment(graphics, x, left, x + width / 2, centre, colour);
		segment(graphics, x + width / 2, centre, x + width, right, colour);
	}

	static int colourFor(PriceTrend trend) {
		if (trend == null) {
			return FLAT;
		}

		if (trend.drift() > FLAT_BAND) {
			return RISING;
		}

		return trend.drift() < -FLAT_BAND ? FALLING : FLAT;
	}

	/**
	 * Where a price sits relative to the long average, in pixels.
	 *
	 * <p>Scaled by the series' own dispersion so the shape means the same thing on a stable
	 * material and a wild one; a fixed percentage scale would flatten every calm item into a
	 * straight line and clip every volatile one.
	 */
	private static int offset(double price, PriceTrend trend) {
		double base = trend.longAverage();

		if (base <= 0.0d) {
			return 0;
		}

		double spread = Math.max(trend.dispersion(), 0.001d);
		double normalised = (price - base) / (base * spread * 2.0d);

		return (int) Math.round(Math.clamp(normalised, -1.0d, 1.0d) * AMPLITUDE);
	}

	/**
	 * A line between two points, one pixel per column.
	 *
	 * <p>{@code GuiGraphicsExtractor} draws axis-aligned rectangles, so a sloped line is filled a
	 * column at a time rather than rasterised.
	 */
	private static void segment(GuiGraphicsExtractor graphics, int x1, int y1, int x2, int y2, int colour) {
		int span = Math.max(1, x2 - x1);

		for (int i = 0; i < span; i++) {
			int y = y1 + (y2 - y1) * i / span;
			graphics.fill(x1 + i, y, x1 + i + 1, y + 1, colour);
		}
	}
}
