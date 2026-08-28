package jeff.skyblockflipper.client;

import jeff.skyblockflipper.client.track.TrackerService;
import jeff.skyblockflipper.core.api.MarketData;
import jeff.skyblockflipper.core.config.FlipperConfig;
import jeff.skyblockflipper.core.ledger.PlannedQuotes;
import jeff.skyblockflipper.core.model.Stacking;
import jeff.skyblockflipper.core.ledger.Quote;
import jeff.skyblockflipper.core.pricing.Fees;
import jeff.skyblockflipper.core.strategy.BazaarCombineStrategy;
import jeff.skyblockflipper.core.strategy.BazaarSpreadStrategy;
import jeff.skyblockflipper.core.strategy.CombineContext;
import jeff.skyblockflipper.core.strategy.FusionContext;
import jeff.skyblockflipper.core.strategy.CombineJob;
import jeff.skyblockflipper.core.strategy.CraftContext;
import jeff.skyblockflipper.core.strategy.CraftFlipStrategy;
import jeff.skyblockflipper.core.strategy.CraftJob;
import jeff.skyblockflipper.core.strategy.FlipCandidate;
import jeff.skyblockflipper.core.strategy.FusionFlipStrategy;
import jeff.skyblockflipper.core.strategy.NpcBasket;
import jeff.skyblockflipper.core.strategy.NpcContext;
import jeff.skyblockflipper.core.strategy.NpcFlipStrategy;
import jeff.skyblockflipper.core.strategy.NpcRound;
import jeff.skyblockflipper.core.strategy.NpcWorklist;
import jeff.skyblockflipper.core.strategy.StrategyContext;
import jeff.skyblockflipper.core.strategy.StrategyEngine;
import jeff.skyblockflipper.core.strategy.StrategyKind;
import jeff.skyblockflipper.core.strategy.WorkedJob;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

	/**
	 * The craft planner, kept beside the engine because the overlay needs more from it than a
	 * ranking: it re-plans each worked recipe every poll. Free to hold a second instance -
	 * {@code RecipeBook.bundled()} is parsed once and cached.
	 */
	private static final CraftFlipStrategy CRAFT = new CraftFlipStrategy();

	/**
	 * The combine planner, kept beside the engine for the same reason as {@link #CRAFT}: the overlay
	 * re-plans one chosen enchant every poll. The allowlist is parsed once, so a second instance is
	 * free.
	 */
	private static final BazaarCombineStrategy COMBINE = new BazaarCombineStrategy();

	/**
	 * The spread planner, kept beside the engine so a worked spread can be re-quoted on its own.
	 * The ranking scans every product; a followed flip needs one, every poll, while the player is
	 * typing its prices.
	 */
	private static final BazaarSpreadStrategy SPREAD = new BazaarSpreadStrategy();

	/**
	 * The fusion planner, kept beside the engine for the same reason as {@link #COMBINE}: the overlay
	 * re-plans one chosen output shard every poll. The graph is parsed once, so a second instance is
	 * free.
	 */
	private static final FusionFlipStrategy FUSION = new FusionFlipStrategy();

	/**
	 * The strategies whose plans are a list of clicks at the bazaar, which is what a worked job is.
	 *
	 * <p>An auction snipe is a search and a bid on a different screen entirely, and an NPC basket
	 * line is already in the worklist, so neither is followable here.
	 */
	private static final Set<StrategyKind> FOLLOWABLE = EnumSet.of(
			StrategyKind.CRAFT, StrategyKind.COMBINE, StrategyKind.FUSION, StrategyKind.BAZAAR_SPREAD);

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

	/**
	 * The flips being worked, item id to the strategy that planned it, in the order they were
	 * picked. Insertion-ordered because that order is the answer to "what am I in the middle of",
	 * and re-sorting it every poll would move the row under a player halfway down the panel.
	 */
	private static final Map<String, StrategyKind> FOLLOWED = new LinkedHashMap<>();

	/**
	 * The name each worked id had when it was picked, so a job whose plan has stopped clearing can
	 * still be named. Without it a stalled flip reads as a raw item id, which is the one thing the
	 * player is never shown anywhere else.
	 */
	private static final Map<String, String> FOLLOWED_NAMES = new LinkedHashMap<>();

	private static volatile List<WorkedJob> jobs = List.of();
	private static long jobsRevision = -1L;

	/**
	 * Bumped whenever the worked list changes, so a pick or a stop rebuilds the jobs without
	 * waiting for the book to move. The book revision alone cannot see it.
	 */
	private static int jobsGeneration;
	private static int builtGeneration = -1;

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
						npcCapRemaining(config),
						// Not a setting: the per-item order cap exists so the sweep can price one
						// against the same allocator the mod plans with.
						NpcContext.UNLIMITED_ORDERS_PER_ITEM,
						config.npcRanking()),
				new CraftContext(config.craftFlipsEnabled, config.craftMaxOrderSlots),
				new CombineContext(config.combineFlipsEnabled),
				new FusionContext(config.fusionFlipsEnabled, config.fusionCrocodileLevel));
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
			// The two readings of an empty book position that are not a reprice in progress: bought
			// out, and pulled off by you. Both looked identical to a row caught between its cancel
			// and its re-post, so a flip that filled and was claimed - and a book the player cleared
			// by hand - kept asking to be repriced, with the slot and the coins reserved out of the
			// basket, until the interval ran out.
			worklist = NpcWorklist.of(
					TrackerService.enabled() ? TrackerService.restingBuyOrders() : List.of(),
					context(), System.currentTimeMillis(), round,
					TrackerService.enabled() && round != null
							? TrackerService.filledSince(round.openedAt())
							: Set.of(),
					TrackerService.enabled() && round != null
							? TrackerService.cancelledSince(round.openedAt())
							: Map.of(),
					FlipIntentsService.foreign(System.currentTimeMillis()));

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

			// So an item now in an NPC basket overrides a stale craft or combine intent on the same id:
			// last write wins, and the fresh NPC_FLIP is what releases it back to the NPC side.
			FlipIntentsService.record(line.plan().itemId(), StrategyKind.NPC_FLIP, now);
		}
	}

	/**
	 * Marks the ids a followed craft or combine rests buy orders on, so the NPC side leaves them
	 * alone. Recorded while the job is followed and persisted, so an order left resting after the
	 * player stops following - or restarts - is still recognised as the other strategy's.
	 */
	private static void rememberForeign(List<String> itemIds, StrategyKind kind) {
		long now = System.currentTimeMillis();

		for (String itemId : itemIds) {
			FlipIntentsService.record(itemId, kind, now);
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

	/**
	 * Work this flip: the bazaar panel and the Jobs tab carry its steps until it is stopped.
	 *
	 * <p><b>Several at once, in the order picked.</b> This used to hold one craft or one combine,
	 * and picking either dropped the other - as did picking a bazaar row merely to read it, which
	 * silently ended a craft the player was halfway through buying materials for. Working two flips
	 * at a time is the normal case: a craft's materials rest for an hour while a combine's source
	 * books fill, and neither is a reason to stop seeing the other.
	 *
	 * <p>Held as ids rather than as plans, because the plans go stale: the prices in them are what
	 * the player is about to type, and the book moves every twenty seconds. So the id is what
	 * persists across a poll and the job is rebuilt beneath it, exactly as the NPC worklist is.
	 *
	 * @return false for a strategy with no bazaar steps to follow - an auction snipe or an NPC
	 *         basket line, both of which have their own view
	 */
	public static boolean work(StrategyKind kind, String itemId, String displayName) {
		if (itemId == null || !FOLLOWABLE.contains(kind)) {
			return false;
		}

		FOLLOWED.put(itemId, kind);
		FOLLOWED_NAMES.put(itemId, displayName == null ? itemId : displayName);
		jobsGeneration++;
		return true;
	}

	/** Whether this item is one of the flips being worked. */
	public static boolean working(String itemId) {
		return itemId != null && FOLLOWED.containsKey(itemId);
	}

	/** The strategy that put this item on the worked list, or null when it is not on it. */
	public static StrategyKind workedAs(String itemId) {
		return itemId == null ? null : FOLLOWED.get(itemId);
	}

	/**
	 * Stop working one flip.
	 *
	 * @return false when it was not being worked
	 */
	public static boolean stopWork(String itemId) {
		if (itemId == null || FOLLOWED.remove(itemId) == null) {
			return false;
		}

		FOLLOWED_NAMES.remove(itemId);
		jobsGeneration++;
		return true;
	}

	/**
	 * Stop working every flip of one strategy.
	 *
	 * @return how many were dropped
	 */
	public static int stopWork(StrategyKind kind) {
		int dropped = 0;

		for (String itemId : List.copyOf(FOLLOWED.keySet())) {
			if (FOLLOWED.get(itemId) == kind) {
				FOLLOWED.remove(itemId);
				FOLLOWED_NAMES.remove(itemId);
				dropped++;
			}
		}

		if (dropped > 0) {
			jobsGeneration++;
		}

		return dropped;
	}

	/**
	 * Stop working everything.
	 *
	 * @return how many were dropped
	 */
	public static int stopWork() {
		int dropped = FOLLOWED.size();

		FOLLOWED.clear();
		FOLLOWED_NAMES.clear();

		if (dropped > 0) {
			jobsGeneration++;
		}

		return dropped;
	}

	/** The ids being worked, in the order they were picked. */
	public static List<String> workedIds() {
		return List.copyOf(FOLLOWED.keySet());
	}

	/**
	 * Every worked flip re-priced against the current book, in the order picked.
	 *
	 * <p>Call from the client thread only, like {@link #worklist()}: every caller is a render or a
	 * tick path, which is why this needs no lock. Rebuilt when the book moves or the list changes,
	 * not every frame - the panel draws this sixty times a second and re-planning a recipe that
	 * often is the waste this class exists to avoid.
	 */
	public static List<WorkedJob> jobs() {
		if (FOLLOWED.isEmpty()) {
			jobs = List.of();
			return jobs;
		}

		long revision = MarketDataService.data().bazaarRevision();

		if (revision == jobsRevision && jobsGeneration == builtGeneration) {
			return jobs;
		}

		jobsRevision = revision;
		builtGeneration = jobsGeneration;

		StrategyContext context = context();
		List<WorkedJob> built = new ArrayList<>();

		for (Map.Entry<String, StrategyKind> followed : FOLLOWED.entrySet()) {
			String itemId = followed.getKey();
			String name = FOLLOWED_NAMES.get(itemId);

			WorkedJob job = switch (followed.getValue()) {
				case CRAFT -> WorkedJob.ofCraft(itemId, name, CRAFT.job(itemId, context).orElse(null));
				case COMBINE -> WorkedJob.ofCombine(itemId, name,
						COMBINE.job(itemId, context).orElse(null));
				case FUSION -> WorkedJob.ofFusion(itemId, name,
						FUSION.job(itemId, context).orElse(null));
				case BAZAAR_SPREAD -> spreadJob(itemId, name, context);
				default -> null;
			};

			if (job != null) {
				built.add(job);
				// So the NPC side does not reprice or cancel an order this flip is resting on.
				rememberForeign(restingIdsOf(job), followed.getValue());
			}
		}

		jobs = List.copyOf(built);
		return jobs;
	}

	private static WorkedJob spreadJob(String itemId, String name, StrategyContext context) {
		FlipCandidate candidate = SPREAD.job(itemId, context).orElse(null);
		long unitsPerOrder = Stacking.unitsPerOrder(context.catalog().get(itemId).orElse(null),
				context.bazaar().product(itemId).orElse(null));

		return WorkedJob.ofSpread(itemId, name, candidate, unitsPerOrder);
	}

	/** The ids a job rests buy orders on, which is what the NPC side has to leave alone. */
	private static List<String> restingIdsOf(WorkedJob job) {
		List<String> ids = new ArrayList<>();

		for (WorkedJob.Step step : job.steps()) {
			if (step.stage() == WorkedJob.Stage.BUY_ORDER) {
				ids.add(step.itemId());
			}
		}

		return ids;
	}

	/** Forces a rebuild on the next tick, for changes the book revision cannot see. */
	public static void invalidate() {
		cachedRevision = -1L;
		worklistRevision = -1L;
		worklist = null;
		jobsRevision = -1L;
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
