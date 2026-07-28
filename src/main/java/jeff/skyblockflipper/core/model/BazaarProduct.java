package jeff.skyblockflipper.core.model;

import java.util.List;
import java.util.OptionalDouble;

/**
 * One bazaar product's order book, in trader-facing terms.
 *
 * <p><b>The names here are deliberately not the API's names.</b> Hypixel's {@code buy_summary}
 * is the sell-offer (ask) side and {@code sell_summary} is the buy-order (bid) side - inverted
 * from what the names suggest. That translation happens exactly once, in
 * {@link jeff.skyblockflipper.core.model.dto.BazaarDto}, and every consumer works with this
 * type instead. Getting the sides backwards inverts every computed spread while still producing
 * numbers that look plausible, which is why it is worth a dedicated type.
 *
 * @param productId   bazaar product id, e.g. {@code ENCHANTED_DIAMOND}
 * @param sellOffers  asks - what other players are selling. You buy from these. Best (lowest) first.
 * @param buyOrders   bids - what other players are buying. You sell into these. Best (highest) first.
 * @param movingWeek  seven-day traded volume, used to judge liquidity
 */
public record BazaarProduct(
		String productId,
		List<OrderLevel> sellOffers,
		List<OrderLevel> buyOrders,
		MovingWeek movingWeek
) {
	/** Seven-day volume on each side, straight from {@code quick_status}. */
	public record MovingWeek(long instantBought, long instantSold) {
	}

	private static final double HOURS_PER_WEEK = 168.0d;

	/**
	 * The smallest price step the bazaar accepts, and so the amount by which you outbid or undercut
	 * to take the top of the book.
	 *
	 * <p>Public because measuring how often you get displaced from the top means asking whether the
	 * book moved past the price this increment would have put you at - see {@code FillStats}. The
	 * two must agree on what "posting inside you" means, and a second copy of 0.1 elsewhere is how
	 * they would eventually stop agreeing.
	 */
	public static final double PRICE_INCREMENT = 0.1d;

	public BazaarProduct {
		sellOffers = List.copyOf(sellOffers);
		buyOrders = List.copyOf(buyOrders);
	}

	/**
	 * Price to instantly buy one unit right now - the best ask.
	 *
	 * <p>Deliberately taken from the book rather than {@code quick_status.buyPrice}, which is a
	 * depth-weighted average across several levels and sits well above the true top of book.
	 */
	public OptionalDouble instantBuyPrice() {
		return best(sellOffers);
	}

	/** Price received by instantly selling one unit right now - the best bid. */
	public OptionalDouble instantSellPrice() {
		return best(buyOrders);
	}

	/**
	 * Price to undercut the current best ask by the minimum increment, i.e. where you would post
	 * a sell offer to sit at the top of the book.
	 */
	public OptionalDouble undercutSellOffer() {
		OptionalDouble best = instantBuyPrice();
		return best.isPresent()
				? OptionalDouble.of(best.getAsDouble() - PRICE_INCREMENT)
				: OptionalDouble.empty();
	}

	/** Price to outbid the current best bid, i.e. where you would post a buy order. */
	public OptionalDouble outbidBuyOrder() {
		OptionalDouble best = instantSellPrice();
		return best.isPresent()
				? OptionalDouble.of(best.getAsDouble() + PRICE_INCREMENT)
				: OptionalDouble.empty();
	}

	/**
	 * Gross spread captured by market making: post a buy order, then sell into the ask side.
	 * Tax is not applied here - see {@code Fees} for the net figure.
	 */
	public OptionalDouble grossMarketMakingSpread() {
		OptionalDouble sell = undercutSellOffer();
		OptionalDouble buy = outbidBuyOrder();

		if (sell.isEmpty() || buy.isEmpty()) {
			return OptionalDouble.empty();
		}

		return OptionalDouble.of(sell.getAsDouble() - buy.getAsDouble());
	}

	/**
	 * The tighter of the two weekly volumes. Market making needs both sides to flow, so a product
	 * that only ever gets dumped into is worth less than its headline volume suggests.
	 */
	public long bottleneckWeeklyVolume() {
		return Math.min(movingWeek.instantBought(), movingWeek.instantSold());
	}

	/**
	 * Units per hour other players instantly buy, i.e. the rate sell offers get lifted.
	 *
	 * <p>This is the ceiling on how fast <b>you</b> can keep instant-buying: what is resting on the
	 * ask side right now is a snapshot, not a supply. Sizing an hourly plan off the visible book
	 * assumes it refills for free, which on a thin item it does not.
	 */
	public double instantBuysPerHour() {
		return movingWeek.instantBought() / HOURS_PER_WEEK;
	}

	/**
	 * Units per hour other players instantly sell, i.e. the rate buy orders get filled.
	 *
	 * <p>The ceiling on a resting buy order: an order only fills as fast as people dump into it,
	 * however good the price looks.
	 */
	public double instantSellsPerHour() {
		return movingWeek.instantSold() / HOURS_PER_WEEK;
	}

	/**
	 * Total resting orders across the returned depth of one side.
	 *
	 * <p>Use this rather than the {@code orders} count on the top level to judge how manipulable a
	 * book is: a single price level normally holds one to three orders even on deep, healthy
	 * markets, so thresholding on the top level alone rejects almost everything.
	 */
	public static int totalOrders(List<OrderLevel> side) {
		int total = 0;

		for (OrderLevel level : side) {
			total += level.orders();
		}

		return total;
	}

	public int sellOfferCount() {
		return totalOrders(sellOffers);
	}

	public int buyOrderCount() {
		return totalOrders(buyOrders);
	}

	private static OptionalDouble best(List<OrderLevel> side) {
		return side.isEmpty() ? OptionalDouble.empty() : OptionalDouble.of(side.getFirst().pricePerUnit());
	}
}
