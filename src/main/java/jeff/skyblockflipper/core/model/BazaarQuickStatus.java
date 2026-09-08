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

/** All raw fields returned in one Bazaar {@code quick_status} object. */
public record BazaarQuickStatus(
		State state,
		String productId,
		double sellPrice,
		long sellVolume,
		long sellMovingWeek,
		int sellOrders,
		double buyPrice,
		long buyVolume,
		long buyMovingWeek,
		int buyOrders
) implements java.io.Serializable {
	public enum State {
		RETURNED,
		LEGACY_ACTIVITY_ONLY,
		FIELD_ABSENT
	}

	public BazaarQuickStatus {
		if (state == null) {
			throw new IllegalArgumentException("state is required");
		}
	}

	public static BazaarQuickStatus absent() {
		return new BazaarQuickStatus(State.FIELD_ABSENT, "", 0.0d, 0L, 0L, 0,
				0.0d, 0L, 0L, 0);
	}

	public static BazaarQuickStatus legacyActivity(long buyMovingWeek, long sellMovingWeek) {
		return new BazaarQuickStatus(State.LEGACY_ACTIVITY_ONLY, "", 0.0d, 0L,
				sellMovingWeek, 0, 0.0d, 0L, buyMovingWeek, 0);
	}
}
