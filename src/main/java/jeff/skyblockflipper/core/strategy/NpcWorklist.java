package jeff.skyblockflipper.core.strategy;

import jeff.skyblockflipper.core.model.BazaarProduct;
import jeff.skyblockflipper.core.model.ItemCatalog;
import jeff.skyblockflipper.core.model.Stacking;
import jeff.skyblockflipper.core.text.Coins;
import jeff.skyblockflipper.core.text.Waits;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 * <p><b>The reprices come from a round, where there is one.</b> {@link NpcRound} is what puts them on
 * a clock: given one, the reprice rows are the frozen rows of that round rather than whatever the
 * book says this second, they hold their slot and their coins against the basket while the player is
 * mid-reprice, and an order the round did not freeze is left alone until the next one opens. Claims
 * and dead-trade cancels are read off the live review either way, because neither improves by
 * waiting. Without a round this is the old behaviour, advice straight off the book, which is what a
 * caller that tracks no clock honestly has.
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
			String reason,
			boolean postsAboveBook
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
	 * @param round  the round the reprice rows were frozen in, or null where the caller tracks none.
	 *               Carried rather than left to each renderer to fetch, for the same reason
	 *               {@link Worklist#headline()} is assembled here: a panel drawing rows from this
	 *               list and a header from a round fetched separately could describe two different
	 *               rounds
	 */
	public record Worklist(List<Task> tasks, NpcBasket.Basket basket, List<NpcReprice.Advice> advice,
			NpcRound round) {
		public Worklist {
			tasks = List.copyOf(tasks);
			advice = List.copyOf(advice);
		}

		/** The shape before reprices were delivered in rounds, for callers that track no clock. */
		public Worklist(List<Task> tasks, NpcBasket.Basket basket, List<NpcReprice.Advice> advice) {
			this(tasks, basket, advice, null);
		}

		public boolean isEmpty() {
			return tasks.isEmpty();
		}

		/** Whether these reprices are on a clock, i.e. whether the round accessors mean anything. */
		public boolean hasRound() {
			return round != null;
		}

		/**
		 * Items the book has moved past that this trip is not asking about.
		 *
		 * <p>The count in the between-rounds header. On a clock they are the orders the round did not
		 * freeze - too young for it, or posted after it opened - and the answer is to wait for the next
		 * round rather than to chase now. Without one they are the orders whose reprice was not worth
		 * the click. Counted per item, because a row is per item and an unstackable product has four
		 * orders behind one of them.
		 */
		public int outbidWaiting() {
			Set<String> asked = new HashSet<>();

			for (Task task : tasks) {
				if (task.kind() == Kind.REPRICE || task.kind() == Kind.CANCEL) {
					asked.add(task.itemId());
				}
			}

			return (int) advice.stream()
					.filter(NpcReprice.Advice::outbid)
					.map(entry -> entry.order().itemId())
					.filter(itemId -> !asked.contains(itemId))
					.distinct()
					.count();
		}

		/**
		 * What the book has moved past that this trip is not asking about, and when it will be.
		 *
		 * <p>Short enough for the panel beside the bazaar menu, which is about seventy pixels wide:
		 * {@code 3 outbid, next round in 12m}. Without it the panel goes quiet over a book that has
		 * walked past your orders, and a mod that has stopped saying anything is indistinguishable
		 * from a mod that has stopped working - the clock is the one thing the player cannot see for
		 * themselves.
		 *
		 * <p>Empty when nothing is waiting, and empty without a round, where the question has no
		 * answer: a caller off the clock is already being told about every outbid order it has.
		 */
		public String waitingNote(long now) {
			int waiting = outbidWaiting();

			return hasRound() && waiting > 0
					? waiting + " outbid, next round " + when(now)
					: "";
		}

		/**
		 * The same clock in a sentence, for chat, where there is room to say what it means for the
		 * rows above it.
		 *
		 * <p>Two different facts depending on what the trip contains. With reprices in it the useful
		 * one is how long their prices stay the prices to type, because that is what the player is
		 * about to walk to a menu with. With none it is when the orders the book has passed will be
		 * asked about. Both come off the one round the tasks came from, for the same reason
		 * {@link #headline()} does.
		 */
		public String roundNote(long now) {
			if (!hasRound() || count(Kind.REPRICE) == 0) {
				return waitingNote(now);
			}

			Duration left = round.remaining(now);

			if (left.isZero()) {
				return "This round is up, so the next list recomputes these prices.";
			}

			int waiting = outbidWaiting();
			String held = "These prices are held for another " + Waits.format(left)
					+ ", when the next round recomputes them";

			return waiting == 0
					? held + "."
					: held + " and asks about the other " + waiting + " outbid.";
		}

		/** How far off the next round is, phrased once so both notes agree on the number. */
		private String when(long now) {
			Duration left = round.remaining(now);
			return left.isZero() ? "any moment" : "in " + Waits.format(left);
		}

		/** Tasks that want a click, which is the list a player at a menu is actually reading. */
		public List<Task> pending() {
			return tasks.stream().filter(Task::needsClick).toList();
		}

		public int count(Kind kind) {
			return (int) tasks.stream().filter(task -> task.kind() == kind).count();
		}

		/**
		 * What the order this row's next click is about is resting at, or 0 where nothing says.
		 *
		 * <p>The orders menu shows one row per order and the worklist shows one row per item, so a
		 * renderer pointing at a row has to choose between an item's orders. The price is the only
		 * thing that separates two rows on one item - {@code OrderMenuParser.slotOf} matches on it -
		 * and the row itself carries the price being moved <i>to</i>, which is a different number.
		 *
		 * <p><b>The choice is made among the orders that need this same click, and the cheapest wins.</b>
		 * Reported live 2026-08-14: with two {@code BRONZE_BOWL} orders resting, the panel highlighted
		 * neither, because the old rule refused to choose and a refusal is indistinguishable from a
		 * mod that has stopped working. Refusing was the wrong reading of the risk. Every order under
		 * one reprice row is outbid and every one of them is to be moved to the same price, so any of
		 * them is a correct click and there is no coin flip to lose - the cheapest is simply the
		 * furthest behind the book, so it is the one worth moving first. The same holds for a cancel.
		 *
		 * <p>An order that does not need the click is never offered, which is what keeps this from
		 * pointing at the healthy half of a position: a player who has already moved one of two orders
		 * leaves exactly one {@code REPRICE} advice behind, and that is the one this returns.
		 */
		public double restingPriceFor(Task task) {
			if (task == null) {
				return 0.0d;
			}

			double lowest = 0.0d;

			for (NpcReprice.Advice entry : advice) {
				if (!entry.order().itemId().equals(task.itemId()) || !about(entry, task.kind())) {
					continue;
				}

				double price = entry.order().unitPrice();

				// A price the escrow line could not produce is no help in telling two rows apart.
				if (price > 0.0d && (lowest <= 0.0d || price < lowest)) {
					lowest = price;
				}
			}

			return lowest;
		}

		/** Whether this advice is about the click {@code kind} names. */
		private static boolean about(NpcReprice.Advice entry, Kind kind) {
			return switch (kind) {
				case CLAIM -> entry.hasUnclaimed();
				case CANCEL -> entry.isCancel();
				case REPRICE -> entry.action() == NpcReprice.Action.REPRICE;
				case PLACE, HOLD -> false;
			};
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
				if (hasRound() && outbidWaiting() > 0) {
					// Not "all of them are on top of the book", which is what this used to say and is
					// exactly wrong here: some of them have been outbid and the round in hand is not
					// asking about them.
					return "Nothing to do yet: " + outbidWaiting()
							+ " outbid, and the next round is what will ask about them";
				}

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
		return of(resting, context, now, null);
	}

	/**
	 * The same trip, with the reprices delivered on a clock.
	 *
	 * <p>What the round changes: the review is valued over what is left of the interval rather than a
	 * whole one, the reprice rows are the round's frozen rows rather than the live advice, and those
	 * rows keep their slot and their coins out of the basket until they are worked. Everything else -
	 * the claims, the dead-trade cancels, the new orders, the holds - is unchanged, because none of
	 * it is about chasing the book.
	 *
	 * @param round the round in hand, or null to review straight off the book. The client owns the
	 *              instance and decides when a new one supersedes it ({@link NpcRound#elapsed}); this
	 *              only reads the one it is handed
	 */
	public static Worklist of(List<NpcReprice.Order> resting, StrategyContext context, long now,
			NpcRound round) {
		return of(resting, context, now, round, Set.of());
	}

	/**
	 * The same trip, told which items have been bought out since the round opened.
	 *
	 * <p>The one thing the resting orders cannot say. A row whose orders have all left the book is
	 * either mid-reprice or finished, and {@link NpcRound#outstanding(List, Set)} needs to be told
	 * which - otherwise a completed flip keeps its row, its order slot and its share of the bankroll
	 * for the rest of the interval.
	 *
	 * @param filled item ids whose buy orders filled and were claimed since {@code round} opened,
	 *               from {@code TradeTracker.filledSince}. Ignored when there is no round
	 */
	public static Worklist of(List<NpcReprice.Order> resting, StrategyContext context, long now,
			NpcRound round, Set<String> filled) {
		return of(resting, context, now, round, filled, Map.of());
	}

	/**
	 * The same trip, told when each item was last cancelled.
	 *
	 * <p>The one thing that separates a reprice half done from a book the player has cleared by hand.
	 * See {@link NpcRound#outstanding(List, Set, Map, long)}.
	 *
	 * @param cancelledAt when each item's last buy order came off the book, from
	 *                    {@code TradeTracker.cancelledSince}. Empty is the old behaviour
	 */
	public static Worklist of(List<NpcReprice.Order> resting, StrategyContext context, long now,
			NpcRound round, Set<String> filled, Map<String, Long> cancelledAt) {
		// A reprice is only worth what it fills before the next trip, so a round part way through
		// values its rows over what is left of it. Null is a full interval, not no time at all.
		Duration horizon = round == null ? null : round.remaining(now);
		List<NpcReprice.Advice> advice = NpcReprice.review(resting, context, now, horizon);

		// Only the orders the review recognised. One it dropped - an item no NPC buys, or a product
		// missing from this snapshot - is not an NPC position, so charging the basket a slot for it
		// would shrink the plan on the strength of a spread flip.
		List<NpcReprice.Order> recognised = advice.stream().map(NpcReprice.Advice::order).toList();
		List<NpcRound.Row> rows = rowsToWork(round, recognised, advice, filled, cancelledAt, now);

		// Re-price each frozen row against the live book once, before anything is sized against it. A
		// row the book has chased past the stop, or one whose item has left the catalog, is dropped
		// here rather than in the task loop, so reserve and roundReprices agree by construction and its
		// slot and coins go back to this trip's basket instead of sitting reserved for a re-post that
		// must not happen. See NpcReprice.repriceNow.
		List<LivePlan> plans = livePlans(rows, context);
		List<NpcRound.Row> working = plans.stream().map(LivePlan::row).toList();
		NpcBasket.Basket basket = NpcBasket.plan(context, reserve(recognised, working, advice));

		List<Task> tasks = new ArrayList<>();

		tasks.addAll(claims(advice));
		tasks.addAll(cancels(advice));
		tasks.addAll(round == null ? reprices(advice) : roundReprices(plans, context, basket));
		tasks.addAll(places(basket, context));
		tasks.addAll(holds(advice, working, round != null));

		return new Worklist(tasks, basket, advice, round);
	}

	/**
	 * Each frozen row re-priced against the live book, minus the ones no longer worth posting.
	 *
	 * <p>One evaluation per row, paired with its row so {@code reserve} and the reprice tasks read the
	 * same answer rather than each computing the price again. A row {@link NpcReprice#repriceNow}
	 * drops - past the stop, or off the catalogue - is simply absent from the list, which is what
	 * frees its slot and its coins to the basket this trip.
	 */
	private static List<LivePlan> livePlans(List<NpcRound.Row> rows, StrategyContext context) {
		List<LivePlan> plans = new ArrayList<>();

		for (NpcRound.Row row : rows) {
			NpcReprice.repriceNow(row, context)
					.filter(live -> !live.pastStop())
					.ifPresent(live -> plans.add(new LivePlan(row, live)));
		}

		return plans;
	}

	/** One frozen round row and its live reprice, paired so reserve and the task read one evaluation. */
	private record LivePlan(NpcRound.Row row, NpcReprice.LiveReprice live) {
	}

	/**
	 * The frozen rows still to work, minus the ones the book has since killed.
	 *
	 * <p><b>A dead trade overrules its own row.</b> The round froze a price on the evidence that
	 * repricing there was still worth a slot; a {@code CANCEL} since then says the book has caught the
	 * NPC price or chased past the stop, and re-posting into that would be buying at a price the
	 * strategy would refuse to open. The cancel is emitted for the same order in the same trip, so
	 * leaving the row in would be telling the player to cancel it and put it back.
	 */
	private static List<NpcRound.Row> rowsToWork(NpcRound round, List<NpcReprice.Order> recognised,
			List<NpcReprice.Advice> advice, Set<String> filled, Map<String, Long> cancelledAt,
			long now) {
		if (round == null) {
			return List.of();
		}

		Set<String> dead = new HashSet<>();

		for (NpcReprice.Advice entry : advice) {
			if (entry.isCancel()) {
				dead.add(entry.order().itemId());
			}
		}

		return round.outstanding(recognised, filled, cancelledAt, now).stream()
				.filter(row -> !dead.contains(row.itemId()))
				.toList();
	}

	/**
	 * What the basket may not spend: the resting orders, plus whatever the round's rows are holding
	 * for a re-post that has not happened yet.
	 *
	 * <p>The reservation is what makes a pinned row survivable. Halfway through a reprice the old
	 * order is cancelled and the new one is not placed, so the slot and the coins are free on every
	 * measure the basket has - and a basket allocated then would spend them on another item, leaving
	 * the player holding a price they can no longer post. Reserved per item at the larger of the two
	 * claims, never their sum, because the row and the orders behind it are the same position: an
	 * item with two of its four orders already moved needs four slots reserved, not six.
	 *
	 * <p><b>It also decides which positions the basket may finish.</b> A line larger than one bazaar
	 * order is placed one order at a time, and a position part way through one is the commonest thing
	 * on the book: 256 of 1,024 units up, three orders still to type. That is
	 * {@link NpcBasket.Position#topUp} - the basket is handed the size of the position and offers the
	 * shortfall - and it is allowed only where nothing else in the trip is asking about the item's
	 * orders. A reprice, a cancel or an expiry carries a price of its own, and a place quoted from
	 * here beside a reprice quoted from the round is two instructions for one item. A claim does not
	 * block it: it is a button with no price on it, and the units behind it were already counted, as
	 * a position is sized on what its orders were placed for rather than on what is still unfilled.
	 */
	private static NpcBasket.Held reserve(List<NpcReprice.Order> resting, List<NpcRound.Row> rows,
			List<NpcReprice.Advice> advice) {
		Map<String, Reservation> perItem = new LinkedHashMap<>();

		for (NpcReprice.Order order : resting) {
			Reservation held = perItem.computeIfAbsent(order.itemId(), id -> new Reservation());

			held.orders++;
			held.units += order.total();
			held.capital += Math.round(order.unitPrice() * order.remaining());
		}

		for (NpcRound.Row row : rows) {
			Reservation held = perItem.computeIfAbsent(row.itemId(), id -> new Reservation());

			held.orders = Math.max(held.orders, row.orders());
			held.units = Math.max(held.units, row.units());
			held.capital = Math.max(held.capital, Math.round(row.postPrice() * row.units()));
			held.spokenFor = true;
		}

		for (NpcReprice.Advice entry : advice) {
			Reservation held = perItem.get(entry.order().itemId());

			if (held != null && entry.needsAction()) {
				held.spokenFor = true;
			}
		}

		Map<String, NpcBasket.Position> positions = new LinkedHashMap<>();
		int orders = 0;
		long capital = 0L;

		for (Map.Entry<String, Reservation> entry : perItem.entrySet()) {
			Reservation held = entry.getValue();

			orders += held.orders;
			capital += held.capital;
			positions.put(entry.getKey(),
					new NpcBasket.Position(held.orders, held.units, !held.spokenFor));
		}

		return new NpcBasket.Held(orders, capital, positions);
	}

	/** One item's claim on the shared resources, while the two claims on it are being reconciled. */
	private static final class Reservation {
		private int orders;
		private long units;
		private long capital;
		private boolean spokenFor;
	}

	/** Filled units waiting to be collected, biggest first. */
	private static List<Task> claims(List<NpcReprice.Advice> advice) {
		return advice.stream()
				.filter(NpcReprice.Advice::hasUnclaimed)
				.sorted(Comparator.comparingDouble(NpcReprice.Advice::claimableProfit).reversed())
				.map(entry -> new Task(Kind.CLAIM, entry.order().itemId(),
						entry.order().displayName(), 0.0d, entry.order().unclaimed(),
						String.valueOf(entry.order().unclaimed()), entry.claimableProfit(), 0L,
						claimReason(entry), false))
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

	/**
	 * The trades that are over, biggest-coins first. Never held for a round: the book has caught the
	 * NPC price or the window has run out, so the capital is stranded until the order comes off.
	 */
	private static List<Task> cancels(List<NpcReprice.Advice> advice) {
		return advice.stream()
				.filter(NpcReprice.Advice::isCancel)
				.map(entry -> new Task(Kind.CANCEL, entry.order().itemId(),
						entry.order().displayName(), 0.0d, entry.order().remaining(),
						String.valueOf(entry.order().remaining()), 0.0d, entry.capitalAtStake(),
						entry.reason(), false))
				.sorted(Comparator.comparingLong(Task::capital).reversed())
				.toList();
	}

	/** Reprices straight off the book, for a caller tracking no round. One row per order. */
	private static List<Task> reprices(List<NpcReprice.Advice> advice) {
		return advice.stream()
				.filter(entry -> entry.action() == NpcReprice.Action.REPRICE)
				.map(entry -> new Task(Kind.REPRICE, entry.order().itemId(),
						entry.order().displayName(), entry.postPrice(), entry.order().remaining(),
						String.valueOf(entry.order().remaining()), entry.profitAtStake(),
						entry.capitalAtStake(), entry.reason(), false))
				.sorted(Comparator.comparingDouble(Task::profit).reversed())
				.toList();
	}

	/**
	 * The round's rows, in the order it ranked them, at the price the bazaar will offer for them now.
	 *
	 * <p>One row per item however many orders it takes, and left in the round's own order rather than
	 * re-sorted: the round ranked on what each reprice is expected to earn before the next trip, which
	 * is the number that decided the row was worth a click at all.
	 *
	 * <p><b>The row's membership is frozen and its price is not.</b> The two were frozen together and
	 * they answer different questions. Which items to work has to hold still, or the list churns and
	 * asks for a click that is stale before the player reaches a menu - that is the whole finding of
	 * {@code docs/adr/0002-reprice-in-rounds.md} and none of it changes here. What to type does not
	 * benefit from holding still, because the player is standing in front of Hypixel's own
	 * "+0.1 coins" button, which reads the live book: a price frozen half an hour ago is visibly a
	 * different number from the one the game is offering, and the player has to decide which to
	 * believe. Measured over 2026-08-11..13 of the bazaar tape, the top bid moved inside a
	 * thirty-minute window on 39% of {@code ENCHANTED_POISONOUS_POTATO} samples and 83% of
	 * {@code BRONZE_BOWL} ones, so that is not a rare disagreement.
	 *
	 * <p>So {@link NpcReprice#repriceNow} quotes {@code outbidBuyOrder()} off the snapshot in hand,
	 * which is the same computation the button performs and at most one poll behind it. The frozen
	 * price is still what {@link NpcRound#outstanding} judges the row worked against - the player acted
	 * on that number - and still what {@code reserve} sizes the hold with. The past-stop rows and the
	 * catalogue-less ones have already been dropped from {@code plans}, so every row here is one to
	 * post.
	 */
	private static List<Task> roundReprices(List<LivePlan> plans, StrategyContext context,
			NpcBasket.Basket basket) {
		// What is free once the basket has taken its share, which is what decides whether the new
		// order can go on the book before the old one comes off. Not consumed row by row: the slot and
		// the coins a place-first borrows are handed straight back by the cancel that follows it, so
		// one free slot serves every row in turn.
		int freeSlots = Math.max(0, basket.slotsFree() - basket.slotsUsed());
		long freeCoins = Math.max(0L, basket.bankrollFree() - basket.capital());

		List<Task> tasks = new ArrayList<>();

		for (LivePlan plan : plans) {
			NpcRound.Row row = plan.row();
			double price = plan.live().price();

			long perOrder = unitsPerOrder(context, row.itemId());
			long capital = Math.round(price * row.units());
			long firstOrder = Math.round(price * Math.min(row.units(), perOrder));
			boolean placeFirst = freeSlots >= 1 && freeCoins >= firstOrder;

			tasks.add(new Task(Kind.REPRICE, row.itemId(), row.displayName(), price,
					row.units(), Stacking.orderSplit(row.units(), perOrder),
					plan.live().profitAtStake(), capital,
					repriceReason(row, price, perOrder, placeFirst, capital, plan.live().chaseStop()),
					false));
		}

		return tasks;
	}

	/**
	 * What to click, in the order that never leaves you off the book where it can be helped.
	 *
	 * <p>The bazaar has no in-place edit, so a reprice is a cancel and then a re-post, and between
	 * the two the order is not collecting anything. Where a slot and the coins for one order are free
	 * the row says place first, which skips that gap entirely at the cost of holding both for the few
	 * seconds in between. Where they are not, the row is pinned: its price is frozen for the rest of
	 * the round and its slot and capital are reserved, so what it needs to go back on the book is
	 * still there when the player gets to it.
	 */
	private static String repriceReason(NpcRound.Row row, double price, long perOrder,
			boolean placeFirst, long capital, double chaseStop) {
		String size = row.orders() == 1
				? String.format("%d units", row.units())
				: String.format("%d orders, %s units", row.orders(),
						Stacking.orderSplit(row.units(), perOrder));

		// One instruction, not two numbers to choose between. The bazaar's "+0.1 coins" button is
		// the same computation this price is - top bid plus one increment - so naming the button
		// rather than asking the player to compare it against the figure means there is nothing to
		// get wrong when the book has moved in the seconds since the last poll. The stop is quoted
		// as a bound and not as a decision: a row whose live price is already past it never reaches
		// this list.
		String worth = String.format("Outbid. Move %s back to the top with the bazaar's own "
						+ "\"+0.1 coins\" button - about %.1f, and never above %.1f. Worth about %s "
						+ "before the next check-in. ",
				size, price, chaseStop, Coins.format(row.gain()));

		return worth + (placeFirst
				? "A slot and the coins are free, so place the new order first and cancel the old "
						+ "one after: that way the item is never off the book"
				: "Cancel first, then re-post - the row, the slot and the " + Coins.format(capital)
						+ " it needs are held for the rest of the round, so the basket cannot spend "
						+ "them while you are mid-reprice");
	}

	/** Units one bazaar order of this item holds, read off the same book the basket sizes on. */
	private static long unitsPerOrder(StrategyContext context, String itemId) {
		ItemCatalog.Entry entry = context.catalog().get(itemId).orElse(null);
		BazaarProduct product = context.bazaar().products().get(itemId);

		return Stacking.unitsPerOrder(entry, product);
	}

	/** The new orders, in the order the allocator ranked them. */
	private static List<Task> places(NpcBasket.Basket basket, StrategyContext context) {
		return basket.lines().stream()
				.map(line -> new Task(Kind.PLACE, line.plan().itemId(), line.plan().displayName(),
						line.plan().postPrice(), line.units(), line.orderSplit(), line.profit(),
						line.capital(), placeReason(line, context), line.plan().postsAboveBook()))
				.toList();
	}

	/**
	 * What to type, and on a part-placed item, what is left to type.
	 *
	 * <p>Same instruction as a reprice, because it is the same click. A new order is priced off the
	 * snapshot in hand rather than off a round, so it was never as stale as a frozen reprice - but
	 * naming the button in one place and a bare number in the other is what makes a player wonder
	 * which of the two the mod means.
	 *
	 * <p>The top-up sentence is the whole answer to "how many are still to go". A 1,024-unit line is
	 * four orders on an unstackable product, and the units on the row are only ever the remainder, so
	 * without this a player who typed one order of 256 has no way to tell a 768-unit row from a fresh
	 * plan that happens to be 768 units.
	 */
	private static String placeReason(NpcBasket.Line line, StrategyContext context) {
		String base = String.format("Buy order with the bazaar's own \"+0.1 coins\" button - about "
						+ "%.1f, and never above %.1f. Sell to the NPC at %.1f: %.0f%% margin on "
						+ "%d units", line.plan().postPrice(),
				context.npc().maxChasePrice(line.plan().npcPrice()), line.plan().npcPrice(),
				line.plan().marginRatio() * 100.0d, line.units());

		if (!line.topUp()) {
			return base;
		}

		return base + String.format(". %d of the %d this item is worth are already resting, so this "
						+ "is the remaining %s - %d more %s", line.restingUnits(),
				line.positionUnits(), line.orderSplit(), line.orders(),
				line.orders() == 1 ? "order" : "orders");
	}

	/**
	 * The orders that are fine, last, so the list a player scans starts with the work.
	 *
	 * <p>An item with a row in the round is not one of them, whatever the live review says about it.
	 * A part-worked reprice reads as a hold the moment the book falls back under the frozen price, and
	 * an item listed as both "reprice at 28594.7" and "hold, top of the book" is the mod arguing with
	 * itself in a seventy-pixel panel.
	 */
	private static List<Task> holds(List<NpcReprice.Advice> advice, List<NpcRound.Row> rows,
			boolean onAClock) {
		Set<String> working = new HashSet<>();

		for (NpcRound.Row row : rows) {
			working.add(row.itemId());
		}

		List<Task> tasks = new ArrayList<>();

		for (NpcReprice.Advice entry : advice) {
			if (working.contains(entry.order().itemId())) {
				continue;
			}

			if (entry.action() == NpcReprice.Action.HOLD) {
				tasks.add(hold(entry, entry.reason()));
			} else if (onAClock && entry.action() == NpcReprice.Action.REPRICE) {
				// Outbid, worth chasing, and not in the round in hand. Left alone rather than dropped
				// from the list, so the order is still counted somewhere and the reason says which of
				// the two lists it is on.
				tasks.add(hold(entry, waitingReason(entry)));
			}
		}

		return tasks;
	}

	private static Task hold(NpcReprice.Advice entry, String reason) {
		return new Task(Kind.HOLD, entry.order().itemId(), entry.order().displayName(),
				entry.order().unitPrice(), entry.order().remaining(),
				String.valueOf(entry.order().remaining()), entry.profitAtStake(),
				entry.capitalAtStake(), reason, false);
	}

	private static String waitingReason(NpcReprice.Advice entry) {
		return String.format("Outbid at %.1f, and this is not in the round in hand - an order has to "
						+ "be an interval old to enter one, and a round already open is not reopened "
						+ "for it. The next round will ask about it", entry.bestBid());
	}
}
