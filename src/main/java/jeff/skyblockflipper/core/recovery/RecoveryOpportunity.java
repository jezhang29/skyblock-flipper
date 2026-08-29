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

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** One active BIN host and the complete evidence-backed floor recoverable from it. */
public record RecoveryOpportunity(
		String auctionUuid,
		String itemId,
		String displayName,
		long purchasePrice,
		Instant observedAt,
		RecoveryComponentQuote cleanHostQuote,
		List<RecoveryComponentQuote> componentQuotes,
		long conservativeFloor,
		long expectedProfit,
		double margin,
		RecoveryConfidence confidence,
		Set<RecoveryWarning> warnings,
		String fingerprint) {

	public RecoveryOpportunity {
		auctionUuid = Objects.requireNonNull(auctionUuid, "auctionUuid");
		itemId = Objects.requireNonNull(itemId, "itemId");
		displayName = Objects.requireNonNull(displayName, "displayName");
		Objects.requireNonNull(observedAt, "observedAt");
		Objects.requireNonNull(cleanHostQuote, "cleanHostQuote");
		componentQuotes = List.copyOf(Objects.requireNonNull(componentQuotes, "componentQuotes"));
		Objects.requireNonNull(confidence, "confidence");
		warnings = Set.copyOf(Objects.requireNonNull(warnings, "warnings"));
		fingerprint = Objects.requireNonNull(fingerprint, "fingerprint");
		if (auctionUuid.isBlank() || itemId.isBlank() || displayName.isBlank() || fingerprint.isBlank()
				|| purchasePrice <= 0L || conservativeFloor < 0L || !Double.isFinite(margin)) {
			throw new IllegalArgumentException("invalid recovery opportunity");
		}
		if (expectedProfit != conservativeFloor - purchasePrice
				|| Double.compare(margin, expectedProfit / (double) purchasePrice) != 0) {
			throw new IllegalArgumentException("inconsistent recovery totals");
		}
	}
}
