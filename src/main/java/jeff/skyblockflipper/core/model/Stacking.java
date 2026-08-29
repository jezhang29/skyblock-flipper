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
package jeff.skyblockflipper.core.model;

/**
 * Whether an item stacks, decided from the order book rather than from the items resource.
 *
 * <p>How much of an item stacks decides two things this mod sizes plans with: how many units one
 * bazaar order may hold, and how many units one inventory load carries to an NPC. Both are 64x to
 * 280x apart between the two cases, so getting it wrong produces a plan that cannot be placed -
 * which is exactly what happened: the basket asked for 500 Jungle Hearts in one order against a
 * real ceiling of 256.
 *
 * <p><b>{@code /v2/resources/skyblock/items} cannot answer this.</b> Its {@code unstackable} flag
 * is set on 515 of 5,646 items and is absent from whole classes of items that do not stack in game.
 * Measured 2026-08-11: <b>0 of the 107 reforge stones carry it</b>, and neither does
 * {@code JUNGLE_HEART}. Nothing else in the resource separates the two cases either - material,
 * tier and category all overlap between items measured stackable and items measured not.
 *
 * <p><b>The book answers it, and does so exactly.</b> A bazaar order holds at most
 * {@link #UNITS_PER_ORDER_UNSTACKABLE} units of an item that does not stack, so a single resting
 * order larger than that proves the item stacks. Across the whole bazaar the observed maxima fall
 * into two clusters and nothing between: 107 products topping out at exactly 256 and 101 at exactly
 * {@link #UNITS_PER_ORDER_STACKABLE}.
 *
 * <p><b>Silence is read as "does not stack", and that costs almost nothing.</b> Of the 117 products
 * an NPC basket could draw from on 2026-08-11, 108 proved stackable and the 9 that did not are all
 * genuinely unstackable in game - the reforge stones, the potato books, Jungle Heart. Nor is the
 * proof marginal: the weakest of the 108 proved it with an 8,786-unit order, 34x the threshold. So
 * reading one snapshot is enough and there is nothing to accumulate across polls; an item cannot
 * flicker between the two answers on a book that deep.
 */
public final class Stacking {
	/** Units one bazaar order may hold of an item that stacks. */
	public static final long UNITS_PER_ORDER_STACKABLE = 71_680L;

	/** Units one bazaar order may hold of an item that does not. */
	public static final long UNITS_PER_ORDER_UNSTACKABLE = 256L;

	/** Units one inventory slot holds of an item that stacks. */
	public static final int STACK_SIZE_STACKABLE = 64;

	private Stacking() {
	}

	/**
	 * Whether this item stacks, on the evidence of its own book.
	 *
	 * <p>The catalog's flag is honoured when it is set, since it has never contradicted a
	 * measurement - 0 of the 68 flagged products on the bazaar has ever shown an order over 256.
	 * It is only ever a second way to reach the same answer, never the only one.
	 */
	public static boolean stackable(ItemCatalog.Entry entry, BazaarProduct product) {
		if (entry != null && entry.unstackable()) {
			return false;
		}

		return product != null && product.largestRestingOrder() > UNITS_PER_ORDER_UNSTACKABLE;
	}

	/** Units one bazaar order of this item may hold. */
	public static long unitsPerOrder(ItemCatalog.Entry entry, BazaarProduct product) {
		return stackable(entry, product) ? UNITS_PER_ORDER_STACKABLE : UNITS_PER_ORDER_UNSTACKABLE;
	}

	/** Units one inventory slot of this item holds. */
	public static int stackSize(ItemCatalog.Entry entry, BazaarProduct product) {
		return stackable(entry, product) ? STACK_SIZE_STACKABLE : 1;
	}

	/**
	 * How a number of units divides into orders the bazaar will accept, e.g. {@code 3 x 256 + 112}.
	 *
	 * <p>Here rather than at each of the two places that need it - a basket line to place and a round
	 * row to re-post - because two copies of this drift apart, and both are read while typing into the
	 * same box. A total on its own reads as one order, which is how a line of 500 Jungle Hearts got
	 * typed into a bazaar that takes 256 of them at a time.
	 */
	public static String orderSplit(long units, long unitsPerOrder) {
		if (unitsPerOrder <= 0L || units <= unitsPerOrder) {
			return String.valueOf(units);
		}

		long full = units / unitsPerOrder;
		long remainder = units % unitsPerOrder;
		String whole = full == 1L ? String.valueOf(unitsPerOrder) : full + " x " + unitsPerOrder;

		return remainder == 0L ? whole : whole + " + " + remainder;
	}

	/**
	 * The first of those orders, which is the number that goes in the amount box now.
	 *
	 * <p>{@code 3 x 256 + 112} is three boxes of 256 and then one of 112, and the box in front of the
	 * player takes one number. Read back off the same string rather than carried alongside it,
	 * because the split is what every renderer already holds and a second field saying 256 could
	 * disagree with the text beside it.
	 *
	 * @return the units to type, or 0 for a string this did not write
	 */
	public static long firstOrder(String split) {
		if (split == null || split.isBlank()) {
			return 0L;
		}

		// "3 x 256 + 112" -> 256, "256 + 112" -> 256, "112" -> 112. The multiplier is never the
		// answer: it counts the boxes, it does not go in one.
		String head = split.split("\\+")[0].trim();
		String units = head.contains(" x ") ? head.substring(head.indexOf(" x ") + 3).trim() : head;

		try {
			return Long.parseLong(units);
		} catch (NumberFormatException e) {
			return 0L;
		}
	}
}
