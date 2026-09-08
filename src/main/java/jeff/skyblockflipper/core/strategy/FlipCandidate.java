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

import jeff.skyblockflipper.core.pricing.FillModel;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One ranked opportunity, in a shape every strategy can produce and the UI can sort uniformly.
 *
 * <p>Legacy supported candidates use {@link #profitPerHour()} as their ranking axis. Quote-only
 * Bazaar spreads retain that field only for compatibility and are excluded from the common hourly
 * comparison; public activity does not establish a personal execution rate.
 *
 * @param itemId          bazaar product or item id
 * @param displayName     human-readable name for the UI
 * @param kind            which strategy produced this
 * @param unitBuyPrice    coins paid per unit
 * @param unitSellPrice   coins received per unit, before fees
 * @param unitNetProfit   profit per unit after every fee
 * @param units           how many units this plan assumes, after capital and liquidity limits
 * @param capitalRequired coins tied up to execute it
 * @param profitPerHour   expected net coins per hour, the ranking axis
 * @param confidence      0-1; how much the inputs deserve to be trusted
 * @param steps           what to actually do, in order
 * @param risks           what could make this not work out
 * @param notes           statements of fact about the flip that are neither an action nor a risk:
 *                        which route was chosen and why, how much the item actually trades, which
 *                        item id this really is. Facts a player would otherwise have to take on
 *                        trust
 * @param fill            how fast the two legs are expected to clear, and whether that was measured
 *                        or assumed. Null for strategies that do not rest an order on a book, and
 *                        for the tests and callers that predate it
 * @param suspect         a flag the strategy could not fully trust: an auction priced so far under
 *                        its own recorded history that the discount is likelier a hidden upgrade the
 *                        signature does not read than a real underprice. Sorted below every trusted
 *                        candidate so a signature-miss mirage can never be the top line, and never
 *                        set by the order-book strategies, which price from a live book
 * @param outcomeAvailability whether the outcome supports a quote, scenarios, or a calibrated
 *                            presentation; legacy non-spread constructors retain their existing UI
 */
public record FlipCandidate(
		String itemId,
		String displayName,
		StrategyKind kind,
		double unitBuyPrice,
		double unitSellPrice,
		double unitNetProfit,
		long units,
		long capitalRequired,
		double profitPerHour,
		double confidence,
		List<String> steps,
		List<String> risks,
		List<String> notes,
		FillModel.FillEstimate fill,
		boolean suspect,
		OutcomeAvailability outcomeAvailability
) implements Comparable<FlipCandidate> {
	public FlipCandidate {
		steps = List.copyOf(steps);
		risks = List.copyOf(risks);
		notes = List.copyOf(notes);
		outcomeAvailability = Objects.requireNonNull(outcomeAvailability, "outcomeAvailability");
	}

	/**
	 * The canonical shape before outcome support was explicit. Non-spread strategies use this
	 * compatibility adapter so Phase 4 does not change their presentation ahead of their migrations.
	 */
	public FlipCandidate(String itemId, String displayName, StrategyKind kind, double unitBuyPrice,
			double unitSellPrice, double unitNetProfit, long units, long capitalRequired,
			double profitPerHour, double confidence, List<String> steps, List<String> risks,
			List<String> notes, FillModel.FillEstimate fill, boolean suspect) {
		this(itemId, displayName, kind, unitBuyPrice, unitSellPrice, unitNetProfit, units,
				capitalRequired, profitPerHour, confidence, steps, risks, notes, fill, suspect,
				OutcomeAvailability.CALIBRATED);
	}

	/**
	 * The shape before a candidate could be flagged suspect, so every existing caller stays a trusted
	 * flip without restating it. Only {@link AuctionValueStrategy} sets the flag, through
	 * {@link #asSuspect()}.
	 */
	public FlipCandidate(String itemId, String displayName, StrategyKind kind, double unitBuyPrice,
			double unitSellPrice, double unitNetProfit, long units, long capitalRequired,
			double profitPerHour, double confidence, List<String> steps, List<String> risks,
			List<String> notes, FillModel.FillEstimate fill) {
		this(itemId, displayName, kind, unitBuyPrice, unitSellPrice, unitNetProfit, units,
				capitalRequired, profitPerHour, confidence, steps, risks, notes, fill, false);
	}

	/**
	 * For strategies with nothing to explain beyond the steps and the risks.
	 *
	 * <p>Kept so that adding {@code notes} did not force every existing strategy and test to state
	 * that it has none.
	 */
	public FlipCandidate(String itemId, String displayName, StrategyKind kind, double unitBuyPrice,
			double unitSellPrice, double unitNetProfit, long units, long capitalRequired,
			double profitPerHour, double confidence, List<String> steps, List<String> risks) {
		this(itemId, displayName, kind, unitBuyPrice, unitSellPrice, unitNetProfit, units,
				capitalRequired, profitPerHour, confidence, steps, risks, List.of());
	}

	/** The shape before fill facts were carried, for the strategies that have none to offer. */
	public FlipCandidate(String itemId, String displayName, StrategyKind kind, double unitBuyPrice,
			double unitSellPrice, double unitNetProfit, long units, long capitalRequired,
			double profitPerHour, double confidence, List<String> steps, List<String> risks,
			List<String> notes) {
		this(itemId, displayName, kind, unitBuyPrice, unitSellPrice, unitNetProfit, units,
				capitalRequired, profitPerHour, confidence, steps, risks, notes, null);
	}

	/**
	 * Legacy estimate of how long the slower modeled leg takes, or empty when its rate is zero.
	 *
	 * <p>Quote-only presentations never expose this value: the underlying public displacement model
	 * is not evidence of personal completion.
	 */
	public Optional<Duration> timeToTurnOver() {
		if (fill == null) {
			return Optional.empty();
		}

		double perHour = fill.throughputPerHour();

		return perHour <= 0.0d || units <= 0L
				? Optional.empty()
				: Optional.of(Duration.ofSeconds(Math.round(units / perHour * 3600.0d)));
	}

	/**
	 * Whether the legacy model used recorded public displacement history. This is not personal fill
	 * evidence and is never rendered for quote-only candidates.
	 */
	public boolean fillMeasured() {
		return fill != null && fill.measured();
	}

	/** Total net profit if the whole plan fills. */
	public double totalNetProfit() {
		return unitNetProfit * units;
	}

	/** Return on capital deployed, as a fraction. */
	public double returnOnCapital() {
		return capitalRequired <= 0L ? 0.0d : totalNetProfit() / capitalRequired;
	}

	/**
	 * The same candidate, marked as one to verify before trusting - a discount too deep on an item
	 * too valuable to take on the model's word alone.
	 *
	 * <p>A wither rather than a constructor argument because only one strategy ever sets it, and only
	 * after the candidate is otherwise built: the profit and the steps are unchanged, what changes is
	 * where it ranks and that it now carries a verify-first risk.
	 */
	public FlipCandidate asSuspect() {
		return suspect ? this : new FlipCandidate(itemId, displayName, kind, unitBuyPrice,
				unitSellPrice, unitNetProfit, units, capitalRequired, profitPerHour, confidence,
				steps, risks, notes, fill, true, outcomeAvailability);
	}

	/**
	 * Outcome-supported candidates first, then legacy suspicion and the applicable display order.
	 *
	 * <p>Quote-only/scenario-only candidates use a stable name order because neither has a comparable
	 * expected-outcome axis. For calibrated-adapter candidates, the suspect split comes before the
	 * profit comparison on purpose: a signature-miss mirage
	 * quotes the most profit of anything on the book precisely because its quote is wrong, so ranking
	 * on profit alone would float it to the top. Demoting it as a class keeps it visible - it is not
	 * dropped, only quarantined - while making it impossible for it to be the first line a player acts
	 * on.
	 */
	@Override
	public int compareTo(FlipCandidate other) {
		if (outcomeAvailability != other.outcomeAvailability) {
			return Integer.compare(supportOrder(other.outcomeAvailability),
					supportOrder(outcomeAvailability));
		}

		if (suspect != other.suspect) {
			return suspect ? 1 : -1;
		}

		if (outcomeAvailability != OutcomeAvailability.CALIBRATED) {
			return displayName.compareToIgnoreCase(other.displayName);
		}

		return Double.compare(other.profitPerHour, profitPerHour);
	}

	private static int supportOrder(OutcomeAvailability availability) {
		return switch (availability) {
			case QUOTE_ONLY -> 0;
			case SCENARIOS_ONLY -> 1;
			case CALIBRATED -> 2;
		};
	}
}
