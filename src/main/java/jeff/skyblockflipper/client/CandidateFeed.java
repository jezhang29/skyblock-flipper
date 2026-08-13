package jeff.skyblockflipper.client;

import jeff.skyblockflipper.client.track.TrackerService;
import jeff.skyblockflipper.core.api.MarketData;
import jeff.skyblockflipper.core.config.FlipperConfig;
import jeff.skyblockflipper.core.ledger.PlannedQuotes;
import jeff.skyblockflipper.core.ledger.Quote;
import jeff.skyblockflipper.core.pricing.Fees;
import jeff.skyblockflipper.core.strategy.FlipCandidate;
import jeff.skyblockflipper.core.strategy.NpcBasket;
import jeff.skyblockflipper.core.strategy.NpcContext;
import jeff.skyblockflipper.core.strategy.NpcFlipStrategy;
import jeff.skyblockflipper.core.strategy.NpcRound;
import jeff.skyblockflipper.core.strategy.NpcWorklist;
import jeff.skyblockflipper.core.strategy.StrategyContext;
import jeff.skyblockflipper.core.strategy.StrategyEngine;
import jeff.skyblockflipper.core.strategy.StrategyKind;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import java.time.Duration;
import java.util.List;
import java.util.Set;

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

	/**
	 * What the mod has advised recently, so the ledger can recognise a fill against a basket line.
	 *
	 * <p>Good for the resting window, which is how long the plan behind an order is the plan the
	 * player is still working. Read through {@code config()} at use time, so {@code /flip reload}
	 * changes it.
	 */
	private static final PlannedQuotes QUOTES = new PlannedQuotes(() -> Duration.ofMinutes(
			Math.round(SkyblockFlipperClient.config().npcRestingHours * 60.0d)));

	private static volatile List<FlipCandidate> cached = List.of();
	private static long cachedRevision = -1L;

	private static NpcWorklist.Worklist worklist;
	private static long worklistRevision = -1L;

	/**
	 * The tracker state the worklist was built from.
	 *
	 * <p>Held beside the book revision because placing or cancelling an order changes what there is
	 * to do without changing the book: without this, a player who has just placed every line watches
	 * the panel go on telling them to place it for the twenty seconds until the next poll.
	 */
	private static long worklistOrders = -1L;

	/**
	 * The round the worklist was built from, compared by identity.
	 *
	 * <p>A round supersedes on its own clock, and neither the book revision nor the tracker moves
	 * when it does. Without this the panel would go on showing prices frozen an interval ago until
	 * the next poll happened to change something else.
	 */
	private static NpcRound worklistRound;

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
				Duration.ofMinutes(config.fillHorizonMinutes),
				config.maxCapitalShare,
				new NpcContext(
						data.npcEdges(),
						config.npcMinMarginRatio,
						Duration.ofMinutes(config.npcCheckInMinutes),
						config.npcRestingHours,
						config.npcMaxOrderSlots,
						npcCapRemaining(config)));
	}

	/**
	 * The day's NPC coin budget, less what the ledger says has already been collected from NPCs.
	 *
	 * <p>Derived rather than stored: the game exposes no counter for this, and a saved number would
	 * go stale the moment a trade was closed outside the mod. What the ledger cannot see is a flip
	 * you never recorded, which reads as budget still available.
	 */
	public static long npcCapRemaining(FlipperConfig config) {
		long spent = LedgerService.ledger()
				.npcCoinsReceivedSince(NpcFlipStrategy.npcDayStart(System.currentTimeMillis()));

		return Math.max(0L, config.npcDailyCapCoins - spent);
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

	/**
	 * Everything to do at the bazaar right now, assembled at most once per book revision.
	 *
	 * <p>Shared by the Basket tab, the bazaar overlay, the reminder and the chat commands because
	 * they must not be able to disagree: a panel telling you to post 84999.9 while the screen behind
	 * it says 85000.1 is worse than either number on its own. Rebuilt on the same rule as the ranked
	 * list, and lazily rather than every tick, so a player who never asks for one never pays for it.
	 *
	 * <p>The resting orders come from {@code TrackerService}, which knows them only while automatic
	 * tracking is on and only after the orders menu has been drawn once. With it off this is a plain
	 * basket against an empty account, which is the honest answer: nothing has told the mod what is
	 * on the book.
	 *
	 * <p>Call from the client thread only. Every caller is a render or tick path, which is why this
	 * needs no lock.
	 */
	public static NpcWorklist.Worklist worklist() {
		long revision = MarketDataService.data().bazaarRevision();
		long orders = TrackerService.orderRevision();
		NpcRound round = NpcRoundService.current();

		if (worklist == null || revision != worklistRevision || orders != worklistOrders
				|| round != worklistRound) {
			worklistRevision = revision;
			worklistOrders = orders;
			worklistRound = round;
			// What has been bought out since the round opened, which is the only thing that tells a
			// row with nothing resting on it apart from a reprice caught between its cancel and its
			// re-post. Without it a flip that filled and was claimed keeps asking to be repriced,
			// and keeps its slot and its coins reserved, until the interval runs out.
			worklist = NpcWorklist.of(
					TrackerService.enabled() ? TrackerService.restingBuyOrders() : List.of(),
					context(), System.currentTimeMillis(), round,
					TrackerService.enabled() && round != null
							? TrackerService.filledSince(round.openedAt())
							: Set.of());

			rememberQuotes(worklist);
		}

		return worklist;
	}

	/**
	 * Records what this basket promised, so a fill hours from now can be held to it.
	 *
	 * <p>Here rather than at the screens because this is the one place a basket is built, and the
	 * panel, the Basket tab, the reminder and {@code /flip npc plan} are four views of it. Recording
	 * at any of them would mean a trade counted as quoted or not depending on which window the
	 * player happened to have open.
	 *
	 * <p>It has to be remembered rather than looked up later: {@code NpcBasket} drops an item the
	 * moment an order rests on it, so by the time the buy claims, the plan that asked for it is gone
	 * from the basket exactly because it was followed.
	 */
	private static void rememberQuotes(NpcWorklist.Worklist built) {
		long now = System.currentTimeMillis();

		QUOTES.prune(now);

		for (NpcBasket.Line line : built.basket().lines()) {
			QUOTES.quoted(new Quote(line.plan().itemId(), line.plan().displayName(),
					StrategyKind.NPC_FLIP, line.plan().unitCost(), line.plan().unitNetProfit(),
					line.units(), line.capital()), now);
		}
	}

	/** What the mod has recently advised, for the ledger to recognise a fill against. */
	public static PlannedQuotes quotes() {
		return QUOTES;
	}

	/** The new orders out of {@link #worklist()}, already sized around what is resting. */
	public static NpcBasket.Basket basket() {
		return worklist().basket();
	}

	/** Forces a rebuild on the next tick, for changes the book revision cannot see. */
	public static void invalidate() {
		cachedRevision = -1L;
		worklistRevision = -1L;
		worklist = null;
		// The open round froze the check-in interval along with its prices, so an edit to it would
		// otherwise not be felt until the round opened under the old one had run out.
		NpcRoundService.clear();
	}

	private static void refreshIfStale() {
		MarketData data = MarketDataService.data();
		long revision = data.bazaarRevision();

		if (revision == cachedRevision) {
			return;
		}

		cachedRevision = revision;
		// The configured filter, so the HUD shows the market the player is actually working. A
		// change to it does not move the book revision, which is what invalidate() is for.
		cached = data.hasBazaar()
				? rank(SkyblockFlipperClient.config().filteredKind(), CACHE_DEPTH)
				: List.of();
	}
}
