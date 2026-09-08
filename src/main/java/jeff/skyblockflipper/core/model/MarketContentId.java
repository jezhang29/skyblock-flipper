/*
 * Skyblock Flipper - a Hypixel Skyblock flipping advisor mod.
 * Copyright (C) 2026 SoupChugger
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package jeff.skyblockflipper.core.model;

/** SHA-256 identity of the canonical market content, excluding source and retrieval clocks. */
public record MarketContentId(String sha256) implements java.io.Serializable {
	public MarketContentId {
		if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
			throw new IllegalArgumentException("content id must be a lowercase SHA-256 value");
		}
	}
}
