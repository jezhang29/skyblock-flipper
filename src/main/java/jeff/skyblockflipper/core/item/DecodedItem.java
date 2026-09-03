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

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.TreeMap;

/**
 * The parts of an auctioned item that move its price, pulled out of the raw blob.
 *
 * <p>Everything else in {@code item_bytes} - textures, lore, bound profile ids, kill counters - is
 * left behind. It is either cosmetic or personal, and carrying it would make {@link #signature()}
 * unique per item, which is the same as having no signature at all.
 *
 * @param reforge      reforge id, or "" for none
 * @param stars        dungeon/master stars, from either the modern or the legacy attribute
 * @param attributes   Kuudra/Crimson attribute rolls as name to level, empty for most items
 * @param runes        applied runes as name to tier, empty for most items
 * @param pet          pet detail, or null when this is not a pet
 * @param potion       potion detail, or null when this is not a potion
 * @param quality      dungeon drop quality, or null when this item carries no such roll
 * @param abilityScrolls normalized Wither-impact ability scroll ids applied to this item
 * @param dye          the named dye applied to this leather item, or "" for none
 * @param ethermerged  whether an Etherwarp Conduit has been merged into this item
 * @param winningBid   coins paid for this item at the Dark Auction, or 0 for the items that are not
 *                     sold there. Deliberately absent from {@link #signature()} - see
 *                     {@link #hasWinningBid()}
 * @param unlockedSlots how many gemstone slots have been paid open, the union of the
 *                     {@code unlocked_slots} array and the slots holding a placed gem. A locked slot
 *                     costs real coins to open, so an item with slots open is worth far more than the
 *                     same item with them shut - and {@link #gemstones} alone cannot tell the two
 *                     apart, because an unlocked-but-empty slot places no gem. Sibling of
 *                     {@code gemstones}; kept at the end to spare the positional constructors
 */
public record DecodedItem(
		String skyblockId,
		String displayName,
		int count,
		Rarity rarity,
		String reforge,
		int stars,
		boolean recombobulated,
		int hotPotatoBooks,
		Map<String, Integer> enchantments,
		List<String> gemstones,
		Map<String, Integer> attributes,
		Map<String, Integer> runes,
		PetInfo pet,
		PotionInfo potion,
		DungeonQuality quality,
		List<String> abilityScrolls,
		String dye,
		boolean ethermerged,
		long winningBid,
		int unlockedSlots
) {
	private static final List<String> KNOWN_ABILITY_SCROLLS = List.of(
			"IMPLOSION_SCROLL", "SHADOW_WARP_SCROLL", "WITHER_SHIELD_SCROLL");
	private static final Set<String> SCROLL_CAPABLE_BLADE_IDS = Set.of(
			"HYPERION", "ASTRAEA", "SCYLLA", "VALKYRIE", "NECRON_BLADE");

	public DecodedItem {
		// Sorted and kept sorted. Map.copyOf would be immutable but not ordered - its iteration
		// order is salted per JVM - and signature() below promises the same string every time.
		enchantments = Collections.unmodifiableMap(new TreeMap<>(enchantments));
		gemstones = List.copyOf(gemstones);
		attributes = Collections.unmodifiableMap(new TreeMap<>(attributes));
		runes = Collections.unmodifiableMap(new TreeMap<>(runes));
		abilityScrolls = normalizeAbilityScrolls(abilityScrolls);
		dye = dye == null ? "" : dye;
	}

	/** The pre-ability-scroll record shape, kept for callers constructing an item with no scrolls. */
	public DecodedItem(String skyblockId, String displayName, int count, Rarity rarity,
			String reforge, int stars, boolean recombobulated, int hotPotatoBooks,
			Map<String, Integer> enchantments, List<String> gemstones,
			Map<String, Integer> attributes, Map<String, Integer> runes, PetInfo pet,
			PotionInfo potion, DungeonQuality quality, String dye, boolean ethermerged,
			long winningBid) {
		this(skyblockId, displayName, count, rarity, reforge, stars, recombobulated,
				hotPotatoBooks, enchantments, gemstones, attributes, runes, pet, potion, quality,
				List.of(), dye, ethermerged, winningBid, 0);
	}

	/** True for every blade whose price depends on the invisible Wither-impact scroll list. */
	public boolean isScrollCapableBlade() {
		return SCROLL_CAPABLE_BLADE_IDS.contains(skyblockId);
	}

	/** Whether all three known Wither-impact scrolls are applied. */
	public boolean hasCompleteAbilityScrollSet() {
		return abilityScrolls.equals(KNOWN_ABILITY_SCROLLS);
	}

	/**
	 * Sorts a valid scroll set and rejects ambiguity rather than silently pooling it.
	 *
	 * <p>Package-private so {@link ItemDecoder} can validate an untrusted NBT list before it creates
	 * an item. Unknown values and duplicates may acquire meaning in a future SkyBlock update; treating
	 * either as a known configuration would be a valuation guess.
	 */
	static List<String> normalizeAbilityScrolls(List<String> scrolls) {
		if (scrolls == null) {
			throw new IllegalArgumentException("ability scrolls are missing");
		}

		Set<String> normalized = new HashSet<>();
		for (String scroll : scrolls) {
			if (!KNOWN_ABILITY_SCROLLS.contains(scroll) || !normalized.add(scroll)) {
				throw new IllegalArgumentException("unknown or duplicate ability scroll");
			}
		}
		return normalized.stream().sorted().toList();
	}

	public boolean isPet() {
		return pet != null;
	}

	public Optional<PetInfo> petInfo() {
		return Optional.ofNullable(pet);
	}

	public boolean isPotion() {
		return potion != null;
	}

	public Optional<PotionInfo> potionInfo() {
		return Optional.ofNullable(potion);
	}

	/** Whether this item carries a dungeon quality roll that says anything about its price. */
	public boolean hasQuality() {
		return quality != null && !quality.signatureTerm().isEmpty();
	}

	public Optional<DungeonQuality> dungeonQuality() {
		return Optional.ofNullable(quality);
	}

	/**
	 * Whether this item was bought at the Dark Auction, and so carries the bid that set its stats.
	 *
	 * <p>The bid is the one price term that must <b>not</b> go in {@link #signature()}. It is a
	 * continuous number - 103 distinct values across 439 taped {@code MIDAS_STAFF} sales - so keying
	 * it makes a cell per sale, which is the drill parts' failure mode with an order of magnitude
	 * more values. Banded into powers of two it still costs coverage and still leaves the sales it
	 * does price out by more than the ratio quote does.
	 *
	 * <p>What it is good for instead is scaling: within one signature the sale price is close to a
	 * fixed multiple of the bid, so a pooled ratio prices every bid off every other bid's sales
	 * without splitting anything. See {@code FairValueModel.valueOf} and {@code MidasBidBacktestTest}.
	 */
	public boolean hasWinningBid() {
		return winningBid > 0L;
	}

	/** Whether a named dye was applied to this item. */
	public boolean isDyed() {
		return !dye.isEmpty();
	}

	/**
	 * A stable key for "items that are the same thing".
	 *
	 * <p>Two items with the same signature should be worth about the same, which is what makes it
	 * possible to price one from realized sales of the other. Built from sorted parts so the same
	 * configuration produces the same string every time, whatever order the blob happened to list
	 * things in.
	 *
	 * <p>Pets carry their level, which is most of what a pet is worth: on the recorded tape a
	 * level 1 pet and a level 100 pet of the same type and tier differ by 2x to 12x. Experience is
	 * still excluded, because the level does not have to be derived from it - see
	 * {@link PetInfo#level()}. A pet whose name stated no level falls back to the levelless key,
	 * which is what every pet used before.
	 */
	public String signature() {
		if (isPet()) {
			return petKey(pet.hasLevel() ? "lvl=" + pet.level() : "");
		}

		StringJoiner key = new StringJoiner("|");
		key.add(skyblockId).add(rarity.name());

		if (!reforge.isEmpty()) {
			key.add("reforge=" + reforge);
		}

		if (stars > 0) {
			key.add("stars=" + stars);
		}

		if (recombobulated) {
			key.add("recomb");
		}

		if (hotPotatoBooks > 0) {
			key.add("hpb=" + hotPotatoBooks);
		}

		if (!enchantments.isEmpty()) {
			StringJoiner enchants = new StringJoiner(",");
			enchantments.forEach((name, level) -> enchants.add(name + ":" + level));
			key.add("ench=" + enchants);
		}

		if (!gemstones.isEmpty()) {
			key.add("gems=" + String.join(",", gemstones));
		}

		// Level, not just presence: a level 1 roll sits near the bare price and a level 7 roll can be
		// worth several times the item under it, so banding the two together would price neither.
		if (!attributes.isEmpty()) {
			StringJoiner rolls = new StringJoiner(",");
			attributes.forEach((name, level) -> rolls.add(name + ":" + level));
			key.add("attrs=" + rolls);
		}

		// Every rune shares the id RUNE, the way every pet shares PET. Without this term the tape's
		// 3,589 rune sales collapse onto five keys - one per rarity - pooling a 2,000 coin GEM rune
		// with a 140,000,000 coin one. The tier belongs here for the same reason a pet's level does:
		// on the recorded tape MUSIC=1 has a median of 5,000,000 against 59,500,000 for MUSIC=3.
		if (!runes.isEmpty()) {
			StringJoiner applied = new StringJoiner(",");
			runes.forEach((name, tier) -> applied.add(name + ":" + tier));
			key.add("runes=" + applied);
		}

		// And POTION is the third id an entire market hides behind, after PET and RUNE. Unread, the
		// tape's 2,758 potion sales collapse onto one key per rarity with a median of 918,000 coins -
		// which is Stinky Cheese's price, because Stinky Cheese and Harvest Harbinger are 70% of the
		// count. Every cheap potion under that median then reads as a large discount on itself.
		if (isPotion()) {
			key.add("potion=" + potion.signatureTerm());
		}

		// Not a shared id like the three above - a dungeon drop keeps its own id - but the same kind
		// of pooling. SKELETON_MASTER_CHESTPLATE's tier 7 and tier 10 sales sat on one key across a
		// 56x gap, so half of them read as enormous discounts on the other half. See DungeonQuality
		// for why the stat boost is a flag here and the tier is a number.
		if (hasQuality()) {
			key.add("quality=" + quality.signatureTerm());
		}

		// Wither-impact scrolls are an invisible half-billion-coin state change on this blade family.
		// The empty set is a term too: without it an unscrolled blade can fall back to
		// a name-and-rarity pool containing fully scrolled sales. Unknown or malformed lists never
		// reach an item at all; ItemDecoder fails them closed.
		if (isScrollCapableBlade() || !abilityScrolls.isEmpty()) {
			key.add("abilityScrolls=" + (abilityScrolls.isEmpty()
					? "none" : String.join(",", abilityScrolls)));
		}

		// One bit, and the only entry from the unread-attribute list in four to survive being
		// measured at this key rather than at the item id. An Etherwarp Conduit merged into an
		// ASPECT_OF_THE_VOID is permanent and the market pays about 4x for it - 396 plain sales at
		// 5,900,000 sit in the same signature as 288 merged ones at 24,900,000 - so unread it both
		// blinds the model to every merged sale and quotes a plain sword at several times what
		// plain swords fetch.
		//
		// Re-measured against the model that ships rather than a hand-built copy of it, and it is
		// worse unread than the copy said: on a 24h holdout of the ids that ever merge, sales quoted
		// at 2x or more of what they fetched go from 175 to 20 and p90 |log err| from 1.440 to
		// 0.230, for 7 valuations in 1,053. The copy kept every sample where the Builder keeps the
		// most recent 200, which is what hid the harm: on a key pooling two populations the ring
		// fills with whichever sold lately, so the pooled median swings to the dearer one and every
		// cheap sale reads as a snipe. Keying the merge is what stops the pooling.
		//
		// tuned_transmission, the Transmission Tuner level that rides along on merged items, is
		// deliberately not here: it is worth 1.06x on top of the merge, and splitting merged sales
		// again strands 6 of them below the sample floor for a difference the market barely prices.
		// See EthermergeBacktestTest.
		if (ethermerged) {
			key.add("ethermerge");
		}

		// The count of gemstone slots paid open, as one bit rather than the number. A locked slot
		// costs real coins and gemstone materials, so an unlocked piece is worth far more than the
		// same piece shut - a Divan's Helmet ran ~38M locked against ~76M with its five slots open on
		// the tape - yet unread the two share this key and the pooled median quotes the locked one at
		// the unlocked price. Found in play 2026-09-02 quoting locked Divan pieces at ~60M as snipes.
		//
		// It is the attribute UnreadAttributeProbeTest was blind to: unlocked_slots lives inside the
		// gems compound, which the probe marked read for its placed gems, so the "no further
		// shared-id-shaped gap on this tape" claim was never tested against it.
		//
		// Shipped as a bit, not the count: on a 24h holdout of the 283 ids that ever unlock a slot,
		// keying the bit and keying the exact count were indistinguishable on fake snipes (622 against
		// 621 of 18,656 priced) and on error (median |log err| 0.126, p90 0.545), and the bit kept
		// more coverage (18,554 priced against 18,529; 229 open-slot sales against 204) because
		// intermediate slot counts are too sparse to form a pool. Against the count unread the bit
		// takes fake snipes 632 -> 622 for 102 valuations, mostly by refusing to quote the locked
		// minority of a mixed pool rather than mispricing it. The slot index (which hole) is dropped,
		// like placed gems: it does not move price. See GemstoneSlotBacktestTest.
		if (unlockedSlots > 0) {
			key.add("slots");
		}

		// Last, so a test can strip it back off to compare a dyed sale against a plain one. Worth
		// little on its own - 67 of 587 dyed keys hold an undyed sale and they run 0.9x to 2.1x,
		// because a dyed item is also a starred and enchanted one - and kept for the reason the
		// maxed quality flag is kept: it costs no coverage, since every dyed sale on the tape
		// carries something else that already keeps it out of the coarse index, and the 833x gap
		// it closes at the item id is a correlation nothing enforces. The raw `color` triple is
		// deliberately absent; see ItemDecoder and DyeSignatureBacktestTest.
		if (isDyed()) {
			key.add("dye=" + dye);
		}

		return key.toString();
	}

	/**
	 * Keys this item may be priced against, most specific first.
	 *
	 * <p>The first is always {@link #signature()}. Anything after it describes the item less
	 * completely and should be trusted less, but is worth having: a key with no realized sales
	 * behind it prices nothing at all, and the alternative to a slightly coarser match is usually
	 * no valuation rather than a better one.
	 *
	 * <p>Only pets have more than one rung today, and the gain is real but modest: on a holdout of
	 * the recorded tape, walking the ladder moved the median absolute log error from 0.146 to 0.134
	 * at identical coverage. <b>Most of the separation between a fresh pet and a maxed one was
	 * already being done by the held item</b>, which stays on every rung - 424 of the 684 taped
	 * pets holding an item were level 100, against 124 of the 1800 holding nothing - so the level
	 * refines that split rather than creating it. Measured against a key of type and tier alone the
	 * improvement looks like 0.291 to 0.155, but that key is not one this model has ever used, and
	 * quoting it would be measuring against a strawman.
	 *
	 * <p>The held item, skin and candy stay on every rung for the older reason too: a held item can
	 * be worth more than the pet under it, so dropping it to widen the pool would price a pet off
	 * sales of a cheaper one.
	 */
	public List<String> valuationKeys() {
		if (!isPet()) {
			return List.of(signature());
		}

		String levelless = petKey("");

		if (!pet.hasLevel()) {
			return List.of(levelless);
		}

		if (pet.bandIsJustThisLevel()) {
			return List.of(signature(), levelless);
		}

		return List.of(signature(), petKey("lvlBand=" + pet.levelBand()), levelless);
	}

	/**
	 * Whether {@link #signature()} leaves nothing about this item unaccounted for.
	 *
	 * <p>False for exactly one case: a pet whose display name carried no readable level. Its key is
	 * then the levelless one, which pools every level of that pet into a single median - the thing
	 * the level rungs exist to stop - so it must not be presented as an exact match even though it
	 * is the only key the item has.
	 */
	public boolean isFullyDescribed() {
		return !isPet() || pet.hasLevel();
	}

	/** The pet identity, with an optional extra term pinning how far up its level ladder it is. */
	private String petKey(String levelTerm) {
		StringJoiner petKey = new StringJoiner("|");
		petKey.add("PET").add(pet.type()).add(pet.tier().name());

		if (!pet.heldItem().isEmpty()) {
			petKey.add("held=" + pet.heldItem());
		}

		if (pet.hasSkin()) {
			petKey.add("skin=" + pet.skin());
		}

		if (pet.hasCandy()) {
			petKey.add("candy");
		}

		if (!levelTerm.isEmpty()) {
			petKey.add(levelTerm);
		}

		return petKey.toString();
	}
}
