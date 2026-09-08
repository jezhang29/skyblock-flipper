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

import java.util.OptionalDouble;

/**
 * Public rolling activity counters reported by Bazaar quick status.
 *
 * <p>These are neither live flow nor a personal order's fill rate. Dividing them by 168 only
 * produces a weekly average of this public proxy.
 */
public record ActivityProxy(State state, long buyMovingWeek, long sellMovingWeek)
		implements java.io.Serializable {
	private static final double HOURS_PER_WEEK = 168.0d;

	public enum State {
		REPORTED,
		UNAVAILABLE
	}

	public ActivityProxy {
		if (state == null || buyMovingWeek < 0L || sellMovingWeek < 0L) {
			throw new IllegalArgumentException("invalid activity proxy");
		}
		if (state == State.UNAVAILABLE && (buyMovingWeek != 0L || sellMovingWeek != 0L)) {
			throw new IllegalArgumentException("unavailable activity cannot contain values");
		}
	}

	public static ActivityProxy reported(long buyMovingWeek, long sellMovingWeek) {
		return new ActivityProxy(State.REPORTED, buyMovingWeek, sellMovingWeek);
	}

	public static ActivityProxy unavailable() {
		return new ActivityProxy(State.UNAVAILABLE, 0L, 0L);
	}

	public OptionalDouble weeklyAverageInstantBuyActivityPerHour() {
		return state == State.REPORTED
				? OptionalDouble.of(buyMovingWeek / HOURS_PER_WEEK)
				: OptionalDouble.empty();
	}

	public OptionalDouble weeklyAverageInstantSellActivityPerHour() {
		return state == State.REPORTED
				? OptionalDouble.of(sellMovingWeek / HOURS_PER_WEEK)
				: OptionalDouble.empty();
	}
}
