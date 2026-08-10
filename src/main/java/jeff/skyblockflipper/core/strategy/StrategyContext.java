package jeff.skyblockflipper.core.strategy;

import jeff.skyblockflipper.core.model.BazaarSnapshot;
import jeff.skyblockflipper.core.model.ItemCatalog;
import jeff.skyblockflipper.core.pricing.Fees;
import jeff.skyblockflipper.core.valuation.PricedListing;
import jeff.skyblockflipper.core.valuation.TrendSnapshot;

import java.time.Duration;
import java.util.List;

/**
 * Everything a strategy is allowed to look at.
 *
 * <p>Passing this in rather than letting strategies reach for globals keeps them pure functions of
 * market state, which is what makes them testable against a fixture.
 *
 * @param underpriced      live listings already matched against realized sales of the same item
 *                         configuration. Pre-computed because finding them means sweeping the whole
 *                         auction house, which is not something a strategy should do per call
 * @param trends           which way bazaar prices have been moving. A live order book says what a
 *                         thing costs but not which direction it is heading, and the two cases pay
 *                         very differently. Empty until enough history has been recorded, which
 *                         every reader must treat as "no signal" rather than as "no trend"
 * @param bankroll         coins available to deploy
 * @param minProfitPerFlip candidates below this are not worth the click
 * @param minConfidence    floor for valuation-derived candidates, which are the only ones resting
 *                         on an estimate rather than on a live order book
 * @param maxAdverseDrift  reject bazaar candidates falling faster than this fraction; 0 disables
 * @param fillHorizon      how long an order may rest before the player would rather have the coins
 *                         back. Plans are sized on what fills inside it, so it is a statement of
 *                         patience rather than a filter: it changes what ranks highest, not what is
 *                         allowed through
 * @param maxCapitalShare  the largest fraction of {@link #bankroll()} one plan may ask for. 1.0
 *                         means no cap, which is what every caller with no opinion gets
 * @param npcCapRemaining  gross coins NPCs will still pay out today, across every item. Unlike
 *                         every other limit here this one is shared and consumed: it is the day's
 *                         budget minus what has already been spent, so it shrinks as you trade
 * @param npcSessionHours  how long the player intends to keep making NPC trips. NPC plans are
 *                         bounded by carrying capacity per hour and by {@link #npcCapRemaining()}
 *                         per day, and this is what decides which of the two binds first
 */
public record StrategyContext(
		BazaarSnapshot bazaar,
		ItemCatalog catalog,
		List<PricedListing> underpriced,
		TrendSnapshot trends,
		Fees fees,
		long bankroll,
		long minProfitPerFlip,
		double minConfidence,
		double maxAdverseDrift,
		Duration fillHorizon,
		double maxCapitalShare,
		long npcCapRemaining,
		double npcSessionHours
) {
	/** What an unstated horizon means: an hour, matching {@code FlipperConfig.fillHorizonMinutes}. */
	public static final Duration DEFAULT_FILL_HORIZON = Duration.ofHours(1);

	/** No cap, which is the behaviour every caller had before the cap existed. */
	public static final double UNCAPPED = 1.0d;

	/**
	 * What an unstated NPC budget means: effectively infinite, so a caller that knows nothing about
	 * the cap gets the sizing the strategy had before the cap existed.
	 */
	public static final long NPC_CAP_UNLIMITED = Long.MAX_VALUE;

	/** What an unstated session means, matching {@code FlipperConfig.npcSessionHours}. */
	public static final double DEFAULT_NPC_SESSION_HOURS = 2.0d;

	public StrategyContext {
		underpriced = List.copyOf(underpriced);

		if (fillHorizon == null || fillHorizon.isZero() || fillHorizon.isNegative()) {
			fillHorizon = DEFAULT_FILL_HORIZON;
		}

		maxCapitalShare = maxCapitalShare <= 0.0d ? UNCAPPED : Math.min(maxCapitalShare, UNCAPPED);
		// A spent budget is zero, not negative, and a negative one would flip the sizing arithmetic
		// into producing plans rather than suppressing them.
		npcCapRemaining = Math.max(0L, npcCapRemaining);
		npcSessionHours = npcSessionHours <= 0.0d ? DEFAULT_NPC_SESSION_HOURS : npcSessionHours;
	}

	/**
	 * The most one plan may ask for, which is what a strategy should size against rather than the
	 * whole bankroll.
	 *
	 * <p>Measured need, from live play on 2026-08-04: a Recombobulator 3000 plan asked for
	 * 249,212,105 of a 250,000,000 bankroll - 99.7% of everything on one item with a 25 unit order,
	 * on a book that then failed to fill a single unit. Nothing in the ranking pushes back on that,
	 * because profit per hour rises with size and the only ceiling was affordability.
	 */
	public long maxCapitalPerFlip() {
		return Math.max(1L, Math.round(bankroll * maxCapitalShare));
	}

	/**
	 * The canonical shape before a fill horizon existed, kept so the callers that have no opinion
	 * on how long they will wait do not have to invent one.
	 */
	public StrategyContext(BazaarSnapshot bazaar, ItemCatalog catalog, List<PricedListing> underpriced,
			TrendSnapshot trends, Fees fees, long bankroll, long minProfitPerFlip,
			double minConfidence, double maxAdverseDrift) {
		this(bazaar, catalog, underpriced, trends, fees, bankroll, minProfitPerFlip, minConfidence,
				maxAdverseDrift, DEFAULT_FILL_HORIZON, UNCAPPED);
	}

	/** The shape before the NPC daily cap existed, for callers that do not track one. */
	public StrategyContext(BazaarSnapshot bazaar, ItemCatalog catalog, List<PricedListing> underpriced,
			TrendSnapshot trends, Fees fees, long bankroll, long minProfitPerFlip,
			double minConfidence, double maxAdverseDrift, Duration fillHorizon,
			double maxCapitalShare) {
		this(bazaar, catalog, underpriced, trends, fees, bankroll, minProfitPerFlip, minConfidence,
				maxAdverseDrift, fillHorizon, maxCapitalShare, NPC_CAP_UNLIMITED,
				DEFAULT_NPC_SESSION_HOURS);
	}

	/** The shape before a per-flip capital cap existed, for callers that do not want one. */
	public StrategyContext(BazaarSnapshot bazaar, ItemCatalog catalog, List<PricedListing> underpriced,
			TrendSnapshot trends, Fees fees, long bankroll, long minProfitPerFlip,
			double minConfidence, double maxAdverseDrift, Duration fillHorizon) {
		this(bazaar, catalog, underpriced, trends, fees, bankroll, minProfitPerFlip, minConfidence,
				maxAdverseDrift, fillHorizon, UNCAPPED);
	}

	/**
	 * For the bazaar-only paths and tests that have no auction scan or price history to hand.
	 *
	 * <p>Defaults to an empty {@link TrendSnapshot}, which every strategy already has to handle:
	 * a client on its first run has no history either.
	 */
	public StrategyContext(BazaarSnapshot bazaar, ItemCatalog catalog, Fees fees,
			long bankroll, long minProfitPerFlip) {
		this(bazaar, catalog, List.of(), TrendSnapshot.empty(), fees,
				bankroll, minProfitPerFlip, 0.0d, 0.0d);
	}

	/**
	 * For the auction paths, which price against realized sales rather than against a live book
	 * and so have no use for a bazaar trend.
	 *
	 * <p>This is the shape the canonical constructor had before price history existed, kept as an
	 * overload so adding a component to the record did not force every caller to state that it
	 * has no trend to offer.
	 */
	public StrategyContext(BazaarSnapshot bazaar, ItemCatalog catalog, List<PricedListing> underpriced,
			Fees fees, long bankroll, long minProfitPerFlip, double minConfidence) {
		this(bazaar, catalog, underpriced, TrendSnapshot.empty(), fees,
				bankroll, minProfitPerFlip, minConfidence, 0.0d);
	}
}
