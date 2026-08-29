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
package jeff.skyblockflipper.client;

import jeff.skyblockflipper.core.api.MarketData;
import jeff.skyblockflipper.core.recovery.RecoveryOpportunity;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import java.util.List;

/** Recovery's independent immutable client cache, keyed only by the recovery scan revision. */
public final class RecoveryFeed {
	private static volatile List<RecoveryOpportunity> cached = List.of();
	private static long cachedRevision = -1L;

	private RecoveryFeed() {}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(client -> refreshIfStale());
	}

	public static List<RecoveryOpportunity> current() {
		refreshIfStale();
		return cached;
	}

	public static long revision() {
		refreshIfStale();
		return cachedRevision;
	}

	public static void invalidate() {
		cachedRevision = -1L;
	}

	static List<RecoveryOpportunity> refresh(MarketData data) {
		long revision = data.recoveryRevision();
		if (revision != cachedRevision) {
			cached = List.copyOf(data.recoveryOpportunities());
			cachedRevision = revision;
		}
		return cached;
	}

	private static void refreshIfStale() {
		refresh(MarketDataService.data());
	}
}
