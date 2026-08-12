package jeff.skyblockflipper.core.strategy;

import jeff.skyblockflipper.core.api.HypixelApi;
import jeff.skyblockflipper.core.model.BazaarSample;
import jeff.skyblockflipper.core.model.BazaarSnapshot;
import jeff.skyblockflipper.core.model.ItemCatalog;
import jeff.skyblockflipper.core.pricing.Fees;
import jeff.skyblockflipper.core.tape.BazaarTape;
import jeff.skyblockflipper.core.valuation.NpcEdgeHistory;
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
	private static final double LIVE_FLOOR = 0.15d;

	private static final double[] FLOORS = {0.05d, 0.075d, 0.09d, 0.10d, 0.105d, 0.11d, 0.115d,
			0.12d, 0.125d, 0.15d, 0.20d, 0.30d};
	private static final int[] SLOTS = {14, 21};

	/** Bankrolls a default has to be right at, from a new player's purse to the user's. */
	private static final long[] BANKROLLS = {
			25_000_000L, 100_000_000L, 250_000_000L, 500_000_000L, 800_000_000L, 2_000_000_000L};

	/** One setting, run out to a day. */
	private record Day(double profit, long capital, long payout, long loads, int lines,
			int cycles, boolean capped) {
		double roc() {
			return capital <= 0L ? 0.0d : profit / capital;
		}
	}

	@Test
	void reportsWhatEachFloorAndSlotCountIsWorthOverADay() throws Exception {
		HypixelApi api = new HypixelApi();
		ItemCatalog catalog = api.fetchItems();
		BazaarSnapshot bazaar = api.fetchBazaar();
		NpcEdgeSnapshot edges = edgesFrom(catalog);

		assertTrue(edges.size() > 0, "no NPC edges built from the tape at " + tapeDir()
				+ " - point -PbazaarTapeDir at a directory with day files in it");

		System.out.printf("%nNPC settings sweep: %d products with a measured edge, %d on the book, "
						+ "bankroll %,d, Bazaar Flipper %d%n",
				edges.size(), bazaar.products().size(), BANKROLL, BAZAAR_FLIPPER_LEVEL);
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
		NpcBasket.Basket basket = NpcBasket.plan(context(bazaar, catalog, npc, BANKROLL));

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
		int cycles = (int) Math.max(1L, Math.round(24.0d / RESTING_HOURS));
		long capLeft = DAILY_CAP;
		double profit = 0.0d;
		long capital = 0L;
		long payout = 0L;
		long loads = 0L;
		int lines = 0;
		int ran = 0;
		boolean capped;

		for (int cycle = 0; cycle < cycles && capLeft > 0L; cycle++) {
			NpcContext npc = new NpcContext(edges, floor, CHECK_IN, RESTING_HOURS, slots, capLeft);
			NpcBasket.Basket basket = NpcBasket.plan(context(bazaar, catalog, npc, bankroll));

			if (basket.lines().isEmpty()) {
				break;
			}

			profit += basket.profit();
			capital += basket.capital();
			payout += basket.npcPayout();
			loads += basket.loads();
			lines += basket.lines().size();
			capLeft -= basket.npcPayout();
			ran++;
		}

		// Whether the day ran out of cap, not whether one basket was truncated by it. A day can
		// spend the last coin of the cap with every individual basket reporting SLOTS.
		capped = capLeft <= 0L;

		return new Day(profit, capital, payout, loads, lines, ran, capped);
	}

	private static StrategyContext context(BazaarSnapshot bazaar, ItemCatalog catalog,
			NpcContext npc, long bankroll) {
		return new StrategyContext(bazaar, catalog, List.of(), TrendSnapshot.empty(),
				new Fees(BAZAAR_FLIPPER_LEVEL, false), bankroll, 0L, 0.0d, 0.0d, CHECK_IN,
				StrategyContext.UNCAPPED, npc);
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

	/** Persistence and drift as the mod builds them, from the same tape the mod writes. */
	private static NpcEdgeSnapshot edgesFrom(ItemCatalog catalog) throws Exception {
		NpcEdgeHistory history = new NpcEdgeHistory(catalog, Instant.now(), HISTORY);

		new BazaarTape(tapeDir(), Integer.MAX_VALUE).forEachRecent(TAPE_DAYS, (BazaarSample sample) -> {
			if (sample.bidPrice() > 0.0d) {
				history.append(sample);
			}
		});

		return history.snapshot();
	}

	private static Path tapeDir() {
		return Path.of(System.getProperty("skyblockflipper.bazaarTapeDir", DEFAULT_TAPE_DIR));
	}
}
