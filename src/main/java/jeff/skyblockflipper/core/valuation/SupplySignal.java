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

/**
 * One coarse key where several live listings cluster below fair value with a gap up to the next
 * price that clears fees - the shape a floor sweep would act on.
 *
 * <p>Data-gathering only. Nothing consumes this to plan a trade; it is logged so the distribution
 * can be studied before a strategy is built on it (roadmap step 5). The count is keyed on name and
 * rarity, so it mixes configurations - a bare helmet and a five-star one land together - which
 * overstates how comparable the cheap listings are. Read it as "this key is worth decoding", not as
 * a settled opportunity.
 *
 * @param itemName        the listing name, coarse key's first half
 * @param rarity          the listing rarity, coarse key's second half
 * @param fairValue       the name-and-rarity median this cluster is judged cheap against
 * @param salesPerHour    observed sale rate for the coarse key; how fast a relist would turn over
 * @param listings        how many live listings share the coarse key
 * @param belowFair       how many of them sit below {@code fairValue}
 * @param cheapestPrice   the cheapest listing
 * @param cheapClusterMax the dearest listing still below fair value - what sweeping the whole
 *                        cluster would cost per unit at worst
 * @param nextTier        the cheapest listing above the cluster, or {@code fairValue} when nothing
 *                        rests above it - where a swept unit would be relisted just under
 * @param profitPerUnit   {@code binRoundTripProfit(cheapClusterMax, nextTier)}: the gap after fees
 */
public record SupplySignal(
		String itemName,
		Rarity rarity,
		double fairValue,
		double salesPerHour,
		int listings,
		int belowFair,
		long cheapestPrice,
		long cheapClusterMax,
		long nextTier,
		long profitPerUnit
) {
	/** One log line, compact enough to scan a sweep's worth of them. */
	public String describe() {
		return String.format(
				"%s [%s]: %d listings, %d below fair %.0f (sales/h %.2f); "
						+ "cheap %d..%d, next tier %d, gap after fees %d",
				itemName, rarity.name(), listings, belowFair, fairValue, salesPerHour,
				cheapestPrice, cheapClusterMax, nextTier, profitPerUnit);
	}
}
