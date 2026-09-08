/*
 * Skyblock Flipper - a Hypixel Skyblock flipping advisor mod.
 * Copyright (C) 2026 SoupChugger
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package jeff.skyblockflipper.core.evidence;

/** Completeness/health of an observation, without treating absence as an empty result. */
public enum CoverageState {
	COMPLETE,
	PARTIAL,
	UNAVAILABLE,
	DISABLED,
	FAILED,
	GAP,
	UNKNOWN
}
