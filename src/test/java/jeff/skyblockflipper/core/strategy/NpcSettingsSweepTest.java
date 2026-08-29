/*
 * Skyblock Flipper - a Hypixel Skyblock flipping advisor mod.
 * Copyright (C) 2026 SoupChugger
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package jeff.skyblockflipper.core.strategy;

import jeff.skyblockflipper.core.api.HypixelApi;
import jeff.skyblockflipper.core.model.BazaarSample;
import jeff.skyblockflipper.core.model.BazaarSnapshot;
import jeff.skyblockflipper.core.model.ItemCatalog;
import jeff.skyblockflipper.core.pricing.Fees;
import jeff.skyblockflipper.core.tape.BazaarTape;
import jeff.skyblockflipper.core.valuation.NpcEdgeHistory;
import jeff.skyblockflipper.core.valuation.PriceHistory;
import jeff.skyblockflipper.core.valuation.NpcEdgeSnapshot;
import jeff.skyblockflipper.core.valuation.TrendSnapshot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the margin floor and the slot count are worth, over a whole day rather than one basket.
 * Run with {@code ./gradlew test -PtapeBacktest}.
 *
 * <p>{@code docs/npc-flipping.md} settles the floor twice and the two answers differ. A day-long
 * simulation under the 500M cap peaked at 15%; a static one-basket sweep at the user's bankroll
 * peaked around 10% and the doc flags the difference as unresolved. They are the same trade-off
 * measured at different bankrolls: a fat floor only pays when the cap is what you are short of,
 * because a thin floor spends more of it per cycle for less profit per coin.
 *
 * <p>This measures the whole day, which is the only unit either answer can be given in. A cycle is
 * one basket planned against the live book, held for {@code npcRestingHours} and repriced every
 * {@code npcCheckInMinutes}; a day is {@code 24 / restingHours} cycles, and the NPC payout of each
 * is charged against the daily cap so a basket that would overrun it is truncated exactly the way
 * {@code NpcBasket} truncates one.
 *
 * <p><b>Every cycle is planned against the same book snapshot,</b> so later cycles are optimistic -
 * their orders would in reality compete with the earlier ones for the same dump flow. The doc says
 * the same thing about its own day figures. What survives that is the comparison between settings,
 * which is what this is for; the absolute coins are an upper bound.
 */
@EnabledIfSystemProperty(named = "skyblockflipper.tapeBacktest", matches = "true")
class NpcSettingsSweepTest {
	private static final String DEFAULT_TAPE_DIR = "run/config/skyblock-flipper/bazaar-tape";

	/** The window {@code MarketPoller} builds edges over, so persistence means what it means. */
	private static final Duration HISTORY = Duration.ofDays(3);

	private static final int TAPE_DAYS = 14;

	/** The user's live settings on 2026-08-11, which is what a candidate change is measured against. */
	private static final long BANKROLL = 800_000_000L;
	private static final int BAZAAR_FLIPPER_LEVEL = 1;
	private static final double RESTING_HOURS = 8.0d;
	private static final Duration CHECK_IN = Duration.ofMinutes(30);
	private static final long DAILY_CAP = 500_000_000L;
	private static final int LIVE_SLOTS = 14;
	private static final double LIVE_FLOOR = 0.10d;

	/** The other two limits on a line's size, which this used to leave unlimited. */
	private static final long MIN_PROFIT_PER_FLIP = 50_000L;
	private static final double MAX_CAPITAL_SHARE = 0.25d;

	/** Matching {@code FlipperConfig.trendWindowHours}, which is what measures displacement. */
	private static final Duration TREND_WINDOW = Duration.ofHours(24L);

	private static final double[] FLOORS = {0.05d, 0.075d, 0.09d, 0.10d, 0.105d, 0.11d, 0.115d,
			0.12d, 0.125d, 0.15d, 0.20d, 0.30d};
	private static final int[] SLOTS = {14, 21};

	/** Bankrolls a default has to be right at, from a new player's purse to the user's. */
	private static final long[] BANKROLLS = {
			25_000_000L, 100_000_000L, 250_000_000L, 500_000_000L, 800_000_000L, 2_000_000_000L};

	/**
	 * Caps on the orders one item's line may take, which is what the click cost of a basket is paid
	 * in. Zero is {@link NpcContext#UNLIMITED_ORDERS_PER_ITEM}, the sizing every other measurement
	 * in {@code docs/npc-flipping.md} was taken under.
	 */
	private static final int[] ORDER_CAPS = {1, 2, NpcContext.UNLIMITED_ORDERS_PER_ITEM};

	/**
	 * How often an order actually gets pushed back to the top, which is playtime rather than a
	 * setting. 30 minutes is what every other measurement here assumes.
	 */
	private static final Duration[] CHECK_INS = {
			Duration.ofMinutes(30L), Duration.ofHours(1L), Duration.ofHours(2L),
			Duration.ofHours(4L), Duration.ofHours(8L), Duration.ofHours(12L),
			Duration.ofHours(24L)};

	/** Resting windows, to see whether leaving orders longer buys back what absence costs. */
	private static final double[] RESTING_WINDOWS = {4.0d, 8.0d, 12.0d, 24.0d};

	/**
	 * Profit floors spanning both readings of {@code minProfitPerFlip}: 6,250 is the user's 50,000
	 * divided by the 8-hour window, and 400,000 is it multiplied by one.
	 */
	private static final long[] PROFIT_FLOORS = {
			0L, 6_250L, 25_000L, 50_000L, 100_000L, 200_000L, 400_000L, 1_000_000L};

	/** One setting, run out to a day. */
	private record Day(double profit, long capital, long payout, long loads, int lines, int orders,
			int cycles, boolean capped) {
		double roc() {
			return capital <= 0L ? 0.0d : profit / capital;
		}

		/** The unit the ADR's open measurement is stated in: one basket, held for the window. */
		double profitPerCycle() {
			return cycles <= 0 ? 0.0d : profit / cycles;
		}
	}

	@Test
	void reportsWhatEachFloorAndSlotCountIsWorthOverADay() throws Exception {
		HypixelApi api = new HypixelApi();
		ItemCatalog catalog = api.fetchItems();
		BazaarSnapshot bazaar = api.fetchBazaar();
		Measured measured = measureFromTape(catalog);
		NpcEdgeSnapshot edges = measured.edges();

		trends = measured.trends();

		assertTrue(edges.size() > 0, "no NPC edges built from the tape at " + tapeDir()
				+ " - point -PbazaarTapeDir at a directory with day files in it");
		assertTrue(trends.productsWithMeasuredFills() > 0, "no displacement measured from the tape, so every "
				+ "line would be sized off the unmeasured fallback and the check-in horizon would "
				+ "do nothing");

		System.out.printf("%nNPC settings sweep: %d products with a measured edge, %d with measured "
						+ "displacement, %d on the book, bankroll %,d, Bazaar Flipper %d%n",
				edges.size(), trends.productsWithMeasuredFills(), bazaar.products().size(), BANKROLL,
				BAZAAR_FLIPPER_LEVEL);
		System.out.printf("  cycle = %.0fh resting, reprice every %d min, cap %,d/day%n",
				RESTING_HOURS, CHECK_IN.toMinutes(), DAILY_CAP);
		System.out.printf("%n  %6s %6s %12s %12s %6s %12s %7s %6s%n",
				"slots", "floor", "profit/day", "capital", "ROC", "payout", "loads", "lines");

		Day live = null;
		Day best = null;
		double bestFloor = 0.0d;
		int bestSlots = 0;

		for (int slots : SLOTS) {
			for (double floor : FLOORS) {
				Day day = simulate(bazaar, catalog, edges, slots, floor);

				System.out.printf("  %6d %6.3f %12.1fM %11.1fM %5.0f%% %11.1fM %7d %6d%s%n",
						slots, floor, day.profit() / 1e6, day.capital() / 1e6, day.roc() * 100.0d,
						day.payout() / 1e6, day.loads(), day.lines(),
						day.capped() ? "  cap" : "");

				if (best == null || day.profit() > best.profit()) {
					best = day;
					bestFloor = floor;
					bestSlots = slots;
				}

				if (slots == LIVE_SLOTS && floor == LIVE_FLOOR) {
					live = day;
				}
			}
		}

		// The sweep is a curve with a cliff in it, and a cliff means one item. A floor is only worth
		// recommending if you know whether it sits on a plateau or on the edge of a single line.
		dumpBasket(bazaar, catalog, edges, LIVE_SLOTS, 0.10d);

		// And the floor that suits this account is not automatically the one to ship as a default:
		// a thin floor only pays while the daily cap is what you run out of, and a player whose
		// coins run out first wants the fat one.
		sweepBankrolls(bazaar, catalog, edges);

		// What a max-orders-per-item cap costs, which ADR 0002 left open. The cap buys clicks: an
		// unstackable line of 1000 units is four orders, four rows and eight clicks to reprice.
		sweepOrderCaps(bazaar, catalog, edges);

		// And what being there is worth, which no setting can buy.
		sweepCheckIn(bazaar, catalog, edges);

		// What the profit floor is worth, and therefore how much it matters that the two halves of
		// the mod compare it against different quantities.
		sweepProfitFloor(bazaar, catalog, edges);

		System.out.printf("%n  live setting: %d slots, floor %.2f -> %.1fM/day%n",
				LIVE_SLOTS, LIVE_FLOOR, live.profit() / 1e6);
		System.out.printf("  best measured: %d slots, floor %.3f -> %.1fM/day (%+.0f%%)%n%n",
				bestSlots, bestFloor, best.profit() / 1e6,
				(best.profit() / live.profit() - 1.0d) * 100.0d);

		assertTrue(best.profit() >= live.profit(), "the sweep found nothing at least as good as "
				+ "the live setting, which means it is not sweeping what it thinks it is");
	}

	/** The first cycle's basket at one setting, biggest line first. */
	private static void dumpBasket(BazaarSnapshot bazaar, ItemCatalog catalog, NpcEdgeSnapshot edges,
			int slots, double floor) {
		NpcContext npc = new NpcContext(edges, floor, CHECK_IN, RESTING_HOURS, slots, DAILY_CAP);
		NpcBasket.Basket basket = NpcBasket.plan(
				context(bazaar, catalog, npc, BANKROLL, MIN_PROFIT_PER_FLIP));

		System.out.printf("%n  first basket at %d slots, floor %.3f - %s, %.1fM profit on "
						+ "%.1fM payout%n", slots, floor, basket.bound(), basket.profit() / 1e6,
				basket.npcPayout() / 1e6);
		System.out.printf("  %-28s %8s %10s %9s %9s %6s%n",
				"item", "margin", "units", "profit", "payout", "loads");

		basket.lines().stream()
				.sorted(Comparator.comparingLong(NpcBasket.Line::npcPayout).reversed())
				.limit(10)
				.forEach(line -> System.out.printf("  %-28s %7.1f%% %10d %8.1fM %8.1fM %6d%n",
						line.plan().displayName(), line.plan().marginRatio() * 100.0d, line.units(),
						line.profit() / 1e6, line.npcPayout() / 1e6, line.loads()));
	}

	/**
	 * One day: a fresh basket every resting window, each charged against what is left of the cap.
	 *
	 * <p>Fills are whatever {@link NpcBasket} sized the line for, which is
	 * {@link jeff.skyblockflipper.core.pricing.FillModel} at the check-in horizon over the resting
	 * window. Repricing is therefore in the numbers already - it is the assumption that the order is
	 * back at the top of the book every {@code CHECK_IN} - rather than simulated click by click.
	 */
	private static Day simulate(BazaarSnapshot bazaar, ItemCatalog catalog, NpcEdgeSnapshot edges,
			int slots, double floor) {
		return simulate(bazaar, catalog, edges, slots, floor, BANKROLL);
	}

	private static Day simulate(BazaarSnapshot bazaar, ItemCatalog catalog, NpcEdgeSnapshot edges,
			int slots, double floor, long bankroll) {
		return simulate(bazaar, catalog, edges, slots, floor, bankroll,
				NpcContext.UNLIMITED_ORDERS_PER_ITEM);
	}

	private static Day simulate(BazaarSnapshot bazaar, ItemCatalog catalog, NpcEdgeSnapshot edges,
			int slots, double floor, long bankroll, int ordersPerItem) {
		return simulate(bazaar, catalog, edges, slots, floor, bankroll, ordersPerItem, CHECK_IN,
				RESTING_HOURS);
	}

	private static Day simulate(BazaarSnapshot bazaar, ItemCatalog catalog, NpcEdgeSnapshot edges,
			int slots, double floor, long bankroll, int ordersPerItem, Duration checkIn,
			double restingHours) {
		return simulate(bazaar, catalog, edges, slots, floor, bankroll, ordersPerItem, checkIn,
				restingHours, MIN_PROFIT_PER_FLIP);
	}

	private static Day simulate(BazaarSnapshot bazaar, ItemCatalog catalog, NpcEdgeSnapshot edges,
			int slots, double floor, long bankroll, int ordersPerItem, Duration checkIn,
			double restingHours, long minProfitPerFlip) {
		int cycles = (int) Math.max(1L, Math.round(24.0d / restingHours));
		long capLeft = DAILY_CAP;
		double profit = 0.0d;
		long capital = 0L;
		long payout = 0L;
		long loads = 0L;
		int lines = 0;
		int orders = 0;
		int ran = 0;
		boolean capped;

		for (int cycle = 0; cycle < cycles && capLeft > 0L; cycle++) {
			NpcContext npc = new NpcContext(edges, floor, checkIn, restingHours, slots, capLeft,
					ordersPerItem);
			NpcBasket.Basket basket = NpcBasket.plan(
					context(bazaar, catalog, npc, bankroll, minProfitPerFlip));

			if (basket.lines().isEmpty()) {
				break;
			}

			profit += basket.profit();
			capital += basket.capital();
			payout += basket.npcPayout();
			loads += basket.loads();
			lines += basket.lines().size();
			orders += basket.slotsUsed();
			capLeft -= basket.npcPayout();
			ran++;
		}

		// Whether the day ran out of cap, not whether one basket was truncated by it. A day can
		// spend the last coin of the cap with every individual basket reporting SLOTS.
		capped = capLeft <= 0L;

		return new Day(profit, capital, payout, loads, lines, orders, ran, capped);
	}

	/**
	 * Measured displacement, set once from the tape before any simulation runs.
	 *
	 * <p>Static because every {@code simulate} overload would otherwise carry it through purely to
	 * hand it back to one constructor, and because it is read-only after the first statement of the
	 * only test in this file.
	 */
	private static TrendSnapshot trends = TrendSnapshot.empty();

	/**
	 * The context a basket is planned in, with the user's real per-flip limits rather than none.
	 *
	 * <p>{@code minProfitPerFlip} and {@code maxCapitalShare} were both left at "unlimited" here,
	 * which quietly measured a player who takes every line at any size. They are two of the three
	 * things that decide how big a line gets, so a sweep without them is not sweeping the account
	 * it claims to.
	 */
	private static StrategyContext context(BazaarSnapshot bazaar, ItemCatalog catalog,
			NpcContext npc, long bankroll, long minProfitPerFlip) {
		return new StrategyContext(bazaar, catalog, List.of(), trends,
				new Fees(BAZAAR_FLIPPER_LEVEL, false), bankroll, minProfitPerFlip, 0.0d, 0.0d,
				npc.checkIn(), MAX_CAPITAL_SHARE, npc);
	}

	/**
	 * The best floor at each bankroll, which is the question a shipped default has to answer.
	 *
	 * <p>The floor and the bankroll are the same lever seen from two ends. A thin floor deploys far
	 * more capital for less profit per coin, so it wins only while there are idle coins and unspent
	 * daily cap to put them in; once the bankroll is what runs out, the fat floor's higher return on
	 * capital wins outright.
	 */
	private static void sweepBankrolls(BazaarSnapshot bazaar, ItemCatalog catalog,
			NpcEdgeSnapshot edges) {
		System.out.printf("%n  best floor by bankroll, at %d slots%n", LIVE_SLOTS);
		System.out.printf("  %10s %8s %12s %12s %14s%n",
				"bankroll", "floor", "profit/day", "capital", "vs floor 0.15");

		for (long bankroll : BANKROLLS) {
			Day atLive = simulate(bazaar, catalog, edges, LIVE_SLOTS, LIVE_FLOOR, bankroll);
			Day best = null;
			double bestFloor = 0.0d;

			for (double floor : FLOORS) {
				Day day = simulate(bazaar, catalog, edges, LIVE_SLOTS, floor, bankroll);

				if (best == null || day.profit() > best.profit()) {
					best = day;
					bestFloor = floor;
				}
			}

			System.out.printf("  %9.0fM %8.3f %11.1fM %11.1fM %+12.0f%%%n", bankroll / 1e6,
					bestFloor, best.profit() / 1e6, best.capital() / 1e6,
					(best.profit() / atLive.profit() - 1.0d) * 100.0d);
		}
	}

	/**
	 * What a max-orders-per-item cap costs, per cycle, which is the open measurement in ADR 0002.
	 *
	 * <p>The cap is the only lever on how much clicking a basket is. Slots and coins are already
	 * allocated across items; orders are what the player actually places and repices, and one
	 * unstackable item can take four of them for a single line. Capping frees those slots for the
	 * next item down the ranking, so the cost is the difference between the item the slots were
	 * taken from and the item they go to - which is a question about this book, not about the design.
	 *
	 * <p>Reported at both slot counts and across bankrolls, because a cap can only cost anything
	 * while slots are the binding constraint. An account short of coins never fills its slots, so
	 * capping the per-item share changes nothing it would have placed anyway.
	 */
	private static void sweepOrderCaps(BazaarSnapshot bazaar, ItemCatalog catalog,
			NpcEdgeSnapshot edges) {
		System.out.printf("%n  max orders per item, at floor %.2f%n", LIVE_FLOOR);
		System.out.printf("  %6s %6s %12s %12s %8s %6s %7s %7s %14s%n", "slots", "cap", "profit/day",
				"profit/cycle", "capital", "lines", "orders", "loads", "vs uncapped");

		for (int slots : SLOTS) {
			Day uncapped = simulate(bazaar, catalog, edges, slots, LIVE_FLOOR, BANKROLL,
					NpcContext.UNLIMITED_ORDERS_PER_ITEM);

			for (int cap : ORDER_CAPS) {
				Day day = simulate(bazaar, catalog, edges, slots, LIVE_FLOOR, BANKROLL, cap);

				System.out.printf("  %6d %6s %11.1fM %11.1fM %7.1fM %6d %7d %7d %+13.1f%%%n",
						slots, cap == NpcContext.UNLIMITED_ORDERS_PER_ITEM ? "none" : cap,
						day.profit() / 1e6, day.profitPerCycle() / 1e6, day.capital() / 1e6,
						day.lines(), day.orders(), day.loads(),
						(day.profit() / uncapped.profit() - 1.0d) * 100.0d);
			}
		}

		// Across the floors as well, because the cap and the floor both decide how many items a
		// basket holds, and a result at one floor could be either lever's doing.
		System.out.printf("%n  max orders per item by floor, at %d slots (M/cycle)%n", LIVE_SLOTS);
		System.out.printf("  %8s %14s %14s %10s %14s%n",
				"floor", "cap 1", "cap 2", "uncapped", "lines 1/2/none");

		for (double floor : FLOORS) {
			Day one = simulate(bazaar, catalog, edges, LIVE_SLOTS, floor, BANKROLL, 1);
			Day two = simulate(bazaar, catalog, edges, LIVE_SLOTS, floor, BANKROLL, 2);
			Day none = simulate(bazaar, catalog, edges, LIVE_SLOTS, floor, BANKROLL,
					NpcContext.UNLIMITED_ORDERS_PER_ITEM);

			System.out.printf("  %8.3f %8.1f%+5.0f%% %8.1f%+5.0f%% %10.1f %14s%n", floor,
					one.profitPerCycle() / 1e6, (one.profit() / none.profit() - 1.0d) * 100.0d,
					two.profitPerCycle() / 1e6, (two.profit() / none.profit() - 1.0d) * 100.0d,
					none.profitPerCycle() / 1e6,
					one.lines() + "/" + two.lines() + "/" + none.lines());
		}

		System.out.printf("%n  max orders per item by bankroll, at %d slots, floor %.2f%n",
				LIVE_SLOTS, LIVE_FLOOR);
		System.out.printf("  %10s %14s %14s %14s%n",
				"bankroll", "cap 1", "cap 2", "uncapped (M/cycle)");

		for (long bankroll : BANKROLLS) {
			Day one = simulate(bazaar, catalog, edges, LIVE_SLOTS, LIVE_FLOOR, bankroll, 1);
			Day two = simulate(bazaar, catalog, edges, LIVE_SLOTS, LIVE_FLOOR, bankroll, 2);
			Day none = simulate(bazaar, catalog, edges, LIVE_SLOTS, LIVE_FLOOR, bankroll,
					NpcContext.UNLIMITED_ORDERS_PER_ITEM);

			System.out.printf("  %9.0fM %8.1f%+5.0f%% %8.1f%+5.0f%% %14.1f%n", bankroll / 1e6,
					one.profitPerCycle() / 1e6,
					(one.profit() / none.profit() - 1.0d) * 100.0d,
					two.profitPerCycle() / 1e6,
					(two.profit() / none.profit() - 1.0d) * 100.0d,
					none.profitPerCycle() / 1e6);
		}
	}

	/**
	 * What playtime is worth, which is the one input the player cannot change by editing settings.
	 *
	 * <p>An order only collects while it is at the front of the book, and it only gets back there
	 * when you push it. So the check-in interval is not really a setting - it is how often you are
	 * actually there, and {@code NpcFlipStrategy} sizes every line at that horizon through
	 * {@link jeff.skyblockflipper.core.pricing.FillModel}. Every other number in this file assumes
	 * 30 minutes, which is 48 trips a day.
	 *
	 * <p>The mapping to real life: pushing orders every 30 minutes while you play is
	 * {@code 2 x playHours} pushes a day, so the honest interval is {@code 24 / (2 x playHours)}.
	 * Three hours a day is a four-hour effective interval, not a thirty-minute one.
	 *
	 * <p>Reported per item as well as in total, because the loss is not uniform: displacement is
	 * measured per product, so a book that turns over every nine minutes punishes an absence that a
	 * book turning over every ten hours does not notice.
	 */
	private static void sweepCheckIn(BazaarSnapshot bazaar, ItemCatalog catalog,
			NpcEdgeSnapshot edges) {
		System.out.printf("%n  what presence is worth, at %d slots, floor %.2f, %.0fh resting%n",
				LIVE_SLOTS, LIVE_FLOOR, RESTING_HOURS);
		System.out.printf("  %10s %12s %12s %10s %8s %6s %12s%n", "check-in", "~play/day",
				"profit/day", "capital", "payout", "lines", "vs 30min");

		Day base = null;

		for (Duration checkIn : CHECK_INS) {
			Day day = simulate(bazaar, catalog, edges, LIVE_SLOTS, LIVE_FLOOR, BANKROLL,
					NpcContext.UNLIMITED_ORDERS_PER_ITEM, checkIn, RESTING_HOURS);

			if (base == null) {
				base = day;
			}

			// The playtime that produces this interval at a 30-minute cadence while you are on.
			double playHours = 24.0d / (checkIn.toMinutes() / 30.0d) / 2.0d;

			System.out.printf("  %9dm %11.1fh %11.1fM %9.1fM %7.1fM %6d %+11.0f%%%n",
					checkIn.toMinutes(), Math.min(playHours, 24.0d), day.profit() / 1e6,
					day.capital() / 1e6, day.payout() / 1e6, day.lines(),
					(day.profit() / base.profit() - 1.0d) * 100.0d);
		}

		// Whether a longer cycle recovers any of it: if you cannot push an order often, the other
		// lever is leaving it there longer, since the NPC price cannot move against you.
		System.out.printf("%n  longer cycles at a 4h check-in (3h/day of play), %d slots, floor %.2f%n",
				LIVE_SLOTS, LIVE_FLOOR);
		System.out.printf("  %10s %8s %12s %10s %6s%n",
				"resting", "cycles", "profit/day", "capital", "lines");

		for (double restingHours : RESTING_WINDOWS) {
			Day day = simulate(bazaar, catalog, edges, LIVE_SLOTS, LIVE_FLOOR, BANKROLL,
					NpcContext.UNLIMITED_ORDERS_PER_ITEM, Duration.ofHours(4L), restingHours);

			System.out.printf("  %9.0fh %8d %11.1fM %9.1fM %6d%n", restingHours, day.cycles(),
					day.profit() / 1e6, day.capital() / 1e6, day.lines());
		}

		// And whether the floor should move when you are away: a fatter margin is a cheaper way to
		// survive falling down the book than being there to stop it.
		System.out.printf("%n  best floor at a 4h check-in, %d slots%n", LIVE_SLOTS);
		System.out.printf("  %8s %12s %10s %6s%n", "floor", "profit/day", "capital", "lines");

		for (double floor : FLOORS) {
			Day day = simulate(bazaar, catalog, edges, LIVE_SLOTS, floor, BANKROLL,
					NpcContext.UNLIMITED_ORDERS_PER_ITEM, Duration.ofHours(4L), RESTING_HOURS);

			System.out.printf("  %8.3f %11.1fM %9.1fM %6d%n",
					floor, day.profit() / 1e6, day.capital() / 1e6, day.lines());
		}
	}

	/**
	 * What the profit floor is worth to a basket, across the range a player might set it to.
	 *
	 * <p>{@code minProfitPerFlip} is compared against a rate in {@code NpcBasket} and
	 * {@code NpcFlipStrategy} - profit over the resting window, per hour - and against a total in
	 * {@code NpcReprice} and the two non-NPC strategies, which is also what {@code ConfigSchema}
	 * describes it as. Making it mean one thing changes the effective NPC floor by a factor of
	 * {@code restingHours}, so this prices the whole range rather than the two endpoints: if the
	 * curve is flat the choice is free and should follow the documentation.
	 */
	private static void sweepProfitFloor(BazaarSnapshot bazaar, ItemCatalog catalog,
			NpcEdgeSnapshot edges) {
		System.out.printf("%n  profit floor, at %d slots, floor %.2f (the value NpcBasket compares "
				+ "against, whatever the units)%n", LIVE_SLOTS, LIVE_FLOOR);
		System.out.printf("  %12s %12s %10s %6s %8s%n",
				"min profit", "profit/day", "capital", "lines", "payout");

		for (long floor : PROFIT_FLOORS) {
			Day day = simulate(bazaar, catalog, edges, LIVE_SLOTS, LIVE_FLOOR, BANKROLL,
					NpcContext.UNLIMITED_ORDERS_PER_ITEM, CHECK_IN, RESTING_HOURS, floor);

			System.out.printf("  %11ss %11.1fM %9.1fM %6d %7.1fM%n", Long.toString(floor),
					day.profit() / 1e6, day.capital() / 1e6, day.lines(), day.payout() / 1e6);
		}

		// The two readings of the user's own 50,000, side by side: as a rate it excludes anything
		// under 400,000 over the window, as a total it excludes anything under 50,000.
		Day asRate = simulate(bazaar, catalog, edges, LIVE_SLOTS, LIVE_FLOOR, BANKROLL,
				NpcContext.UNLIMITED_ORDERS_PER_ITEM, CHECK_IN, RESTING_HOURS,
				MIN_PROFIT_PER_FLIP);
		Day asTotal = simulate(bazaar, catalog, edges, LIVE_SLOTS, LIVE_FLOOR, BANKROLL,
				NpcContext.UNLIMITED_ORDERS_PER_ITEM, CHECK_IN, RESTING_HOURS,
				Math.round(MIN_PROFIT_PER_FLIP / RESTING_HOURS));

		System.out.printf("%n  50,000 read as a rate: %.1fM/day, %d lines%n",
				asRate.profit() / 1e6, asRate.lines());
		System.out.printf("  50,000 read as a total: %.1fM/day, %d lines (%+.1f%%)%n",
				asTotal.profit() / 1e6, asTotal.lines(),
				(asTotal.profit() / asRate.profit() - 1.0d) * 100.0d);
	}

	/**
	 * Persistence, drift and displacement as the mod builds them, from the tape the mod writes.
	 *
	 * <p>Both histories in one pass, because reading the tape twice is the expensive half and
	 * because the two must describe the same days. {@link PriceHistory} is what carries
	 * {@link jeff.skyblockflipper.core.valuation.FillStats}, and without it every product falls back
	 * to {@code NpcFlipStrategy.UNMEASURED_FILL_SHARE}, which has no displacement rate in it at all:
	 * line sizes then ignore the check-in horizon completely and the whole sweep answers a question
	 * about a market where nobody ever outbids you.
	 */
	private record Measured(NpcEdgeSnapshot edges, TrendSnapshot trends) {
	}

	private static Measured measureFromTape(ItemCatalog catalog) throws Exception {
		NpcEdgeHistory edges = new NpcEdgeHistory(catalog, Instant.now(), HISTORY);
		PriceHistory prices = new PriceHistory(TREND_WINDOW);

		new BazaarTape(tapeDir(), Integer.MAX_VALUE).forEachRecent(TAPE_DAYS, (BazaarSample sample) -> {
			if (sample.bidPrice() > 0.0d) {
				edges.append(sample);
			}

			prices.append(sample);
		});

		return new Measured(edges.snapshot(), prices.snapshot());
	}

	private static Path tapeDir() {
		return Path.of(System.getProperty("skyblockflipper.bazaarTapeDir", DEFAULT_TAPE_DIR));
	}
}
