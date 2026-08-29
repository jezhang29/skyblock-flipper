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

import jeff.skyblockflipper.core.api.AuctionScanSnapshot;
import jeff.skyblockflipper.core.config.RecoverySettings;

import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Staleness, current-snapshot, family and bounded UUID/fingerprint deduplication for alerts. */
public final class RecoveryAlertGate {
	private final int maximumEntries;
	private final Duration ttl;
	private final LinkedHashMap<String, Instant> seen = new LinkedHashMap<>();

	public RecoveryAlertGate(int maximumEntries, Duration ttl) {
		if (maximumEntries < 1 || ttl == null || ttl.isNegative() || ttl.isZero()) {
			throw new IllegalArgumentException("invalid recovery alert cache bounds");
		}
		this.maximumEntries = maximumEntries;
		this.ttl = ttl;
	}

	public synchronized boolean claim(RecoveryOpportunity opportunity, AuctionScanSnapshot snapshot,
			RecoverySettings settings, Instant now) {
		prune(now);
		if (!eligible(opportunity, snapshot, settings, now)) {
			return false;
		}
		String key = opportunity.auctionUuid() + "\0" + opportunity.fingerprint();
		if (seen.containsKey(key)) {
			return false;
		}
		seen.put(key, now);
		while (seen.size() > maximumEntries) {
			Iterator<String> oldest = seen.keySet().iterator();
			oldest.next();
			oldest.remove();
		}
		return true;
	}

	public synchronized int size() {
		return seen.size();
	}

	public static boolean eligible(RecoveryOpportunity opportunity, AuctionScanSnapshot snapshot,
			RecoverySettings settings, Instant now) {
		if (opportunity == null || snapshot == null || settings == null || now == null
				|| !settings.alertsEnabled()
				|| !(settings.chatNotifications() || settings.toastNotifications() || settings.sound())
				|| opportunity.expectedProfit() < settings.minimumProfit()
				|| opportunity.margin() < settings.minimumMargin()
				|| opportunity.warnings().contains(RecoveryWarning.STALE_EVIDENCE)
				|| !current(opportunity, snapshot)
				|| stale(opportunity.observedAt(), now, settings.maximumAgeSeconds())
				|| stale(snapshot.scannedAt(), now, settings.maximumAgeSeconds())) {
			return false;
		}
		return opportunity.componentQuotes().stream().anyMatch(quote ->
				quote.credited() && familyEnabled(quote.kind(), settings));
	}

	private static boolean current(RecoveryOpportunity opportunity, AuctionScanSnapshot snapshot) {
		return snapshot.recovery().stream().anyMatch(current ->
				current.auctionUuid().equals(opportunity.auctionUuid())
						&& current.fingerprint().equals(opportunity.fingerprint()));
	}

	private static boolean stale(Instant observed, Instant now, int maximumAgeSeconds) {
		Duration age = Duration.between(observed, now);
		return age.isNegative() || age.compareTo(Duration.ofSeconds(maximumAgeSeconds)) > 0;
	}

	private static boolean familyEnabled(RecoveryComponentKind kind, RecoverySettings settings) {
		return switch (kind) {
			case GEMSTONE -> settings.gemstones();
			case DRILL_ENGINE, DRILL_FUEL_TANK, DRILL_UPGRADE_MODULE, GOBLIN_OMELETTE ->
					settings.drills();
			case FISHING_HOOK, FISHING_LINE, FISHING_SINKER -> settings.rods();
			case LEGACY_SHARD, ANANKE_FEATHER -> settings.legacy();
			case HOST -> false;
		};
	}

	private void prune(Instant now) {
		Iterator<Map.Entry<String, Instant>> entries = seen.entrySet().iterator();
		while (entries.hasNext()) {
			Duration age = Duration.between(entries.next().getValue(), now);
			if (age.isNegative() || age.compareTo(ttl) > 0) {
				entries.remove();
			}
		}
	}
}
