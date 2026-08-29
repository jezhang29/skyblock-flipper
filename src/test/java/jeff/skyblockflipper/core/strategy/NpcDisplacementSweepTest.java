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
import jeff.skyblockflipper.core.model.BazaarProduct;
import jeff.skyblockflipper.core.model.BazaarSample;
import jeff.skyblockflipper.core.model.BazaarSnapshot;
import jeff.skyblockflipper.core.model.ItemCatalog;
import jeff.skyblockflipper.core.model.OrderLevel;
import jeff.skyblockflipper.core.pricing.Fees;
import jeff.skyblockflipper.core.tape.BazaarTape;
import jeff.skyblockflipper.core.valuation.NpcEdgeHistory;
import jeff.skyblockflipper.core.valuation.NpcEdgeSnapshot;
import jeff.skyblockflipper.core.valuation.PriceHistory;
import jeff.skyblockflipper.core.valuation.TrendSnapshot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What re-choosing the basket mid-window would be worth, which is the one question the settings
 * sweep cannot ask. Run with {@code ./gradlew test -PtapeBacktest}.
 *
 * <p>{@code NpcSettingsSweepTest} plans every cycle against one book snapshot, so within a cycle
 * nothing can change and the item set chosen at the start is trivially still the best one at the
 * end. That makes it blind to the only thing that would justify displacing a resting order: the
 * book moving under a different item until it outruns something already on the book.
 *
 * <p>This replays the real book from the tape at the check-in interval and runs the same allocator
 * at every step, under two policies:
 *
 * <ul>
 *   <li><b>Sticky</b> - what ships. A resting order keeps its slot until the book kills it, and the
 *       slots that come free are refilled from the whole market at the next check-in. Prices move
 *       and orders are repriced, because every step is re-planned against the live book. The one
 *       thing it will not do is cancel a live order to make room for a better item.
 *   <li><b>Reshuffle</b> - the proposal, valued generously. The whole basket is re-chosen from
 *       scratch at every check-in, with no cost for cancelling, no queue position lost and no
 *       partial fill stranded. Nothing an implementation could do would beat it.
 * </ul>
 *
 * <p><b>Sticky has to include the refill or the comparison is rigged.</b> An item whose edge closes
 * is cancelled by {@code NpcReprice} and its slot goes back to the basket, so a policy that simply
 * froze the opening set would be scored on orders the mod would already have replaced, and every
 * coin of that would be miscredited to displacement.
 *
 * <p><b>Measured 2026-08-12, over 9 windows of real tape: do not build it.</b> Reshuffling is worth
 * +6.4% in profit and every coin of that is bought by handing the NPC 7.3% more, on a day that
 * already wants to hand over 804M against a 500M cap. On the number that decides a capped day -
 * profit per coin of payout - reshuffling is <i>worse</i>, 38.24% against 38.57%. The freedom to
 * chase a better item is the freedom to reach the same ceiling slightly sooner at a slightly worse
 * price, and that is before charging it for the cancelled queue position it would really pay.
 *
 * <p>This is kept as the alarm rather than deleted. If the cap stops binding - a much bigger
 * bankroll is not what does it, but a Hypixel change to the cap would - the profit column becomes
 * the one that matters and the answer flips. The assertion at the end is what says so.
 */
@EnabledIfSystemProperty(named = "skyblockflipper.tapeBacktest", matches = "true")
class NpcDisplacementSweepTest {
	private static final String DEFAULT_TAPE_DIR = "run/config/skyblock-flipper/bazaar-tape";

	/** The window {@code MarketPoller} builds edges over. */
	private static final Duration HISTORY = Duration.ofDays(3);
	private static final Duration TREND_WINDOW = Duration.ofHours(24L);
	private static final int TAPE_DAYS = 14;

	/** The user's live settings on 2026-08-12, after the audit. */
	private static final long BANKROLL = 800_000_000L;
	private static final int BAZAAR_FLIPPER_LEVEL = 1;
	private static final double RESTING_HOURS = 8.0d;
	private static final Duration CHECK_IN = Duration.ofMinutes(30);
	private static final long DAILY_CAP = 500_000_000L;
	private static final int LIVE_SLOTS = 0;
	private static final double LIVE_FLOOR = 0.20d;
	private static final long MIN_PROFIT_PER_FLIP = 50_000L;
	private static final double MAX_CAPITAL_SHARE = 0.25d;

	/** Days of tape to replay step by step, most recent last. */
	private static final int REPLAY_DAYS = 3;

	private static TrendSnapshot trends = TrendSnapshot.empty();

	/** One replayed step: the book as it stood, at the instant it stood there. */
	private record Step(long at, BazaarSnapshot book) {
	}

	/**
	 * One policy run over one window.
	 *
	 * @param payout coins handed over by the NPC, which is what the 500M daily cap counts. Carried
	 *               because a gain in profit is only a real gain if it does not spend more of the
	 *               cap to get it - once the cap is what runs out, the day is won on margin per
	 *               coin of payout and not on profit per hour
	 */
	private record Run(double profit, double payout, int swaps, int steps) {
		double marginOnPayout() {
			return payout <= 0.0d ? 0.0d : profit / payout;
		}
	}

	@Test
	void reportsWhatReshufflingTheBasketMidWindowWouldBeWorth() throws Exception {
		HypixelApi api = new HypixelApi();
		ItemCatalog catalog = api.fetchItems();

		NpcEdgeHistory edgeHistory = new NpcEdgeHistory(catalog, Instant.now(), HISTORY);
		PriceHistory prices = new PriceHistory(TREND_WINDOW);

		// Bucketed by check-in, keeping the last sample per product per bucket, and only for the
		// products an NPC actually buys. Everything else can never become a basket line, so holding
		// it would be tens of millions of samples to answer a question about none of them.
		Map<Long, Map<String, BazaarSample>> buckets = new TreeMap<>();
		long bucketMillis = CHECK_IN.toMillis();
		long replayFrom = Instant.now().toEpochMilli() - Duration.ofDays(REPLAY_DAYS).toMillis();

		new BazaarTape(tapeDir(), Integer.MAX_VALUE).forEachRecent(TAPE_DAYS, (BazaarSample sample) -> {
			if (sample.bidPrice() > 0.0d) {
				edgeHistory.append(sample);
			}

			prices.append(sample);

			if (sample.timestamp() < replayFrom || !buysAtNpc(catalog, sample.productId())) {
				return;
			}

			buckets.computeIfAbsent(sample.timestamp() / bucketMillis * bucketMillis,
							key -> new HashMap<>())
					.merge(sample.productId(), sample,
							(older, newer) -> newer.timestamp() >= older.timestamp() ? newer : older);
		});

		NpcEdgeSnapshot edges = edgeHistory.snapshot();

		trends = prices.snapshot();

		assertTrue(edges.size() > 0, "no NPC edges built from the tape at " + tapeDir());
		assertTrue(trends.productsWithMeasuredFills() > 0, "no displacement measured from the tape");

		List<Step> steps = new ArrayList<>();

		buckets.forEach((at, samples) -> steps.add(new Step(at, snapshotOf(at, samples))));

		int perWindow = (int) Math.round(RESTING_HOURS * 60.0d / CHECK_IN.toMinutes());

		assertTrue(steps.size() > perWindow, "only " + steps.size() + " replay steps of "
				+ CHECK_IN.toMinutes() + " minutes, which is under one " + RESTING_HOURS
				+ "h window - point -PbazaarTapeDir at a tape with more days in it");

		System.out.printf("%nNPC displacement backtest: %d steps of %d min replayed from %d days of "
						+ "tape, %d products with an NPC edge, %d with measured displacement%n",
				steps.size(), CHECK_IN.toMinutes(), REPLAY_DAYS, edges.size(),
				trends.productsWithMeasuredFills());
		System.out.printf("  sticky = hold an order until the book kills it, refilling free slots; "
				+ "reshuffle = re-pick everything every %d min, free of charge%n",
				CHECK_IN.toMinutes());
		System.out.printf("%n  %-18s %10s %10s %8s %10s %10s %7s %7s%n",
				"window", "sticky", "reshuffle", "gain", "pay-stick", "pay-shuf", "m/stick",
				"m/shuf");

		double totalSticky = 0.0d;
		double totalReshuffle = 0.0d;
		double stickyPayout = 0.0d;
		double reshufflePayout = 0.0d;
		int totalSwaps = 0;
		int windows = 0;

		for (int start = 0; start + perWindow <= steps.size(); start += perWindow) {
			List<Step> window = steps.subList(start, start + perWindow);
			Run sticky = runWindow(window, catalog, edges, true);
			Run reshuffle = runWindow(window, catalog, edges, false);

			totalSticky += sticky.profit();
			totalReshuffle += reshuffle.profit();
			stickyPayout += sticky.payout();
			reshufflePayout += reshuffle.payout();
			totalSwaps += reshuffle.swaps();
			windows++;

			System.out.printf("  %-18s %9.1fM %9.1fM %+7.1f%% %9.1fM %9.1fM %6.1f%% %6.1f%%%n",
					Instant.ofEpochMilli(window.get(0).at()).toString().substring(0, 16),
					sticky.profit() / 1e6, reshuffle.profit() / 1e6,
					gain(sticky.profit(), reshuffle.profit()),
					sticky.payout() / 1e6, reshuffle.payout() / 1e6,
					sticky.marginOnPayout() * 100.0d, reshuffle.marginOnPayout() * 100.0d);
		}

		System.out.printf("%n  %d windows: sticky %.1fM, reshuffle %.1fM, %+.1f%% for re-picking "
						+ "the whole basket every %d minutes at no cost, %d swaps%n",
				windows, totalSticky / 1e6, totalReshuffle / 1e6,
				gain(totalSticky, totalReshuffle), CHECK_IN.toMinutes(), totalSwaps);

		// The cap is the test the profit gain has to survive. A basket that earns 6% more by handing
		// the NPC 6% more of a capped 500M has not earned anything: it has spent tomorrow's cap
		// today. What would survive is a higher margin per coin of payout, which is the same day's
		// cap turned into more coins.
		double days = windows * RESTING_HOURS / 24.0d;

		System.out.printf("  payout %.1fM/day sticky against a %.0fM cap (%.0f%% of it), %.1fM/day "
						+ "reshuffled (%.0f%% of it)%n",
				stickyPayout / days / 1e6, DAILY_CAP / 1e6,
				stickyPayout / days / DAILY_CAP * 100.0d,
				reshufflePayout / days / 1e6, reshufflePayout / days / DAILY_CAP * 100.0d);
		System.out.printf("  margin on payout: %.2f%% sticky, %.2f%% reshuffled (%+.1f%%) - this is "
						+ "the number that decides it once the cap binds%n%n",
				totalSticky / stickyPayout * 100.0d, totalReshuffle / reshufflePayout * 100.0d,
				gain(totalSticky / stickyPayout, totalReshuffle / reshufflePayout));

		assertTrue(totalReshuffle >= totalSticky * 0.98d, "re-choosing the basket every step came "
				+ "out worse than holding it, which cannot happen when swaps are free - the two "
				+ "policies are not being valued on the same footing");

		// The finding, as a tripwire. Displacement was measured and rejected because the cap binds
		// and reshuffling does not improve margin against it. Both halves of that have to stop being
		// true before the question is worth re-opening, so both are asserted.
		assertTrue(stickyPayout / days > DAILY_CAP, String.format("the basket now wants only %.1fM "
						+ "of payout a day against a %.0fM cap, so the cap has stopped binding and "
						+ "throughput is worth something again - re-open the displacement question, "
						+ "reshuffling was measured at %+.1f%% profit",
				stickyPayout / days / 1e6, DAILY_CAP / 1e6, gain(totalSticky, totalReshuffle)));

		assertTrue(totalReshuffle / reshufflePayout <= totalSticky / stickyPayout * 1.02d,
				String.format("reshuffling now earns %.2f%% per coin of payout against %.2f%% for "
								+ "holding, so it beats the shipped basket on the capped day and is "
								+ "worth building after all - see the class javadoc for what was "
								+ "measured on 2026-08-12",
						totalReshuffle / reshufflePayout * 100.0d,
						totalSticky / stickyPayout * 100.0d));
	}

	/**
	 * One window under one policy, valued step by step against the book as it actually moved.
	 *
	 * <p>Both policies re-plan at every step, so both get the benefit of repricing: the difference
	 * is only whether the allocator may take a slot away from an order that is still working.
	 *
	 * <p>Each step is credited with its own share of a window's profit, because a basket's profit is
	 * quoted over {@code npcRestingHours} and a step is one check-in of that.
	 */
	private static Run runWindow(List<Step> window, ItemCatalog catalog, NpcEdgeSnapshot edges,
			boolean sticky) {
		double share = CHECK_IN.toMillis() / (RESTING_HOURS * 3_600_000.0d);
		double profit = 0.0d;
		double payout = 0.0d;
		int swaps = 0;
		Set<String> held = Set.of();

		for (Step step : window) {
			StrategyContext full = context(step.book(), catalog, edges);

			if (!sticky) {
				NpcBasket.Basket basket = NpcBasket.plan(full);
				Set<String> now = itemIds(basket);
				Set<String> added = new HashSet<>(now);

				added.removeAll(held);
				swaps += held.isEmpty() ? 0 : added.size();
				held = now;
				profit += basket.profit() * share;
				payout += basket.npcPayout() * share;
				continue;
			}

			// What is still worth holding, priced at the book as it is now. An item that has dropped
			// out is one NpcReprice would be cancelling this trip, so its slot is free again.
			NpcBasket.Basket kept = NpcBasket.plan(
					context(restrict(step.book(), held), catalog, edges));
			Set<String> alive = itemIds(kept);

			// And the free slots go back to the whole market, which is what the mod does today.
			NpcBasket.Basket refill = NpcBasket.plan(full,
					new NpcBasket.Held(kept.slotsUsed(), kept.capital(), alive));
			Set<String> now = new HashSet<>(alive);

			now.addAll(itemIds(refill));
			held = now;
			profit += (kept.profit() + refill.profit()) * share;
			payout += (kept.npcPayout() + refill.npcPayout()) * share;
		}

		return new Run(profit, payout, swaps, window.size());
	}

	/** The same book with everything but these products removed. */
	private static BazaarSnapshot restrict(BazaarSnapshot book, Set<String> itemIds) {
		Map<String, BazaarProduct> kept = new HashMap<>();

		for (String itemId : itemIds) {
			BazaarProduct product = book.products().get(itemId);

			if (product != null) {
				kept.put(itemId, product);
			}
		}

		return new BazaarSnapshot(book.lastUpdated(), kept);
	}

	private static Set<String> itemIds(NpcBasket.Basket basket) {
		Set<String> ids = new HashSet<>();

		basket.lines().forEach(line -> ids.add(line.plan().itemId()));

		return ids;
	}

	/**
	 * A book rebuilt from tape samples, one level a side.
	 *
	 * <p>Faithful for this question and not in general: the resting-order route reads the top of the
	 * bid side and the weekly volumes and nothing else, so a one-level book is the whole of what
	 * {@code NpcFlipStrategy.buyOrderPlan} would have looked at. The instant-buy route does read
	 * depth, and it is absent here - which is why this measures baskets of resting orders only, the
	 * ones a displacement feature would ever be cancelling.
	 */
	private static BazaarSnapshot snapshotOf(long at, Map<String, BazaarSample> samples) {
		Map<String, BazaarProduct> products = new HashMap<>();

		samples.forEach((productId, sample) -> products.put(productId, new BazaarProduct(
				productId,
				sample.askPrice() > 0.0d
						? List.of(new OrderLevel(sample.askPrice(), Long.MAX_VALUE / 4L, 1))
						: List.of(),
				sample.bidPrice() > 0.0d
						? List.of(new OrderLevel(sample.bidPrice(), Long.MAX_VALUE / 4L, 1))
						: List.of(),
				new BazaarProduct.MovingWeek(sample.boughtWeek(), sample.soldWeek()))));

		return new BazaarSnapshot(Instant.ofEpochMilli(at), products);
	}

	private static boolean buysAtNpc(ItemCatalog catalog, String productId) {
		return catalog.get(productId)
				.flatMap(ItemCatalog.Entry::npcPrice)
				.filter(price -> price > 0.0d)
				.isPresent();
	}

	private static StrategyContext context(BazaarSnapshot bazaar, ItemCatalog catalog,
			NpcEdgeSnapshot edges) {
		NpcContext npc = new NpcContext(edges, LIVE_FLOOR, CHECK_IN, RESTING_HOURS, LIVE_SLOTS,
				DAILY_CAP);

		return new StrategyContext(bazaar, catalog, List.of(), trends,
				new Fees(BAZAAR_FLIPPER_LEVEL, false), BANKROLL, MIN_PROFIT_PER_FLIP, 0.0d, 0.0d,
				CHECK_IN, MAX_CAPITAL_SHARE, npc);
	}

	private static double gain(double from, double to) {
		return from <= 0.0d ? 0.0d : (to / from - 1.0d) * 100.0d;
	}

	private static double churn(int swaps, int steps) {
		return steps <= 1 ? 0.0d : swaps * 100.0d / (steps - 1);
	}

	private static Path tapeDir() {
		return Path.of(System.getProperty("skyblockflipper.bazaarTapeDir", DEFAULT_TAPE_DIR));
	}
}
