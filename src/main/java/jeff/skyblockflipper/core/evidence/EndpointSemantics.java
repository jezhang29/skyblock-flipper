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

/** Whether the two endpoints of a numerical interval are members of the interval. */
public enum EndpointSemantics {
	CLOSED_CLOSED(true, true),
	CLOSED_OPEN(true, false),
	OPEN_CLOSED(false, true),
	OPEN_OPEN(false, false);

	private final boolean lowerInclusive;
	private final boolean upperInclusive;

	EndpointSemantics(boolean lowerInclusive, boolean upperInclusive) {
		this.lowerInclusive = lowerInclusive;
		this.upperInclusive = upperInclusive;
	}

	public boolean lowerInclusive() {
		return lowerInclusive;
	}

	public boolean upperInclusive() {
		return upperInclusive;
	}
}
