package jeff.skyblockflipper.core.strategy;

import jeff.skyblockflipper.core.config.NpcRanking;
import jeff.skyblockflipper.core.pricing.Fees;
import jeff.skyblockflipper.core.valuation.NpcEdge;
import jeff.skyblockflipper.core.valuation.NpcEdgeSnapshot;

import java.time.Duration;
import java.util.Optional;

/**
 * Everything an NPC plan needs that is specific to NPC flipping, market and settings together.
 *
 * <p>Bundled rather than spread across {@link StrategyContext} because these five figures are only
 * ever read as a set - a plan is the intersection of "is this gap durable", "is it fat enough",
 * "how often will I be here to reprice", "how long may the coins sit" and "how much budget is
 * left" - and because {@code NpcBasket} will read exactly the same set when it allocates slots
 * across items.
 *
 * <p>Every measured figure behind the defaults is in {@code docs/npc-flipping.md}.
 *
 * @param edges          per-product history of how durably each bid has sat under its NPC price.
 *                       Empty until the tape has enough of it, which readers must treat as "not
 *                       measured" rather than as "no edge"
 * @param minMarginRatio the floor a resting plan's margin must clear, as a share of the NPC price,
 *                       and the same number as the chase stop. One threshold, one meaning: you post
 *                       only where the gap is at least this wide, and you stop repricing when it
 *                       narrows to it
 * @param checkIn        how often the player expects to come back and move their orders to the
 *                       front of the book. This is the fill horizon for an NPC buy order, not the
 *                       resting window: what an order collects is set by how long it stays at the
 *                       top, and repricing resets that clock
 * @param restingHours   how long an order may sit in total before the coins would rather be
 *                       somewhere else. There is no price risk in waiting - the NPC's price cannot
 *                       move - so this bounds how long capital is tied up, and it is what a plan's
 *                       size is measured over
 * @param maxOrderSlots  the most bazaar order slots NPC plans may use, or {@link #ALL_ORDER_SLOTS}
 *                       for every slot the account has. A coop shares one bazaar
 * @param capRemaining   gross coins NPCs will still pay out today, across every item. Unlike every
 *                       other limit here this one is shared and consumed: it is the day's budget
 *                       minus what has already been spent, so it shrinks as you trade
 * @param maxOrdersPerItem the most bazaar orders one item's line may occupy, or
 *                       {@link #UNLIMITED_ORDERS_PER_ITEM}. Not a setting: it exists so
 *                       {@code NpcSettingsSweepTest} can price a cap against the same allocator the
 *                       mod plans with, which is the only way to know what capping one costs
 * @param driftPremium   how much of the resting window's measured upward drift to pay into the
 *                       posted price rather than into repricing, as a multiple of it. Zero is the
 *                       shipped behaviour: post one increment above the book and chase it. See
 *                       {@code FlipperConfig.npcDriftPremium} for what the alternative measured
 * @param ranking        which resource the basket ranks candidates against when it has to choose,
 *                       from {@code FlipperConfig.npcRankingKey}
 */
public record NpcContext(
		NpcEdgeSnapshot edges,
		double minMarginRatio,
		Duration checkIn,
		double restingHours,
		int maxOrderSlots,
		long capRemaining,
		int maxOrdersPerItem,
		double driftPremium,
		NpcRanking ranking
) {
	/**
	 * What an unstated NPC budget means: effectively infinite, so a caller that knows nothing about
	 * the cap gets the sizing the strategy had before the cap existed.
	 */
	public static final long CAP_UNLIMITED = Long.MAX_VALUE;

	/** Matching {@code FlipperConfig.npcMinMarginRatio}: the peak of a measured floor sweep. */
	public static final double DEFAULT_MIN_MARGIN_RATIO = 0.15d;

	/** Matching {@code FlipperConfig.npcCheckInMinutes}. */
	public static final Duration DEFAULT_CHECK_IN = Duration.ofMinutes(30);

	/** Matching {@code FlipperConfig.npcRestingHours}: one cycle. */
	public static final double DEFAULT_RESTING_HOURS = 8.0d;

	/** What {@code maxOrderSlots} of zero means, matching {@code FlipperConfig.npcMaxOrderSlots}. */
	public static final int ALL_ORDER_SLOTS = 0;

	/**
	 * What {@code maxOrdersPerItem} of zero means: an item may take every slot it is worth, which is
	 * how the basket has always sized a line and what the 59.7M cycle was measured under.
	 */
	public static final int UNLIMITED_ORDERS_PER_ITEM = 0;

	private static final double MILLIS_PER_HOUR = 3_600_000.0d;

	public NpcContext {
		edges = edges == null ? NpcEdgeSnapshot.empty() : edges;
		// A negative or absurd floor would invert the filter rather than loosen it. FlipperConfig
		// clamps harder than this; the bound here is what makes a hand-built context safe.
		minMarginRatio = Math.clamp(minMarginRatio, 0.0d, 1.0d);
		checkIn = checkIn == null || checkIn.isZero() || checkIn.isNegative()
				? DEFAULT_CHECK_IN
				: checkIn;
		restingHours = restingHours <= 0.0d ? DEFAULT_RESTING_HOURS : restingHours;
		maxOrderSlots = Math.max(ALL_ORDER_SLOTS, maxOrderSlots);
		// A spent budget is zero, not negative, and a negative one would flip the sizing arithmetic
		// into producing plans rather than suppressing them. Unlimited survives the clamp.
		capRemaining = Math.max(0L, capRemaining);
		maxOrdersPerItem = Math.max(UNLIMITED_ORDERS_PER_ITEM, maxOrdersPerItem);
		// A negative premium would post under the book, which is not a cheaper order but no order.
		driftPremium = Math.max(0.0d, driftPremium);
		ranking = ranking == null ? NpcRanking.LOAD : ranking;
	}

	/**
	 * The shape before the premium and the ranking key were settings, which is what every test and
	 * every sweep that has no opinion on either is written against.
	 */
	public NpcContext(NpcEdgeSnapshot edges, double minMarginRatio, Duration checkIn,
			double restingHours, int maxOrderSlots, long capRemaining, int maxOrdersPerItem) {
		this(edges, minMarginRatio, checkIn, restingHours, maxOrderSlots, capRemaining,
				maxOrdersPerItem, 0.0d, NpcRanking.LOAD);
	}

	/**
	 * The six-argument form, which means "no per-item cap".
	 *
	 * <p>Every caller in the mod is this one: the cap is a sweep dimension, not a setting, so the
	 * shipped behaviour is the one that has always been measured. Only the sweep names the seventh.
	 */
	public NpcContext(NpcEdgeSnapshot edges, double minMarginRatio, Duration checkIn,
			double restingHours, int maxOrderSlots, long capRemaining) {
		this(edges, minMarginRatio, checkIn, restingHours, maxOrderSlots, capRemaining,
				UNLIMITED_ORDERS_PER_ITEM);
	}

	/** Shipped defaults with no measured history and no budget to respect, for tests and callers
	 * that track neither. */
	public static NpcContext unlimited() {
		return new NpcContext(NpcEdgeSnapshot.empty(), DEFAULT_MIN_MARGIN_RATIO, DEFAULT_CHECK_IN,
				DEFAULT_RESTING_HOURS, ALL_ORDER_SLOTS, CAP_UNLIMITED);
	}

	/** This product's measured edge, present only when enough tape backs it. */
	public Optional<NpcEdge> edgeFor(String productId) {
		return edges.edgeFor(productId);
	}

	/** {@link #restingHours()} as a duration, which is what {@link NpcEdge#chaseCostRatio} takes. */
	public Duration restingWindow() {
		return Duration.ofMillis(Math.round(restingHours * MILLIS_PER_HOUR));
	}

	/** Whether orders are posted above the book rather than one increment inside it. */
	public boolean paysThePremium() {
		return driftPremium > 0.0d;
	}

	/**
	 * How long a plan is actually left alone for, which is what its fill is worth measuring over.
	 *
	 * <p>Without a premium that is the check-in interval: the order is moved back to the front every
	 * time the player returns, so what it collects is the interval's average rate. With one, the
	 * whole point is that the player does not return, so the horizon is the resting window - and the
	 * premium is what stops the longer horizon simply reporting a worse fill, because it delays the
	 * displacement the horizon is being averaged over. See
	 * {@code FillModel.estimate(..., displacementDelayHours)}.
	 */
	public Duration fillHorizon() {
		return paysThePremium() ? restingWindow() : checkIn;
	}

	/**
	 * How long the book must climb before anything can outbid an order posted at the premium.
	 *
	 * <p>The premium is {@code driftPremium} multiples of what the book drifts upward over a whole
	 * resting window, so at the drift rate it measured, climbing it takes exactly that fraction of
	 * the window. It cancels down to a number of hours with no drift rate in it, which is what makes
	 * it safe to hand a model that has its own opinion about rates.
	 */
	public double displacementDelayHours() {
		return driftPremium * restingHours;
	}

	/**
	 * Order slots NPC plans may actually use.
	 *
	 * <p>The account's real limit always wins: asking for more slots than
	 * {@link Fees#bazaarOrderSlots()} in settings does not create them.
	 */
	public int orderSlots(Fees fees) {
		int available = fees.bazaarOrderSlots();

		return maxOrderSlots == ALL_ORDER_SLOTS ? available : Math.min(maxOrderSlots, available);
	}

	/**
	 * Order slots one item's line may take, out of the {@code slotsLeft} the basket still has.
	 *
	 * <p>Uncapped means "all of them", which is the sizing the basket has always used: an item is
	 * taken at the largest size it is worth on its own and the next one gets what is left.
	 */
	public int ordersForItem(int slotsLeft) {
		return ordersForItem(slotsLeft, 0);
	}

	/**
	 * The same allowance for an item that already has orders resting, which they count against.
	 *
	 * <p>A cap of two means two orders on the item, not two more every time the basket is re-planned
	 * around a position it is topping up.
	 */
	public int ordersForItem(int slotsLeft, int alreadyResting) {
		if (maxOrdersPerItem == UNLIMITED_ORDERS_PER_ITEM) {
			return slotsLeft;
		}

		return Math.min(Math.max(0, maxOrdersPerItem - Math.max(0, alreadyResting)), slotsLeft);
	}

	/**
	 * The highest price worth repricing a buy order to, given what the NPC pays.
	 *
	 * <p>The stop that makes chasing safe: with the exit price fixed, repricing upward spends a
	 * known amount of a known margin, and this is where it stops being worth spending. Nothing
	 * about it is a stop-loss - an order that never fills is cancelled, not lost.
	 */
	public double maxChasePrice(double npcPrice) {
		return npcPrice * (1.0d - minMarginRatio);
	}

	/** False when the caller stated no budget, so nothing about it is worth reporting. */
	public boolean capKnown() {
		return capRemaining != CAP_UNLIMITED;
	}

	/**
	 * Units of an item at {@code npcPrice} the remaining budget can still pay out for.
	 *
	 * <p>Unlimited stays unlimited rather than becoming an enormous but finite unit count, so a
	 * caller with no cap to report gets the sizing this had before the cap existed.
	 */
	public long capUnits(double npcPrice) {
		if (!capKnown()) {
			return Long.MAX_VALUE;
		}

		return npcPrice <= 0.0d ? 0L : (long) (capRemaining / npcPrice);
	}
}
