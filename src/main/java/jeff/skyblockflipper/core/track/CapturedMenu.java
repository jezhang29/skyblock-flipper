package jeff.skyblockflipper.core.track;

import java.util.List;

/**
 * The contents of one Hypixel menu at one moment.
 *
 * <p>Chat is an event stream and a menu is a snapshot, and a tracker needs both: chat says something
 * happened the instant it happens, a menu says what is actually true right now including everything
 * that happened while the client was closed.
 *
 * @param title  menu title with formatting stripped, the only handle there is on which menu this is
 * @param slots  non-empty slots only; a menu is mostly filler glass
 */
public record CapturedMenu(long at, String title, List<CapturedSlot> slots) {
	public CapturedMenu {
		slots = List.copyOf(slots);
	}

	/**
	 * Identity for "these are the same menu contents".
	 *
	 * <p>Hypixel fills a menu in over several ticks and then repaints it on a timer, so a snapshot
	 * taken every tick would be thousands of copies of the same thing. Deliberately excludes
	 * {@link #at}.
	 */
	public int contentsHash() {
		return title.hashCode() * 31 + slots.hashCode();
	}
}
