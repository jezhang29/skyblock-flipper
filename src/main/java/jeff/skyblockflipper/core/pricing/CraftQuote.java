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
import jeff.skyblockflipper.core.model.UpgradeCost;
import jeff.skyblockflipper.core.pricing.FillModel.FillEstimate;
import jeff.skyblockflipper.core.recipe.Recipe;
import jeff.skyblockflipper.core.valuation.FillStats;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.TreeSet;

/**
 * What one crafting recipe is worth per hour at the current order book.
 *
 * <p>The arithmetic is small. What decides whether the number is real is the route it prices and
 * the gates it refuses at, both of which were measured rather than chosen - see
 * {@code docs/craft-flipping.md}.
 *
 * <h2>The exit is a resting sell offer</h2>
 *
 * <p>The output is sold into an offer one increment under the best ask, never dumped into the bid
 * side and never sold to an NPC. This is the single largest decision in the strategy: measured
 * across the whole recipe table, the offer exit is worth roughly <b>ten times</b> the dump exit,
 * and the first measurement of craft flipping undersold the strategy by exactly that factor by
 * pricing the dump. The NPC exit measured worst of the three and is worse than it measures - an NPC
 * sale draws down the 500M daily cap that already binds the NPC basket strategy, so those coins come
 * out of the daily driver's budget. A bazaar exit does not touch that cap.
 *
 * <h2>Each ingredient is routed on its own</h2>
 *
 * <p>A material can be <b>instant-bought</b> at the ask, or <b>bought on a resting order</b> one
 * increment above the best bid. The two differ in what they cost and in how fast they arrive:
 *
 * <ul>
 *   <li>{@link InputRoute#INSTANT_BUY} costs the ask, depth-walked through the real book for the
 *       quantity planned, and is limited by a share of the rate the ask side is consumed
 *       ({@link BazaarProduct#instantBuysPerHour()}). Nothing rests, so nothing can be displaced.</li>
 *   <li>{@link InputRoute#BUY_ORDER} costs {@link BazaarProduct#outbidBuyOrder()} and is sized by
 *       {@link FillModel}, exactly like the sell leg: arrival flow discounted by how often the order
 *       is outbid. It costs order slots and it costs time, and it can leave the player holding
 *       materials rather than coins.</li>
 * </ul>
 *
 * <p><b>Measured on the live book, resting the materials that are worth resting won on all 31
 * recipes that passed every gate, taking the best eight from 1.29M coins an hour to 8.67M.</b> Most
 * of that is not the 3-21% saved on the bill. It is the fill rate, and it is largest exactly where
 * the strategy makes its money: farm materials are dumped into buy orders constantly and
 * instant-bought rarely, so {@code TARANTULA_SILK} runs a thousand times faster on a resting order
 * than on an instant buy at a cost saving of only 5.2%. Several recipes -
 * {@code CONDENSED_HELIANTHUS}, {@code FLAWLESS_JASPER_GEM} - are outright losses instant-bought and
 * solid flips on a resting order, so this is not a refinement of the margin but the difference
 * between a flip existing and not.
 *
 * <p><b>The route is chosen per ingredient rather than for the recipe, because the rate is a
 * minimum and the bill is a sum.</b> A plan runs at the rate of its slowest leg, so one material
 * nobody dumps into drags the whole recipe down to a trickle - even where instant-buying that one
 * material costs a few percent more and arrives a thousand times faster. Choosing one route for the
 * whole bill can only pick between two corners of that trade; choosing per ingredient reaches every
 * point between them, and the two corners stay in the search, so the answer is never worse than the
 * old one.
 *
 * <p>The search is exact rather than greedy. A plan's rate is one of a small set - the sell leg's,
 * or one of the two rates each ingredient can hold to - so every achievable rate is tried, each
 * ingredient takes the cheaper route that still holds that rate, and the best of those plans wins.
 * That is the cheapest bill at every rate, so nothing between the corners is missed.
 *
 * <p>An ingredient whose own book fails {@link #liquid(BazaarProduct)} is instant-bought whatever
 * the arithmetic says. A resting price is only a real price if the book behind it is real: without
 * that check an 89%-spread book quoted an 89% cost saving and three such recipes ranked in the top
 * eight.
 *
 * <h2>What it refuses</h2>
 *
 * <p>Empty, never a partial answer, when any of these holds. Each was measured to produce a
 * plausible number rather than an error:
 *
 * <ol>
 *   <li><b>The output book is thin or wide.</b> Under {@link #MIN_ORDERS_PER_SIDE} resting orders a
 *       side, or a spread over {@link #MAX_PLAUSIBLE_SPREAD_FRACTION}. Without these the resting
 *       basis produces margins of 30,000% on books where the bid is near zero and the ask enormous.
 *       <b>This is the gate that actually moves.</b> Both headline recipes from the first snapshot
 *       dropped out a day later on it, their resting bids falling to 12 and to 8, without either
 *       losing a coin of margin.</li>
 *   <li><b>Any ingredient is unpriceable</b>, or neither route can supply it at any rate the rest of
 *       the plan can run at. A bill that silently omits what it could not find understates the
 *       cost, which on this number always reads as a better deal than it is.</li>
 *   <li><b>Nothing clears.</b> A recipe whose legs fill no units an hour is inventory, not a
 *       flip.</li>
 * </ol>
 *
 * <p>Pure and static, like {@link Fees} and {@link FillModel}: same book, same answer.
 *
 * @param recipe            what this prices
 * @param route             whether anything rests: {@link InputRoute#BUY_ORDER} when at least one
 *                          ingredient is bought on an order, {@link InputRoute#INSTANT_BUY} when the
 *                          whole bill is taken at the ask. Which ingredients those are is
 *                          {@link #restingBuyOrders()}
 * @param crafts            crafts planned over the horizon, after every rate limit
 * @param craftsPerHour     sustainable rate, the smallest of the ceilings above
 * @param inputCostPerCraft coins to buy one craft's inputs on the routes chosen
 * @param unitSellPrice     coins per output unit before tax: one increment under the best ask
 * @param netPerCraft       profit per craft after tax on the sale
 * @param profitPerHour     the ranking axis
 * @param bound             which leg holds the rate down
 * @param boundProductId    the product that {@link #bound} names, so the UI can say which one
 * @param restingBuyOrders  ingredients bought on a resting order, each of which occupies one of the
 *                          player's bazaar order slots for as long as it takes to fill
 * @param fill              the sell leg's estimate, carried so a candidate can report whether the
 *                          rate was measured from history or assumed
 */
public record CraftQuote(
		Recipe recipe,
		InputRoute route,
		long crafts,
		double craftsPerHour,
		double inputCostPerCraft,
		double unitSellPrice,
		double netPerCraft,
		double profitPerHour,
		Bound bound,
		String boundProductId,
		List<String> restingBuyOrders,
		FillEstimate fill
) {
	/** How the materials are acquired. */
	public enum InputRoute {
		/** Take the ask now. Dearer and slower to source, but immediate and it rests nothing. */
		INSTANT_BUY,
		/** Rest an order one increment above the best bid. Cheaper and usually faster, but it waits. */
		BUY_ORDER
	}

	/** What holds the plan down. */
	public enum Bound {
		/** The output's sell offer clears slower than the inputs arrive. */
		SELL_LEG,
		/** One ingredient cannot be acquired fast enough to keep the crafting going. */
		INGREDIENT_SUPPLY,
		/** Coins ran out before either flow did. */
		CAPITAL,
		/** The plan holds only as much as the player's order slots can rest at once. */
		SLOTS
	}

	public CraftQuote {
		restingBuyOrders = List.copyOf(restingBuyOrders);
	}

	/**
	 * Resting orders required on each side of a book before a resting price quoted against it is
	 * believed.
	 *
	 * <p>Same figure as {@code BazaarSpreadStrategy} uses, and for the same reason: a book held up
	 * by a handful of orders is trivially pulled. Counted across the whole returned depth rather
	 * than the top level, which normally holds one to three orders even on healthy books.
	 */
	public static final int MIN_ORDERS_PER_SIDE = 15;

	/** A spread wider than this means the two sides are not pricing the same thing. */
	public static final double MAX_PLAUSIBLE_SPREAD_FRACTION = 0.25d;

	/**
	 * Share of an item's flow one crafter may assume they capture.
	 *
	 * <p>The same 5% {@code BazaarSpreadStrategy} assumes. Used for the instant route and as
	 * {@link FillModel}'s fallback on the resting legs, so a product the tape has measured is sized
	 * from its own history and only an unmeasured one falls back to this.
	 */
	public static final double DEFAULT_FLOW_SHARE = 0.05d;

	/**
	 * Slack when asking whether a leg can hold a rate.
	 *
	 * <p>Every candidate rate is itself one of the leg rates, so the comparison that decides
	 * feasibility is a double against a copy of itself that has been through a divide and a
	 * multiply. Without the slack a leg can fail to hold the very rate it set.
	 */
	private static final double RATE_TOLERANCE = 1e-9d;

	/** Measured displacement for a product, or null where the tape has not covered it. */
	@FunctionalInterface
	public interface FillHistory {
		FillStats forProduct(String productId);

		/** What a caller with no tape passes, and what a fresh install effectively has. */
		static FillHistory none() {
			return productId -> null;
		}
	}

	/**
	 * Prices {@code recipe} against a live book, routing each ingredient by whichever way pays best
	 * for the plan as a whole.
	 *
	 * @param history    measured displacement per product; see {@link FillHistory#none()}
	 * @param horizon    how long the player will leave an order resting
	 * @param maxCapital the most coins this one plan may tie up
	 * @param flowShare  fraction of flow to assume; see {@link #DEFAULT_FLOW_SHARE}
	 */
	public static Optional<CraftQuote> quote(Recipe recipe, BazaarSnapshot bazaar, Fees fees,
			FillHistory history, Duration horizon, long maxCapital, double flowShare) {
		return quote(recipe, bazaar, fees, history, horizon, maxCapital, flowShare, Set.of());
	}

	/** {@link #quote} with the shared flow share, which is what every caller without an opinion wants. */
	public static Optional<CraftQuote> quote(Recipe recipe, BazaarSnapshot bazaar, Fees fees,
			FillHistory history, Duration horizon, long maxCapital) {
		return quote(recipe, bazaar, fees, history, horizon, maxCapital, DEFAULT_FLOW_SHARE);
	}

	/**
	 * Prices {@code recipe} with {@code instantOnly} taken at the ask whatever the arithmetic says.
	 *
	 * <p>For the caller that has to give slots back rather than coins. {@link #orderSlots()} is a
	 * hard budget shared with every other strategy at once, and the only way to shrink a plan's
	 * claim on it is to stop resting one of its legs. Naming the leg rather than the route is what
	 * lets the strategy give up the slot-hungry one and keep the rest of the plan cheap, instead of
	 * paying the ask on the whole bill.
	 */
	public static Optional<CraftQuote> quote(Recipe recipe, BazaarSnapshot bazaar, Fees fees,
			FillHistory history, Duration horizon, long maxCapital, double flowShare,
			Set<String> instantOnly) {
		return quote(recipe, bazaar, fees, history, horizon, maxCapital, flowShare, instantOnly,
				Long.MAX_VALUE);
	}

	/**
	 * The same, held to at most {@code maxCrafts} crafts however much flow and coin there is.
	 *
	 * <p>The ceiling a caller reaches for is order slots. A plan sized to a whole horizon of flow
	 * can want far more of a material than the player's slots can rest at once - 1,269,600 units of
	 * mithril ore is eighteen bazaar orders against a budget of six - and the answer to that is a
	 * smaller plan at the same margin, not a different route. Measured on the live book of
	 * 2026-08-20: {@code ENCHANTED_MITHRIL} and {@code ENCHANTED_WHEAT} were the two plans that
	 * overran the shipped budget, and buying their bill at the ask instead dropped them from
	 * 3.13M and 5.18M coins an hour to 1,123 and 4,774 - because nobody instant-sells ore or wheat,
	 * so the ask side supplies a trickle. Sizing them to six slots keeps the margin and takes the
	 * share of the flow those slots can actually hold.
	 *
	 * <p>How many crafts that is depends on how many units of each material one order holds, which
	 * needs the item catalog as well as the book. Both live a layer up, so the ceiling arrives as a
	 * number rather than as a rule this class works out.
	 */
	public static Optional<CraftQuote> quote(Recipe recipe, BazaarSnapshot bazaar, Fees fees,
			FillHistory history, Duration horizon, long maxCapital, double flowShare,
			Set<String> instantOnly, long maxCrafts) {
		return plan(recipe, bazaar, fees, history, horizon, maxCapital, flowShare,
				instantOnly == null ? Set.of() : instantOnly, false, maxCrafts);
	}

	/**
	 * Prices {@code recipe} on one named route for the whole bill rather than per ingredient.
	 *
	 * <p>{@link InputRoute#INSTANT_BUY} rests nothing but the sell offer;
	 * {@link InputRoute#BUY_ORDER} rests every ingredient whose book is real enough to rest against.
	 * Both are corners of the search {@link #quote(Recipe, BazaarSnapshot, Fees, FillHistory,
	 * Duration, long, double)} already covers, kept for the caller that wants to price one corner
	 * and compare rather than be handed the winner.
	 */
	public static Optional<CraftQuote> quote(Recipe recipe, BazaarSnapshot bazaar, Fees fees,
			FillHistory history, Duration horizon, long maxCapital, double flowShare,
			InputRoute route) {
		return plan(recipe, bazaar, fees, history, horizon, maxCapital, flowShare,
				route == InputRoute.INSTANT_BUY ? everyIngredient(recipe) : Set.of(),
				route == InputRoute.BUY_ORDER, Long.MAX_VALUE);
	}

	private static Set<String> everyIngredient(Recipe recipe) {
		if (recipe == null) {
			return Set.of();
		}

		Set<String> ids = new HashSet<>();

		for (UpgradeCost.Ingredient ingredient : recipe.ingredients()) {
			ids.add(ingredient.productId());
		}

		return ids;
	}

	/**
	 * The whole search: price the exit once, then try every rate the plan could run at.
	 *
	 * @param instantOnly ingredients that may not rest, whatever it costs
	 * @param restAlways  whether an ingredient that can rest must, which is what asking for
	 *                    {@link InputRoute#BUY_ORDER} by name means
	 * @param maxCrafts   a hard ceiling on the plan's size, whatever the flow and the coins allow
	 */
	private static Optional<CraftQuote> plan(Recipe recipe, BazaarSnapshot bazaar, Fees fees,
			FillHistory history, Duration horizon, long maxCapital, double flowShare,
			Set<String> instantOnly, boolean restAlways, long maxCrafts) {
		Exit exit = exit(recipe, bazaar, fees, history, horizon, maxCapital, flowShare);

		if (exit == null) {
			return Optional.empty();
		}

		List<Leg> legs = legs(recipe, bazaar, exit.history(), horizon, flowShare, instantOnly,
				restAlways);

		if (legs == null) {
			return Optional.empty();
		}

		CraftQuote best = null;

		for (double target : candidateRates(exit.sellCraftsPerHour(), legs)) {
			CraftQuote quote = at(target, recipe, bazaar, fees, exit, legs, horizon, maxCapital,
					maxCrafts);

			if (quote != null && (best == null || quote.profitPerHour() > best.profitPerHour())) {
				best = quote;
			}
		}

		return Optional.ofNullable(best);
	}

	/**
	 * The half of the quote every rate shares: the output book, the offer price, and what that
	 * offer sheds per hour.
	 *
	 * <p>Held apart so trying a second rate costs the arithmetic that actually differs. Null where
	 * the output alone already refuses, which is every plan's answer too.
	 */
	private record Exit(double sellPrice, double sellCraftsPerHour, FillHistory history,
			FillEstimate fill) {
	}

	private static Exit exit(Recipe recipe, BazaarSnapshot bazaar, Fees fees, FillHistory history,
			Duration horizon, long maxCapital, double flowShare) {
		if (recipe == null || bazaar == null || fees == null || maxCapital <= 0L) {
			return null;
		}

		BazaarProduct output = bazaar.product(recipe.outputId()).orElse(null);

		if (output == null || !liquid(output)) {
			return null;
		}

		double sellPrice = output.undercutSellOffer().orElseThrow();

		if (sellPrice <= 0.0d) {
			return null;
		}

		FillHistory lookup = history == null ? FillHistory.none() : history;
		FillEstimate fill = FillModel.estimate(output, lookup.forProduct(recipe.outputId()), horizon,
				flowShare);

		// Units the offer sheds per hour, converted to crafts. A recipe yielding 160 at a time needs
		// the book to absorb 160 units before it may craft again.
		return new Exit(sellPrice, fill.sellUnitsPerHour() / recipe.outputCount(), lookup, fill);
	}

	/**
	 * One ingredient's two ways in, both priced and both rated, so a plan can pick between them.
	 *
	 * <p>The resting cost is per craft and fixed: an order sits at one price and fills there or not
	 * at all. The instant cost is not here because it depends on how much is being bought - the ask
	 * book is walked, and every level below the first is dearer.
	 *
	 * @param restable    whether a resting order on this book means anything, and is allowed
	 * @param instantable whether taking the ask is allowed. False only where the caller has asked
	 *                    for the resting corner by name
	 */
	private record Leg(String productId, long amount, boolean restable, boolean instantable,
			double restCostPerCraft, double restRate, double instantRate) {
	}

	/**
	 * Both routes for every ingredient, or null where one of them cannot be priced at all.
	 *
	 * <p>Null rather than a shorter list: a bill missing an ingredient understates the cost, and
	 * understating the cost always reads as a better deal than it is.
	 */
	private static List<Leg> legs(Recipe recipe, BazaarSnapshot bazaar, FillHistory history,
			Duration horizon, double flowShare, Set<String> instantOnly, boolean restAlways) {
		List<Leg> legs = new ArrayList<>(recipe.ingredients().size());

		for (UpgradeCost.Ingredient ingredient : recipe.ingredients()) {
			BazaarProduct input = bazaar.product(ingredient.productId()).orElse(null);

			if (input == null || input.sellOffers().isEmpty()) {
				return null;
			}

			long amount = ingredient.amount();
			boolean restable = !instantOnly.contains(ingredient.productId()) && liquid(input);
			double restCost = 0.0d;
			double restRate = 0.0d;

			if (restable) {
				double price = input.outbidBuyOrder().orElse(0.0d);

				if (price <= 0.0d) {
					restable = false;
				} else {
					restCost = price * amount;
					restRate = FillModel.estimate(input, history.forProduct(ingredient.productId()),
							horizon, flowShare).buyUnitsPerHour() / amount;
				}
			}

			legs.add(new Leg(ingredient.productId(), amount, restable, !(restAlways && restable),
					restCost, restRate, input.instantBuysPerHour() * flowShare / amount));
		}

		return legs;
	}

	/**
	 * Every rate the plan could run at.
	 *
	 * <p>A plan's rate is the smallest of the sell leg's and one per ingredient, so the rate of the
	 * best plan is always one of these. Rates at or above the sell leg's are folded into it: the
	 * offer cannot shed faster than it sheds, so asking an ingredient to beat it buys nothing and
	 * only narrows the choice of route.
	 */
	private static double[] candidateRates(double sellCraftsPerHour, List<Leg> legs) {
		if (sellCraftsPerHour <= 0.0d) {
			return new double[0];
		}

		TreeSet<Double> rates = new TreeSet<>();
		rates.add(sellCraftsPerHour);

		for (Leg leg : legs) {
			if (leg.restable() && leg.restRate() > 0.0d && leg.restRate() < sellCraftsPerHour) {
				rates.add(leg.restRate());
			}

			if (leg.instantable() && leg.instantRate() > 0.0d
					&& leg.instantRate() < sellCraftsPerHour) {
				rates.add(leg.instantRate());
			}
		}

		double[] out = new double[rates.size()];
		int index = 0;

		for (double rate : rates) {
			out[index++] = rate;
		}

		return out;
	}

	/**
	 * Which route each ingredient takes to hold one rate, and what that bill comes to.
	 *
	 * @param resting  ingredients bought on an order, in recipe order
	 * @param rate     the slowest leg's rate, which is what the plan actually runs at
	 * @param slowest  the ingredient that {@link #rate} belongs to
	 */
	private record Sourcing(List<String> resting, double rate, String slowest) {
	}

	/**
	 * The cheapest way to supply every ingredient at {@code target} crafts an hour, or null when one
	 * of them cannot be supplied that fast by either route.
	 *
	 * <p>Cheapest at a fixed rate is the whole trick: profit per hour is (revenue - bill) times the
	 * rate, so once the rate is pinned the only thing left to do is minimise the bill. Trying that
	 * at every achievable rate is what makes the answer exact rather than greedy.
	 */
	private static Sourcing cheapest(List<Leg> legs, double target, BazaarSnapshot bazaar,
			long crafts) {
		double bar = target * (1.0d - RATE_TOLERANCE);
		List<String> resting = new ArrayList<>();
		double slowestRate = Double.MAX_VALUE;
		String slowest = null;

		for (Leg leg : legs) {
			boolean canRest = leg.restable() && leg.restRate() >= bar;
			OptionalDouble instantUnit = leg.instantable() && leg.instantRate() >= bar
					? bazaar.product(leg.productId()).orElseThrow()
							.costToBuy(Math.multiplyExact(crafts, leg.amount()))
					: OptionalDouble.empty();

			boolean rest;

			if (canRest && instantUnit.isPresent()) {
				rest = leg.restCostPerCraft() <= instantUnit.getAsDouble() * leg.amount();
			} else if (canRest) {
				rest = true;
			} else if (instantUnit.isPresent()) {
				rest = false;
			} else {
				// Neither route holds this rate, or the visible book cannot cover the instant one.
				return null;
			}

			double rate = rest ? leg.restRate() : leg.instantRate();

			if (rest) {
				resting.add(leg.productId());
			}

			if (rate < slowestRate) {
				slowestRate = rate;
				slowest = leg.productId();
			}
		}

		return new Sourcing(resting, slowestRate, slowest);
	}

	/** One whole plan, priced at the rate its slowest chosen leg actually holds. */
	private static CraftQuote at(double target, Recipe recipe, BazaarSnapshot bazaar, Fees fees,
			Exit exit, List<Leg> legs, Duration horizon, long maxCapital, long maxCrafts) {
		double horizonHours = hoursOf(horizon);
		long planned = Math.max(1L, (long) (target * horizonHours));
		Sourcing sourcing = cheapest(legs, target, bazaar, planned);

		if (sourcing == null) {
			return null;
		}

		// The chosen routes may all be faster than the rate they were picked at, in which case the
		// plan really runs at the faster one - every leg holds it by construction.
		double craftsPerHour = Math.min(exit.sellCraftsPerHour(), sourcing.rate());

		if (craftsPerHour <= 0.0d) {
			return null;
		}

		Bound bound;
		String boundProduct;

		if (sourcing.rate() < exit.sellCraftsPerHour()) {
			bound = Bound.INGREDIENT_SUPPLY;
			boundProduct = sourcing.slowest();
		} else {
			bound = Bound.SELL_LEG;
			boundProduct = recipe.outputId();
		}

		long crafts = Math.max(1L, (long) (craftsPerHour * horizonHours));

		// Slots before coins, because the ceiling is structural: it is how much of the material can
		// be resting at once, and the bill is quoted for whatever ends up being bought.
		if (maxCrafts >= 1L && maxCrafts < crafts) {
			crafts = maxCrafts;
			bound = Bound.SLOTS;
			boundProduct = recipe.outputId();
		}

		// Cost first at the planned size, then again at whatever the coins actually fund. Sizing on
		// a cost quoted for a larger order would fund fewer crafts than it claims.
		OptionalDouble cost = costPerCraft(recipe, bazaar, sourcing.resting(), crafts);

		if (cost.isEmpty()) {
			return null;
		}

		long affordable = (long) (maxCapital / cost.getAsDouble());

		if (affordable <= 0L) {
			return null;
		}

		if (affordable < crafts) {
			crafts = affordable;
			bound = Bound.CAPITAL;
			boundProduct = recipe.outputId();
			cost = costPerCraft(recipe, bazaar, sourcing.resting(), crafts);

			if (cost.isEmpty()) {
				return null;
			}
		}

		double inputCost = cost.getAsDouble();
		double netPerCraft = fees.bazaarSaleProceeds(exit.sellPrice() * recipe.outputCount())
				- inputCost;

		// The plan cannot turn over faster than it was sized for: a horizon's worth of crafts in an
		// hour would be quoting the horizon's throughput as an hourly one.
		double ratePerHour = Math.min(craftsPerHour, crafts / horizonHours);

		return new CraftQuote(recipe,
				sourcing.resting().isEmpty() ? InputRoute.INSTANT_BUY : InputRoute.BUY_ORDER,
				crafts, craftsPerHour, inputCost, exit.sellPrice(), netPerCraft,
				netPerCraft * ratePerHour, bound, boundProduct, sourcing.resting(), exit.fill());
	}

	/**
	 * Whether a book is deep enough and tight enough for a resting price quoted against it to mean
	 * anything.
	 *
	 * <p>Public because a caller that wants to explain a refusal has to ask the same question this
	 * does, and a second copy of the thresholds is how the explanation would drift from the rule.
	 */
	public static boolean liquid(BazaarProduct product) {
		if (product.sellOffers().isEmpty() || product.buyOrders().isEmpty()) {
			return false;
		}

		if (product.sellOfferCount() < MIN_ORDERS_PER_SIDE
				|| product.buyOrderCount() < MIN_ORDERS_PER_SIDE) {
			return false;
		}

		double ask = product.instantBuyPrice().orElseThrow();
		double bid = product.instantSellPrice().orElseThrow();

		return ask > 0.0d && (ask - bid) / ask < MAX_PLAUSIBLE_SPREAD_FRACTION;
	}

	/**
	 * Coins for one craft's inputs when buying enough for {@code crafts} of them.
	 *
	 * <p>An instant buy is depth-walked, because eating several price levels is what a real order
	 * does and every level below the first is dearer. A resting order is not: it sits at one price
	 * and fills at that price or not at all, so the walk would be pricing a trade nobody makes.
	 *
	 * <p>Empty, never a partial total, when one ingredient cannot be covered.
	 */
	private static OptionalDouble costPerCraft(Recipe recipe, BazaarSnapshot bazaar,
			List<String> resting, long crafts) {
		double total = 0.0d;

		for (UpgradeCost.Ingredient ingredient : recipe.ingredients()) {
			BazaarProduct input = bazaar.product(ingredient.productId()).orElse(null);

			if (input == null) {
				return OptionalDouble.empty();
			}

			OptionalDouble unit = resting.contains(ingredient.productId())
					? input.outbidBuyOrder()
					: input.costToBuy(Math.multiplyExact(crafts, (long) ingredient.amount()));

			if (unit.isEmpty()) {
				return OptionalDouble.empty();
			}

			total += unit.getAsDouble() * ingredient.amount();
		}

		return OptionalDouble.of(total);
	}

	/**
	 * Bazaar order slots the plan occupies, counting one order per resting leg: one sell offer,
	 * plus one per ingredient resting.
	 *
	 * <p>Carried because slots are a hard budget the player shares across every strategy at once -
	 * 14 to 28 of them, from {@link Fees#bazaarOrderSlots()} - and the NPC basket has already
	 * measured that slots bind where coins do not. A six-ingredient recipe with every material on an
	 * order asks for seven of them.
	 *
	 * <p><b>A lower bound, not the count to budget against.</b> One order per leg holds only while
	 * every leg fits in one order, and a bazaar order takes 71,680 units of an item that stacks
	 * against 256 of one that does not. A craft wanting 58,624 units of an unstackable material
	 * needs 229 orders, not one. {@code CraftJob.orderSlots()} counts the real orders, and that is
	 * what the strategy spends its budget on; this stays as the shape of the plan, which is what a
	 * caller with no catalog to hand can still ask for.
	 */
	public int orderSlots() {
		return 1 + restingBuyOrders.size();
	}

	/** Total coins tied up to run the plan. */
	public long capitalRequired() {
		return Math.round(inputCostPerCraft * crafts);
	}

	/** Output units the plan produces, which is what goes on the sell offer. */
	public long outputUnits() {
		return crafts * recipe.outputCount();
	}

	public double totalNetProfit() {
		return netPerCraft * crafts;
	}

	/** Whether the sell leg's rate came from recorded history rather than an assumed share. */
	public boolean fillMeasured() {
		return fill != null && fill.measured();
	}

	private static double hoursOf(Duration horizon) {
		double hours = horizon == null ? 0.0d : horizon.toMillis() / 3_600_000.0d;

		return hours > 0.0d ? hours : 1.0d;
	}
}
