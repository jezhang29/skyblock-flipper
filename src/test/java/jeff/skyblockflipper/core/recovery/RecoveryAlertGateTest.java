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
import jeff.skyblockflipper.core.pricing.Fees;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RecoveryAlertGateTest {
	private static final Instant NOW = Instant.parse("2026-08-28T12:00:00Z");

	@Test
	void defaultsAreOffAndStaleOrMissingRowsFailClosed() {
		RecoveryOpportunity opportunity = opportunity("uuid", NOW.minusSeconds(20), 300_000L);
		AuctionScanSnapshot current = snapshot(opportunity, NOW.minusSeconds(10));

		assertFalse(RecoveryAlertGate.eligible(opportunity, current, settings(false, true, true), NOW));
		assertFalse(RecoveryAlertGate.eligible(opportunity, current,
				settings(true, true, true, 5), NOW));
		assertFalse(RecoveryAlertGate.eligible(opportunity,
				new AuctionScanSnapshot(1L, NOW, List.of(), List.of(), 1L, 1L, ""),
				settings(true, true, true), NOW));
	}

	@Test
	void appliesProfitMarginFamilyAndDeliveryGates() {
		RecoveryOpportunity opportunity = opportunity("uuid", NOW, 300_000L);
		AuctionScanSnapshot snapshot = snapshot(opportunity, NOW);

		assertTrue(RecoveryAlertGate.eligible(opportunity, snapshot,
				settings(true, true, true), NOW));
		assertFalse(RecoveryAlertGate.eligible(opportunity, snapshot,
				settings(true, false, true), NOW));
		assertFalse(RecoveryAlertGate.eligible(opportunity, snapshot,
				settings(true, true, false), NOW));
		assertFalse(RecoveryAlertGate.eligible(opportunity, snapshot,
				new RecoverySettings(true, false, false, false, 1L, 0.0d, 0.15d,
						6, 1.0d, 48.0d, 1.0d, 120, true, true, true, false), NOW));
	}

	@Test
	void uncreditedFamilyCannotTriggerAnAlert() {
		RecoveryOpportunity opportunity = opportunityWithUncreditedGemstone();

		assertFalse(RecoveryAlertGate.eligible(opportunity, snapshot(opportunity, NOW),
				settings(true, true, true), NOW));
	}

	@Test
	void deduplicatesUuidAndFingerprintWithBoundedTtlMemory() {
		RecoveryAlertGate gate = new RecoveryAlertGate(2, Duration.ofSeconds(30));
		RecoverySettings settings = settings(true, true, true);
		RecoveryOpportunity first = opportunity("one", NOW, 300_000L);

		assertTrue(gate.claim(first, snapshot(first, NOW), settings, NOW));
		assertFalse(gate.claim(first, snapshot(first, NOW), settings, NOW.plusSeconds(1)));

		RecoveryOpportunity changed = opportunity("one", NOW, 350_000L);
		assertTrue(gate.claim(changed, snapshot(changed, NOW), settings, NOW.plusSeconds(2)));
		RecoveryOpportunity third = opportunity("three", NOW, 300_000L);
		assertTrue(gate.claim(third, snapshot(third, NOW), settings, NOW.plusSeconds(3)));
		assertEquals(2, gate.size());

		// The oldest key was evicted to enforce the cap, and all keys expire after the TTL.
		assertTrue(gate.claim(first, snapshot(first, NOW), settings, NOW.plusSeconds(4)));
		assertTrue(gate.claim(changed, snapshot(changed, NOW.plusSeconds(40)), settings,
				NOW.plusSeconds(40)));
		assertTrue(gate.size() <= 2);
	}

	private static RecoverySettings settings(boolean enabled, boolean gemstone, boolean channel) {
		return settings(enabled, gemstone, channel, 120);
	}

	private static RecoverySettings settings(boolean enabled, boolean gemstone, boolean channel,
			int maxAge) {
		return new RecoverySettings(enabled, channel, false, false, 100_000L, 0.10d, 0.15d,
				6, 1.0d, 48.0d, 1.0d, maxAge, gemstone, true, true, false);
	}

	private static AuctionScanSnapshot snapshot(RecoveryOpportunity opportunity, Instant scannedAt) {
		return new AuctionScanSnapshot(1L, scannedAt, List.of(), List.of(opportunity),
				1L, 1L, "");
	}

	private static RecoveryOpportunity opportunity(String uuid, Instant observedAt, long gemGross) {
		RecoveryComponentQuote host = RecoveryFloorCalculator.quote(new RecoveryLeg(
				RecoveryComponentKind.HOST, "HOST", "Host", 1L, RecoveryExitVenue.AH,
				1_000_000L, 0L, 10, 5.0d, 1_000L, 0L, RecoveryConfidence.MEDIUM,
				true, Set.of()), 0.15d, Fees.none()).orElseThrow();
		RecoveryComponentQuote gem = RecoveryFloorCalculator.quote(new RecoveryLeg(
				RecoveryComponentKind.GEMSTONE, "FINE_RUBY_GEM", "Fine Ruby Gem", 1L,
				RecoveryExitVenue.BAZAAR, gemGross, 10_000L, 0, 10.0d, 0L, 1L,
				RecoveryConfidence.HIGH, true, Set.of()), 0.15d, Fees.none()).orElseThrow();
		return RecoveryFloorCalculator.compose(uuid, "HOST", "Host", 500_000L, observedAt,
				host, List.of(gem)).orElseThrow();
	}

	private static RecoveryOpportunity opportunityWithUncreditedGemstone() {
		RecoveryComponentQuote host = RecoveryFloorCalculator.quote(new RecoveryLeg(
				RecoveryComponentKind.HOST, "HOST", "Host", 1L, RecoveryExitVenue.AH,
				1_000_000L, 0L, 10, 5.0d, 1_000L, 0L, RecoveryConfidence.MEDIUM,
				true, Set.of()), 0.15d, Fees.none()).orElseThrow();
		RecoveryComponentQuote gem = RecoveryFloorCalculator.quote(RecoveryLeg.uncredited(
				RecoveryComponentKind.GEMSTONE, "FINE_RUBY_GEM", "Fine Ruby Gem", 1L,
				RecoveryWarning.UNKNOWN_REMOVAL_COST), 0.15d, Fees.none()).orElseThrow();
		return RecoveryFloorCalculator.compose("uuid", "HOST", "Host", 500_000L, NOW,
				host, List.of(gem)).orElseThrow();
	}
}
