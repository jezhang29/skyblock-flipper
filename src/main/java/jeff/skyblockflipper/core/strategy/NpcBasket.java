package jeff.skyblockflipper.core.strategy;

import jeff.skyblockflipper.core.model.Stacking;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What to do with all of your order slots at once, rather than what one item is worth.
 *
 * <p>{@link NpcFlipStrategy} answers "which single plan pays most per hour", and a list of those
 * answers is not a plan: every candidate on it is sized against the whole bankroll, so following
 * three of them spends the bankroll three times. Order slots are worse - about 21 of them, shared
 * across every item and across a coop - and nothing in a ranked list is aware that the plan above
 * it took some.
 *
 * <p>So this allocates the shared resources once: <b>bankroll, order slots and the day's remaining
 * NPC coin budget</b>. Greedy, by profit per inventory load. Measured over a full day of tape
 * against the alternatives - 76.4M ranking on profit per slot-load, 4.8M ranking on cap efficiency,
 * which spends the slots on 9-coin items - and greedy is the right shape for it: the items are
 * small against the budgets, so there is no knapsack pathology to solve around.
 *
 * <p><b>Hauling is counted and reported, never enforced.</b> Measured on a live basket it used 34
 * of 864 available slot-loads, so a limit on it would be a parameter with no measurement behind it
 * suppressing plans that fit comfortably. {@link Basket#loads()} is there so a player can see how
 * much clicking a basket is.
 *
 * <p><b>Nothing here is cached, and that is the point.</b> {@link #plan(StrategyContext)} reads the
 * bankroll, the slots and the budget off the context every time it is called, so raising the
 * bankroll in settings and running {@code /flip reload} re-sizes the whole basket on the next call.
 * A basket is a snapshot of an answer, never a live object.
 */
public final class NpcBasket {
	private NpcBasket() {
	}

	/**
	 * What is already on the book, so a basket adds to a position rather than re-planning from an
	 * empty account.
	 *
	 * <p>Without this the second basket of a session is the first one again: every slot free, the
	 * whole bankroll available, and the items you are already bidding on offered back to you. Follow
	 * it and you spend coins you have not got on orders you already have, at a second price, bidding
	 * against your own resting order on the same book.
	 *
	 * @param orders    bazaar order slots the resting orders occupy
	 * @param capital   coins they have tied up, at the price they actually rest at
	 * @param positions one entry per product with an order already resting on it
	 */
	public record Held(int orders, long capital, Map<String, Position> positions) {
		public Held {
			positions = Map.copyOf(positions);
			orders = Math.max(0, orders);
			capital = Math.max(0L, capital);
		}

		/**
		 * The shape before a position could be topped up: these items are held and none of them is
		 * open to another order.
		 *
		 * <p>Kept because that is the right default for a caller that knows only which items it
		 * holds. Sizing a top-up needs the units, and guessing them from an item id would size it
		 * wrong in the direction that over-buys.
		 */
		public Held(int orders, long capital, Set<String> itemIds) {
			this(orders, capital, closedPositions(itemIds));
		}

		/** Nothing resting: a fresh account, or a caller with no way to know. */
		public static Held nothing() {
			return new Held(0, 0L, Map.of());
		}

		public Set<String> itemIds() {
			return positions.keySet();
		}

		/** This item's resting position, or an empty one where nothing of it is on the book. */
		public Position position(String itemId) {
			return positions.getOrDefault(itemId, Position.none());
		}

		/**
		 * Whether the basket must leave this item alone: something is resting on it and nothing may
		 * be added to that.
		 */
		public boolean closed(String itemId) {
			return positions.containsKey(itemId) && !positions.get(itemId).topUp();
		}

		public boolean isEmpty() {
			return orders == 0 && capital == 0L && positions.isEmpty();
		}

		private static Map<String, Position> closedPositions(Set<String> itemIds) {
			Map<String, Position> positions = new LinkedHashMap<>();

			for (String itemId : itemIds) {
				positions.put(itemId, new Position(0, 0L, false));
			}

			return positions;
		}
	}

	/**
	 * One item's resting position, and whether the basket may add to it.
	 *
	 * <p><b>Why a held item is not simply dropped any more.</b> One bazaar order holds 256 units of
	 * an unstackable product, so a 1,024-unit line is four orders and the player places them one at
	 * a time. Dropping the item on the first of the four deleted the line the moment it was
	 * half-followed: the panel went quiet with 768 units unplaced and nothing anywhere said how many
	 * were left. Reported from live play on 2026-08-12.
	 *
	 * <p>What the old rule was protecting is still protected, by {@link #topUp} rather than by
	 * dropping every position. A second line is refused whenever something else in the trip is
	 * already talking about the item's orders - a reprice, a cancel, a claim - because those carry a
	 * price this allocator does not know, and two lines on one item quoted from two sides of the mod
	 * is how a player ends up outbidding themselves. A position that is simply resting on top of the
	 * book has no such second opinion attached: the shortfall goes at the same price a fresh line
	 * would, which is where the book is now.
	 *
	 * @param orders bazaar orders resting on this item, which is how many slots it already holds
	 * @param units  units the position has already committed - what the orders were placed for, not
	 *               what is still unfilled. A part-filled order has already bought its filled units,
	 *               and sizing the top-up off what remains on the book would buy them twice
	 * @param topUp  whether the basket may offer the difference between {@code units} and what the
	 *               item is worth
	 */
	public record Position(int orders, long units, boolean topUp) {
		public Position {
			orders = Math.max(0, orders);
			units = Math.max(0L, units);
		}

		/** Nothing of this item on the book, which is what every item not in {@link Held} has. */
		public static Position none() {
			return new Position(0, 0L, false);
		}
	}

	/** Which shared resource ran out, i.e. what would have to change to deploy more. */
	public enum Bound {
		/** Every order slot is in use. Buying the Bazaar Flipper perk is what lifts this. */
		SLOTS,

		/** The coins ran out before the slots did. */
		CAPITAL,

		/** The day's NPC coin budget ran out. It refills at UTC midnight. */
		DAILY_CAP,

		/** Nothing ran out: the market has no more items worth resting an order on. */
		CANDIDATES
	}

	/**
	 * One item's share of the basket.
	 *
	 * @param units        units to buy, after every shared budget was applied
	 * @param orders       order slots this line occupies
	 * @param capital      coins it ties up, at the chase-inclusive unit cost
	 * @param profit       expected coins if the whole line fills
	 * @param restingUnits units of this item the account has already committed, which this line is
	 *                     the remainder of. Zero on a line that opens a position, and the number the
	 *                     row has to quote on one that finishes it: a player who placed one order of
	 *                     four needs to be told which three are left, not handed a fresh total
	 */
	public record Line(NpcPlan plan, long units, int orders, long capital, double profit,
			long restingUnits) {
		/** A line that opens a position, i.e. the shape every line had before top-ups existed. */
		public Line(NpcPlan plan, long units, int orders, long capital, double profit) {
			this(plan, units, orders, capital, profit, 0L);
		}

		/** Whether this adds to a position already on the book rather than opening one. */
		public boolean topUp() {
			return restingUnits > 0L;
		}

		/** Units the item will be holding once this line is placed, which is what it was sized to. */
		public long positionUnits() {
			return units + restingUnits;
		}

		/** Coins the NPC pays out for this line, which is what the daily cap is spent in. */
		public long npcPayout() {
			return Math.round(plan.npcPrice() * units);
		}

		public long loads() {
			return plan.loadsFor(units);
		}

		/**
		 * How the units divide into orders you can actually place, e.g. {@code 3 x 256 + 112}.
		 *
		 * <p>Shared with the reprice rows through {@link Stacking#orderSplit}, for the same reason
		 * {@link Basket#boundExplanation()} is assembled once: a place and a re-post of the same item
		 * are typed into the same box, and two copies of this arithmetic drift apart.
		 */
		public String orderSplit() {
			return Stacking.orderSplit(units, plan.unitsPerOrder());
		}
	}

	/**
	 * A whole basket, sized and costed.
	 *
	 * @param slotsUsed      order slots the new lines want
	 * @param slotsAvailable order slots the basket was allowed to use, settings and account together
	 * @param slotsOnAccount order slots the account actually has, which is only different when
	 *                       {@code npcMaxOrderSlots} caps it below that
	 * @param bankroll       coins it was allowed to spend, read at plan time
	 * @param held           what was already resting when this was allocated, which is what the new
	 *                       lines were sized around
	 * @param bound          which shared resource ran out first
	 */
	public record Basket(
			List<Line> lines,
			int slotsUsed,
			int slotsAvailable,
			int slotsOnAccount,
			long capital,
			long bankroll,
			double profit,
			long npcPayout,
			long loads,
			double restingHours,
			Held held,
			Bound bound
	) {
		public Basket {
			lines = List.copyOf(lines);
			held = held == null ? Held.nothing() : held;
		}

		public static Basket empty(StrategyContext context) {
			return new Basket(List.of(), 0, context.npc().orderSlots(context.fees()),
					context.fees().bazaarOrderSlots(), 0L, context.bankroll(), 0.0d, 0L, 0L,
					context.npc().restingHours(), Held.nothing(), Bound.CANDIDATES);
		}

		public boolean isEmpty() {
			return lines.isEmpty();
		}

		/** Slots left after what is already resting, which is what the new lines were fitted into. */
		public int slotsFree() {
			return Math.max(0, slotsAvailable - held.orders());
		}

		/** Coins left after what is already resting, which is what the new lines were sized against. */
		public long bankrollFree() {
			return Math.max(0L, bankroll - held.capital());
		}

		/** Coins an hour across the resting window, the same axis a single candidate is ranked on. */
		public double profitPerHour() {
			return restingHours <= 0.0d ? 0.0d : profit / restingHours;
		}

		/** Profit over coins deployed, as a fraction. */
		public double returnOnCapital() {
			return capital <= 0L ? 0.0d : profit / capital;
		}

		/** Share of the bankroll the basket asks for. */
		public double capitalShare() {
			return bankroll <= 0L ? 0.0d : (double) capital / bankroll;
		}

		/**
		 * What ran out and what could be done about it, in one sentence.
		 *
		 * <p>Here rather than in each of the two things that display a basket, so chat and the screen
		 * cannot end up telling a player different stories about the same allocation.
		 */
		/**
		 * Whether the settings, rather than the account, are what capped the slots.
		 *
		 * <p>Worth its own answer because it is the one slot limit that costs nothing to lift and is
		 * invisible from inside the game. Measured on the live book on 2026-08-11 with this user's
		 * own settings: 14 slots made 25.1M a cycle where the 21 the account had made 32.3M.
		 */
		public boolean slotsCappedBySettings() {
			return slotsAvailable < slotsOnAccount;
		}

		public String boundExplanation() {
			return switch (bound) {
				case SLOTS -> slotsCappedBySettings()
						? "Order slots are what ran out, and NPC order slots in settings is holding "
								+ "you to " + slotsAvailable + " of the " + slotsOnAccount
								+ " your account has. Set it to 0 for all of them."
						: held.orders() == 0
						? "Order slots are what ran out. Each Bazaar Flipper level adds seven."
						: "Order slots are what ran out, and " + held.orders() + " of "
								+ slotsAvailable + " are already resting. Cancelling one that is "
								+ "past its window is the cheapest way to free one.";
				case CAPITAL -> "Coins ran out with " + (slotsFree() - slotsUsed)
						+ " order slots still free.";
				case DAILY_CAP -> "The day's NPC coin budget ran out. It refills at UTC midnight.";
				case CANDIDATES -> "Nothing else on the book clears the filters, so this is the "
						+ "market limiting the basket rather than your account.";
			};
		}
	}

	/**
	 * Allocates every shared resource across the items worth resting an order on.
	 *
	 * <p>Ranks on {@link NpcPlan#profitPerLoad()} and takes each item at the largest size it is
	 * worth on its own, until a shared budget runs out. An item that would earn less over the whole
	 * window than {@code minProfitPerFlip} is skipped rather than allowed to occupy a slot.
	 */
	public static Basket plan(StrategyContext context) {
		return plan(context, Held.nothing());
	}

	/**
	 * The same allocation, over what is left after {@code held}.
	 *
	 * <p>The overload that matters in play. A basket planned against an empty account is only right
	 * once per cycle, on the first trip; every trip after that, some slots and some coins are
	 * already committed, and a plan that does not know it is a plan for an account you do not have.
	 */
	public static Basket plan(StrategyContext context, Held held) {
		List<NpcPlan> plans = new ArrayList<>(NpcFlipStrategy.restingPlans(context));

		if (plans.isEmpty()) {
			return withHeld(Basket.empty(context), held);
		}

		plans.sort(Comparator.comparingDouble(NpcPlan::profitPerLoad).reversed());

		NpcContext npc = context.npc();
		int slotsAvailable = npc.orderSlots(context.fees());
		// Read here rather than held anywhere: a basket planned before a bankroll change must not
		// survive it, and the only way to guarantee that is to never keep the number.
		long bankroll = context.bankroll();

		int slotsLeft = Math.max(0, slotsAvailable - held.orders());
		long coinsLeft = Math.max(0L, bankroll - held.capital());
		long payoutLeft = npc.capRemaining();

		List<Line> lines = new ArrayList<>();
		long capital = 0L;
		long payout = 0L;
		long loads = 0L;
		double profit = 0.0d;
		boolean shortOfCoins = false;

		for (NpcPlan plan : plans) {
			if (slotsLeft <= 0) {
				break;
			}

			// Something else in the trip is already talking about this item's orders, at a price this
			// allocator does not know. Offering a line as well would have the player bid against
			// their own order.
			if (held.closed(plan.itemId())) {
				continue;
			}

			Position position = held.position(plan.itemId());

			// Only the shortfall. maxUnits is the size the position is worth in total, so an item
			// with 256 of its 1,024 already resting is worth 768 more and not another 1,024.
			long room = Math.max(0L, plan.maxUnits() - position.units());

			// What is left of the position is drift rather than a remainder, so it is not offered.
			// See minimumTopUp: an order slot and a six-click place flow for one unit is a worse
			// trip than the one unit is worth.
			if (position.units() > 0L && room < minimumTopUp(plan.maxUnits())) {
				continue;
			}

			// The cap bounds this line's slots, never the basket's: what it does not spend here is
			// left for the next item down the list, which is the whole point of capping one. The
			// orders already resting count against it, for the same reason the units do.
			long wanted = Math.min(room,
					(long) npc.ordersForItem(slotsLeft, position.orders()) * plan.unitsPerOrder());
			long affordable = affordableUnits(coinsLeft, plan.unitCost());
			long withinCap = payoutUnits(payoutLeft, plan.npcPrice());
			long units = Math.min(wanted, Math.min(affordable, withinCap));

			// Whether the bankroll cut this line short, including when it only truncated one. A run
			// where every line was trimmed for coins but none was priced out entirely is still a
			// run where more coins would have bought more, and reporting it as the market's fault
			// sends the player looking in the wrong place.
			shortOfCoins |= affordable < wanted && affordable <= withinCap;

			if (units <= 0L) {
				// Not a reason to stop: a cheaper item further down the list may still fit in what
				// is left, and it is the same slot either way.
				continue;
			}

			double lineProfit = plan.unitNetProfit() * units;

			// What the line makes in total, on the same reading of minProfitPerFlip as every other
			// filter in the mod. See NpcFlipStrategy for why this stopped being a rate.
			//
			// Judged over the whole position rather than over the remainder, because one item's
			// position is one flip and the floor is per flip. Otherwise the last part-order of a
			// four-order line - 112 units, a quarter of the profit - could fall under a floor the
			// 1,024-unit flip cleared, and the row would vanish with the position unfinished, which
			// is the failure this whole top-up path exists to fix.
			if (plan.unitNetProfit() * (units + position.units()) < context.minProfitPerFlip()) {
				continue;
			}

			int orders = plan.ordersFor(units);
			long lineCapital = Math.round(plan.unitCost() * units);
			Line line = new Line(plan, units, orders, lineCapital, lineProfit, position.units());

			lines.add(line);
			slotsLeft -= orders;
			coinsLeft -= lineCapital;
			capital += lineCapital;
			profit += lineProfit;
			payout += line.npcPayout();
			loads += line.loads();

			if (payoutLeft != NpcContext.CAP_UNLIMITED) {
				payoutLeft = Math.max(0L, payoutLeft - line.npcPayout());
			}
		}

		return new Basket(lines, slotsAvailable - held.orders() - slotsLeft, slotsAvailable,
				context.fees().bazaarOrderSlots(), capital, bankroll, profit, payout, loads,
				npc.restingHours(), held,
				bound(slotsLeft, payoutLeft, coinsLeft, shortOfCoins, lines.size(), plans.size()));
	}

	/**
	 * An empty basket, told what it was empty against.
	 *
	 * <p>"Nothing to place" and "nothing to place because every slot is already working" are
	 * different answers, and only one of them is a reason to go and look at the book.
	 */
	private static Basket withHeld(Basket basket, Held held) {
		return new Basket(basket.lines(), basket.slotsUsed(), basket.slotsAvailable(),
				basket.slotsOnAccount(), basket.capital(), basket.bankroll(), basket.profit(),
				basket.npcPayout(), basket.loads(), basket.restingHours(), held,
				held.orders() >= basket.slotsAvailable() ? Bound.SLOTS : basket.bound());
	}

	/**
	 * What ran out, in the order a player can do something about it.
	 *
	 * <p>Slots first because they are the resource the whole design is about, then the day's budget,
	 * then coins. "Candidates" is the honest answer when none of the three ran out, and it is not a
	 * failure: it means the market, not the account, is what limits today.
	 */
	private static Bound bound(int slotsLeft, long payoutLeft, long coinsLeft, boolean shortOfCoins,
			int taken, int offered) {
		if (slotsLeft <= 0) {
			return Bound.SLOTS;
		}

		if (payoutLeft <= 0L) {
			return Bound.DAILY_CAP;
		}

		return shortOfCoins || (coinsLeft <= 0L && taken < offered) ? Bound.CAPITAL : Bound.CANDIDATES;
	}

	/**
	 * The smallest shortfall worth an order slot: a twentieth of what the position is worth in total.
	 *
	 * <p><b>{@code maxUnits} is recomputed off the live book every trip</b>, out of flows that move
	 * between one trip and the next, so it drifts by a percent or two even when nothing about the
	 * position changed. Without a floor the difference comes back as a line of its own: measured live
	 * 2026-08-14, an order placed for its full size returned on the next trip as "place 1", which
	 * spends an order slot and the whole six-click place flow on one unit.
	 *
	 * <p><b>A twentieth cannot swallow a whole order.</b> An account has at most fourteen order slots,
	 * so a position is at most fourteen orders and one of them is never smaller than a fourteenth of
	 * it - which is above this floor whatever the item. What the floor removes is the part-order left
	 * over from the arithmetic moving, never an order the player still has to type.
	 *
	 * <p>The floor is at least one unit, so a position small enough that one unit is a twentieth of it
	 * is never blocked by this.
	 */
	private static long minimumTopUp(long maxUnits) {
		return Math.max(1L, maxUnits / 20L);
	}

	private static long affordableUnits(long coins, double unitCost) {
		return unitCost <= 0.0d ? 0L : (long) (coins / unitCost);
	}

	private static long payoutUnits(long payoutLeft, double npcPrice) {
		if (payoutLeft == NpcContext.CAP_UNLIMITED) {
			return Long.MAX_VALUE;
		}

		return npcPrice <= 0.0d ? 0L : (long) (payoutLeft / npcPrice);
	}
}
