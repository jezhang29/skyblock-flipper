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
 * One server chat line, recorded verbatim so a parser can be written against what Hypixel actually
 * says rather than against what anyone remembered it saying.
 *
 * @param at   wall clock when the client received it, so a captured line can be lined up with the
 *             menu snapshot taken seconds later
 * @param text the line with its formatting codes already stripped, which is the form a parser will
 *             see
 */
public record CapturedChat(long at, String text) implements CaptureRecord {
}
