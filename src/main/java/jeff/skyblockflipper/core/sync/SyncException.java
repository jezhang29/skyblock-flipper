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
package jeff.skyblockflipper.core.sync;

/**
 * A sync that stopped early.
 *
 * <p>Checked, because every caller has something better to do than fail: the local tape is still
 * the tape, and a server that is down or a token that is wrong should cost a log line and the
 * hours the collector holds, not the poll loop.
 */
public class SyncException extends Exception {
	private static final long serialVersionUID = 1L;

	public SyncException(String message) {
		super(message);
	}

	public SyncException(String message, Throwable cause) {
		super(message, cause);
	}
}
