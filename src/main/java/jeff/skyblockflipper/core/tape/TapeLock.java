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
package jeff.skyblockflipper.core.tape;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One monitor per tape directory, shared by every tape object that writes into it.
 *
 * <p>Locking on the tape object is not enough, for two reasons that both end with two buffered
 * writers appending to one file and interleaving mid-line:
 *
 * <ul>
 *   <li>Rolling a day up appends to {@code daily.jsonl} from the maintenance thread while a
 *       collector sync merges into that same file from its own thread.</li>
 *   <li>{@code /flip reload} builds a second tape object over a directory whose previous object is
 *       still being written to by an in-flight sync, and two objects do not share an instance
 *       lock.</li>
 * </ul>
 *
 * <p>Keyed on the normalised directory so the lock outlives the object, which is the whole point.
 * The map is never cleared: one entry per tape directory a process has opened is two entries in
 * practice, and forgetting one while a thread still held it would be the bug this exists to stop.
 */
final class TapeLock {
	private static final Map<Path, Object> LOCKS = new ConcurrentHashMap<>();

	private TapeLock() {
	}

	static Object forDirectory(Path directory) {
		return LOCKS.computeIfAbsent(directory.toAbsolutePath().normalize(), key -> new Object());
	}
}
