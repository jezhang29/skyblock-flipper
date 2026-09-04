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
package jeff.skyblockflipper.core.api;

import jeff.skyblockflipper.core.model.TimedListing;

/**
 * Receives active timed (non-BIN) listings one at a time as a sweep walks the auction pages.
 *
 * <p>The twin of {@link ListingSink} for the bid side, and a callback for the same reason: a full
 * sweep holds the whole house, and handing each listing over as it is parsed lets the sink keep the
 * few it wants and lets the rest of the page be collected immediately. The BIN sweep and this share
 * one pass over the pages, so the collection costs no extra request.
 */
@FunctionalInterface
public interface TimedListingSink {
	void offer(TimedListing listing);
}
