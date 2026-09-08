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

import java.util.Objects;

/** Stable identity assigned at observation intake rather than derived from the payload. */
public record ObservationId(String value) {
	public static final int SCHEMA_VERSION = 1;

	public ObservationId {
		Objects.requireNonNull(value, "value");
		if (value.isBlank()) {
			throw new IllegalArgumentException("observation id must not be blank");
		}
	}
}
