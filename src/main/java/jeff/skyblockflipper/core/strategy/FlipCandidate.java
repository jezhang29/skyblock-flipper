package jeff.skyblockflipper.core.strategy;

import java.util.List;

/**
 * One ranked opportunity, in a shape every strategy can produce and the UI can sort uniformly.
 *
 * <p>The ranking axis is {@link #profitPerHour()}, not margin percent. A 15% spread on something
 * that fills four units a day is worthless; a 2% spread on something moving 500k units an hour is
 * a business. Ranking by margin is the single most common way flipping tools send people after
 * illiquid junk.
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
		List<String> risks
) implements Comparable<FlipCandidate> {
	public FlipCandidate {
		steps = List.copyOf(steps);
		risks = List.copyOf(risks);
	}

	/** Total net profit if the whole plan fills. */
	public double totalNetProfit() {
		return unitNetProfit * units;
	}

	/** Return on capital deployed, as a fraction. */
	public double returnOnCapital() {
		return capitalRequired <= 0L ? 0.0d : totalNetProfit() / capitalRequired;
	}

	/** Descending by profit per hour, so natural ordering is already "best first". */
	@Override
	public int compareTo(FlipCandidate other) {
		return Double.compare(other.profitPerHour, profitPerHour);
	}
}
