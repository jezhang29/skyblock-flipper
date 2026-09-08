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

/** What the Bazaar endpoint returned for one side of one product's book. */
public record DepthCoverage(
		State state,
		int returnedLevels,
		long returnedOrders,
		int documentedMaximumOrders
) implements java.io.Serializable {
	/** Hypixel documents each summary as containing at most the top 30 orders. */
	public static final int DOCUMENTED_MAXIMUM_ORDERS = 30;

	public enum State {
		FIELD_ABSENT,
		RETURNED_EMPTY,
		RETURNED_LEVELS
	}

	public DepthCoverage {
		if (state == null) {
			throw new IllegalArgumentException("state is required");
		}
		if (returnedLevels < 0 || returnedOrders < 0L || documentedMaximumOrders <= 0) {
			throw new IllegalArgumentException("invalid depth coverage");
		}
		if (state == State.FIELD_ABSENT && (returnedLevels != 0 || returnedOrders != 0L)) {
			throw new IllegalArgumentException("an absent field cannot contain levels");
		}
		if (state == State.RETURNED_EMPTY && (returnedLevels != 0 || returnedOrders != 0L)) {
			throw new IllegalArgumentException("an empty field cannot contain levels");
		}
		if (state == State.RETURNED_LEVELS && returnedLevels == 0) {
			throw new IllegalArgumentException("returned levels must be non-empty");
		}
	}

	public static DepthCoverage absent() {
		return new DepthCoverage(State.FIELD_ABSENT, 0, 0L, DOCUMENTED_MAXIMUM_ORDERS);
	}

	public static DepthCoverage returned(java.util.List<OrderLevel> levels) {
		long orders = 0L;
		for (OrderLevel level : levels) {
			orders = Math.addExact(orders, level.orders());
		}
		return new DepthCoverage(levels.isEmpty() ? State.RETURNED_EMPTY : State.RETURNED_LEVELS,
				levels.size(), orders, DOCUMENTED_MAXIMUM_ORDERS);
	}

	/** True when the returned rows account for the documented maximum number of orders. */
	public boolean mayBeTruncated() {
		return returnedOrders >= documentedMaximumOrders;
	}
}
