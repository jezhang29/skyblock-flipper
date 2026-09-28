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
import jeff.skyblockflipper.core.model.BazaarSnapshot;
import jeff.skyblockflipper.core.model.ItemCatalog;
import jeff.skyblockflipper.core.model.Stacking;
import jeff.skyblockflipper.core.pricing.FusionQuote;
import jeff.skyblockflipper.core.pricing.FusionQuote.Fusion;
import jeff.skyblockflipper.core.pricing.FusionQuote.Leaf;
import jeff.skyblockflipper.core.pricing.FusionQuote.SourceRoute;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * One fusion flip as a list of clicks, in the order to make them.
 *
 * <p>This is to fusion what {@link CombineJob} is to combining, and it exists for the same reason:
 * the flip screen, the bazaar overlay and {@code /flip fusion} must describe the same job, and three
 * renderers each formatting a price from the quote is three chances to disagree by a tenth of a coin.
 * So the job is the one source: the candidate's step text is its rows described, and the overlay
 * follows a copy rebuilt from the live book every poll.
 *
 * <p>A fusion is a <b>tree</b> of clicks, flattened here into three kinds of station: buy each base
 * shard on the bazaar, fuse the shards up (bottom-up, intermediates made and consumed inside the
 * labels and never traded), then rest the output on a sell offer. Each row is counted in its own
 * unit - base shards, fusion clicks, output shards. The base-shard rows are aggregated by id, summed
 * across every branch of the tree that reaches them, because {@link WorkedJob#progressOf} matches on
 * item id and side and two rows for one id would double-count its fills.
 *
 * @param outputId    the shard this flip sells
 * @param displayName its name, resolved once so no renderer has to
 * @param rows        the clicks: base buys, then fusions bottom-up, then the sell offer
 * @param quote       what the rows were priced from
 */
public record FusionJob(String outputId, String displayName, List<Row> rows, FusionQuote quote) {
	/** What one row asks the player to do. */
	public enum Action {
		/** Rest a buy order one increment above the best bid and wait for a dump. */
		BUY_ORDER("Buy Order", true),
		/** Take the ask now. No price to type, so none is shown. */
		INSTANT_BUY("Buy Instantly", false),
		/** Leave the bazaar and fuse the shards at the Fusion Machine. */
		FUSE("Fuse", false),
		/** Rest the finished output shard one increment under the cheapest offer. */
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
	 * @param units      how many, in the row's own unit: base shards, fusion clicks, or output shards
	 * @param orderSplit how {@code units} divides into orders the bazaar will accept
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
					? String.format(Locale.ROOT, "%s: %s at %.1f %s", action.label(), displayName,
							price, amount)
					: String.format(Locale.ROOT, "%s: %s %s", action.label(), displayName, amount);
		}
	}

	public FusionJob {
		rows = List.copyOf(rows);
	}

	/**
	 * The base-shard ids this flip rests a buy order on, which are what the NPC side would otherwise
	 * misread as its own. Only {@link Action#BUY_ORDER} rows: an instant buy rests nothing, and the
	 * sell offer is the finished shard on the other leg.
	 */
	public List<String> restingBuyOrderIds() {
		return rows.stream()
				.filter(row -> row.action() == Action.BUY_ORDER)
				.map(Row::itemId)
				.filter(id -> id != null && !id.isEmpty())
				.toList();
	}

	/**
	 * The clicks a quote implies, or empty where the book has moved out from under it.
	 *
	 * <p>Empty rather than partial for the same reason {@link FusionQuote} refuses rather than
	 * guesses: a base-shard row whose bid side just emptied is a row that gets followed and then has
	 * no price to type.
	 */
	public static Optional<FusionJob> of(FusionQuote quote, ItemCatalog catalog, BazaarSnapshot bazaar) {
		if (quote == null || bazaar == null || quote.leaves().isEmpty()) {
			return Optional.empty();
		}

		String outputId = quote.outputId();
		BazaarProduct target = bazaar.product(outputId).orElse(null);

		if (target == null) {
			return Optional.empty();
		}

		String outputName = nameOf(outputId, catalog);
		List<Row> rows = new ArrayList<>(quote.leaves().size() + quote.fusions().size() + 1);

		// Base buys first, aggregated by id already inside the quote.
		for (Leaf leaf : quote.leaves()) {
			BazaarProduct source = bazaar.product(leaf.shardId()).orElse(null);

			if (source == null) {
				return Optional.empty();
			}

			long units = quote.shardsToBuy(leaf);
			String name = nameOf(leaf.shardId(), catalog);

			if (leaf.route() == SourceRoute.BUY_ORDER) {
				rows.add(restingRow(Action.BUY_ORDER, leaf.shardId(), name, source, leaf.unitPrice(),
						units, catalog));
			} else {
				// An instant buy is a button, not an order, so it occupies no slot and needs no split.
				rows.add(new Row(Action.INSTANT_BUY, leaf.shardId(), name, 0.0d, units,
						String.valueOf(units), 0));
			}
		}

		// Fusions bottom-up, exactly as the quote laid them out: make the inputs, then fuse them.
		for (Fusion fusion : quote.fusions()) {
			long clicks = Math.max(1L, Math.round(fusion.fusionsPerOutput() * quote.outputs()));
			String recipe = String.format(Locale.ROOT, "%s x%d + %s x%d -> %s",
					nameOf(fusion.inputA(), catalog), fusion.amountA(),
					nameOf(fusion.inputB(), catalog), fusion.amountB(),
					nameOf(fusion.outputId(), catalog));

			rows.add(new Row(Action.FUSE, fusion.outputId(), recipe, 0.0d, clicks,
					String.valueOf(clicks), 0));
		}

		rows.add(restingRow(Action.SELL_OFFER, outputId, outputName, target, quote.unitSellPrice(),
				quote.outputs(), catalog));

		return Optional.of(new FusionJob(outputId, outputName, rows, quote));
	}

	/** One row that rests on the book, split into orders the bazaar will take. */
	private static Row restingRow(Action action, String itemId, String name, BazaarProduct product,
			double price, long units, ItemCatalog catalog) {
		ItemCatalog names = catalog == null ? ItemCatalog.empty() : catalog;
		long perOrder = Stacking.unitsPerOrder(names.get(itemId).orElse(null), product);

		return new Row(action, itemId, name, price, units, Stacking.orderSplit(units, perOrder),
				(int) Math.max(1L, (units + perOrder - 1L) / perOrder));
	}

	/** The shard's name: the catalog's where it has one, otherwise its bazaar id. */
	public static String nameOf(String shardId, ItemCatalog catalog) {
		return catalog == null ? shardId : catalog.displayName(shardId);
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
