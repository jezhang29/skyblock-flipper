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

import jeff.skyblockflipper.core.text.Coins;
import jeff.skyblockflipper.core.text.Waits;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Pure, client-independent policy for what a candidate may claim on any presentation surface.
 *
 * <p>Bazaar spread candidates still carry legacy scoring fields so old files and non-UI callers do
 * not need a destructive migration. This policy is the quarantine boundary: quote-only candidates
 * expose current-price arithmetic and the evidence gap, never the legacy hourly score, fill timer,
 * confidence, or action steps.
 */
public record OpportunityPresentation(
		OutcomeAvailability availability,
		String headline,
		String unitNetLabel,
		String fullFillLabel,
		String completionLabel,
		String completion,
		Optional<String> hourly,
		List<String> notes,
		List<String> risks,
		List<String> steps,
		boolean actionable,
		boolean sharedHourlyComparable
) {
	public static final String QUOTED_UNIT_NET = "Quoted net/unit";
	public static final String QUOTED_FULL_FILL_NET = "Quoted net at reference size";
	public static final String PERSONAL_COMPLETION = "Personal completion";
	public static final String PERSONAL_COMPLETION_UNAVAILABLE = "unavailable";
	public static final String SPREAD_EVIDENCE_GAP =
			"Personal completion unavailable; no comparable spread cohort.";
	public static final String SCENARIO_EVIDENCE_GAP =
			"Expected personal outcome unavailable; scenarios have no assigned probabilities.";

	public OpportunityPresentation {
		hourly = hourly == null ? Optional.empty() : hourly;
		notes = List.copyOf(notes);
		risks = List.copyOf(risks);
		steps = List.copyOf(steps);
	}

	public static OpportunityPresentation of(FlipCandidate candidate) {
		if (candidate.outcomeAvailability() == OutcomeAvailability.QUOTE_ONLY) {
			return new OpportunityPresentation(
					OutcomeAvailability.QUOTE_ONLY,
					"Quoted +" + Coins.format(candidate.totalNetProfit()) + " over "
							+ candidate.units() + " visible-book reference units",
					QUOTED_UNIT_NET,
					QUOTED_FULL_FILL_NET,
					PERSONAL_COMPLETION,
					PERSONAL_COMPLETION_UNAVAILABLE,
					Optional.empty(),
					Stream.concat(Stream.of(SPREAD_EVIDENCE_GAP), candidate.notes().stream())
							.toList(),
					// Phase 4 asks a quote-only row to show its evidence gaps, not to go quiet: a
					// falling series, a pushable book and a shallow book are descriptions of the
					// public record, not predictions of a personal outcome. Blanking them alongside
					// the removal of the admission gates would leave the row with no warning at all.
					candidate.risks(),
					List.of(),
					false,
					false);
		}

		if (candidate.outcomeAvailability() == OutcomeAvailability.SCENARIOS_ONLY) {
			return new OpportunityPresentation(
					OutcomeAvailability.SCENARIOS_ONLY,
					"Scenario +" + Coins.format(candidate.totalNetProfit()) + " if full fill",
					"Conditional net/unit",
					"Conditional full-fill net",
					PERSONAL_COMPLETION,
					PERSONAL_COMPLETION_UNAVAILABLE,
					Optional.empty(),
					List.of(SCENARIO_EVIDENCE_GAP),
					List.of(),
					List.of(),
					false,
					false);
		}

		Optional<String> completion = candidate.fill() == null
				? Optional.empty()
				: Optional.of(candidate.timeToTurnOver().map(Waits::format).orElse("never at this size")
						+ (candidate.fillMeasured() ? " (measured)" : " (assumed)"));

		return new OpportunityPresentation(
				candidate.outcomeAvailability(),
				Coins.format(candidate.profitPerHour()) + "/hr",
				"Net/unit",
				"Total",
				"Fill",
				completion.orElse("-"),
				Optional.of(Coins.format(candidate.profitPerHour())),
				candidate.notes(),
				candidate.risks(),
				candidate.steps(),
				true,
				true);
	}
}
