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
package jeff.skyblockflipper.core.valuation;

import jeff.skyblockflipper.core.item.Rarity;
import jeff.skyblockflipper.core.model.ActiveListing;
import jeff.skyblockflipper.core.pricing.Fees;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Counts how many live listings sit below fair value per coarse key, over a single sweep.
 *
 * <p>Every listing already flows past the sweep sink with its name, rarity and price readable
 * without decoding, so accumulating them costs nothing beyond memory. That is the whole design
 * constraint of this step: no extra decode, no extra request. The output feeds a log line, not a
 * trade - it is here to show whether floor-sweep opportunities (several cheap listings under a gap
 * to the next price) exist often enough to be worth a strategy.
 *
 * <p>The key is name-and-rarity, the same coarse key the underpriced scan prunes on, so it mixes
 * configurations. A signal here is an invitation to decode that key, not proof of an opportunity;
 * see {@link SupplySignal}.
 */
public final class SupplyCounter {
	/** Below this sale rate a swept relist parks capital longer than the gap is worth waiting for. */
	private static final double MIN_SALES_PER_HOUR = 0.2d;

	/** One cheap listing is a snipe, not a floor to sweep. Two or more is the shape we are counting. */
	private static final int MIN_BELOW_FAIR = 2;

	private final FairValueModel model;
	private final Fees fees;
	private final Map<String, Bucket> buckets = new HashMap<>();

	public SupplyCounter(FairValueModel model, Fees fees) {
		this.model = model;
		this.fees = fees;
	}

	/** Records one listing's price under its coarse key. No decode, no allocation past the price. */
	public void observe(ActiveListing listing) {
		buckets.computeIfAbsent(listing.coarseKey(),
						key -> new Bucket(listing.itemName(), listing.rarity()))
				.prices.add(listing.price());
	}

	/**
	 * The keys where multiple listings sit below fair value with a gap to the next tier that clears
	 * fees. Cheapest gap-after-fees first, so a log truncated to the top few keeps the best.
	 */
	public List<SupplySignal> signals() {
		List<SupplySignal> signals = new ArrayList<>();

		for (Bucket bucket : buckets.values()) {
			signal(bucket).ifPresent(signals::add);
		}

		signals.sort(Comparator.comparingLong(SupplySignal::profitPerUnit).reversed());
		return signals;
	}

	private Optional<SupplySignal> signal(Bucket bucket) {
		if (bucket.prices.size() < MIN_BELOW_FAIR) {
			return Optional.empty();
		}

		Optional<ValueEstimate> rough = model.roughValueOf(bucket.itemName, bucket.rarity);

		if (rough.isEmpty()) {
			// Never sold in the window: there is nothing to call these listings cheap against.
			return Optional.empty();
		}

		double fairValue = rough.get().median();
		double salesPerHour = rough.get().salesPerHour();

		if (salesPerHour < MIN_SALES_PER_HOUR) {
			return Optional.empty();
		}

		long[] sorted = bucket.prices.stream().mapToLong(Long::longValue).sorted().toArray();

		int belowFair = 0;
		for (long price : sorted) {
			if (price < fairValue) {
				belowFair++;
			}
		}

		if (belowFair < MIN_BELOW_FAIR) {
			return Optional.empty();
		}

		long cheapClusterMax = sorted[belowFair - 1];

		// The next resting listing above the cluster, or fair value when the whole live supply is
		// below it and the median is the only exit to relist under.
		long nextTier = (long) fairValue;
		for (long price : sorted) {
			if (price > cheapClusterMax) {
				nextTier = price;
				break;
			}
		}

		long profitPerUnit = fees.binRoundTripProfit(cheapClusterMax, nextTier);

		if (profitPerUnit <= 0L) {
			// The gap does not survive the fee stack, so sweeping it would shred coins.
			return Optional.empty();
		}

		return Optional.of(new SupplySignal(bucket.itemName, bucket.rarity, fairValue, salesPerHour,
				sorted.length, belowFair, sorted[0], cheapClusterMax, nextTier, profitPerUnit));
	}

	private static final class Bucket {
		private final String itemName;
		private final Rarity rarity;
		private final List<Long> prices = new ArrayList<>();

		private Bucket(String itemName, Rarity rarity) {
			this.itemName = itemName;
			this.rarity = rarity;
		}
	}
}
