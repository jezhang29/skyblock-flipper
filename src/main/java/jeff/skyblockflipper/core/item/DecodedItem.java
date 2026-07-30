package jeff.skyblockflipper.core.item;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
		PetInfo pet
) {
	public DecodedItem {
		// Sorted and kept sorted. Map.copyOf would be immutable but not ordered - its iteration
		// order is salted per JVM - and signature() below promises the same string every time.
		enchantments = Collections.unmodifiableMap(new TreeMap<>(enchantments));
		gemstones = List.copyOf(gemstones);
		attributes = Collections.unmodifiableMap(new TreeMap<>(attributes));
		runes = Collections.unmodifiableMap(new TreeMap<>(runes));
	}

	public boolean isPet() {
		return pet != null;
	}

	public Optional<PetInfo> petInfo() {
		return Optional.ofNullable(pet);
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
