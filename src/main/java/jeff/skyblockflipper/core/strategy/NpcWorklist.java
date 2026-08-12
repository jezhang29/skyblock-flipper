package jeff.skyblockflipper.core.strategy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Everything to do at the bazaar this trip, as one list in the order to do it.
 *
 * <p>The mod used to answer this question in two halves that never met. {@code /flip npc plan}
 * allocated a fresh basket as though the account were empty, and {@code /flip npc reprice} reviewed
 * the resting orders without knowing a basket existed. A player coming back to a half-filled book
 * therefore got two lists, each correct on its own terms and jointly impossible: place twenty-one
 * orders, and also here are the fourteen you already have.
 *
 * <p>This is the join. The resting orders are reviewed first, what they hold is subtracted from the
 * slots and the bankroll, and the basket is allocated over what is left - so the two halves are
 * sized against one account. What comes out is a list of clicks.
 *
 * <p><b>Order matters and it is not the order the money is in.</b> Claims come first because those
 * coins are already earned and the item cannot leave the order until they are collected. Cancels
 * next, because they hand back the resource everything else is short of. Then reprices, largest
 * first. Then the new orders, which are the only part that needs coins - and by then the cancels
 * have supplied some.
 *
 * <p>Pure, and in {@code core} with the rest of the money math. Three things render it: chat, the
 * Basket tab and the panel beside the bazaar menu. They must not be able to disagree, which is what
 * they would do if each assembled its own list from the two halves.
 */
public final class NpcWorklist {
	private NpcWorklist() {
	}

	/** What a row is asking for, which is also what colour it should be. */
	public enum Kind {
		/** Units filled and never collected. Click Claim; the coins are already yours. */
		CLAIM,

		/** Outbid, still worth chasing. Cancel and re-post at the price given. */
		REPRICE,

		/** Take it off the book: past the chase stop, or past the window the coins were lent for. */
		CANCEL,

		/** Not on the book yet. This is the new order to place. */
		PLACE,

		/** On the book, on top of it, inside its window. Nothing to do. */
		HOLD
	}

	/**
	 * One click, with everything needed to perform it and nothing else.
	 *
	 * <p>Flat on purpose. It is rendered into a panel about seventy pixels wide beside a Hypixel
	 * menu, and a row there has room for a name, a number to type and a size - so the type carries
	 * exactly those, whichever half of the cycle produced them.
	 *
	 * @param price      what to type into the price box, or 0 where the click has no price: a claim
	 *                   and a cancel are both a button
	 * @param units      units the task is about - to claim, to move, or to buy
	 * @param orderSplit how {@code units} divides into orders that the bazaar will actually accept,
	 *                   e.g. {@code 3 x 256 + 112}. The total alone reads as one order, which is how
	 *                   a 500-unit line got typed into a book that takes 256 at a time
	 * @param profit     coins at stake: earned and uncollected on a claim, still reachable on a
	 *                   reprice or a place, and zero on a cancel, which is about the slot
	 * @param capital    coins the click moves, in or out
	 */
	public record Task(
			Kind kind,
			String itemId,
			String displayName,
			double price,
			long units,
			String orderSplit,
			double profit,
			long capital,
			String reason
	) {
		public boolean needsClick() {
			return kind != Kind.HOLD;
		}

		/** The imperative the row starts with, so every renderer uses the same word. */
		public String verb() {
			return switch (kind) {
				case CLAIM -> "claim";
				case REPRICE -> "reprice";
				case CANCEL -> "cancel";
				case PLACE -> "place";
				case HOLD -> "hold";
			};
		}

		/** Whether there is a number worth copying into the price box. */
		public boolean hasPrice() {
			return price > 0.0d && (kind == Kind.REPRICE || kind == Kind.PLACE);
		}
	}

	/**
	 * The whole trip.
	 *
	 * @param basket the new orders, already sized around what was resting
	 * @param advice the review of every resting order, holds included, for anything that wants to
	 *               explain a single one in full
	 */
	public record Worklist(List<Task> tasks, NpcBasket.Basket basket, List<NpcReprice.Advice> advice) {
		public Worklist {
			tasks = List.copyOf(tasks);
			advice = List.copyOf(advice);
		}

		public boolean isEmpty() {
			return tasks.isEmpty();
		}

		/** Tasks that want a click, which is the list a player at a menu is actually reading. */
		public List<Task> pending() {
			return tasks.stream().filter(Task::needsClick).toList();
		}

		public int count(Kind kind) {
			return (int) tasks.stream().filter(task -> task.kind() == kind).count();
		}

		/** Orders that are exactly where you left them, which is a count and never a list. */
		public int holding() {
			return count(Kind.HOLD);
		}

		/**
		 * What this trip is, in one line, ordered by what a player can least afford to skip.
		 *
		 * <p>Assembled here rather than in each renderer, for the same reason
		 * {@link NpcBasket.Basket#boundExplanation()} is: chat, the tab and the bazaar panel are
		 * three views of one answer and must not be able to phrase it differently.
		 */
		public String headline() {
			List<String> parts = new ArrayList<>();

			add(parts, count(Kind.CLAIM), "to claim");
			add(parts, count(Kind.CANCEL), "to cancel");
			add(parts, count(Kind.REPRICE), "to reprice");
			add(parts, count(Kind.PLACE), "to place");

			if (parts.isEmpty()) {
				return holding() == 0
						? "Nothing to do: no orders resting and nothing on the book worth one"
						: "Nothing to do: all " + holding() + " orders are on top of the book";
			}

			return String.join(", ", parts);
		}

		private static void add(List<String> parts, int count, String label) {
			if (count > 0) {
				parts.add(count + " " + label);
			}
		}
	}

	/**
	 * Reviews what is resting, then allocates the rest of the account over what is left.
	 *
	 * @param resting every NPC-eligible buy order believed to be on the book. Empty is a legitimate
	 *                answer and produces a plain basket, which is what the first trip of a cycle is
	 * @param now     epoch millis, passed rather than read: the resting-window rule needs a clock
	 *                and {@code core} does not own one
	 */
	public static Worklist of(List<NpcReprice.Order> resting, StrategyContext context, long now) {
		List<NpcReprice.Advice> advice = NpcReprice.review(resting, context, now);

		// Only the orders the review recognised. One it dropped - an item no NPC buys, or a product
		// missing from this snapshot - is not an NPC position, so charging the basket a slot for it
		// would shrink the plan on the strength of a spread flip.
		List<NpcReprice.Order> recognised = advice.stream().map(NpcReprice.Advice::order).toList();
		NpcBasket.Basket basket = NpcBasket.plan(context, NpcBasket.Held.of(recognised));

		List<Task> tasks = new ArrayList<>();

		tasks.addAll(claims(advice));
		tasks.addAll(bookTasks(advice));
		tasks.addAll(places(basket));
		tasks.addAll(holds(advice));

		return new Worklist(tasks, basket, advice);
	}

	/** Filled units waiting to be collected, biggest first. */
	private static List<Task> claims(List<NpcReprice.Advice> advice) {
		return advice.stream()
				.filter(NpcReprice.Advice::hasUnclaimed)
				.sorted(Comparator.comparingDouble(NpcReprice.Advice::claimableProfit).reversed())
				.map(entry -> new Task(Kind.CLAIM, entry.order().itemId(),
						entry.order().displayName(), 0.0d, entry.order().unclaimed(),
						String.valueOf(entry.order().unclaimed()), entry.claimableProfit(), 0L,
						claimReason(entry)))
				.toList();
	}

	/**
	 * A claim is emitted for a partial fill even on an order that is about to be cancelled.
	 *
	 * <p>Cancelling a part-filled buy order may well hand the filled units over with the refund, but
	 * "may well" is not something to build a click order on, and claiming first is correct either
	 * way at the cost of one button. The reason line says which case the player is in.
	 */
	private static String claimReason(NpcReprice.Advice entry) {
		NpcReprice.Order order = entry.order();
		String base = String.format("%d of %d units filled at %.1f. Those coins are earned and the "
						+ "items cannot go to the NPC until they are out of the order",
				order.filled(), order.total(), order.unitPrice());

		return entry.isCancel() ? base + ", so claim before cancelling the rest" : base;
	}

	/** Cancels first, then reprices, each biggest-coins first. */
	private static List<Task> bookTasks(List<NpcReprice.Advice> advice) {
		List<Task> cancels = new ArrayList<>();
		List<Task> reprices = new ArrayList<>();

		for (NpcReprice.Advice entry : advice) {
			NpcReprice.Order order = entry.order();

			switch (entry.action()) {
				case CANCEL, EXPIRED -> cancels.add(new Task(Kind.CANCEL, order.itemId(),
						order.displayName(), 0.0d, order.remaining(),
						String.valueOf(order.remaining()), 0.0d, entry.capitalAtStake(),
						entry.reason()));
				case REPRICE -> reprices.add(new Task(Kind.REPRICE, order.itemId(),
						order.displayName(), entry.postPrice(), order.remaining(),
						String.valueOf(order.remaining()), entry.profitAtStake(),
						entry.capitalAtStake(), entry.reason()));
				case HOLD -> {
				}
			}
		}

		cancels.sort(Comparator.comparingLong(Task::capital).reversed());
		reprices.sort(Comparator.comparingDouble(Task::profit).reversed());
		cancels.addAll(reprices);

		return cancels;
	}

	/** The new orders, in the order the allocator ranked them. */
	private static List<Task> places(NpcBasket.Basket basket) {
		return basket.lines().stream()
				.map(line -> new Task(Kind.PLACE, line.plan().itemId(), line.plan().displayName(),
						line.plan().postPrice(), line.units(), line.orderSplit(), line.profit(),
						line.capital(),
						String.format("Buy order at %.1f, sell to the NPC at %.1f: %.0f%% margin on "
										+ "%d units", line.plan().postPrice(), line.plan().npcPrice(),
								line.plan().marginRatio() * 100.0d, line.units())))
				.toList();
	}

	/** The orders that are fine, last, so the list a player scans starts with the work. */
	private static List<Task> holds(List<NpcReprice.Advice> advice) {
		return advice.stream()
				.filter(entry -> entry.action() == NpcReprice.Action.HOLD)
				.map(entry -> new Task(Kind.HOLD, entry.order().itemId(),
						entry.order().displayName(), entry.order().unitPrice(),
						entry.order().remaining(), String.valueOf(entry.order().remaining()),
						entry.profitAtStake(), entry.capitalAtStake(), entry.reason()))
				.toList();
	}
}
