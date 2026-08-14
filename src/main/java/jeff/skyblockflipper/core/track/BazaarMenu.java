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
		String item = itemOf(title);

		if (item.isEmpty()) {
			return "";
		}

		boolean cut = title.trim().length() >= TITLE_CAP;
		String found = "";
		int matches = 0;

		for (String name : names) {
			if (name != null && matches(item, name, cut)) {
				found = name;
				matches++;
			}
		}

		// Two basket items sharing the prefix a cut title left behind is a page nothing can name.
		return matches == 1 ? found : "";
	}

	/**
	 * The item a product page's title names, or empty.
	 *
	 * <p><b>A product page is titled {@code Item Upgrades ➜ Transmission Tuner}</b> - the
	 * sub-category it was reached through, then the item - which is what a bare-name match missed,
	 * leaving the product page with no panel on it at all. Photographed live 2026-08-13.
	 *
	 * <p>The bare name is still accepted, because nothing rules out a page that has no path in front
	 * of it and the cost of accepting one is a panel on a renamed chest.
	 */
	public static String itemOf(String title) {
		if (title == null || title.isBlank()) {
			return "";
		}

		String trimmed = title.trim();
		int arrow = trimmed.lastIndexOf(ARROW);

		return arrow < 0 ? trimmed : trimmed.substring(arrow + ARROW.length()).trim();
	}

	/**
	 * Whether a title's item and a basket name are the same item.
	 *
	 * <p>Equality, except on a title that was cut off. <b>Hypixel's menu titles stop at 32
	 * characters</b> - measured across 850 captured menus, where the longest is exactly 32 and
	 * {@code Bazaar ➜ "Enchanted Cooked Mutt} is cut mid-word - so a long item's page can only ever
	 * be matched on the prefix that survived.
	 *
	 * <p><b>The prefix is only allowed on a title that hit the cap.</b> Item names are prefixes of one
	 * another constantly - 187 of 5,549 of them - and "Enchanted Melon" is a whole item as well as the
	 * start of "Enchanted Melon Slice". Accepting a prefix from a title with room to spare would put
	 * the box on the wrong item's page, which is an order for the wrong item.
	 */
	private static boolean matches(String item, String name, boolean cut) {
		String left = item.toLowerCase(Locale.ROOT);
		String right = name.toLowerCase(Locale.ROOT);

		return cut ? right.startsWith(left) : left.equals(right);
	}

	/** The path separator Hypixel puts between a category and what is under it. */
	private static final String ARROW = "➜";

	/** Characters a Hypixel menu title holds before it is cut, measured over 850 of them. */
	private static final int TITLE_CAP = 32;
}
