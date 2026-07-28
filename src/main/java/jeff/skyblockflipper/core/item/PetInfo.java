package jeff.skyblockflipper.core.item;

/**
 * A pet, which Hypixel stores as a JSON string inside an NBT string tag inside the item blob.
 *
 * <p>Every pet shares the item id {@code PET}, so without this the whole pet market looks like one
 * item trading between 10k and 500M.
 *
 * @param heldItem  pet item held, or "" - a held item can be worth more than the pet
 * @param candyUsed pet candies used; a candied pet sells for noticeably less than a clean one
 * @param exp       raw experience, as it appeared in the blob
 * @param level     the pet's level, or 0 when the name did not state one. <b>Not derived from
 *                  {@link #exp}</b> - Hypixel writes the level into the item's display name as
 *                  {@code [Lvl 100] Mole}, on every one of the 2484 pet sales on the recorded tape,
 *                  so the XP curves that differ by tier and by pet never have to be bundled to read
 *                  it. Zero is the honest answer when the prefix is missing rather than a guess,
 *                  and it costs only the level-aware rungs of the valuation ladder
 */
public record PetInfo(
		String type,
		Rarity tier,
		double exp,
		int level,
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

	/** False when the display name carried no {@code [Lvl N]} prefix to read. */
	public boolean hasLevel() {
		return level > 0;
	}

	/**
	 * Upper bound of each level band, in order. A pet falls in the first band it does not exceed.
	 *
	 * <p>Levels 1 and 100 are bands of their own because that is what the market is: of 2484 pet
	 * sales on the tape, 1455 were level 1 and 548 were level 100, so 81% of pets are either fresh
	 * or maxed and nothing else. Grouping either with its neighbours would reintroduce exactly the
	 * error these bands exist to remove - a single median over a distribution with two peaks, which
	 * is wrong for both of them.
	 */
	private static final int[] BAND_BOUNDS = {1, 29, 59, 89, 99, 100, Integer.MAX_VALUE};

	/**
	 * A coarser level key, for when this pet's exact level has too few sales to price against.
	 *
	 * <p>Empty when the level is unknown. The top band is open-ended because Golden Dragon reaches
	 * 200 while every other pet stops at 100.
	 */
	public String levelBand() {
		if (!hasLevel()) {
			return "";
		}

		int low = 1;

		for (int high : BAND_BOUNDS) {
			if (level <= high) {
				if (low == high) {
					return String.valueOf(low);
				}

				return high == Integer.MAX_VALUE ? low + "+" : low + "-" + high;
			}

			low = high + 1;
		}

		return String.valueOf(level);
	}

	/**
	 * True when this pet's band holds only its own level, so the band says nothing new.
	 *
	 * <p>Levels 1 and 100 are bands of one, and between them they are 81% of pets. Without this the
	 * valuation ladder would carry a second key with an identical set of sales behind it for four
	 * pets in five - the same median, reached more expensively, and never able to match anything
	 * the exact key did not already match.
	 */
	public boolean bandIsJustThisLevel() {
		return levelBand().equals(String.valueOf(level));
	}
}
