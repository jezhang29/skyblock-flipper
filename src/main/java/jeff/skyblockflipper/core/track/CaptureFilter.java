package jeff.skyblockflipper.core.track;

import java.util.List;
import java.util.Locale;

/**
 * What is worth writing to the capture file.
 *
 * <p>Deliberately loose. The point of a capture session is to find out how Hypixel words things, so
 * a filter tight enough to be sure would throw away exactly the lines nobody thought of. Cheap to
 * over-record now, impossible to recover a wording nobody wrote down.
 *
 * <p>It is still a filter rather than nothing, because "record every chat line" on a public lobby
 * means recording other players' conversations to disk, and none of that is any use here.
 */
public final class CaptureFilter {
	/**
	 * Words that appear in the messages a trade produces. {@code coins} is the broadest and pulls in
	 * unrelated lines; that is the trade being made on purpose.
	 */
	private static final List<String> CHAT_KEYWORDS = List.of(
			"bazaar", "auction", "order", "offer", "bid", "coins", "sold", "bought", "purchas",
			"claim", "collect", "cancel", "expired");

	/** Menu titles worth snapshotting. Hypixel titles are short and stable enough to match on. */
	private static final List<String> MENU_KEYWORDS = List.of(
			"bazaar", "auction", "order", "bid", "offer");

	/**
	 * How long after a bazaar menu every other menu is recorded too.
	 *
	 * <p>Long enough to cover walking a whole order through - product page, amount, price, confirm -
	 * at the speed a sign is typed on, and short enough that a chest opened on your island a minute
	 * later is not snapshotted into the file.
	 */
	private static final long TRAIL_MILLIS = 30_000L;

	private CaptureFilter() {
	}

	public static boolean keepChat(String line) {
		if (line == null || line.isBlank()) {
			return false;
		}

		// The mod's own chat output would otherwise be captured and parsed back as if Hypixel had
		// said it, which is a loop that ends in the tracker recording its own reports of trades.
		if (line.startsWith("[Flipper]")) {
			return false;
		}

		return matches(line, CHAT_KEYWORDS);
	}

	public static boolean keepMenu(String title) {
		return title != null && !title.isBlank() && matches(title, MENU_KEYWORDS);
	}

	/**
	 * The same question, for a menu opened {@code lastBazaarAt} milliseconds after a bazaar screen.
	 *
	 * <p>The keyword list cannot see the three screens an order is actually placed on. A product page
	 * is titled with the item's own name, and so are the amount and price pages behind it, so
	 * {@code Enchanted Melon} matches nothing in the list and 850 menu records from the 2026-08-09
	 * session contain not one of them. They are the screens a slot detector most needs measured.
	 *
	 * <p>So proximity stands in for a title nothing can match on: everything opened within
	 * {@link #TRAIL_MILLIS} of a bazaar menu is recorded, whatever it is called. It over-records by
	 * design, like the chat side - a menu nobody thought of is cheap now and unrecoverable later.
	 */
	public static boolean keepMenu(String title, long at, long lastBazaarAt) {
		if (title == null || title.isBlank()) {
			return false;
		}

		return keepMenu(title) || at - lastBazaarAt <= TRAIL_MILLIS;
	}

	private static boolean matches(String text, List<String> keywords) {
		String lower = text.toLowerCase(Locale.ROOT);

		for (String keyword : keywords) {
			if (lower.contains(keyword)) {
				return true;
			}
		}

		return false;
	}
}
