package jeff.skyblockflipper.core.strategy;

import jeff.skyblockflipper.core.model.BazaarProduct;
import jeff.skyblockflipper.core.model.ItemCatalog;
import jeff.skyblockflipper.core.model.OrderLevel;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Buy on the bazaar below the fixed price an NPC pays, then sell to the NPC.
 *
 * <p>NPC prices never move, so this is pure one-sided arbitrage against a constant: no spread to
 * capture, no counterparty to wait for, and no sales tax, because selling to an NPC is not a bazaar
 * transaction. When it exists it is free money, which is exactly why it rarely lasts long.
 *
 * <p>Two things make this different from the bazaar strategy and drive the design here:
 *
 * <ul>
 *   <li>You must <b>instant-buy</b>, crossing the spread to take the ask. Posting a buy order and
 *       waiting defeats the point, since the edge closes in minutes.</li>
 *   <li>The edge is capped by <b>book depth</b>, not by volume. Only the levels priced below the
 *       NPC price are profitable, so the plan walks the book and stops at the first level that is
 *       not. Sizing off anything else overstates the opportunity badly.</li>
 * </ul>
 */
public final class NpcFlipStrategy implements FlipStrategy {
	/** How deep into the ask side to walk before giving up. */
	private static final int MAX_LEVELS = 10;

	/**
	 * Inventory capacity for one trip: 36 usable slots at a 64 stack.
	 *
	 * <p>This is the constraint that makes NPC flipping much smaller than it first looks. Buying is
	 * instant and unbounded, but selling is manual, one inventory at a time, so the bazaar side of
	 * the book is almost never the binding limit.
	 */
	private static final long UNITS_PER_TRIP = 36L * 64L;

	/** Round trips an attentive player manages per hour: buy, walk to the NPC, sell, return. */
	private static final long TRIPS_PER_HOUR = 12L;

	private static final long UNITS_PER_HOUR = UNITS_PER_TRIP * TRIPS_PER_HOUR;

	@Override
	public StrategyKind kind() {
		return StrategyKind.NPC_FLIP;
	}

	@Override
	public List<FlipCandidate> findCandidates(StrategyContext context) {
		List<FlipCandidate> candidates = new ArrayList<>();

		if (context.catalog().isEmpty()) {
			return candidates;
		}

		for (BazaarProduct product : context.bazaar().products().values()) {
			Optional<ItemCatalog.Entry> entry = context.catalog().get(product.productId());

			if (entry.isEmpty()) {
				continue;
			}

			entry.get().npcPrice()
					.flatMap(npcPrice -> evaluate(product, npcPrice, context))
					.ifPresent(candidates::add);
		}

		candidates.sort(null);
		return candidates;
	}

	private Optional<FlipCandidate> evaluate(BazaarProduct product, double npcPrice, StrategyContext context) {
		if (npcPrice <= 0.0d || product.sellOffers().isEmpty()) {
			return Optional.empty();
		}

		long budget = context.bankroll();
		long units = 0L;
		double spend = 0.0d;

		// Walk the ask side. Each level is only worth taking while it is cheaper than the NPC pays.
		for (OrderLevel level : product.sellOffers().subList(0, Math.min(MAX_LEVELS, product.sellOffers().size()))) {
			if (level.pricePerUnit() >= npcPrice) {
				break;
			}

			long affordable = (long) ((budget - spend) / level.pricePerUnit());

			if (affordable <= 0L) {
				break;
			}

			long take = Math.min(level.amount(), affordable);

			units += take;
			spend += take * level.pricePerUnit();
		}

		if (units <= 0L) {
			return Optional.empty();
		}

		double averageCost = spend / units;

		// Cap the plan at what can actually be sold in an hour of manual trips. Without this the
		// strategy happily proposes instant-buying a million units of a 3-coin item and claims the
		// whole notional profit per hour, which no player can physically realize.
		if (units > UNITS_PER_HOUR) {
			units = UNITS_PER_HOUR;
			spend = units * averageCost;
		}

		// No sales tax: an NPC sale is not a bazaar transaction.
		double profit = units * npcPrice - spend;

		if (profit < context.minProfitPerFlip()) {
			return Optional.empty();
		}

		long trips = (units + UNITS_PER_TRIP - 1L) / UNITS_PER_TRIP;
		String name = context.catalog().displayName(product.productId());

		return Optional.of(new FlipCandidate(
				product.productId(),
				name,
				kind(),
				averageCost,
				npcPrice,
				profit / units,
				units,
				Math.round(spend),
				// Already sized to one hour of trips, so the whole profit is the hourly rate.
				profit,
				// Both legs are fixed and immediate, so there is little left to be wrong about.
				0.95d,
				steps(name, units, npcPrice, trips),
				risks(trips)));
	}

	private static List<String> risks(long trips) {
		List<String> risks = new ArrayList<>();

		risks.add("Edge closes fast once others notice; verify prices before committing");
		risks.add("Requires walking to an NPC that buys this item");

		if (trips > 1L) {
			risks.add("Needs " + trips + " inventory trips: assumes 36 slots at a 64 stack");
		}

		return risks;
	}

	private static List<String> steps(String name, long units, double npcPrice, long trips) {
		return List.of(
				"Bazaar -> search " + name + " -> Instant Buy " + units + " (do not post a buy order)",
				trips > 1L
						? "Take the stock to an NPC that buys it, across roughly " + trips + " trips"
						: "Take the stock to any NPC that buys it",
				String.format("Sell to the NPC at %.2f coins each", npcPrice),
				"Re-check the bazaar price before repeating; this closes quickly");
	}
}
