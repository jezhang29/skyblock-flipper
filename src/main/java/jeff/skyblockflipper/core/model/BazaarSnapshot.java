/*
 * Skyblock Flipper - a Hypixel Skyblock flipping advisor mod.
 * Copyright (C) 2026 SoupChugger
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package jeff.skyblockflipper.core.model;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * A full bazaar order book at one instant.
 *
 * @param lastUpdated when Hypixel generated the data, not when we fetched it
 */
public record BazaarSnapshot(Instant lastUpdated, Map<String, BazaarProduct> products) {
	public BazaarSnapshot {
		products = Map.copyOf(products);
	}

	public static BazaarSnapshot empty() {
		return new BazaarSnapshot(Instant.EPOCH, Map.of());
	}

	public Optional<BazaarProduct> product(String productId) {
		return Optional.ofNullable(products.get(productId));
	}

	public boolean isEmpty() {
		return products.isEmpty();
	}
}
