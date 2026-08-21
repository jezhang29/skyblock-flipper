package jeff.skyblockflipper.core.strategy;

import jeff.skyblockflipper.core.model.BazaarProduct;
import jeff.skyblockflipper.core.model.BazaarSnapshot;
import jeff.skyblockflipper.core.model.ItemCatalog;
import jeff.skyblockflipper.core.model.Stacking;
import jeff.skyblockflipper.core.pricing.CombineQuote;
import jeff.skyblockflipper.core.pricing.CombineQuote.SourceRoute;
import jeff.skyblockflipper.core.strategy.CombineTable.Entry;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * One combine flip as a list of clicks, in the order to make them.
 *
 * <p>This is to combining what {@link CraftJob} is to crafting and {@link NpcWorklist} is to the
 * basket, and it exists for the same reason: the flip screen, the bazaar overlay and
 * {@code /flip combine} all describe the same job, and three renderers each formatting a price from
 * the quote is three chances to disagree by a tenth of a coin. So the job is the one source: the
 * candidate's step text is its rows described, and the overlay follows a copy rebuilt from the live
 * book every poll.
 *
 * <p>A combine is three stations rather than a materials list: buy the source book on the bazaar,
 * combine it up at the anvil, rest the finished book on a sell offer. Each row is counted in the
 * unit its own station works in - source books, anvil merges, output books - which is why the three
 * counts differ.
 *
 * @param targetId    the top-tier book this flip sells
 * @param displayName its name, resolved once so no renderer has to
 * @param rows        the clicks: source first, the anvil, then the sell offer
 * @param quote       what the rows were priced from
 */
public record CombineJob(String targetId, String displayName, List<Row> rows, CombineQuote quote) {
	/** What one row asks the player to do. */
	public enum Action {
		/** Rest a buy order one increment above the best bid and wait for a dump. */
		BUY_ORDER("Buy Order", true),
		/** Take the ask now. No price to type, so none is shown. */
		INSTANT_BUY("Buy Instantly", false),
		/** Leave the bazaar and merge the books up at the anvil. */
		COMBINE("Combine", false),
		/** Rest the finished top-tier book one increment under the cheapest offer. */
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
	 * @param units      how many, in the unit the row's station counts in: source books, anvil
	 *                   merges, or output books
	 * @param orderSplit how {@code units} divides into orders the bazaar will accept, e.g.
	 *                   {@code 2 x 256 + 8}. Books do not stack, so a plan wanting more than 256 of
	 *                   one is more than one order and a bare total is a number the box refuses
	 * @param orders     how many orders that split is, which is what the row costs in slots
	 */
	public record Row(Action action, String itemId, String displayName, double price, long units,
			String orderSplit, int orders) {
		/** One line, for the places that render a job as text rather than as rows. */
		public String describe() {
			String amount = orderSplit.equals(String.valueOf(units))
					? "x" + units
					: "x" + units + " (" + orderSplit + ")";

			return action.priced()
					? String.format("%s: %s at %.1f %s", action.label(), displayName, price, amount)
					: String.format("%s: %s %s", action.label(), displayName, amount);
		}
	}

	public CombineJob {
		rows = List.copyOf(rows);
	}

	/**
	 * The clicks a quote implies, or empty where the book has moved out from under it.
	 *
	 * <p>Empty rather than partial for the same reason {@link CombineQuote} refuses rather than
	 * guesses: a source row whose bid side just emptied is a row that gets followed and then has no
	 * price to type.
	 */
	public static Optional<CombineJob> of(CombineQuote quote, ItemCatalog catalog,
			BazaarSnapshot bazaar) {
		if (quote == null || bazaar == null) {
			return Optional.empty();
		}

		Entry entry = quote.entry();
		int tier = quote.sourceTier();
		String sourceId = entry.bookId(tier);
		String targetId = entry.targetId();

		BazaarProduct source = bazaar.product(sourceId).orElse(null);
		BazaarProduct target = bazaar.product(targetId).orElse(null);

		if (source == null || target == null) {
			return Optional.empty();
		}

		String sourceName = nameOf(entry, tier, catalog);
		String targetName = nameOf(entry, entry.maxTier(), catalog);
		List<Row> rows = new ArrayList<>(3);

		if (quote.route() == SourceRoute.BUY_ORDER) {
			double price = source.outbidBuyOrder().orElse(-1.0d);

			if (price <= 0.0d) {
				return Optional.empty();
			}

			rows.add(restingRow(Action.BUY_ORDER, sourceId, sourceName, source, price,
					quote.sourceBooks(), catalog));
		} else {
			// An instant buy is a button, not an order, so it occupies no slot and needs no split.
			rows.add(new Row(Action.INSTANT_BUY, sourceId, sourceName, 0.0d, quote.sourceBooks(),
					String.valueOf(quote.sourceBooks()), 0));
		}

		// Counted in anvil merges, the click cost the combine view is actually for: sixteen tier-6
		// books become one tier-10 in fifteen merges, not one.
		rows.add(new Row(Action.COMBINE, targetId, targetName, 0.0d, quote.totalCombines(),
				String.valueOf(quote.totalCombines()), 0));

		rows.add(restingRow(Action.SELL_OFFER, targetId, targetName, target, quote.unitSellPrice(),
				quote.outputs(), catalog));

		return Optional.of(new CombineJob(targetId, targetName, rows, quote));
	}

	/** One row that rests on the book, split into orders the bazaar will take. */
	private static Row restingRow(Action action, String itemId, String name, BazaarProduct product,
			double price, long units, ItemCatalog catalog) {
		ItemCatalog names = catalog == null ? ItemCatalog.empty() : catalog;
		long perOrder = Stacking.unitsPerOrder(names.get(itemId).orElse(null), product);

		return new Row(action, itemId, name, price, units, Stacking.orderSplit(units, perOrder),
				(int) Math.max(1L, (units + perOrder - 1L) / perOrder));
	}

	/**
	 * The book's name: the catalog's where it has one, otherwise built from the enchant id.
	 *
	 * <p>Most enchanted books are absent from {@code /v2/resources/skyblock/items}, so the catalog
	 * returns the raw id and a bare {@code ENCHANTMENT_FEATHER_FALLING_10} would reach the screen.
	 * Title-casing the enchant and appending the tier gives "Feather Falling 10", the name the player
	 * searches the bazaar for.
	 */
	public static String nameOf(Entry entry, int tier, ItemCatalog catalog) {
		String id = entry.bookId(tier);
		String known = catalog == null ? id : catalog.displayName(id);

		if (!known.equals(id)) {
			return known;
		}

		StringBuilder name = new StringBuilder();

		for (String part : entry.enchantId().split("_")) {
			if (part.isEmpty()) {
				continue;
			}

			if (name.length() > 0) {
				name.append(' ');
			}

			name.append(Character.toUpperCase(part.charAt(0)))
					.append(part.substring(1).toLowerCase(Locale.ROOT));
		}

		return name.append(' ').append(tier).toString();
	}

	/** Bazaar order slots the job occupies while it is being worked. */
	public int orderSlots() {
		int slots = 0;

		for (Row row : rows) {
			slots += row.orders();
		}

		return slots;
	}

	public long capital() {
		return quote.capitalRequired();
	}

	public double profitPerHour() {
		return quote.profitPerHour();
	}

	public double totalNetProfit() {
		return quote.totalNetProfit();
	}
}
