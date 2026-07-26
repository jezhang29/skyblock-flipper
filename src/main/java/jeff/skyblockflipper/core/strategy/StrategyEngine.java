package jeff.skyblockflipper.core.strategy;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs every strategy and merges the results onto one ranking.
 *
 * <p>Comparing a bazaar spread against an NPC flip only works because both report net profit per
 * hour after fees, which is the reason {@link FlipCandidate} is a shared shape rather than each
 * strategy having its own.
 */
public final class StrategyEngine {
	private final List<FlipStrategy> strategies;

	public StrategyEngine(List<FlipStrategy> strategies) {
		this.strategies = List.copyOf(strategies);
	}

	public static StrategyEngine withDefaults() {
		return new StrategyEngine(List.of(new BazaarSpreadStrategy(), new NpcFlipStrategy()));
	}

	/** Best candidates across all strategies, capped at {@code limit}. */
	public List<FlipCandidate> rank(StrategyContext context, int limit) {
		List<FlipCandidate> all = new ArrayList<>();

		for (FlipStrategy strategy : strategies) {
			all.addAll(strategy.findCandidates(context));
		}

		all.sort(null);

		return all.size() <= limit ? List.copyOf(all) : List.copyOf(all.subList(0, limit));
	}

	/** Candidates from a single strategy, for the per-strategy views. */
	public List<FlipCandidate> rank(StrategyContext context, StrategyKind kind, int limit) {
		for (FlipStrategy strategy : strategies) {
			if (strategy.kind() == kind) {
				List<FlipCandidate> found = strategy.findCandidates(context);
				return found.size() <= limit ? found : List.copyOf(found.subList(0, limit));
			}
		}

		return List.of();
	}
}
