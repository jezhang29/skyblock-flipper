package jeff.skyblockflipper.core.track;

import java.util.List;
import java.util.Locale;

/**
 * Which Hypixel menu titles mean "you are at the bazaar".
 *
 * <p>Beside {@link CaptureFilter} because both classify menu titles and both were written from the
 * same measurement: 1,150 menu records in the user's own capture file, 2026-08-09. That filter is
 * deliberately loose, since its job is to over-record. This one is tight, because it decides whether
 * a panel is drawn over someone's screen.
 *
 * <p><b>The titles, as measured.</b> Browsing is {@code Bazaar} followed by a category or a quoted
 * search - {@code Bazaar ➜ Oddities}, {@code Bazaar ➜ "feather falling"} - which is 300 of the
 * records. Your resting orders are {@code Co-op Bazaar Orders}, 236 records. One order is
 * {@code Order options}, 70. Placing one ends at {@code Confirm Buy Order} or
 * {@code Confirm Sell Offer}, 16 together.
 *
 * <p><b>A product page is not in that list and cannot be.</b> Its title is the item's own name, so
 * there is nothing about it to match on - and the capture filter dropped every one of them, so none
 * was measured either. {@link #productPageFor} handles it the only way that is safe: a title is a
 * product page only when it is the name of something already in the basket, which is exactly the
 * case where there is anything to show.
 */
public final class BazaarMenu {
	/** Titles that are a bazaar screen outright, lower-cased. */
	private static final List<String> EXACT = List.of(
			"order options", "confirm buy order", "confirm sell offer");

	/** {@code Bazaar ➜ Mining}, {@code Bazaar ➜ "peridot"}, and the top-level menu itself. */
	private static final String BROWSE_PREFIX = "bazaar";

	/** {@code Co-op Bazaar Orders}, which is where a resting order is repriced or cancelled. */
	private static final String ORDERS = "bazaar orders";

	private BazaarMenu() {
	}

	/** Whether this title is a bazaar screen in its own right. */
	public static boolean isBazaar(String title) {
		if (title == null || title.isBlank()) {
			return false;
		}

		String lower = title.toLowerCase(Locale.ROOT).trim();

		return lower.startsWith(BROWSE_PREFIX) || lower.contains(ORDERS) || EXACT.contains(lower);
	}

	/**
	 * Which of {@code names} this title is the product page for, or empty for any other title.
	 *
	 * <p>Matched against the basket rather than against the whole item catalog on purpose. A chest
	 * somebody renamed "Enchanted Melon" would otherwise open a panel, and matching only the handful
	 * of items you are being told to buy keeps that to items you are already working.
	 *
	 * <p>Returns the name rather than a boolean because both callers want it: one to decide whether
	 * to draw at all, the other to highlight that row.
	 */
	public static String productPageFor(String title, List<String> names) {
		if (title == null || title.isBlank()) {
			return "";
		}

		String trimmed = title.trim();

		for (String name : names) {
			if (name != null && name.equalsIgnoreCase(trimmed)) {
				return name;
			}
		}

		return "";
	}
}
