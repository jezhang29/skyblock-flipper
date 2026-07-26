package jeff.skyblockflipper.core.item;

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
		PetInfo pet
) {
	public DecodedItem {
		enchantments = Map.copyOf(new TreeMap<>(enchantments));
		gemstones = List.copyOf(gemstones);
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
	 * <p>Pets deliberately exclude experience: level is what a pet is worth, level comes from
	 * experience through a curve that differs by tier and by pet, and those tables are not bundled
	 * yet. Banding raw experience would look like a level without being one.
	 */
	public String signature() {
		if (isPet()) {
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

			return petKey.toString();
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

		return key.toString();
	}
}
