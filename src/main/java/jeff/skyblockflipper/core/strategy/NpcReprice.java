package jeff.skyblockflipper.core.strategy;

import jeff.skyblockflipper.core.model.BazaarProduct;
import jeff.skyblockflipper.core.model.ItemCatalog;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * What to do with the buy orders already resting under an NPC price.
 *
 * <p>{@link NpcBasket} decides what to place; this is the other half of the same cycle, and it is
 * where most of the money is. Measured per 8-hour cycle on three days of tape: posting once and
 * walking away makes 11.5M, returning every 30 minutes to put the order back on top of the book
 * makes 59.7M. An order only collects flow while it is the best bid, so a basket that is never
 * repriced is quoting five times what it will make.
 *
 * <p><b>It says what to do and the player clicks.</b> No part of this places, edits or cancels an
 * order. Automating bazaar clicks is a macro and against Hypixel's rules.
 *
 * <p><b>The stop is the same 15% the plans were filtered on.</b> Chasing a rising book upward eats
 * the margin, and past {@code npc x (1 - npcMinMarginRatio)} the slot is worth more holding
 * something else. That is the one number that turns a reprice into a cancel, and it is the same
 * threshold {@link NpcFlipStrategy} used to admit the item in the first place, so an order is never
 * told to chase to a price its own plan would have refused.
 *
 * <p>Nothing here can lose coins by being wrong. The NPC price cannot move, so the worst outcome of
 * a missed reprice is an order that sits unfilled, and the worst outcome of a needless cancel is a
 * re-placed order.
 */
public final class NpcReprice {
	private NpcReprice() {
	}

	/**
	 * One resting buy order, as whatever read the orders menu understands it.
	 *
	 * <p>Deliberately not {@code TrackedOrder}: this needs a price and a size and nothing else, and
	 * taking the tracker's type would tie the money math to the bookkeeping that happens to be
	 * feeding it today.
	 *
	 * @param unitPrice the price the order actually rests at, which is the whole input. A plan's
	 *                  quoted cost includes the chase it had not paid yet, so it is not this
	 * @param remaining units still on the book, i.e. what a reprice would be moving
	 */
	public record Order(String itemId, String displayName, double unitPrice, long remaining) {
	}

	public enum Action {
		/** Still the best bid. Nothing to do. */
		HOLD,

		/** Outbid, and getting back to the top still leaves the margin above the floor. */
		REPRICE,

		/** Outbid past the point where the trade is worth a slot. Take the order off the book. */
		CANCEL
	}

	/**
	 * One order and what to do with it.
	 *
	 * @param bestBid     the best bid on the book right now, which is your own price while you are
	 *                    at the top of it
	 * @param postPrice   where you would have to post to be the best bid again
	 * @param chaseStop   the highest price this item is worth chasing to
	 * @param marginRatio margin against the NPC price at the price this advice is about: yours on a
	 *                    hold, the new one on a reprice, what it would have to become on a cancel
	 */
	public record Advice(
			Order order,
			Action action,
			double npcPrice,
			double bestBid,
			double postPrice,
			double chaseStop,
			double marginRatio,
			String reason
	) {
		/** Coins the reprice adds to what the remaining units will cost. Zero unless repricing. */
		public double extraCost() {
			return action == Action.REPRICE
					? Math.max(0.0d, postPrice - order.unitPrice()) * order.remaining()
					: 0.0d;
		}

		/** Profit still on the table if the remaining units fill at the advised price. */
		public double profitAtStake() {
			return action == Action.CANCEL
					? 0.0d
					: (npcPrice - priceInQuestion()) * order.remaining();
		}

		/** Coins tied up in the units still resting, at whatever they would end up costing. */
		public long capitalAtStake() {
			return Math.round(priceInQuestion() * order.remaining());
		}

		private double priceInQuestion() {
			return action == Action.REPRICE ? postPrice : order.unitPrice();
		}

		public boolean needsAction() {
			return action != Action.HOLD;
		}
	}

	/**
	 * Reviews every order against the book as it is now.
	 *
	 * <p>Orders on items no NPC buys are dropped rather than reported: they are ordinary spread
	 * flips, and this has nothing to say about them. So are orders on a product the current bazaar
	 * snapshot does not carry, which means the book has not been fetched rather than that the order
	 * is wrong.
	 *
	 * <p>Sorted with the ones needing a click first and the largest of those at the top, because the
	 * list is read while standing at a bazaar menu with a limited amount of patience.
	 */
	public static List<Advice> review(List<Order> orders, StrategyContext context) {
		List<Advice> advice = new ArrayList<>();

		for (Order order : orders) {
			adviseOn(order, context).ifPresent(advice::add);
		}

		advice.sort(Comparator
				.comparing((Advice a) -> a.needsAction() ? 0 : 1)
				.thenComparing(Comparator.comparingLong(Advice::capitalAtStake).reversed()));

		return advice;
	}

	private static Optional<Advice> adviseOn(Order order, StrategyContext context) {
		ItemCatalog.Entry entry = context.catalog().get(order.itemId()).orElse(null);
		BazaarProduct product = context.bazaar().products().get(order.itemId());

		if (entry == null || product == null || order.remaining() <= 0L) {
			return Optional.empty();
		}

		Double npcPrice = entry.npcPrice().filter(price -> price > 0.0d).orElse(null);

		if (npcPrice == null) {
			return Optional.empty();
		}

		NpcContext npc = context.npc();
		double chaseStop = npc.maxChasePrice(npcPrice);
		OptionalDouble bestBid = product.instantSellPrice();

		// An empty buy side with an order resting in it means the snapshot predates the order. Say
		// so rather than computing a reprice off a book that does not contain your own bid.
		if (bestBid.isEmpty()) {
			return Optional.of(new Advice(order, Action.HOLD, npcPrice, order.unitPrice(),
					order.unitPrice(), chaseStop, margin(npcPrice, order.unitPrice()),
					"Nothing else is bidding on this book, so the order is the top of it"));
		}

		double bid = bestBid.getAsDouble();

		// Your own order is in the book, so being the best bid reads as a tie rather than as a lead.
		// A tie is behind whoever posted first, but outbidding yourself by an increment to find out
		// costs the increment on every unit for no measured gain.
		if (order.unitPrice() >= bid) {
			return Optional.of(new Advice(order, Action.HOLD, npcPrice, bid, order.unitPrice(),
					chaseStop, margin(npcPrice, order.unitPrice()),
					String.format("Top of the book at %.1f, %.0f%% under the %.1f the NPC pays",
							order.unitPrice(), margin(npcPrice, order.unitPrice()) * 100.0d,
							npcPrice)));
		}

		double postPrice = product.outbidBuyOrder().orElse(bid + BazaarProduct.PRICE_INCREMENT);

		if (postPrice >= npcPrice) {
			return Optional.of(new Advice(order, Action.CANCEL, npcPrice, bid, postPrice, chaseStop,
					margin(npcPrice, postPrice),
					String.format("The book has caught the NPC price: the top bid is %.1f against "
							+ "%.1f, so there is no trade here any more", bid, npcPrice)));
		}

		if (postPrice > chaseStop) {
			return Optional.of(new Advice(order, Action.CANCEL, npcPrice, bid, postPrice, chaseStop,
					margin(npcPrice, postPrice),
					String.format("Getting back on top costs %.1f, past the %.1f stop - that is a "
									+ "%.0f%% margin against the %.0f%% floor, and the slot is worth "
									+ "more elsewhere", postPrice, chaseStop,
							margin(npcPrice, postPrice) * 100.0d,
							npc.minMarginRatio() * 100.0d)));
		}

		return Optional.of(new Advice(order, Action.REPRICE, npcPrice, bid, postPrice, chaseStop,
				margin(npcPrice, postPrice),
				String.format("Outbid at %.1f. Move to %.1f and it is still a %.0f%% margin, with "
								+ "%.1f of room left before the stop", bid, postPrice,
						margin(npcPrice, postPrice) * 100.0d, chaseStop - postPrice)));
	}

	private static double margin(double npcPrice, double price) {
		return npcPrice <= 0.0d ? 0.0d : (npcPrice - price) / npcPrice;
	}
}
