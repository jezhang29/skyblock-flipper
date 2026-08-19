package jeff.skyblockflipper.core.strategy;

import jeff.skyblockflipper.core.model.BazaarProduct;
import jeff.skyblockflipper.core.model.BazaarSnapshot;
import jeff.skyblockflipper.core.model.ItemCatalog;
import jeff.skyblockflipper.core.model.Stacking;
import jeff.skyblockflipper.core.model.UpgradeCost;
import jeff.skyblockflipper.core.pricing.CraftQuote;
import jeff.skyblockflipper.core.pricing.CraftQuote.InputRoute;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * One craft flip as a list of clicks, in the order to make them.
 *
 * <p>This is to crafting what {@link NpcWorklist} is to the basket, and it exists for the same
 * reason: the flip screen, the bazaar overlay and {@code /flip craft} all have to describe the same
 * job, and three renderers each formatting a price from the quote is three chances to disagree by a
 * tenth of a coin. A panel that says 1061.9 while the screen behind it says 1062.0 is worse than
 * either number on its own, because the player cannot tell which one the mod meant.
 *
 * <p>Rebuilt from the live book rather than frozen at selection time. Prices move while you are
 * typing them, and a plan that quietly goes stale is the failure this avoids; the flip is still the
 * same flip, so the job keeps its identity through a rebuild and only its numbers change.
 *
 * @param outputId    the crafted item
 * @param displayName its name, resolved once so no renderer has to
 * @param rows        the clicks, materials first and the sell offer last
 * @param quote       what the rows were priced from, for the handful of figures a view wants
 */
public record CraftJob(String outputId, String displayName, List<Row> rows, CraftQuote quote) {
	/** What one row asks the player to do. */
	public enum Action {
		/** Rest a buy order at this price and wait for someone to sell into it. */
		BUY_ORDER("Buy Order", true),
		/** Take the ask now. No price to type, so none is shown. */
		INSTANT_BUY("Buy Instantly", false),
		/** Leave the bazaar and put the materials together. */
		CRAFT("Craft", false),
		/** Rest the finished item one increment under the cheapest offer. */
		SELL_OFFER("Sell Offer", true);

		private final String label;
		private final boolean priced;

		Action(String label, boolean priced) {
			this.label = label;
			this.priced = priced;
		}

		public String label() {
			return label;
		}

		/** Whether this row has a price the player types, as opposed to a button they press. */
		public boolean priced() {
			return priced;
		}
	}

	/**
	 * @param price      coins per unit to type, or 0 where {@link Action#priced()} is false
	 * @param units      how many, in the unit the row's screen counts in
	 * @param orderSplit how {@code units} divides into orders the bazaar will actually accept, e.g.
	 *                   {@code 3 x 256 + 112}. A craft can want tens of thousands of a material, and
	 *                   an item that does not stack takes 256 at a time, so a bare total here is a
	 *                   number that gets typed into a box that refuses it
	 * @param orders     how many orders that split is, which is what the row really costs in slots
	 */
	public record Row(Action action, String itemId, String displayName, double price, long units,
			String orderSplit, int orders) {
		/**
		 * One line, for the places that render a job as text rather than as rows.
		 *
		 * <p>The split is quoted after the total rather than instead of it, because the two answer
		 * different questions: the total is how much of the material the flip needs, and the split
		 * is how many trips to the order box that takes.
		 */
		public String describe() {
			String amount = orderSplit.equals(String.valueOf(units))
					? "x" + units
					: "x" + units + " (" + orderSplit + ")";

			return action.priced()
					? String.format("%s: %s at %.1f %s", action.label(), displayName, price, amount)
					: String.format("%s: %s %s", action.label(), displayName, amount);
		}
	}

	public CraftJob {
		rows = List.copyOf(rows);
	}

	/**
	 * The clicks a quote implies, or empty where the book has moved out from under it.
	 *
	 * <p>Empty rather than partial for the same reason {@link CraftQuote} refuses rather than
	 * guesses: a materials list missing the ingredient whose book just emptied is a list that gets
	 * followed and then fails at the crafting table, with the coins already spent.
	 */
	public static Optional<CraftJob> of(CraftQuote quote, ItemCatalog catalog, BazaarSnapshot bazaar) {
		if (quote == null || bazaar == null) {
			return Optional.empty();
		}

		ItemCatalog names = catalog == null ? ItemCatalog.empty() : catalog;
		List<Row> rows = new ArrayList<>();

		for (UpgradeCost.Ingredient ingredient : quote.recipe().ingredients()) {
			BazaarProduct input = bazaar.product(ingredient.productId()).orElse(null);

			if (input == null) {
				return Optional.empty();
			}

			long units = quote.crafts() * ingredient.amount();
			boolean resting = quote.route() == InputRoute.BUY_ORDER
					&& quote.restingBuyOrders().contains(ingredient.productId());

			if (resting) {
				double price = input.outbidBuyOrder().orElse(-1.0d);

				if (price <= 0.0d) {
					return Optional.empty();
				}

				rows.add(row(Action.BUY_ORDER, ingredient.productId(), names, input, price, units));
			} else {
				// An instant buy is a button, not an order, so it occupies no slot and needs no
				// split - the bazaar sells you the lot in one click.
				rows.add(new Row(Action.INSTANT_BUY, ingredient.productId(),
						names.displayName(ingredient.productId()), 0.0d, units,
						String.valueOf(units), 0));
			}
		}

		String outputId = quote.recipe().outputId();
		String outputName = names.displayName(outputId);

		// Counted in crafts rather than in output units: the crafting grid is what the player is
		// standing at, and a recipe yielding four at a time would otherwise ask for four times the
		// clicks it needs.
		rows.add(new Row(Action.CRAFT, outputId, outputName, 0.0d, quote.crafts(),
				String.valueOf(quote.crafts()), 0));
		rows.add(row(Action.SELL_OFFER, outputId, names,
				bazaar.product(outputId).orElseThrow(), quote.unitSellPrice(), quote.outputUnits()));

		return Optional.of(new CraftJob(outputId, outputName, rows, quote));
	}

	/** One row that rests on the book, split into orders the bazaar will take. */
	private static Row row(Action action, String itemId, ItemCatalog names, BazaarProduct product,
			double price, long units) {
		long perOrder = Stacking.unitsPerOrder(names.get(itemId).orElse(null), product);

		return new Row(action, itemId, names.displayName(itemId), price, units,
				Stacking.orderSplit(units, perOrder),
				(int) Math.max(1L, (units + perOrder - 1L) / perOrder));
	}

	/**
	 * Bazaar order slots the job occupies while it is being worked.
	 *
	 * <p>Counted off the rows rather than taken from {@link CraftQuote#orderSlots()}, which assumes
	 * one order per resting ingredient. That holds for anything that stacks and fails badly for
	 * anything that does not: 58,624 units of a 256-at-a-time material is 229 orders, not one, and
	 * an account has at most 28 slots in total.
	 */
	public int orderSlots() {
		int slots = 0;

		for (Row row : rows) {
			slots += row.orders();
		}

		return slots;
	}

	/** Coins the materials cost in total. */
	public long capital() {
		return quote.capitalRequired();
	}

	public double profitPerHour() {
		return quote.profitPerHour();
	}

	public double totalNetProfit() {
		return quote.totalNetProfit();
	}

	/** NEU's collection requirement, or empty. Nothing here checks it against the player. */
	public String unlockText() {
		return quote.recipe().unlockText();
	}
}
