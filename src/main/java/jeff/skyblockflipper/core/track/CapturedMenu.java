/*
 * Skyblock Flipper - a Hypixel Skyblock flipping advisor mod.
 * Copyright (C) 2026 SoupChugger
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
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
public record CapturedMenu(long at, String title, List<CapturedSlot> slots) implements CaptureRecord {
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
