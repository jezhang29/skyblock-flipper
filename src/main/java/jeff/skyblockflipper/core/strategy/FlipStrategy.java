package jeff.skyblockflipper.core.strategy;

import java.util.List;

/** Produces ranked opportunities from a market snapshot. */
public interface FlipStrategy {
	StrategyKind kind();

	/** Candidates, best first. Returns empty rather than throwing when data is missing. */
	List<FlipCandidate> findCandidates(StrategyContext context);
}
