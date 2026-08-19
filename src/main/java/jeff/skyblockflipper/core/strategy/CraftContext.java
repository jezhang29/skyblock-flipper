package jeff.skyblockflipper.core.strategy;

import jeff.skyblockflipper.core.pricing.Fees;

/**
 * The craft-specific settings, bundled for the same reason {@link NpcContext} is: they are only
 * ever read as a set, and a strategy that reached for them one at a time would grow the shared
 * context every time crafting learned a new parameter.
 *
 * @param enabled       whether craft candidates are produced at all
 * @param maxOrderSlots the most bazaar order slots one craft plan may ask for
 */
public record CraftContext(boolean enabled, int maxOrderSlots) {
	/**
	 * Slots one craft plan may occupy before it is starving the rest of the account.
	 *
	 * <p>Measured, not chosen: on the live book of 2026-08-18 the best eight craft plans together
	 * wanted 19 of the 21 order slots a Bazaar Flipper 1 account has (see
	 * {@code docs/craft-flipping.md}). Those are the same slots the NPC basket needs, and the NPC
	 * work already measured that slots bind where coins do not. Six leaves a six-ingredient recipe
	 * on the resting route reachable while still leaving most of the account's slots alone.
	 */
	public static final int DEFAULT_MAX_ORDER_SLOTS = 6;

	public CraftContext {
		// A plan always needs the one slot its sell offer rests on, so a budget under that would
		// mean "no craft flips" while reading as a tightened budget.
		maxOrderSlots = Math.clamp(maxOrderSlots, 1, Fees.MAX_BAZAAR_ORDER_SLOTS);
	}

	/** What a caller with no opinion gets: crafting on, at the measured slot budget. */
	public static CraftContext defaults() {
		return new CraftContext(true, DEFAULT_MAX_ORDER_SLOTS);
	}

	/** Crafting off, for the callers and tests that want the ranking without it. */
	public static CraftContext off() {
		return new CraftContext(false, DEFAULT_MAX_ORDER_SLOTS);
	}
}
