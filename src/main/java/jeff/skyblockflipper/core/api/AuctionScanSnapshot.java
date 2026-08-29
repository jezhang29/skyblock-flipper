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
package jeff.skyblockflipper.core.api;

import jeff.skyblockflipper.core.recovery.RecoveryOpportunity;
import jeff.skyblockflipper.core.valuation.PricedListing;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Ordinary and recovery outputs atomically published from one completed active-AH sweep. */
public record AuctionScanSnapshot(long lastUpdated, Instant scannedAt,
		List<PricedListing> ordinary, List<RecoveryOpportunity> recovery,
		long ordinaryRevision, long recoveryRevision, String summary) {
	public AuctionScanSnapshot {
		Objects.requireNonNull(scannedAt, "scannedAt");
		ordinary = List.copyOf(Objects.requireNonNull(ordinary, "ordinary"));
		recovery = List.copyOf(Objects.requireNonNull(recovery, "recovery"));
		summary = Objects.requireNonNull(summary, "summary");
		if (lastUpdated < 0L || ordinaryRevision < 0L || recoveryRevision < 0L) {
			throw new IllegalArgumentException("invalid auction scan snapshot");
		}
	}

	public static AuctionScanSnapshot empty() {
		return new AuctionScanSnapshot(0L, Instant.EPOCH, List.of(), List.of(), 0L, 0L, "");
	}
}
