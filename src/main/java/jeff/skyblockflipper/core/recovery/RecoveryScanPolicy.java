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

import jeff.skyblockflipper.core.config.RecoverySettings;

/** Conservative scan gates; checkpoint 7 exposes these through validated configuration. */
public record RecoveryScanPolicy(double safetyBuffer, int minimumAhSamples,
		double minimumAhSalesPerDay, double maximumAhHoursToSell,
		double minimumBazaarInstantSellsPerHour, long minimumProfit,
		double minimumMargin, int maximumResults) {
	public RecoveryScanPolicy {
		if (!Double.isFinite(safetyBuffer) || safetyBuffer < 0.10d || safetyBuffer > 0.15d
				|| minimumAhSamples < 1 || !Double.isFinite(minimumAhSalesPerDay)
				|| minimumAhSalesPerDay < 0.0d || !Double.isFinite(maximumAhHoursToSell)
				|| maximumAhHoursToSell <= 0.0d
				|| !Double.isFinite(minimumBazaarInstantSellsPerHour)
				|| minimumBazaarInstantSellsPerHour < 0.0d
				|| minimumProfit < 0L || !Double.isFinite(minimumMargin)
				|| minimumMargin < 0.0d
				|| maximumResults < 1 || maximumResults > 1_000) {
			throw new IllegalArgumentException("invalid recovery scan policy");
		}
	}

	public static RecoveryScanPolicy conservativeDefaults() {
		return new RecoveryScanPolicy(0.15d, 6, 1.0d, 48.0d, 1.0d,
				500_000L, 0.15d, 200);
	}

	public static RecoveryScanPolicy from(RecoverySettings settings) {
		return new RecoveryScanPolicy(settings.safetyBuffer(), settings.minimumAhSamples(),
				settings.minimumAhSalesPerDay(), settings.maximumAhSellHours(),
				settings.minimumBazaarInstantSellsPerHour(), settings.minimumProfit(),
				settings.minimumMargin(), 200);
	}
}
