package jeff.skyblockflipper.core.item;

/**
 * A pet, which Hypixel stores as a JSON string inside an NBT string tag inside the item blob.
 *
 * <p>Every pet shares the item id {@code PET}, so without this the whole pet market looks like one
 * item trading between 10k and 500M.
 *
 * @param heldItem  pet item held, or "" - a held item can be worth more than the pet
 * @param candyUsed pet candies used; a candied pet sells for noticeably less than a clean one
 * @param exp       raw experience. Not turned into a level here: the curve differs by tier and by
 *                  pet (Golden Dragon has its own), and guessing a level would be worse than
 *                  reporting the number that was actually in the blob.
 */
public record PetInfo(
		String type,
		Rarity tier,
		double exp,
		String heldItem,
		int candyUsed,
		String skin
) {
	public boolean hasCandy() {
		return candyUsed > 0;
	}

	public boolean hasSkin() {
		return !skin.isEmpty();
	}
}
