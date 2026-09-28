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
package jeff.skyblockflipper.core.recovery;

import jeff.skyblockflipper.core.item.DecodedItem;
import jeff.skyblockflipper.core.item.DetailedDecodedItem;
import jeff.skyblockflipper.core.item.Rarity;
import jeff.skyblockflipper.core.model.ActiveListing;
import jeff.skyblockflipper.core.valuation.Keying;
import jeff.skyblockflipper.core.valuation.ValueEstimate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Realized-sale values used only by recovery analysis.
 *
 * <p>Clean hosts are trained only from sales with no removable attachments. Bare standalone items
 * are indexed by their stable SkyBlock id for AH component exits. Neither index changes production
 * signatures or permits an active listing to train itself.
 */
public final class RecoveryValueModel {
	private final Map<String, ValueEstimate> cleanHosts;
	private final Map<String, ValueEstimate> cleanHostFamilies;
	private final Set<String> recoveryFamilies;
	private final Map<String, ValueEstimate> bareComponents;
	private final Duration window;

	private RecoveryValueModel(Map<String, ValueEstimate> cleanHosts,
			Map<String, ValueEstimate> cleanHostFamilies,
			Set<String> recoveryFamilies, Map<String, ValueEstimate> bareComponents,
			Duration window) {
		this.cleanHosts = Map.copyOf(cleanHosts);
		this.cleanHostFamilies = Map.copyOf(cleanHostFamilies);
		this.recoveryFamilies = Set.copyOf(recoveryFamilies);
		this.bareComponents = Map.copyOf(bareComponents);
		this.window = window;
	}

	public static RecoveryValueModel empty() {
		return new RecoveryValueModel(Map.of(), Map.of(), Set.of(), Map.of(), Duration.ZERO);
	}

	public Optional<ValueEstimate> cleanHostValue(DetailedDecodedItem detailed) {
		if (detailed == null || uncertain(detailed.recovery())) {
			return Optional.empty();
		}
		return Optional.ofNullable(cleanHosts.get(cleanHostKey(detailed.item())))
				.filter(ValueEstimate::isUsable);
	}

	public Optional<ValueEstimate> bareComponentValue(String stableComponentId) {
		if (stableComponentId == null || stableComponentId.isBlank()) {
			return Optional.empty();
		}
		return Optional.ofNullable(bareComponents.get(stableComponentId))
				.filter(ValueEstimate::isUsable);
	}

	/** Cheap test used before paying to decode an active listing's item_bytes. */
	public boolean hasCleanHostFamily(String itemName, Rarity rarity) {
		return itemName != null && rarity != null
				&& cleanHostFamilies.containsKey(ActiveListing.coarseKey(itemName, rarity));
	}

	/**
	 * Whether realized sales prove this display family can supply either a clean host comparison or
	 * removable metadata. One attached sale is enough for the cheap active-sweep prefilter; the
	 * value legs still require their independent sample and liquidity gates.
	 */
	public boolean mightHaveRecovery(String itemName, Rarity rarity) {
		if (itemName == null || rarity == null) {
			return false;
		}
		String key = ActiveListing.coarseKey(itemName, rarity);
		return cleanHostFamilies.containsKey(key) || recoveryFamilies.contains(key);
	}

	public int cleanHostConfigurations() {
		return cleanHosts.size();
	}

	public int componentIdentities() {
		return bareComponents.size();
	}

	public Duration window() {
		return window;
	}

	static String cleanHostKey(DecodedItem item) {
		DecodedItem stripped = new DecodedItem(item.skyblockId(), item.displayName(), item.count(),
				item.rarity(), item.reforge(), item.stars(), item.recombobulated(),
				item.hotPotatoBooks(), item.enchantments(), List.of(), Map.of(), item.runes(),
				item.pet(), item.potion(), item.quality(), item.dye(), item.ethermerged(),
				item.winningBid());
		return stripped.signature() + "|recovery=clean";
	}

	private static boolean uncertain(RecoveryMetadata metadata) {
		return metadata.warnings().contains(RecoveryWarning.MALFORMED_METADATA)
				|| metadata.warnings().contains(RecoveryWarning.UNKNOWN_MAPPING)
				|| metadata.warnings().contains(RecoveryWarning.UNSUPPORTED_SLOT);
	}

	static final class Builder {
		private static final int MAX_SAMPLES_PER_KEY = 200;
		private final Map<String, List<Double>> cleanHosts = new HashMap<>();
		private final Map<String, List<Double>> cleanHostFamilies = new HashMap<>();
		private final java.util.HashSet<String> recoveryFamilies = new java.util.HashSet<>();
		private final Map<String, List<Double>> bareComponents = new HashMap<>();
		private final Duration window;

		Builder(Duration window) {
			this.window = window;
		}

		void add(DetailedDecodedItem detailed, double unitPrice) {
			if (detailed == null || !Double.isFinite(unitPrice) || unitPrice <= 0.0d
					|| uncertain(detailed.recovery())) {
				return;
			}

			DecodedItem item = detailed.item();
			if (detailed.recovery().hasRecoverableParts()) {
				recoveryFamilies.add(ActiveListing.coarseKey(item.displayName(), item.rarity()));
			}
			if (!detailed.recovery().hasRecoverableParts()) {
				record(cleanHosts, cleanHostKey(item), unitPrice);
				record(cleanHostFamilies,
						ActiveListing.coarseKey(item.displayName(), item.rarity()), unitPrice);
			}
			if (Keying.PRODUCTION.isBare(item)) {
				record(bareComponents, item.skyblockId(), unitPrice);
			}
		}

		RecoveryValueModel build() {
			double hours = window.toMillis() / 3_600_000.0d;
			return new RecoveryValueModel(estimates(cleanHosts, hours),
					estimates(cleanHostFamilies, hours),
					recoveryFamilies, estimates(bareComponents, hours), window);
		}

		private static void record(Map<String, List<Double>> index, String key, double price) {
			List<Double> prices = index.computeIfAbsent(key, ignored -> new ArrayList<>());
			if (prices.size() >= MAX_SAMPLES_PER_KEY) {
				prices.removeFirst();
			}
			prices.add(price);
		}

		private static Map<String, ValueEstimate> estimates(Map<String, List<Double>> samples,
				double windowHours) {
			Map<String, ValueEstimate> out = new HashMap<>();
			samples.forEach((key, prices) -> {
				if (prices.size() >= ValueEstimate.MIN_SAMPLES) {
					out.put(key, ValueEstimate.of(key, prices, windowHours,
							ValueEstimate.Basis.EXACT));
				}
			});
			return out;
		}
	}
}
