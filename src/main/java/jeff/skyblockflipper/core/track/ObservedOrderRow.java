/*
 * Skyblock Flipper - a Hypixel Skyblock flipping advisor mod.
 * Copyright (C) 2026 SoupChugger
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package jeff.skyblockflipper.core.track;

import jeff.skyblockflipper.core.evidence.NumericEvidence;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/** A parsed row that retains raw and independent fields even when one field is uncertain. */
public record ObservedOrderRow(long at, int slotIndex, TradeEvent.Side side, String itemId,
		String displayName, String owner, long total, NumericEvidence<Long> filled,
		Optional<BigDecimal> unitPrice, Optional<BigDecimal> claimCoins,
		OptionalLong claimItems, List<String> rawLore) {
	public ObservedOrderRow {
		Objects.requireNonNull(side, "side");
		itemId = Objects.requireNonNull(itemId, "itemId");
		displayName = Objects.requireNonNull(displayName, "displayName");
		owner = Objects.requireNonNull(owner, "owner");
		if (total <= 0L) {
			throw new IllegalArgumentException("order total must be positive");
		}
		Objects.requireNonNull(filled, "filled");
		unitPrice = Objects.requireNonNull(unitPrice, "unitPrice");
		claimCoins = Objects.requireNonNull(claimCoins, "claimCoins");
		Objects.requireNonNull(claimItems, "claimItems");
		rawLore = List.copyOf(rawLore);
	}

	public OptionalLong exactFilled() {
		return filled instanceof NumericEvidence.Exact<Long> exact
				? OptionalLong.of(exact.value())
				: OptionalLong.empty();
	}

	/** Exact legacy actions are safe only when the cumulative filled count is exact. */
	public boolean supportsExactQuantityActions() {
		return exactFilled().isPresent();
	}

	Optional<OrderSnapshot> legacySnapshot() {
		OptionalLong exact = exactFilled();
		if (exact.isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(new OrderSnapshot(at, side, itemId, displayName, owner, total,
				exact.getAsLong(), unitPrice.map(BigDecimal::doubleValue).orElse(0.0d),
				claimCoins.map(BigDecimal::doubleValue).orElse(0.0d), claimItems.orElse(0L)));
	}
}
