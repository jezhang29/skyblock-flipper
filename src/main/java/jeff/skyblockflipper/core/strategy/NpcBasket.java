package jeff.skyblockflipper.core.strategy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

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
	 * @param units   units to buy, after every shared budget was applied
	 * @param orders  order slots this line occupies
	 * @param capital coins it ties up, at the chase-inclusive unit cost
	 * @param profit  expected coins if the whole line fills
	 */
	public record Line(NpcPlan plan, long units, int orders, long capital, double profit) {
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
		 * <p>Here rather than in chat and on the screen separately, for the same reason
		 * {@link Basket#boundExplanation()} is: two copies of this drift apart. A total on its own
		 * reads as one order, which is how a line of 500 Jungle Hearts got typed into a bazaar that
		 * takes 256 of them at a time.
		 */
		public String orderSplit() {
			long perOrder = plan.unitsPerOrder();

			if (perOrder <= 0L || units <= perOrder) {
				return String.valueOf(units);
			}

			long full = units / perOrder;
			long remainder = units % perOrder;
			String whole = full == 1L ? String.valueOf(perOrder) : full + " x " + perOrder;

			return remainder == 0L ? whole : whole + " + " + remainder;
		}
	}

	/**
	 * A whole basket, sized and costed.
	 *
	 * @param slotsAvailable order slots the basket was allowed to use, settings and account together
	 * @param bankroll       coins it was allowed to spend, read at plan time
	 * @param bound          which shared resource ran out first
	 */
	public record Basket(
			List<Line> lines,
			int slotsUsed,
			int slotsAvailable,
			long capital,
			long bankroll,
			double profit,
			long npcPayout,
			long loads,
			double restingHours,
			Bound bound
	) {
		public Basket {
			lines = List.copyOf(lines);
		}

		public static Basket empty(StrategyContext context) {
			return new Basket(List.of(), 0, context.npc().orderSlots(context.fees()), 0L,
					context.bankroll(), 0.0d, 0L, 0L, context.npc().restingHours(),
					Bound.CANDIDATES);
		}

		public boolean isEmpty() {
			return lines.isEmpty();
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
		public String boundExplanation() {
			return switch (bound) {
				case SLOTS -> "Order slots are what ran out. Each Bazaar Flipper level adds seven.";
				case CAPITAL -> "Coins ran out with " + (slotsAvailable - slotsUsed)
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
	 * worth on its own, until a shared budget runs out. An item that would earn less over the
	 * window than {@code minProfitPerFlip} an hour is skipped rather than allowed to occupy a slot.
	 */
	public static Basket plan(StrategyContext context) {
		List<NpcPlan> plans = new ArrayList<>(NpcFlipStrategy.restingPlans(context));

		if (plans.isEmpty()) {
			return Basket.empty(context);
		}

		plans.sort(Comparator.comparingDouble(NpcPlan::profitPerLoad).reversed());

		NpcContext npc = context.npc();
		int slotsAvailable = npc.orderSlots(context.fees());
		// Read here rather than held anywhere: a basket planned before a bankroll change must not
		// survive it, and the only way to guarantee that is to never keep the number.
		long bankroll = context.bankroll();

		int slotsLeft = slotsAvailable;
		long coinsLeft = bankroll;
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

			long wanted = Math.min(plan.maxUnits(), (long) slotsLeft * plan.unitsPerOrder());
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

			if (lineProfit / npc.restingHours() < context.minProfitPerFlip()) {
				continue;
			}

			int orders = plan.ordersFor(units);
			long lineCapital = Math.round(plan.unitCost() * units);
			Line line = new Line(plan, units, orders, lineCapital, lineProfit);

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

		return new Basket(lines, slotsAvailable - slotsLeft, slotsAvailable, capital, bankroll,
				profit, payout, loads, npc.restingHours(),
				bound(slotsLeft, payoutLeft, coinsLeft, shortOfCoins, lines.size(), plans.size()));
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
