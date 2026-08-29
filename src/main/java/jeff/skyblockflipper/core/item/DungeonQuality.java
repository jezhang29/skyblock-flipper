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
package jeff.skyblockflipper.core.item;

import java.util.StringJoiner;

/**
 * The quality roll a dungeon drop is stamped with: how good its stats came out, and which floor
 * tier it dropped at.
 *
 * <p>Present on 9,759 of 222,430 taped BIN sales (4.4%), and the largest remaining pooling gap by
 * coins after pets, runes and potions - {@code SKELETON_MASTER_CHESTPLATE} alone moves roughly
 * three billion coins a day across a handful of keys that today are one key.
 *
 * <p>The two attributes behind it are not symmetric, and measuring them separately is what decides
 * the shape of {@link #signatureTerm()}:
 *
 * <ul>
 *   <li>{@code item_tier} is where the coins are, and it goes in exactly. Holding
 *       {@code SKELETON_MASTER_CHESTPLATE} at a maxed boost, the tiers price at 980,000 (5),
 *       2,990,000 (6), 2,000,000 (7), 5,000,000 (8), 32,676,767 (9) and 113,000,000 (10). Pooled,
 *       the 54 tier-7 sales and the 63 tier-10 sales share one median across a 56x gap, which is
 *       precisely the fake-snipe generator that runes and potions were.
 *   <li>{@code baseStatBoostPercentage} runs 1-50 and <b>carries no price signal below 50</b>. Over
 *       two days of tape the values 1 through 49 are near-uniform in count (n between 158 and 218
 *       each) and flat in price, medians sitting between 48,000 and 74,000 with comparable p90s.
 *       Within a single item it is flatter still: {@code SNIPER_HELMET} sells at 79,000 / 100,000 /
 *       100,000 / 85,000 / 90,000 / 100,000 / 80,000 across boosts 5 to 40. Only the maximum
 *       separates - {@code boost=50} is n=536 at a 233,000 median with a p90 of 107,000,000, an
 *       order of magnitude above every other value's. So it is read as a flag, not a number.
 *       Splitting on the raw value would cut each item into fifty cells of about four sales apiece:
 *       measured, that prices 9 held-out sales where the flag prices 601.
 * </ul>
 *
 * <p>The two travel together but are not the same population: items that are not floor drops carry
 * a boost and no tier at all ({@code SNIPER_HELMET}, {@code ZOMBIE_COMMANDER_WHIP}), so the terms
 * are emitted independently rather than as one compound.
 *
 * <p><b>The maxed flag is measured to change nothing today, and is kept anyway.</b> Against the
 * full signature it is inert: of 716 keys holding a maxed sale, <b>not one also holds an unmaxed
 * one</b>, so adding the flag splits no key and scores byte-identically to leaving it out. That is
 * not because maxedness is worthless - at a coarse {@code id|rarity|tier} key it is worth 43.98x on
 * {@code SKELETON_MASTER_CHESTPLATE|LEGENDARY|tier=10}, 109,940,000 maxed against 2,500,000 plain -
 * but because the rest of the signature already fingerprints it. A maxed tier-10 chestplate is a
 * fully invested item carrying eight stars, fifteen hot potatoes and six enchantments, and an
 * unmaxed one is a bare drop, so the investment terms separate the two without ever naming the
 * roll. Nothing enforces that correlation. The day a fully starred unmaxed drop is listed, it would
 * land on the maxed pool and read as a snipe at anything under 110,000,000. A term that costs
 * exactly zero coverage and closes a 44x hole is worth keeping even while it never fires.
 *
 * <p>Distinct from {@code stars} despite both sounding like dungeon upgrades. Stars are bought
 * afterwards with essence and live in {@code upgrade_level}; this is the roll the drop arrived
 * with and cannot be changed.
 *
 * @param maxedStats whether {@code baseStatBoostPercentage} is at its 50 maximum
 * @param floorTier  the {@code item_tier} it dropped at, or {@link #NO_TIER} when absent
 */
public record DungeonQuality(boolean maxedStats, int floorTier) {
	/** No {@code item_tier} on the blob, which is the normal case for a non-floor drop. */
	public static final int NO_TIER = -1;

	public boolean hasTier() {
		return floorTier != NO_TIER;
	}

	/**
	 * The signature term, e.g. {@code maxed,tier=10}, or an empty string when this roll says
	 * nothing worth keying on.
	 *
	 * <p>An unmaxed item with no tier contributes nothing, which keeps every item that carries only
	 * a middling boost on the same key it had before this was read. That is deliberate: those sales
	 * measured flat, so splitting them would cost coverage to describe noise.
	 */
	public String signatureTerm() {
		StringJoiner term = new StringJoiner(",");

		if (maxedStats) {
			term.add("maxed");
		}

		if (hasTier()) {
			term.add("tier=" + floorTier);
		}

		return term.toString();
	}
}
