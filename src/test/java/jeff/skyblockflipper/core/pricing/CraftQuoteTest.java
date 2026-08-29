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
package jeff.skyblockflipper.core.pricing;

import jeff.skyblockflipper.core.model.BazaarProduct;
import jeff.skyblockflipper.core.model.BazaarSnapshot;
import jeff.skyblockflipper.core.model.OrderLevel;
import jeff.skyblockflipper.core.model.UpgradeCost;
import jeff.skyblockflipper.core.pricing.CraftQuote.InputRoute;
import jeff.skyblockflipper.core.recipe.Recipe;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the two routes {@link CraftQuote} prices and every gate it refuses at.
 *
 * <p>The refusals are the substance. A craft quote that returns a number where it should have
 * returned nothing does not fail loudly - it ranks first, because every way of getting this wrong
 * (a missing ingredient, a thin book, an undercounted grid cell, a bid nothing rests on) makes the
 * flip look better than it is. Each test below is one of those, with the measurement that motivated
 * it.
 */
class CraftQuoteTest {
	private static final Duration HOUR = Duration.ofHours(1);
	private static final long PLENTY = 1_000_000_000L;
	private static final Fees FLIPPER_1 = new Fees(1, false);
	private static final double TAX = 0.01125d;

	private final Map<String, BazaarProduct> products = new HashMap<>();

	/** A healthy book: deep enough on both sides to rest a price against, at a believable spread. */
	private void book(String id, double ask, double bid, long weeklyBought, long weeklySold) {
		book(id, ask, bid, weeklyBought, weeklySold, 20, 20, 10_000_000L);
	}

	/**
	 * A book too thin to rest an order against, so anything quoting it must instant-buy. Volume is
	 * left healthy: this isolates the depth gate from the rate.
	 */
	private void thinBook(String id, double ask, double bid, long weeklyBought, long weeklySold) {
		book(id, ask, bid, weeklyBought, weeklySold, 20, 3, 10_000_000L);
	}

	private void book(String id, double ask, double bid, long weeklyBought, long weeklySold,
			int askOrders, int bidOrders, long depth) {
		products.put(id, new BazaarProduct(id,
				List.of(new OrderLevel(ask, depth, askOrders)),
				List.of(new OrderLevel(bid, depth, bidOrders)),
				new BazaarProduct.MovingWeek(weeklyBought, weeklySold)));
	}

	private BazaarSnapshot bazaar() {
		return new BazaarSnapshot(Instant.now(), Map.copyOf(products));
	}

	private static Recipe recipe(String output, int count, Object... idsAndAmounts) {
		List<UpgradeCost.Ingredient> ingredients = new ArrayList<>();

		for (int i = 0; i < idsAndAmounts.length; i += 2) {
			ingredients.add(new UpgradeCost.Ingredient((String) idsAndAmounts[i],
					(Integer) idsAndAmounts[i + 1]));
		}

		return new Recipe(output, count, ingredients, "");
	}

	private Optional<CraftQuote> quote(Recipe recipe) {
		return quote(recipe, PLENTY);
	}

	private Optional<CraftQuote> quote(Recipe recipe, long maxCapital) {
		return CraftQuote.quote(recipe, bazaar(), FLIPPER_1, CraftQuote.FillHistory.none(), HOUR,
				maxCapital);
	}

	// ---- the exit -------------------------------------------------------------------------

	/** The output always leaves on a resting offer one increment under the best ask. */
	@Test
	void sellsTheOutputOneIncrementUnderTheBestAsk() {
		book("OUT", 1000.1d, 950.0d, 1_000_000L, 1_000_000L);
		book("IN", 100.0d, 95.0d, 1_000_000L, 1_000_000L);

		assertEquals(1000.0d, quote(recipe("OUT", 1, "IN", 8)).orElseThrow().unitSellPrice(), 1e-9);
	}

	/** A recipe yielding several units sells all of them, and the tax lands on the whole sale. */
	@Test
	void multipliesTheSaleByTheOutputCount() {
		book("OUT", 100.1d, 95.0d, 10_000_000L, 10_000_000L);
		thinBook("IN", 1000.0d, 950.0d, 10_000_000L, 10_000_000L);

		CraftQuote quote = quote(recipe("OUT", 160, "IN", 1)).orElseThrow();

		assertEquals(160L * quote.crafts(), quote.outputUnits());
		assertEquals(100.0d * 160.0d * (1.0d - TAX) - 1000.0d, quote.netPerCraft(), 1e-6);
	}

	// ---- the two input routes -------------------------------------------------------------

	/** Instant-buying pays the ask; the bill is what the ask says it is. */
	@Test
	void pricesTheInstantRouteAtTheAsk() {
		book("OUT", 1000.1d, 950.0d, 1_000_000L, 1_000_000L);
		thinBook("IN", 100.0d, 95.0d, 1_000_000L, 1_000_000L);

		CraftQuote quote = quote(recipe("OUT", 1, "IN", 8)).orElseThrow();

		assertEquals(InputRoute.INSTANT_BUY, quote.route());
		assertEquals(800.0d, quote.inputCostPerCraft(), 1e-9);
		assertEquals(1000.0d * (1.0d - TAX) - 800.0d, quote.netPerCraft(), 1e-6);
	}

	/** A resting order pays one increment over the best bid, which is the whole point of it. */
	@Test
	void pricesTheRestingRouteOneIncrementOverTheBestBid() {
		book("OUT", 1000.1d, 950.0d, 1_000_000L, 1_000_000L);
		book("IN", 100.0d, 95.0d, 1_000_000L, 1_000_000L);

		CraftQuote quote = quote(recipe("OUT", 1, "IN", 8)).orElseThrow();

		assertEquals(InputRoute.BUY_ORDER, quote.route());
		assertEquals(8 * 95.1d, quote.inputCostPerCraft(), 1e-9);
		assertEquals(List.of("IN"), quote.restingBuyOrders());
	}

	/**
	 * The measured result: the cheaper route wins where both are open. On the live book it won on
	 * all 31 recipes that passed every gate, taking the best eight from 1.29M an hour to 8.67M.
	 */
	@Test
	void prefersWhicheverRoutePaysMorePerHour() {
		book("OUT", 1000.1d, 950.0d, 1_000_000L, 1_000_000L);
		// A believable spread: 50 against an ask of 100 would trip the liquidity gate and be
		// instant-bought, which is the gate working rather than the route losing.
		book("CHEAP_TO_REST", 100.0d, 90.0d, 1_000_000L, 1_000_000L);

		assertEquals(InputRoute.BUY_ORDER, quote(recipe("OUT", 1, "CHEAP_TO_REST", 1))
				.orElseThrow().route());

		// Now make the resting route slow rather than dear: nobody dumps into this book, so an
		// order on it fills at a trickle while the ask side is consumed briskly.
		products.clear();
		book("OUT", 1000.1d, 950.0d, 1_000_000L, 1_000_000L);
		book("SLOW_TO_REST", 100.0d, 99.0d, 10_000_000L, 168L);

		assertEquals(InputRoute.INSTANT_BUY, quote(recipe("OUT", 1, "SLOW_TO_REST", 1))
				.orElseThrow().route());
	}

	/**
	 * A bid with nothing resting behind it is not a price. Measured: without this gate, three books
	 * quoting cost savings of 79% to 89% against empty bid sides ranked in the live top eight.
	 */
	@Test
	void instantBuysAnIngredientWhoseOwnBookIsTooThinToRestAgainst() {
		book("OUT", 100_000.1d, 99_000.0d, 1_000_000L, 1_000_000L);
		thinBook("IN", 100.0d, 1.0d, 1_000_000L, 1_000_000L);

		CraftQuote quote = quote(recipe("OUT", 1, "IN", 1)).orElseThrow();

		assertEquals(100.0d, quote.inputCostPerCraft(), 1e-9,
				"quoted the 1.1 bid on a book nothing is resting on");
		assertTrue(quote.restingBuyOrders().isEmpty());
	}

	/** Same for an ingredient whose spread is too wide to believe either side of. */
	@Test
	void instantBuysAnIngredientWhoseSpreadIsImplausible() {
		book("OUT", 100_000.1d, 99_000.0d, 1_000_000L, 1_000_000L);
		book("IN", 100.0d, 10.0d, 1_000_000L, 1_000_000L);

		assertEquals(100.0d, quote(recipe("OUT", 1, "IN", 1)).orElseThrow().inputCostPerCraft(), 1e-9);
	}

	/** A recipe may rest orders on some ingredients and instant-buy the rest of them. */
	@Test
	void mixesTheRoutesWithinOneRecipe() {
		book("OUT", 100_000.1d, 99_000.0d, 1_000_000L, 1_000_000L);
		book("LIQUID", 100.0d, 90.0d, 1_000_000L, 1_000_000L);
		thinBook("ILLIQUID", 100.0d, 90.0d, 1_000_000L, 1_000_000L);

		CraftQuote quote = quote(recipe("OUT", 1, "LIQUID", 1, "ILLIQUID", 1)).orElseThrow();

		assertEquals(InputRoute.BUY_ORDER, quote.route());
		assertEquals(List.of("LIQUID"), quote.restingBuyOrders());
		assertEquals(90.1d + 100.0d, quote.inputCostPerCraft(), 1e-9);
	}

	/** Slots are a hard shared budget, so a plan must say how many of them it wants. */
	@Test
	void countsOneOrderSlotPerRestingOrderPlusTheSellOffer() {
		book("OUT", 100_000.1d, 99_000.0d, 1_000_000L, 1_000_000L);
		book("A", 100.0d, 90.0d, 1_000_000L, 1_000_000L);
		book("B", 100.0d, 90.0d, 1_000_000L, 1_000_000L);
		thinBook("C", 100.0d, 90.0d, 1_000_000L, 1_000_000L);

		assertEquals(3, quote(recipe("OUT", 1, "A", 1, "B", 1, "C", 1)).orElseThrow().orderSlots());

		products.clear();
		book("OUT", 100_000.1d, 99_000.0d, 1_000_000L, 1_000_000L);
		thinBook("A", 100.0d, 90.0d, 1_000_000L, 1_000_000L);

		assertEquals(1, quote(recipe("OUT", 1, "A", 1)).orElseThrow().orderSlots(),
				"an all-instant plan rests only the sell offer");
	}

	// ---- the output gate ------------------------------------------------------------------

	/**
	 * Without this a book whose bid is near zero and whose ask is enormous quotes a margin in the
	 * tens of thousands of percent - measured, and the reason the first pass produced garbage.
	 */
	@Test
	void refusesAnOutputWhoseSpreadIsImplausible() {
		book("OUT", 1000.0d, 10.0d, 1_000_000L, 1_000_000L);
		book("IN", 1.0d, 0.9d, 1_000_000L, 1_000_000L);

		assertTrue(quote(recipe("OUT", 1, "IN", 1)).isEmpty());
	}

	/**
	 * The gate that actually moves in practice. Both headline recipes from the first snapshot
	 * dropped out a day later on this alone, their resting bids falling to 12 and to 8, without
	 * either losing a coin of margin.
	 */
	@Test
	void refusesAnOutputWithTooFewRestingOrders() {
		book("IN", 100.0d, 95.0d, 1_000_000L, 1_000_000L);

		book("OUT", 1000.1d, 950.0d, 1_000_000L, 1_000_000L, 30, 8, 10_000_000L);
		assertTrue(quote(recipe("OUT", 1, "IN", 1)).isEmpty(), "8 resting bids should refuse");

		book("OUT", 1000.1d, 950.0d, 1_000_000L, 1_000_000L, 8, 30, 10_000_000L);
		assertTrue(quote(recipe("OUT", 1, "IN", 1)).isEmpty(), "8 resting asks should refuse");

		book("OUT", 1000.1d, 950.0d, 1_000_000L, 1_000_000L, 15, 15, 10_000_000L);
		assertFalse(quote(recipe("OUT", 1, "IN", 1)).isEmpty(), "15 a side is the floor, not below it");
	}

	// ---- the ingredient gate --------------------------------------------------------------

	/**
	 * A bill missing an ingredient understates the cost, and understating the cost always reads as a
	 * better deal. Same rule as {@link UpgradePricing#quoteStars}: empty, never partial.
	 */
	@Test
	void refusesWhenAnIngredientIsNotOnTheBazaar() {
		book("OUT", 1000.1d, 950.0d, 1_000_000L, 1_000_000L);
		book("IN", 10.0d, 9.0d, 1_000_000L, 1_000_000L);

		assertTrue(quote(recipe("OUT", 1, "IN", 1, "NOT_TRADED", 1)).isEmpty());
	}

	/** The whole point of walking the book: on the instant route, the levels below the first are dearer. */
	@Test
	void walksTheAskBookRatherThanQuotingItsTopLevel() {
		book("OUT", 100_000.1d, 99_000.0d, 1_000_000L, 1_000_000L);
		products.put("IN", new BazaarProduct("IN",
				List.of(new OrderLevel(100.0d, 10L, 20), new OrderLevel(200.0d, 10_000L, 20)),
				// Three resting bids, so this ingredient cannot be bought on a resting order.
				List.of(new OrderLevel(95.0d, 10_000L, 3)),
				new BazaarProduct.MovingWeek(1_000_000L, 1_000_000L)));

		CraftQuote quote = quote(recipe("OUT", 1, "IN", 1)).orElseThrow();

		assertEquals(InputRoute.INSTANT_BUY, quote.route());
		assertTrue(quote.crafts() > 10L, "the plan must be large enough to eat the cheap level");
		assertTrue(quote.inputCostPerCraft() > 100.0d,
				"quoted at top of book (" + quote.inputCostPerCraft() + "), not walked");
		assertTrue(quote.inputCostPerCraft() < 200.0d);
	}

	/**
	 * A resting order sits at one price and fills there or not at all, so walking the book would be
	 * pricing a trade nobody makes.
	 */
	@Test
	void doesNotWalkTheBookForARestingOrder() {
		book("OUT", 100_000.1d, 99_000.0d, 1_000_000L, 1_000_000L);
		products.put("IN", new BazaarProduct("IN",
				List.of(new OrderLevel(100.0d, 10L, 20)),
				List.of(new OrderLevel(90.0d, 10L, 20), new OrderLevel(50.0d, 10_000L, 20)),
				new BazaarProduct.MovingWeek(1_000_000L, 1_000_000L)));

		CraftQuote quote = quote(recipe("OUT", 1, "IN", 1)).orElseThrow();

		assertEquals(InputRoute.BUY_ORDER, quote.route());
		assertEquals(90.1d, quote.inputCostPerCraft(), 1e-9);
	}

	/** On the instant route, an ingredient the visible book cannot cover is a refusal. */
	@Test
	void refusesWhenTheVisibleBookCannotCoverAnInstantPlan() {
		book("OUT", 1000.1d, 950.0d, 100_000_000L, 100_000_000L);
		book("IN", 10.0d, 9.0d, 100_000_000L, 100_000_000L, 20, 3, 5L);

		assertTrue(quote(recipe("OUT", 1, "IN", 1)).isEmpty());
	}

	// ---- rates and bounds -----------------------------------------------------------------

	/**
	 * The sell leg rests on a book, so it often binds. A recipe whose output barely trades cannot be
	 * run at the rate its ingredients could supply.
	 */
	@Test
	void reportsTheSellLegWhenTheOutputClearsSlowest() {
		book("OUT", 1000.1d, 950.0d, 1_680L, 1_680L);
		book("IN", 10.0d, 9.0d, 100_000_000L, 100_000_000L);

		CraftQuote quote = quote(recipe("OUT", 1, "IN", 1)).orElseThrow();

		assertEquals(CraftQuote.Bound.SELL_LEG, quote.bound());
		assertEquals("OUT", quote.boundProductId());

		// 1,680 a week is 10 an hour, of which an unmeasured leg claims the 5% fallback share.
		assertEquals(0.5d, quote.craftsPerHour(), 1e-9);
	}

	/** "Worth 5M an hour, held back by enchanted bread" is actionable; the figure alone is not. */
	@Test
	void namesTheIngredientThatHoldsTheRateDown() {
		book("OUT", 1000.1d, 950.0d, 100_000_000L, 100_000_000L);
		book("PLENTIFUL", 1.0d, 0.9d, 100_000_000L, 100_000_000L);
		book("SCARCE", 1.0d, 0.9d, 1_680L, 1_680L);

		CraftQuote quote = quote(recipe("OUT", 1, "PLENTIFUL", 1, "SCARCE", 1)).orElseThrow();

		assertEquals(CraftQuote.Bound.INGREDIENT_SUPPLY, quote.bound());
		assertEquals("SCARCE", quote.boundProductId());
	}

	/**
	 * The two routes are limited by different flows, which is most of why the resting route wins:
	 * a farm material is dumped into buy orders constantly and instant-bought rarely.
	 */
	@Test
	void sizesTheRestingRouteOnDumpsAndTheInstantRouteOnLifts() {
		book("OUT", 1000.1d, 950.0d, 100_000_000L, 100_000_000L);
		// Barely instant-bought, heavily dumped into: exactly a farmed material.
		book("FARMED", 10.0d, 9.5d, 1_680L, 16_800_000L);

		CraftQuote quote = quote(recipe("OUT", 1, "FARMED", 1)).orElseThrow();

		assertEquals(InputRoute.BUY_ORDER, quote.route());
		assertTrue(quote.craftsPerHour() > 100.0d,
				"the resting route should collect the dumps, got " + quote.craftsPerHour());
	}

	/** A recipe consuming 64 of something needs 64 times the flow to run at the same rate. */
	@Test
	void dividesIngredientSupplyByTheAmountConsumed() {
		book("OUT", 100_000.1d, 99_000.0d, 100_000_000L, 100_000_000L);
		book("IN", 1.0d, 0.9d, 1_680_000L, 1_680_000L);

		double one = quote(recipe("OUT", 1, "IN", 1)).orElseThrow().craftsPerHour();
		double many = quote(recipe("OUT", 1, "IN", 64)).orElseThrow().craftsPerHour();

		assertEquals(one / 64.0d, many, 1e-9);
	}

	/** Coins are a real ceiling, and the plan must say so rather than quote a rate it cannot fund. */
	@Test
	void capsThePlanAtTheCapitalAllowed() {
		book("OUT", 1000.1d, 950.0d, 100_000_000L, 100_000_000L);
		thinBook("IN", 100.0d, 95.0d, 100_000_000L, 100_000_000L);

		CraftQuote quote = quote(recipe("OUT", 1, "IN", 1), 1_000L).orElseThrow();

		assertEquals(CraftQuote.Bound.CAPITAL, quote.bound());
		assertEquals(10L, quote.crafts());
		assertTrue(quote.capitalRequired() <= 1_000L);
	}

	/** Nothing clearing is inventory, not a flip. */
	@Test
	void refusesWhenTheOutputDoesNotTrade() {
		book("OUT", 1000.1d, 950.0d, 0L, 0L);
		book("IN", 10.0d, 9.0d, 1_000_000L, 1_000_000L);

		assertTrue(quote(recipe("OUT", 1, "IN", 1)).isEmpty());
	}

	/**
	 * A loss-making recipe still quotes. Refusing here would hide the answer from a caller asking
	 * what a craft is worth; ranking is the strategy's job, not the quote's.
	 */
	@Test
	void quotesARecipeThatLosesMoney() {
		book("OUT", 100.1d, 95.0d, 1_000_000L, 1_000_000L);
		book("IN", 1000.0d, 999.0d, 1_000_000L, 1_000_000L);

		CraftQuote quote = quote(recipe("OUT", 1, "IN", 1)).orElseThrow();

		assertTrue(quote.netPerCraft() < 0.0d);
		assertTrue(quote.profitPerHour() < 0.0d);
	}

	/** Without measured history the rate is an assumption, and a caller must be able to say so. */
	@Test
	void reportsWhetherTheRateWasMeasured() {
		book("OUT", 1000.1d, 950.0d, 1_000_000L, 1_000_000L);
		book("IN", 10.0d, 9.0d, 1_000_000L, 1_000_000L);

		assertFalse(quote(recipe("OUT", 1, "IN", 1)).orElseThrow().fillMeasured());
	}

	/** Profit per hour is the ranking axis, so it may not quote a horizon's throughput as hourly. */
	@Test
	void doesNotQuoteMoreThroughputThanThePlanHolds() {
		book("OUT", 1000.1d, 950.0d, 1_000_000L, 1_000_000L);
		book("IN", 100.0d, 95.0d, 1_000_000L, 1_000_000L);

		CraftQuote quote = CraftQuote.quote(recipe("OUT", 1, "IN", 1), bazaar(), FLIPPER_1,
				CraftQuote.FillHistory.none(), Duration.ofHours(8), PLENTY).orElseThrow();

		assertTrue(quote.profitPerHour() <= quote.totalNetProfit() / 8.0d + 1e-6,
				"an 8-hour plan quoted " + quote.profitPerHour() + " an hour");
	}
}
