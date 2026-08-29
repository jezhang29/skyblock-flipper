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
package jeff.skyblockflipper.core.strategy;

/**
 * The combine-specific settings, bundled for the same reason {@link CraftContext} and
 * {@link NpcContext} are: they are read as a set, and a strategy reaching for them one at a time
 * would grow the shared context every time combining learned a parameter.
 *
 * <p>There is only one so far. A combine plan rests at most two orders - the source and the sell
 * offer - so the order-slot budget that constrains the craft and NPC baskets does not bind here, and
 * no measured setting exists to add. See {@code docs/combine-flipping.md}.
 *
 * @param enabled whether combine candidates are produced at all
 */
public record CombineContext(boolean enabled) {
	/** What a caller with no opinion gets: combining on. */
	public static CombineContext defaults() {
		return new CombineContext(true);
	}

	/** Combining off, for the callers and tests that want the ranking without it. */
	public static CombineContext off() {
		return new CombineContext(false);
	}
}
