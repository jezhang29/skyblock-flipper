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

import jeff.skyblockflipper.core.model.ActiveListing;

/**
 * Receives live listings one at a time as a sweep walks the auction pages.
 *
 * <p>A callback rather than a returned list on purpose. A full sweep is ~46,000 buy-it-now
 * listings carrying about 70MB of item blobs between them; collecting them all so the caller can
 * filter afterwards would mean holding the entire auction house in memory to keep a few dozen
 * rows. Handing each listing over as it is parsed lets the sink keep what it wants and lets the
 * rest of the page be collected immediately.
 */
@FunctionalInterface
public interface ListingSink {
	void offer(ActiveListing listing);
}
