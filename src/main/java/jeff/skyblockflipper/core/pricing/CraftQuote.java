package jeff.skyblockflipper.core.pricing;

import jeff.skyblockflipper.core.model.BazaarProduct;
import jeff.skyblockflipper.core.model.BazaarSnapshot;
import jeff.skyblockflipper.core.model.UpgradeCost;
import jeff.skyblockflipper.core.pricing.FillModel.FillEstimate;
import jeff.skyblockflipper.core.recipe.Recipe;
import jeff.skyblockflipper.core.valuation.FillStats;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

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
 * <h2>The inputs have two routes, and the cheap one usually wins</h2>
 *
 * <p>Materials can be <b>instant-bought</b> at the ask, or <b>bought on a resting order</b> one
 * increment above the best bid. Both are priced and the better profit per hour is the one quoted,
 * reported as an {@link InputRoute}:
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
 * <p><b>Measured on the live book, the resting route won on all 31 recipes that passed every gate,
 * taking the best eight from 1.29M coins an hour to 8.67M.</b> Most of that is not the 3-21% saved
 * on the bill. It is the fill rate, and it is largest exactly where the strategy makes its money:
 * farm materials are dumped into buy orders constantly and instant-bought rarely, so
 * {@code TARANTULA_SILK} runs a thousand times faster on a resting order than on an instant buy at a
 * cost saving of only 5.2%. Several recipes - {@code CONDENSED_HELIANTHUS},
 * {@code FLAWLESS_JASPER_GEM} - are outright losses instant-bought and solid flips on a resting
 * order, so this is not a refinement of the margin but the difference between a flip existing and
 * not.
 *
 * <p>The route is chosen per recipe, but an ingredient whose own book fails
 * {@link #liquid(BazaarProduct)} is instant-bought even inside the resting route. A resting price is
 * only a real price if the book behind it is real: without that check an 89%-spread book quoted an
 * 89% cost saving and three such recipes ranked in the top eight.
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
 *   <li><b>Any ingredient is unpriceable</b>, or the visible book cannot cover the planned quantity
 *       on the instant route. A bill that silently omits what it could not find understates the
 *       cost, which on this number always reads as a better deal than it is.</li>
 *   <li><b>Nothing clears.</b> A recipe whose legs fill no units an hour is inventory, not a
 *       flip.</li>
 * </ol>
 *
 * <p>Pure and static, like {@link Fees} and {@link FillModel}: same book, same answer.
 *
 * @param recipe            what this prices
 * @param route             how the inputs are bought, chosen by profit per hour
 * @param crafts            crafts planned over the horizon, after every rate limit
 * @param craftsPerHour     sustainable rate, the smallest of the ceilings above
 * @param inputCostPerCraft coins to buy one craft's inputs by {@link #route()}
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

	/** Which leg decides the rate. */
	public enum Bound {
		/** The output's sell offer clears slower than the inputs arrive. */
		SELL_LEG,
		/** One ingredient cannot be acquired fast enough to keep the crafting going. */
		INGREDIENT_SUPPLY,
		/** Coins ran out before either flow did. */
		CAPITAL
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
	 * Prices {@code recipe} against a live book, by whichever input route pays better per hour.
	 *
	 * @param history    measured displacement per product; see {@link FillHistory#none()}
	 * @param horizon    how long the player will leave an order resting
	 * @param maxCapital the most coins this one plan may tie up
	 * @param flowShare  fraction of flow to assume; see {@link #DEFAULT_FLOW_SHARE}
	 */
	public static Optional<CraftQuote> quote(Recipe recipe, BazaarSnapshot bazaar, Fees fees,
			FillHistory history, Duration horizon, long maxCapital, double flowShare) {
		Exit exit = exit(recipe, bazaar, fees, history, horizon, maxCapital, flowShare);

		if (exit == null) {
			return Optional.empty();
		}

		Optional<CraftQuote> instant = exit.price(recipe, bazaar, fees, InputRoute.INSTANT_BUY,
				horizon, maxCapital, flowShare);
		Optional<CraftQuote> resting = exit.price(recipe, bazaar, fees, InputRoute.BUY_ORDER,
				horizon, maxCapital, flowShare);

		if (instant.isEmpty()) {
			return resting;
		}

		if (resting.isEmpty()) {
			return instant;
		}

		// Ranked on the same axis everything else is. The routes differ in what they cost the
		// player beyond coins - slots and patience - which is the strategy layer's to weigh.
		return Optional.of(resting.get().profitPerHour() > instant.get().profitPerHour()
				? resting.get()
				: instant.get());
	}

	/** {@link #quote} with the shared flow share, which is what every caller without an opinion wants. */
	public static Optional<CraftQuote> quote(Recipe recipe, BazaarSnapshot bazaar, Fees fees,
			FillHistory history, Duration horizon, long maxCapital) {
		return quote(recipe, bazaar, fees, history, horizon, maxCapital, DEFAULT_FLOW_SHARE);
	}

	/**
	 * Prices {@code recipe} on one named input route rather than on whichever pays better.
	 *
	 * <p>For the caller that has a reason to refuse a route rather than a preference about it. The
	 * strategy layer has one: {@link #orderSlots()} is a hard budget shared with every other
	 * strategy at once, and a recipe whose resting route wants seven slots is still a flip on the
	 * instant route at one. Asking for the route directly is the difference between offering that
	 * flip and dropping it.
	 */
	public static Optional<CraftQuote> quote(Recipe recipe, BazaarSnapshot bazaar, Fees fees,
			FillHistory history, Duration horizon, long maxCapital, double flowShare,
			InputRoute route) {
		Exit exit = exit(recipe, bazaar, fees, history, horizon, maxCapital, flowShare);

		return exit == null
				? Optional.empty()
				: exit.price(recipe, bazaar, fees, route, horizon, maxCapital, flowShare);
	}

	/**
	 * The half of the quote both routes share: the output book, the offer price, and what that
	 * offer sheds per hour.
	 *
	 * <p>Held apart so pricing a second route costs the arithmetic that actually differs. Null
	 * where the output alone already refuses, which is every route's answer too.
	 */
	private record Exit(double sellPrice, double sellCraftsPerHour, FillHistory history,
			FillEstimate fill) {
		Optional<CraftQuote> price(Recipe recipe, BazaarSnapshot bazaar, Fees fees, InputRoute route,
				Duration horizon, long maxCapital, double flowShare) {
			return CraftQuote.price(recipe, bazaar, fees, route, history, horizon, maxCapital,
					flowShare, sellPrice, sellCraftsPerHour, fill);
		}
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

	private static Optional<CraftQuote> price(Recipe recipe, BazaarSnapshot bazaar, Fees fees,
			InputRoute route, FillHistory history, Duration horizon, long maxCapital,
			double flowShare, double sellPrice, double sellCraftsPerHour, FillEstimate fill) {
		double craftsPerHour = sellCraftsPerHour;
		Bound bound = Bound.SELL_LEG;
		String boundProduct = recipe.outputId();
		List<String> resting = new ArrayList<>();

		for (UpgradeCost.Ingredient ingredient : recipe.ingredients()) {
			BazaarProduct input = bazaar.product(ingredient.productId()).orElse(null);

			if (input == null || input.sellOffers().isEmpty()) {
				return Optional.empty();
			}

			double supply;

			if (restable(route, input)) {
				resting.add(ingredient.productId());
				supply = FillModel.estimate(input, history.forProduct(ingredient.productId()), horizon,
						flowShare).buyUnitsPerHour() / ingredient.amount();
			} else {
				// Nothing rests, so there is no displacement to discount - only the rate at which
				// the ask side is actually consumed, of which we may claim a share.
				supply = input.instantBuysPerHour() * flowShare / ingredient.amount();
			}

			if (supply < craftsPerHour) {
				craftsPerHour = supply;
				bound = Bound.INGREDIENT_SUPPLY;
				boundProduct = ingredient.productId();
			}
		}

		if (craftsPerHour <= 0.0d) {
			return Optional.empty();
		}

		double horizonHours = hoursOf(horizon);
		long crafts = Math.max(1L, (long) (craftsPerHour * horizonHours));

		// Cost first at the planned size, then again at whatever the coins actually fund. Sizing on
		// a cost quoted for a larger order would fund fewer crafts than it claims.
		OptionalDouble cost = costPerCraft(recipe, bazaar, route, crafts);

		if (cost.isEmpty()) {
			return Optional.empty();
		}

		long affordable = (long) (maxCapital / cost.getAsDouble());

		if (affordable <= 0L) {
			return Optional.empty();
		}

		if (affordable < crafts) {
			crafts = affordable;
			bound = Bound.CAPITAL;
			boundProduct = recipe.outputId();
			cost = costPerCraft(recipe, bazaar, route, crafts);

			if (cost.isEmpty()) {
				return Optional.empty();
			}
		}

		double inputCost = cost.getAsDouble();
		double netPerCraft = fees.bazaarSaleProceeds(sellPrice * recipe.outputCount()) - inputCost;

		// The plan cannot turn over faster than it was sized for: a horizon's worth of crafts in an
		// hour would be quoting the horizon's throughput as an hourly one.
		double ratePerHour = Math.min(craftsPerHour, crafts / horizonHours);

		return Optional.of(new CraftQuote(recipe, route, crafts, craftsPerHour, inputCost, sellPrice,
				netPerCraft, netPerCraft * ratePerHour, bound, boundProduct, resting, fill));
	}

	/**
	 * Whether an ingredient is bought on a resting order under {@code route}.
	 *
	 * <p>The resting route still instant-buys anything whose own book fails {@link #liquid}. Quoting
	 * a bid as a price you can actually pay requires the book behind it to be real, and the three
	 * recipes this rejects from the live top eight were quoting cost savings of 79% to 89% against
	 * bid sides that nothing was resting on.
	 */
	private static boolean restable(InputRoute route, BazaarProduct input) {
		return route == InputRoute.BUY_ORDER && liquid(input);
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
	private static OptionalDouble costPerCraft(Recipe recipe, BazaarSnapshot bazaar, InputRoute route,
			long crafts) {
		double total = 0.0d;

		for (UpgradeCost.Ingredient ingredient : recipe.ingredients()) {
			BazaarProduct input = bazaar.product(ingredient.productId()).orElse(null);

			if (input == null) {
				return OptionalDouble.empty();
			}

			OptionalDouble unit = restable(route, input)
					? input.outbidBuyOrder()
					: input.costToBuy(Math.multiplyExact(crafts, ingredient.amount()));

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
	 * measured that slots bind where coins do not. A six-ingredient recipe on the resting route
	 * asks for seven of them.
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
