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
package jeff.skyblockflipper.core.strategy;

import jeff.skyblockflipper.core.pricing.FillModel.FillEstimate;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpportunityPresentationTest {
	@Test
	void quoteOnlySpreadShowsEconomicsAndTheEvidenceGap() {
		FlipCandidate candidate = candidate(OutcomeAvailability.QUOTE_ONLY, 9_999_999.0d, 0.99d);
		OpportunityPresentation presentation = OpportunityPresentation.of(candidate);

		assertEquals(OpportunityPresentation.QUOTED_UNIT_NET, presentation.unitNetLabel());
		assertEquals(OpportunityPresentation.QUOTED_FULL_FILL_NET, presentation.fullFillLabel());
		assertEquals(OpportunityPresentation.PERSONAL_COMPLETION,
				presentation.completionLabel());
		assertEquals(OpportunityPresentation.PERSONAL_COMPLETION_UNAVAILABLE,
				presentation.completion());
		assertEquals(List.of(OpportunityPresentation.SPREAD_EVIDENCE_GAP, "Legacy note"),
				presentation.notes());
		assertEquals("Quoted +86 over 10 visible-book reference units", presentation.headline());
	}

	@Test
	void quoteOnlySpreadNeverRendersLegacyOutcomeDiagnosticsOrActions() {
		OpportunityPresentation presentation = OpportunityPresentation.of(
				candidate(OutcomeAvailability.QUOTE_ONLY, 9_999_999.0d, 0.99d));
		String rendered = String.join(" ", presentation.headline(), presentation.unitNetLabel(),
				presentation.fullFillLabel(), presentation.completionLabel(), presentation.completion(),
				String.join(" ", presentation.notes()), String.join(" ", presentation.risks()),
				String.join(" ", presentation.steps()), presentation.hourly().orElse(""));

		assertFalse(rendered.contains("/hr"), rendered);
		assertFalse(rendered.toLowerCase().contains("confidence"), rendered);
		assertFalse(rendered.toLowerCase().contains("countdown"), rendered);
		assertTrue(presentation.hourly().isEmpty());
		assertTrue(presentation.steps().isEmpty());
		assertFalse(presentation.actionable());
		assertFalse(presentation.sharedHourlyComparable());
	}

	@Test
	void scenariosWithoutProbabilitiesAlsoFailClosed() {
		OpportunityPresentation presentation = OpportunityPresentation.of(
				candidate(OutcomeAvailability.SCENARIOS_ONLY, 100.0d, 0.5d));

		assertFalse(presentation.actionable());
		assertFalse(presentation.sharedHourlyComparable());
		assertTrue(presentation.hourly().isEmpty());
		assertEquals(List.of(OpportunityPresentation.SCENARIO_EVIDENCE_GAP),
				presentation.notes());
	}

	@Test
	void calibratedLegacyNonSpreadPresentationKeepsItsExistingHourlySurface() {
		OpportunityPresentation presentation = OpportunityPresentation.of(
				candidate(OutcomeAvailability.CALIBRATED, 1234.0d, 0.75d));

		assertEquals("1.2k/hr", presentation.headline());
		assertEquals("1.2k", presentation.hourly().orElseThrow());
		assertTrue(presentation.actionable());
		assertTrue(presentation.sharedHourlyComparable());
	}

	@Test
	void canonicalCandidateRejectsMissingOutcomeSupport() {
		assertThrows(NullPointerException.class,
				() -> candidate(null, 100.0d, 0.5d));
	}

	private static FlipCandidate candidate(OutcomeAvailability availability, double legacyHourly,
			double legacyConfidence) {
		return new FlipCandidate("TEST", "Test Item", StrategyKind.BAZAAR_SPREAD,
				100.0d, 110.0d, 8.625d, 10L, 1_000L, legacyHourly, legacyConfidence,
				List.of("Place the order"), List.of("Legacy risk"), List.of("Legacy note"),
				new FillEstimate(100.0d, 80.0d, 12.0d, true), false, availability);
	}
}
