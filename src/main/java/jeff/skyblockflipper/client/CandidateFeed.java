package jeff.skyblockflipper.client;

import jeff.skyblockflipper.core.api.MarketData;
import jeff.skyblockflipper.core.config.FlipperConfig;
import jeff.skyblockflipper.core.pricing.Fees;
import jeff.skyblockflipper.core.strategy.FlipCandidate;
import jeff.skyblockflipper.core.strategy.StrategyContext;
import jeff.skyblockflipper.core.strategy.StrategyEngine;
import jeff.skyblockflipper.core.strategy.StrategyKind;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import java.time.Duration;
import java.util.List;

/**
 * The single place market state gets turned into ranked candidates.
 *
 * <p>Commands rank on demand, because a player who typed {@code /flip} wants the book as it is
 * right now. The HUD cannot do that: it draws every frame, and ranking ~2000 order books at 60fps
 * to produce the same three lines is pure waste. So the list it draws is cached and rebuilt only
 * when {@link MarketData#bazaarRevision()} moves, which is the only thing that can change the
 * answer, plus {@link #invalidate()} for the config edits that also can.
 */
public final class CandidateFeed {
	private static final StrategyEngine ENGINE = StrategyEngine.withDefaults();

	/** Deep enough to serve any allowed {@code hudLines} without re-ranking when it changes. */
	private static final int CACHE_DEPTH = 10;

	private static volatile List<FlipCandidate> cached = List.of();
	private static long cachedRevision = -1L;

	private CandidateFeed() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(client -> refreshIfStale());
	}

	/** Everything a strategy is allowed to see, assembled from live config and live market data. */
	public static StrategyContext context() {
		// Read through config() at use time rather than caching a field, so /flip reload takes
		// effect on the next tick instead of the next game launch.
		FlipperConfig config = SkyblockFlipperClient.config();
		MarketData data = MarketDataService.data();

		return new StrategyContext(
				data.bazaar(),
				data.catalog(),
				data.underpriced(),
				data.trends(),
				new Fees(config.bazaarFlipperLevel, data.mayor().isDerpy()),
				config.bankroll,
				config.minProfitPerFlip,
				config.minConfidence,
				config.maxAdverseDrift,
				Duration.ofMinutes(config.fillHorizonMinutes));
	}

	/** Fresh ranking across every strategy, or a single one when {@code kind} is non-null. */
	public static List<FlipCandidate> rank(StrategyKind kind, int limit) {
		StrategyContext context = context();
		return kind == null ? ENGINE.rank(context, limit) : ENGINE.rank(context, kind, limit);
	}

	/** The cached top candidates the HUD draws. Never null; empty until the first book arrives. */
	public static List<FlipCandidate> top() {
		return cached;
	}

	/** Forces a rebuild on the next tick, for changes the book revision cannot see. */
	public static void invalidate() {
		cachedRevision = -1L;
	}

	private static void refreshIfStale() {
		MarketData data = MarketDataService.data();
		long revision = data.bazaarRevision();

		if (revision == cachedRevision) {
			return;
		}

		cachedRevision = revision;
		cached = data.hasBazaar() ? rank(null, CACHE_DEPTH) : List.of();
	}
}
