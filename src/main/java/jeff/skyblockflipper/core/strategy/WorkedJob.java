package jeff.skyblockflipper.core.strategy;

import jeff.skyblockflipper.core.model.Stacking;
import jeff.skyblockflipper.core.track.TradeEvent;
import jeff.skyblockflipper.core.track.TrackedOrder;

import java.util.ArrayList;
import java.util.List;

/**
 * One flip the player said they are working, as a list of clicks with progress against each.
 *
 * <p>A craft, a combine and a bazaar spread are three different plans that read the same way at the
 * menu: rest an order, wait, do something to what arrives, offer the result. Each one already has
 * its own job type or its own steps, and every renderer that wanted to show more than one of them
 * at a time had to know all three. So they collapse to this, and the panel, the Jobs tab and
 * {@code /flip jobs} render one shape.
 *
 * <p><b>Several are worked at once.</b> The overlay used to follow exactly one transformation, and
 * picking a second dropped the first - including picking a bazaar row merely to read it, which
 * silently ended a craft the player was halfway through buying materials for. A list is what the
 * player actually has open, so a list is what is kept.
 *
 * <p><b>Rebuilt from the live book, never frozen.</b> Like {@link CraftJob}, the identity is the
 * item id and the numbers are re-quoted under it every poll.
 *
 * @param kind        which strategy planned it, for the heading and for {@code FlipIntents}
 * @param itemId      the thing being made or flipped - the id the job is followed by
 * @param displayName its name, resolved once so no renderer has to
 * @param steps       the clicks, in the order to make them
 * @param capital     coins the whole job ties up, so a player working four of them can see what
 *                    they have committed without adding it up themselves
 * @param netProfit   coins the whole job makes once it has run, net of fees
 * @param note        why there are no steps, empty when there are
 */
public record WorkedJob(StrategyKind kind, String itemId, String displayName, List<Step> steps,
		long capital, double netProfit, String note) {
	public WorkedJob {
		steps = List.copyOf(steps);
	}

	/** What a step asks for, and which side of the book - if any - would show it happening. */
	public enum Stage {
		/** Rest a buy order and wait for someone to sell into it. */
		BUY_ORDER("Buy Order", true, TradeEvent.Side.BUY),
		/** Take the ask now. No price to type, so none is shown. */
		INSTANT_BUY("Buy Instantly", false, TradeEvent.Side.BUY),
		/** Leave the bazaar: the anvil, the crafting table. Nothing on the book records it. */
		TRANSFORM("Craft", false, TradeEvent.Side.NONE),
		/** Rest the finished item one increment under the cheapest offer. */
		SELL_OFFER("Sell Offer", true, TradeEvent.Side.SELL);

		private final String label;
		private final boolean priced;
		private final TradeEvent.Side side;

		Stage(String label, boolean priced, TradeEvent.Side side) {
			this.label = label;
			this.priced = priced;
			this.side = side;
		}

		public String label() {
			return label;
		}

		/** Whether this step has a price the player types, as opposed to a button they press. */
		public boolean priced() {
			return priced;
		}

		/** The order side that shows this step happening, or {@code NONE} when nothing does. */
		public TradeEvent.Side side() {
			return side;
		}
	}

	/**
	 * @param label      the verb, which a source job may word differently from {@link Stage#label()}
	 *                   - a combine says "Combine" where a craft says "Craft"
	 * @param price      coins per unit to type, or 0 where {@link Stage#priced()} is false
	 * @param orderSplit how {@code units} divides into orders the bazaar will take
	 */
	public record Step(Stage stage, String label, String itemId, String displayName, double price,
			long units, String orderSplit) {
	}

	/** How far along a step is, as far as the order tracker can tell. */
	public enum State {
		/** Nothing on the book for it yet. */
		TODO,
		/** An order is resting, and some of it may have filled. */
		RESTING,
		/** Every unit filled and was collected. */
		DONE,
		/** Nothing can say: the tracker is off, or the step happens away from the bazaar. */
		UNTRACKED
	}

	/**
	 * A step's state with the units behind it.
	 *
	 * @param filled units filled across every order the tracker matched to this step
	 * @param total  units those orders were placed for, which is what {@code filled} is out of
	 */
	public record Progress(State state, long filled, long total) {
		public static final Progress UNTRACKED = new Progress(State.UNTRACKED, 0L, 0L);

		/** Three characters, so a column of them lines up whatever the state. */
		public String badge() {
			return switch (state) {
				case TODO -> "[ ]";
				case RESTING -> "[~]";
				case DONE -> "[x]";
				case UNTRACKED -> "   ";
			};
		}

		/** The fill fraction, or empty when there is nothing to say beyond the badge. */
		public String describe() {
			return state == State.RESTING && total > 0L ? filled + "/" + total : "";
		}
	}

	/** Steps whose progress says they are finished, over the steps the tracker can see at all. */
	public int doneCount(List<TrackedOrder> orders) {
		int done = 0;

		for (Step step : steps) {
			if (progressOf(step, orders).state() == State.DONE) {
				done++;
			}
		}

		return done;
	}

	/** How many steps the tracker can say anything about, which is what {@link #doneCount} is of. */
	public int trackableCount() {
		int trackable = 0;

		for (Step step : steps) {
			if (step.stage().side() != TradeEvent.Side.NONE) {
				trackable++;
			}
		}

		return trackable;
	}

	/**
	 * What the tracked orders say about one step.
	 *
	 * <p>Matched on item id and side rather than on the order itself, because the mod never sees an
	 * order placed - it sees the orders menu afterwards - and a step sized at 111,507 units is
	 * several orders by the time it reaches the book. Summing them is the only reading that
	 * survives {@link Stacking#orderSplit}.
	 *
	 * <p>A resting order wins over a finished one on the same item. The player who has already
	 * flipped this once today and is doing it again needs to see the live order, not the old one.
	 *
	 * @param orders every order the tracker holds, resting and finished alike
	 */
	public Progress progressOf(Step step, List<TrackedOrder> orders) {
		TradeEvent.Side side = step.stage().side();

		if (side == TradeEvent.Side.NONE || orders == null || orders.isEmpty()) {
			return Progress.UNTRACKED;
		}

		long restingFilled = 0L;
		long restingTotal = 0L;
		long doneFilled = 0L;
		long doneTotal = 0L;

		for (TrackedOrder order : orders) {
			if (order.side() != side || !step.itemId().equals(order.itemId())) {
				continue;
			}

			if (order.isResting()) {
				restingFilled += order.filled();
				restingTotal += order.total();
			} else if (order.finishedByFilling()) {
				doneFilled += order.filled();
				doneTotal += order.total();
			}
		}

		if (restingTotal > 0L) {
			return new Progress(State.RESTING, restingFilled, restingTotal);
		}

		if (doneTotal > 0L) {
			return new Progress(State.DONE, doneFilled, doneTotal);
		}

		return new Progress(State.TODO, 0L, 0L);
	}

	/**
	 * A craft plan as a worked job. A null plan is a real state and not a missing one: the flip
	 * stopped clearing its gates while it was being worked, and the note says so rather than the
	 * job vanishing out from under a player who is halfway through buying its materials.
	 */
	public static WorkedJob ofCraft(String outputId, String displayName, CraftJob job) {
		if (job == null) {
			return stalled(StrategyKind.CRAFT, outputId, displayName);
		}

		List<Step> steps = new ArrayList<>();

		for (CraftJob.Row row : job.rows()) {
			steps.add(new Step(stageOf(row.action()), row.action().label(), row.itemId(),
					row.displayName(), row.price(), row.units(), row.orderSplit()));
		}

		return new WorkedJob(StrategyKind.CRAFT, outputId, job.displayName(), steps, job.capital(),
				job.totalNetProfit(), "");
	}

	/** A combine plan as a worked job. The twin of {@link #ofCraft}. */
	public static WorkedJob ofCombine(String targetId, String displayName, CombineJob job) {
		if (job == null) {
			return stalled(StrategyKind.COMBINE, targetId, displayName);
		}

		List<Step> steps = new ArrayList<>();

		for (CombineJob.Row row : job.rows()) {
			steps.add(new Step(stageOf(row.action()), row.action().label(), row.itemId(),
					row.displayName(), row.price(), row.units(), row.orderSplit()));
		}

		return new WorkedJob(StrategyKind.COMBINE, targetId, job.displayName(), steps,
				job.capital(), job.totalNetProfit(), "");
	}

	/** A fusion plan as a worked job. The twin of {@link #ofCombine}. */
	public static WorkedJob ofFusion(String outputId, String displayName, FusionJob job) {
		if (job == null) {
			return stalled(StrategyKind.FUSION, outputId, displayName);
		}

		List<Step> steps = new ArrayList<>();

		for (FusionJob.Row row : job.rows()) {
			steps.add(new Step(stageOf(row.action()), row.action().label(), row.itemId(),
					row.displayName(), row.price(), row.units(), row.orderSplit()));
		}

		return new WorkedJob(StrategyKind.FUSION, outputId, job.displayName(), steps, job.capital(),
				job.totalNetProfit(), "");
	}

	/**
	 * A bazaar spread as a worked job: rest the bid, then offer what fills at the ask.
	 *
	 * <p>Built from the candidate rather than from a job type of its own, because a spread has no
	 * plan beyond its two prices - the ranking is the whole of it. The candidate is the one the
	 * strategy re-ranked this poll, so the prices move under the job exactly as a craft's do.
	 *
	 * @param unitsPerOrder what one order of this item holds, for the split
	 */
	public static WorkedJob ofSpread(String itemId, String displayName, FlipCandidate candidate,
			long unitsPerOrder) {
		if (candidate == null) {
			return stalled(StrategyKind.BAZAAR_SPREAD, itemId, displayName);
		}

		String split = Stacking.orderSplit(candidate.units(), unitsPerOrder);

		return new WorkedJob(StrategyKind.BAZAAR_SPREAD, itemId, candidate.displayName(), List.of(
				new Step(Stage.BUY_ORDER, Stage.BUY_ORDER.label(), itemId, candidate.displayName(),
						candidate.unitBuyPrice(), candidate.units(), split),
				new Step(Stage.SELL_OFFER, Stage.SELL_OFFER.label(), itemId,
						candidate.displayName(), candidate.unitSellPrice(), candidate.units(),
						split)),
				candidate.capitalRequired(), candidate.totalNetProfit(), "");
	}

	private static WorkedJob stalled(StrategyKind kind, String itemId, String displayName) {
		return new WorkedJob(kind, itemId, displayName == null ? itemId : displayName, List.of(),
				0L, 0.0d, "no longer clears - check the flip screen");
	}

	private static Stage stageOf(CraftJob.Action action) {
		return switch (action) {
			case BUY_ORDER -> Stage.BUY_ORDER;
			case INSTANT_BUY -> Stage.INSTANT_BUY;
			case CRAFT -> Stage.TRANSFORM;
			case SELL_OFFER -> Stage.SELL_OFFER;
		};
	}

	private static Stage stageOf(CombineJob.Action action) {
		return switch (action) {
			case BUY_ORDER -> Stage.BUY_ORDER;
			case INSTANT_BUY -> Stage.INSTANT_BUY;
			case COMBINE -> Stage.TRANSFORM;
			case SELL_OFFER -> Stage.SELL_OFFER;
		};
	}

	private static Stage stageOf(FusionJob.Action action) {
		return switch (action) {
			case BUY_ORDER -> Stage.BUY_ORDER;
			case INSTANT_BUY -> Stage.INSTANT_BUY;
			case FUSE -> Stage.TRANSFORM;
			case SELL_OFFER -> Stage.SELL_OFFER;
		};
	}

	/** One line, for the places that render a job as text rather than as rows. */
	public String describe(Step step, List<TrackedOrder> orders) {
		Progress progress = progressOf(step, orders);
		StringBuilder line = new StringBuilder(progress.badge()).append(' ').append(step.label())
				.append(' ').append(step.displayName());

		if (step.stage().priced()) {
			line.append(String.format(" at %.1f", step.price()));
		}

		line.append(" x").append(step.orderSplit());

		if (!progress.describe().isEmpty()) {
			line.append(" (").append(progress.describe()).append(" filled)");
		}

		return line.toString();
	}
}
