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

import java.util.Objects;
import java.util.Set;

/** A recovery leg after conservative buffer, venue fee, and fixed removal-cost math. */
public record RecoveryComponentQuote(
		RecoveryComponentKind kind,
		String stableComponentId,
		String displayName,
		long quantity,
		RecoveryExitVenue exitVenue,
		long grossQuickSale,
		long bufferedGross,
		long fee,
		long removalCost,
		long netContribution,
		int sampleCount,
		double salesPerDay,
		long medianSellingTimeMillis,
		long quotedDepth,
		RecoveryConfidence confidence,
		boolean credited,
		Set<RecoveryWarning> warnings) {

	public RecoveryComponentQuote {
		Objects.requireNonNull(kind, "kind");
		stableComponentId = Objects.requireNonNull(stableComponentId, "stableComponentId");
		displayName = Objects.requireNonNull(displayName, "displayName");
		Objects.requireNonNull(exitVenue, "exitVenue");
		Objects.requireNonNull(confidence, "confidence");
		warnings = Set.copyOf(Objects.requireNonNull(warnings, "warnings"));
		if (quantity <= 0L || grossQuickSale < 0L || bufferedGross < 0L || fee < 0L
				|| removalCost < 0L || sampleCount < 0 || !Double.isFinite(salesPerDay)
				|| salesPerDay < 0.0d || medianSellingTimeMillis < 0L || quotedDepth < 0L) {
			throw new IllegalArgumentException("invalid recovery quote");
		}
		if (!credited && (exitVenue != RecoveryExitVenue.NONE || grossQuickSale != 0L
				|| bufferedGross != 0L || fee != 0L || removalCost != 0L
				|| netContribution != 0L || confidence != RecoveryConfidence.NONE
				|| warnings.isEmpty())) {
			throw new IllegalArgumentException("uncredited quote must be an explained zero");
		}
		if (credited) {
			try {
				if (exitVenue == RecoveryExitVenue.NONE || bufferedGross > grossQuickSale
						|| fee > bufferedGross || netContribution != Math.subtractExact(
						Math.subtractExact(bufferedGross, fee), removalCost)) {
					throw new IllegalArgumentException("inconsistent credited quote");
				}
			} catch (ArithmeticException failure) {
				throw new IllegalArgumentException("overflowing credited quote", failure);
			}
		}
	}
}
