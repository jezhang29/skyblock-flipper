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

import jeff.skyblockflipper.core.model.BazaarProduct;
import jeff.skyblockflipper.core.model.Stacking;
import jeff.skyblockflipper.core.pricing.FillModel;
import jeff.skyblockflipper.core.pricing.FillModel.FillEstimate;
import jeff.skyblockflipper.core.text.Coins;
import jeff.skyblockflipper.core.text.Waits;
import jeff.skyblockflipper.core.valuation.PriceTrend;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Bazaar market making: post a buy order, wait, then post a sell offer.
 *
 * <p>The reframe that matters is that this is not flipping, it is providing immediacy. Someone who
 * has been grinding for four hours instant-sells into your buy order because they want coins now;
 * someone who needs materials right now instant-buys from your sell offer. The spread is the fee
 * you charge both of them.
 *
 * <p>The spread arithmetic is the easy part. What separates paper margin from realized margin is
 * the filtering, so most of this class is filters:
 *
 * <ul>
 *   <li><b>Liquidity.</b> Ranked on profit per hour, using the thinner of the two weekly volumes.
 *       A product that only ever gets dumped into is not a two-sided market.</li>
 *   <li><b>Order-book depth.</b> A book held up by a handful of orders is trivially manipulated;
 *       a whale's buy wall evaporates the moment you commit.</li>
 *   <li><b>Implausible spreads.</b> A 40% spread on a liquid item is not free money nobody noticed,
 *       it is a signal that something is wrong with the data or the item.</li>
 *   <li><b>Direction.</b> A spread quoted on a falling market is not the spread you will realize.
 *       Your buy orders fill fastest exactly when people are dumping, and your sell offers fill
 *       slowest at the same moment, so adverse selection makes realized P&amp;L systematically worse
 *       than the number measured at order time.</li>
 *   <li><b>Manipulation.</b> A thin book that has abruptly left its own normal range is usually
 *       someone building a trap rather than a market repricing.</li>
 * </ul>
 *
 * <p>The last two need price history, which the bazaar tape now provides. Before enough of it
 * accumulates - a fresh install, or a window the client was closed for - {@code trends} is empty
 * and both tests stand down rather than guessing. That case is reported as a risk in its own right:
 * "cannot tell" is honest, and silently behaving as though the market were stable is not.
 */
public final class BazaarSpreadStrategy implements FlipStrategy {
	/** Below this weekly volume on the thinner side, fills are too slow to model. */
	private static final long MIN_WEEKLY_VOLUME = 50_000L;

	/**
	 * Books this thin are dominated by a few players and can be pulled at will.
	 *
	 * <p>Counted across the whole returned depth, not the top level. A single price level normally
	 * holds one to three orders even on healthy books, so thresholding the top level alone rejects
	 * effectively the entire market.
	 */
	private static final int MIN_ORDERS_PER_SIDE = 15;

	/** A spread wider than this on a supposedly liquid item means something is off. */
	private static final double MAX_PLAUSIBLE_SPREAD_FRACTION = 0.25d;

	/** Ignore sub-coin items: rounding and the 0.1 undercut dominate the economics. */
	private static final double MIN_UNIT_PRICE = 1.0d;

	/**
	 * Above this weekly volume a book is too expensive to push around, so an abrupt move is far
	 * more likely to be real news than someone building a trap.
	 */
	private static final long MANIPULATION_VOLUME_CEILING = 500_000L;

	/** Standard deviations from the item's own recent mean before a move counts as abrupt. */
	private static final double SPIKE_SIGMA = 3.0d;

	/**
	 * How far above its multi-day normal a thin item may sit before it is treated as manipulated.
	 *
	 * <p>Checked in addition to {@link #SPIKE_SIGMA} because the two catch different things: a
	 * scheme that has been running longer than the in-memory window is already inside that
	 * window's average and no longer looks abrupt against it. The daily rollup does not move.
	 */
	private static final double MANIPULATION_DAILY_MULTIPLE = 0.5d;

	/** Drift worth warning about, well below the level that gets a candidate rejected outright. */
	private static final double FALLING_RISK_THRESHOLD = 0.01d;

	/** Per-sample log-return deviation above which a book is worth calling choppy. */
	private static final double VOLATILE_THRESHOLD = 0.015d;

	/**
	 * You are one participant among many, so you cannot expect to capture the whole flow. Assume a
	 * modest share of the thinner side's volume actually routes through your orders.
	 */
	private static final double ASSUMED_VOLUME_SHARE = 0.05d;

	/** A round trip slower than this is worth saying out loud, whatever the profit per hour. */
	private static final Duration SLOW_FILL = Duration.ofMinutes(45);

	/**
	 * The leg that finishes last, or null if either never finishes.
	 *
	 * <p>Null rather than an arbitrarily large duration: "does not clear at all" and "clears in
	 * nine hours" deserve different sentences, and collapsing them loses the one that matters.
	 */
	private static Duration slower(Optional<Duration> first, Optional<Duration> second) {
		if (first.isEmpty() || second.isEmpty()) {
			return null;
		}

		return first.get().compareTo(second.get()) >= 0 ? first.get() : second.get();
	}

	@Override
	public StrategyKind kind() {
		return StrategyKind.BAZAAR_SPREAD;
	}

	@Override
	public List<FlipCandidate> findCandidates(StrategyContext context) {
		List<FlipCandidate> candidates = new ArrayList<>();

		for (BazaarProduct product : context.bazaar().products().values()) {
			evaluate(product, context).ifPresent(candidates::add);
		}

		candidates.sort(null);
		return candidates;
	}

	/**
	 * This one product re-quoted against the live book, on the same gates the ranking uses.
	 *
	 * <p>The twin of {@code CraftFlipStrategy.job} and {@code BazaarCombineStrategy.job}, and it
	 * exists for the same reason: a spread the player is working has to be re-priced every poll
	 * while they type it, and an item that has dropped out of the top of the ranking is still the
	 * item they have coins resting on. Empty means the flip stopped clearing its own gates, which
	 * the panel says out loud rather than going on showing the last numbers that worked.
	 */
	public java.util.Optional<FlipCandidate> job(String productId, StrategyContext context) {
		if (productId == null) {
			return java.util.Optional.empty();
		}

		return context.bazaar().product(productId).flatMap(product -> evaluate(product, context));
	}

	private java.util.Optional<FlipCandidate> evaluate(BazaarProduct product, StrategyContext context) {
		if (product.sellOffers().isEmpty() || product.buyOrders().isEmpty()) {
			return java.util.Optional.empty();
		}

		double buyPrice = product.outbidBuyOrder().orElseThrow();
		double sellPrice = product.undercutSellOffer().orElseThrow();

		if (buyPrice < MIN_UNIT_PRICE || sellPrice <= buyPrice) {
			return java.util.Optional.empty();
		}

		long weeklyVolume = product.bottleneckWeeklyVolume();

		if (weeklyVolume < MIN_WEEKLY_VOLUME) {
			return java.util.Optional.empty();
		}

		if (product.sellOfferCount() < MIN_ORDERS_PER_SIDE
				|| product.buyOrderCount() < MIN_ORDERS_PER_SIDE) {
			// A book resting on almost nothing: the price you see is not the price you get.
			return java.util.Optional.empty();
		}

		double netPerUnit = context.fees().bazaarRoundTripProfit(buyPrice, sellPrice);

		if (netPerUnit <= 0.0d) {
			return java.util.Optional.empty();
		}

		if ((sellPrice - buyPrice) / buyPrice > MAX_PLAUSIBLE_SPREAD_FRACTION) {
			return java.util.Optional.empty();
		}

		// Empty until the tape has enough history, and every use below has to cope with that.
		PriceTrend trend = context.trends().trendFor(product.productId()).orElse(null);

		if (trend != null) {
			// Posting a buy order into a decline is the single most reliable way to turn a quoted
			// margin into a realized loss, and it is invisible in the book you are quoting off.
			if (context.maxAdverseDrift() > 0.0d && trend.isFalling(context.maxAdverseDrift())) {
				return java.util.Optional.empty();
			}

			if (looksManipulated(product, trend, context)) {
				return java.util.Optional.empty();
			}
		}

		// What the two legs are expected to fill, from recorded displacement where there is any.
		// Without it this falls back to a flat share of the flow, which is what this strategy
		// assumed unconditionally before the tape could answer the question - so a product the
		// tape has not covered yet ranks exactly where it used to.
		FillEstimate fill = FillModel.estimate(
				product,
				context.trends().fillStatsFor(product.productId()).orElse(null),
				context.fillHorizon(),
				ASSUMED_VOLUME_SHARE);

		double unitsPerHour = fill.throughputPerHour();

		if (unitsPerHour <= 0.0d) {
			return java.util.Optional.empty();
		}

		double horizonHours = hoursOf(context.fillHorizon());
		// The per-flip cap, not the whole bankroll: profit per hour rises with size, so without a
		// ceiling the ranking's own logic puts everything into one item.
		long affordableUnits = (long) (context.maxCapitalPerFlip() / buyPrice);

		if (affordableUnits <= 0L) {
			return java.util.Optional.empty();
		}

		// Size the plan at what the horizon is expected to clear, capped by what the coins fund.
		// A horizon's worth of inventory is the position we are willing to hold, which also caps
		// how much adverse selection can hurt on any single item.
		long units = Math.max(1L, Math.min(affordableUnits, (long) (unitsPerHour * horizonHours)));
		double profitPerHour = netPerUnit * Math.min(unitsPerHour, units / horizonHours);
		long capital = Math.round(buyPrice * units);

		if (netPerUnit * units < context.minProfitPerFlip()) {
			return java.util.Optional.empty();
		}

		String name = context.catalog().displayName(product.productId());

		return java.util.Optional.of(new FlipCandidate(
				product.productId(),
				name,
				kind(),
				buyPrice,
				sellPrice,
				netPerUnit,
				units,
				capital,
				profitPerHour,
				confidence(weeklyVolume, product, trend),
				steps(name, buyPrice, sellPrice, units,
						Stacking.unitsPerOrder(context.catalog().get(product.productId()).orElse(null),
								product)),
				risks(product, trend, fill, units, context),
				notes(fill, units),
				fill));
	}

	private static double hoursOf(Duration horizon) {
		return horizon.toMillis() / 3_600_000.0d;
	}

	/**
	 * A thin book that has abruptly left its own normal range.
	 *
	 * <p>Both halves are required. An abrupt move on a deep market is news; the same move on a book
	 * a single player can shift is usually bait, priced to look like an opportunity right up until
	 * the wall it is resting on disappears.
	 */
	private static boolean looksManipulated(BazaarProduct product, PriceTrend trend,
			StrategyContext context) {
		if (product.bottleneckWeeklyVolume() > MANIPULATION_VOLUME_CEILING) {
			return false;
		}

		if (trend.isSpiking(SPIKE_SIGMA)) {
			return true;
		}

		java.util.OptionalDouble daily = context.trends().dailyMedianFor(product.productId());

		return daily.isPresent()
				&& trend.latest() > daily.getAsDouble() * (1.0d + MANIPULATION_DAILY_MULTIPLE);
	}

	/**
	 * Confidence rises with liquidity and book depth, because both make the quoted prices more
	 * likely to still be there when you act on them, and falls with adverse drift and volatility,
	 * because both make it less likely that the spread you measured is the spread you collect.
	 *
	 * <p>A candidate with no history is not penalised, and neither is one whose history turns out
	 * to be calm. Both must score the same: if merely having been measured cost confidence, the
	 * ranking would drift toward whichever items the tape happened not to cover yet, which is the
	 * opposite of what recording history is for. Only an actual decline or actual choppiness
	 * costs anything, and the unmeasured case is said out loud in the risks instead.
	 */
	private static double confidence(long weeklyVolume, BazaarProduct product, PriceTrend trend) {
		double volumeScore = Math.min(1.0d, weeklyVolume / 2_000_000.0d);
		int depth = Math.min(product.sellOfferCount(), product.buyOrderCount());
		double depthScore = Math.min(1.0d, depth / 60.0d);
		double base = 0.35d + 0.4d * volumeScore + 0.25d * depthScore;

		if (trend == null) {
			return base;
		}

		double driftPenalty = trend.drift() < 0.0d
				? Math.min(0.30d, -trend.drift() * 4.0d)
				: 0.0d;

		// Measured against the same threshold that earns a candidate the "choppy" risk note, so
		// the number and the warning cannot disagree about what counts as choppy.
		double excessVolatility = Math.max(0.0d, trend.volatility() - VOLATILE_THRESHOLD);
		double volatilityPenalty = Math.min(0.20d, excessVolatility * 10.0d);

		return Math.clamp(base - driftPenalty - volatilityPenalty, 0.05d, 1.0d);
	}

	/**
	 * The clicks, with the quantity written the way the order box will take it.
	 *
	 * <p>A bazaar order holds at most 71,680 units of an item that stacks and <b>256</b> of one that
	 * does not, and a plan sized to an hour of flow can want more than that: {@code ESSENCE_CRIMSON}
	 * wanted 111,507 on the book of 2026-08-20, which is two orders. A bare total reads as one
	 * order, and that is how a line of 500 Jungle Hearts came to be typed into a box that takes 256
	 * - the failure {@link Stacking#orderSplit} exists to stop. Both legs carry it, because both are
	 * a number typed into an amount box.
	 */
	private static List<String> steps(String name, double buyPrice, double sellPrice, long units,
			long unitsPerOrder) {
		String amount = amount(units, unitsPerOrder);

		return List.of(
				"Bazaar -> search " + name + " -> Create Buy Order",
				String.format("Set price %.1f and quantity %s", buyPrice, amount),
				"Wait for the order to fill; do not chase the price if it moves away",
				String.format("Once filled, Create Sell Offer at %.1f for %s", sellPrice, amount),
				"Cancel and reprice if the book moves against you rather than holding stock");
	}

	/** {@code 111507 (2 x 71680)}, or a bare total where one order covers it. */
	private static String amount(long units, long unitsPerOrder) {
		String split = Stacking.orderSplit(units, unitsPerOrder);

		return split.equals(String.valueOf(units)) ? split : units + " (" + split + ")";
	}

	/**
	 * What the fill estimate says, stated as fact rather than as a warning.
	 *
	 * <p>The two legs are reported separately because they fail differently: a buy order that never
	 * fills costs nothing but the wait, while a sell offer that never fills leaves you holding the
	 * item, which is the position this strategy exists to avoid.
	 */
	private static List<String> notes(FillEstimate fill, long units) {
		if (!fill.measured()) {
			return List.of();
		}

		List<String> notes = new ArrayList<>();

		notes.add(String.format("Fills about %s units an hour: %s to buy %d, %s to sell them",
				Coins.format(fill.throughputPerHour()),
				Waits.formatOrNever(fill.buyTimeToFill(units).orElse(null)), units,
				Waits.formatOrNever(fill.sellTimeToFill(units).orElse(null))));

		if (fill.outbidsPerHour() > 0.0d) {
			notes.add(String.format(
					"Measured from history: your buy order gets outbid about %.1f times an hour",
					fill.outbidsPerHour()));
		}

		return notes;
	}

	private static List<String> risks(BazaarProduct product, PriceTrend trend, FillEstimate fill,
			long units, StrategyContext context) {
		List<String> risks = new ArrayList<>();

		if (trend == null) {
			// Unmeasured, not absent. This used to be an unconditional note on every candidate
			// because there was no way to tell; now it means the tape has not seen this product
			// for long enough, which is a different and much narrower claim.
			risks.add("No price history for this item yet: a falling market would not be visible "
					+ "here, and the fill rate is an assumed share of volume rather than a measured one");
		} else if (trend.isFalling(FALLING_RISK_THRESHOLD)) {
			risks.add(String.format(
					"Price down %.1f%% against its %dh average; buy orders fill fastest into a decline",
					-trend.drift() * 100.0d, context.trends().window().toHours()));
		} else if (trend.volatility() > VOLATILE_THRESHOLD) {
			risks.add("Choppy price series: the book you quoted against may not be there on the fill");
		}

		// Measured where possible, inferred from volume only where it is not. The old unconditional
		// "fills may take hours" was a guess dressed as a warning; a measured wait can say which
		// leg is slow and how slow, and stays quiet when the fill is brisk.
		if (fill.measured()) {
			Duration slowest = slower(fill.buyTimeToFill(units), fill.sellTimeToFill(units));

			if (slowest == null) {
				risks.add("At this size one leg does not clear inside your fill horizon at all");
			} else if (slowest.compareTo(SLOW_FILL) >= 0) {
				risks.add("Slow to complete: about " + Waits.format(slowest)
						+ " for the round trip, during which the spread you quoted can close");
			}
		} else if (product.bottleneckWeeklyVolume() < 250_000L) {
			risks.add("Thin two-sided flow: fills may take hours");
		}

		int depth = Math.min(product.sellOfferCount(), product.buyOrderCount());

		if (depth < 30) {
			risks.add("Shallow book (" + depth + " resting orders): prone to undercut spirals");
		}

		return risks;
	}
}
