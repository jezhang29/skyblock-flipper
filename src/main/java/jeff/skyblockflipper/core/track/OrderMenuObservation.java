/*
 * Skyblock Flipper - a Hypixel Skyblock flipping advisor mod.
 * Copyright (C) 2026 SoupChugger
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package jeff.skyblockflipper.core.track;

import java.util.List;
import java.util.Objects;

/** One recognized orders-menu pass, including rows whose fill quantity is not exact. */
public record OrderMenuObservation(long at, String title, MenuCoverage coverage,
		List<ObservedOrderRow> rows, String parserVersion) {
	public OrderMenuObservation {
		Objects.requireNonNull(title, "title");
		Objects.requireNonNull(coverage, "coverage");
		rows = List.copyOf(rows);
		Objects.requireNonNull(parserVersion, "parserVersion");
	}

	public List<ObservedOrderRow> ownedBy(String player) {
		if (player == null || player.isBlank()) {
			return List.of();
		}
		return rows.stream().filter(row -> row.owner().equalsIgnoreCase(player)).toList();
	}
}
