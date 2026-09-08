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

import java.time.Instant;
import java.util.Map;

/** Immutable, serializable publication of one complete Bazaar response. */
public record MarketObservation(
		int schemaVersion,
		String endpoint,
		Instant sourceTime,
		Instant retrievedAt,
		MarketContentId contentId,
		Map<String, BazaarProduct> products
) implements java.io.Serializable {
	public static final int SCHEMA_VERSION = 1;
	public static final String BAZAAR_ENDPOINT = "/v2/skyblock/bazaar";

	public MarketObservation {
		if (schemaVersion <= 0 || endpoint == null || endpoint.isBlank()
				|| sourceTime == null || retrievedAt == null || contentId == null || products == null) {
			throw new IllegalArgumentException("complete market observation fields are required");
		}
		products = Map.copyOf(products);
		if (!contentId.equals(MarketContentHasher.hash(products))) {
			throw new IllegalArgumentException("content id does not match market content");
		}
	}

	public static MarketObservation bazaar(Instant sourceTime, Instant retrievedAt,
			Map<String, BazaarProduct> products) {
		Map<String, BazaarProduct> immutable = Map.copyOf(products);
		return new MarketObservation(SCHEMA_VERSION, BAZAAR_ENDPOINT, sourceTime, retrievedAt,
				MarketContentHasher.hash(immutable), immutable);
	}

	public static MarketObservation fromSnapshot(BazaarSnapshot snapshot, Instant retrievedAt) {
		return bazaar(snapshot.lastUpdated(), retrievedAt, snapshot.products());
	}

	public BazaarSnapshot snapshot() {
		return new BazaarSnapshot(sourceTime, products);
	}
}
