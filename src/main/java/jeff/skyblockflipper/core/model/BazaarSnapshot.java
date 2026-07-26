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
