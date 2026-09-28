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

/** A failed Hypixel API call. */
public class ApiException extends Exception {
	private final boolean rateLimited;

	public ApiException(String message, boolean rateLimited) {
		super(message);
		this.rateLimited = rateLimited;
	}

	public ApiException(String message, Throwable cause, boolean rateLimited) {
		super(message, cause);
		this.rateLimited = rateLimited;
	}

	/** True when the failure was a rate limit, which callers should treat as "retry later", not "broken". */
	public boolean isRateLimited() {
		return rateLimited;
	}
}
