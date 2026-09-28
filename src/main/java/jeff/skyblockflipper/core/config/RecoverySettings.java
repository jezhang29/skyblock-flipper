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
package jeff.skyblockflipper.core.config;

/** Immutable recovery settings read fresh for each sweep or client tick. */
public record RecoverySettings(
		boolean alertsEnabled,
		boolean chatNotifications,
		boolean toastNotifications,
		boolean sound,
		long minimumProfit,
		double minimumMargin,
		double safetyBuffer,
		int minimumAhSamples,
		double minimumAhSalesPerDay,
		double maximumAhSellHours,
		double minimumBazaarInstantSellsPerHour,
		int maximumAgeSeconds,
		boolean gemstones,
		boolean drills,
		boolean rods,
		boolean legacy) {
}
