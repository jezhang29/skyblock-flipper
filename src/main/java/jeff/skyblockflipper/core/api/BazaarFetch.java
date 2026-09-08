/*
 * Skyblock Flipper - a Hypixel Skyblock flipping advisor mod.
 * Copyright (C) 2026 SoupChugger
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package jeff.skyblockflipper.core.api;

import jeff.skyblockflipper.core.model.MarketObservation;

import java.time.Instant;

/** Outcome and health metadata for one Bazaar retrieval attempt. */
public record BazaarFetch(
		Status status,
		Instant attemptedAt,
		MarketObservation observation,
		String error,
		boolean rateLimited
) {
	public enum Status {
		NEVER_ATTEMPTED,
		SUCCESS,
		FAILED
	}

	public BazaarFetch {
		if (status == null || attemptedAt == null || error == null) {
			throw new IllegalArgumentException("fetch status, time, and error are required");
		}
		if ((status == Status.SUCCESS) != (observation != null)) {
			throw new IllegalArgumentException("only successful fetches contain observations");
		}
	}

	public static BazaarFetch never() {
		return new BazaarFetch(Status.NEVER_ATTEMPTED, Instant.EPOCH, null, "", false);
	}

	public static BazaarFetch success(MarketObservation observation) {
		return new BazaarFetch(Status.SUCCESS, observation.retrievedAt(), observation, "", false);
	}

	public static BazaarFetch failed(Instant attemptedAt, String error, boolean rateLimited) {
		return new BazaarFetch(Status.FAILED, attemptedAt, null, error, rateLimited);
	}
}
