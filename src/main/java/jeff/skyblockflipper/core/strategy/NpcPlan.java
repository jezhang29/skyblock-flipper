package jeff.skyblockflipper.core.strategy;

import jeff.skyblockflipper.core.valuation.NpcEdge;

/**
 * One item's resting NPC plan, priced but not yet sized against anything shared.
 *
 * <p>What {@link NpcFlipStrategy} produces before it decides how large a single plan should be, and
 * what {@link NpcBasket} allocates over. Both need the same filters applied - persistence, the
 * margin floor, the chase charge - and the whole reason this record exists is that they must not
 * apply them twice or differently.
 *
 * <p><b>{@link #maxUnits()} deliberately excludes order slots.</b> Everything else that bounds a
 * plan is a fact about this one item: how fast the book dumps into it over the window, what the
 * day's remaining NPC budget can pay out for it, what one flip may spend. Slots are shared, and a
 * basket that took each item's standalone slot ceiling would hand the whole book to the first
 * candidate.
 *
 * @param unitCost      expected coins per unit including the chase, which is what a plan should be
 *                      costed at. {@link #postPrice()} is what you type into the bazaar
 * @param maxUnits      the most units worth buying of this item on its own, from fill over the
 *                      resting window, the per-flip capital cap and the remaining daily NPC budget
 * @param unitsPerOrder units one bazaar order holds: 71,680 stackable, 256 not
 * @param unitsPerLoad  units one inventory load carries through {@code /trades}, which is the unit
 *                      the basket ranks in
 * @param edge          measured tape history, or null where the product has too little of it. Null
 *                      is expected on a fresh install and is not a rejection
 * @param postsAboveBook whether the drift premium lifted {@link #postPrice()} above the live top of
 *                      the book. When true the order is meant to rest above the book, so the price
 *                      page must type it on the sign rather than click Hypixel's "+0.1" button,
 *                      which would post at the top of the book and spend the whole premium
 */
public record NpcPlan(
		String itemId,
		String displayName,
		double npcPrice,
		double postPrice,
		double unitCost,
		double unitNetProfit,
		long maxUnits,
		long unitsPerOrder,
		long unitsPerLoad,
		double fillPerHour,
		boolean fillMeasured,
		NpcEdge edge,
		double confidence,
		boolean postsAboveBook
) {
	/**
	 * The ranking key: coins of profit one inventory load of this item carries.
	 *
	 * <p>Measured over a full day against the alternatives: 76.4M a day ranking on this against
	 * 4.8M ranking on cap efficiency, which picks 9-coin items and drowns the player in hauling. A
	 * Lagrangian blend of the two peaked 4.4% higher and was rejected as a tuning parameter that
	 * drifts with the book.
	 */
	public double profitPerLoad() {
		return unitNetProfit * unitsPerLoad;
	}

	/**
	 * The other ranking key: coins of profit one bazaar order slot of this item carries.
	 *
	 * <p>Order slots are the resource that actually runs out - every sweep in
	 * {@code docs/npc-flipping.md} came back {@code SLOTS} - so this is profit per unit of the binding
	 * budget, which is what a greedy allocator should rank on. Measured on the live book on
	 * 2026-08-14 at the user's settings it is worth 65.5M a cycle against 47.3M.
	 *
	 * <p>Bounded by {@link #maxUnits()} rather than left at the order's capacity, because an item
	 * worth 300 units does not carry a slot's worth of 71,680 and ranking it as though it did would
	 * put the whole stackable half of the book above the whole unstackable half on paper alone.
	 *
	 * <p>It is not the default. The 65.5M costs 363 inventory loads against 114, and hauling is paid
	 * in clicks rather than coins - see {@code NpcRanking}.
	 */
	public double profitPerOrder() {
		return unitNetProfit * Math.min(maxUnits, unitsPerOrder);
	}

	/** Margin as a share of the NPC price, after the chase charge. */
	public double marginRatio() {
		return npcPrice <= 0.0d ? 0.0d : unitNetProfit / npcPrice;
	}

	/** Coins of chasing per unit already taken out of {@link #unitNetProfit()}. */
	public double chaseCost() {
		return unitCost - postPrice;
	}

	/** Whether the tape has watched this gap long enough to say whether it stands. */
	public boolean edgeMeasured() {
		return edge != null;
	}

	/** Fraction of taped samples the gap was present in, or 0 where nothing has watched it. */
	public double persistence() {
		return edge == null ? 0.0d : edge.persistence();
	}

	/** Bazaar orders {@code units} of this item needs. */
	public int ordersFor(long units) {
		return unitsPerOrder <= 0L ? 0 : (int) ((units + unitsPerOrder - 1L) / unitsPerOrder);
	}

	/** Inventory loads {@code units} of this item hauls. */
	public long loadsFor(long units) {
		return unitsPerLoad <= 0L ? 0L : (units + unitsPerLoad - 1L) / unitsPerLoad;
	}
}
