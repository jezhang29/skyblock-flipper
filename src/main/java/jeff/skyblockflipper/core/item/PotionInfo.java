package jeff.skyblockflipper.core.item;

import java.util.StringJoiner;

/**
 * A brewed potion, which shares the item id {@code POTION} with every other potion in the game.
 *
 * <p>The same shape of bug as {@link PetInfo}, and on the recorded tape the most expensive one left:
 * 2,758 BIN sales worth 2.1 billion coins all keyed as {@code POTION|<rarity>}. That single median
 * sat at 918,000 coins because Stinky Cheese and Harvest Harbinger dominate the count, so every
 * 25,000 coin splash Healing potion looked like it was listed at a thirty-sixth of its value.
 *
 * <p>What separates them, measured over those sales:
 *
 * <ul>
 *   <li>{@code potion} - the effect, and the dominant term. Stinky Cheese 950,000, Harvest Harbinger
 *       1,200,000, splash Healing 25,000, Combat XP Boost 500.
 *   <li>{@code potion_level} - the tier, worth roughly a factor of two per step where both trade.
 *   <li>{@code splash}, {@code enhanced}, {@code extended} - the alchemy perks. Present on 2,506,
 *       231 and 231 sales respectively, and they move the price: Speed 8 enhanced sells at 59,000
 *       against 82,500 once extended as well.
 * </ul>
 *
 * <p>Deliberately left out: {@code potion_type}, which read {@code POTION} on all 2,758 sales and so
 * separates nothing, and {@code potion_name}, a cosmetic custom name on 33 of them.
 *
 * @param type  the effect id, e.g. {@code stinky_cheese}
 * @param level the potion tier, 1-8
 */
public record PotionInfo(
		String type,
		int level,
		boolean splash,
		boolean enhanced,
		boolean extended
) {
	/**
	 * The signature term for this potion, e.g. {@code speed:8,enhanced,extended}.
	 *
	 * <p>The perks are part of one term rather than terms of their own so that a potion contributes
	 * a single clause to the signature, the way a rune or an attribute roll does.
	 */
	public String signatureTerm() {
		StringJoiner term = new StringJoiner(",");
		term.add(type + ":" + level);

		if (splash) {
			term.add("splash");
		}

		if (enhanced) {
			term.add("enhanced");
		}

		if (extended) {
			term.add("extended");
		}

		return term.toString();
	}
}
