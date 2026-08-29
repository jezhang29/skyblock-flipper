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

/**
 * One line of a capture file, either something Hypixel said or something it drew.
 *
 * <p>The two are only useful together and only in the order they arrived: a claim line means one
 * thing after the menu that showed a partial fill and another thing before it. Sealed because those
 * two forms are the whole file format, and a third would be a change to {@link CaptureLog} before
 * it was a change to a reader.
 */
public sealed interface CaptureRecord permits CapturedChat, CapturedMenu {
	/** Wall clock when the client saw it. */
	long at();
}
