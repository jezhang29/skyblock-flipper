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
