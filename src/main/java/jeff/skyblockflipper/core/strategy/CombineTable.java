package jeff.skyblockflipper.core.strategy;

import java.util.ArrayList;
import java.util.List;

/**
 * Which enchanted books combine on the anvil, and over what tier range.
 *
 * <p><b>This is game data, and it is not price-inferable.</b> The bazaar lists a book only when it
 * cannot come straight off the enchant table, so a listed low tier can still be drop-gated rather
 * than anvil-combinable, and a thousand-fold gap between two tiers can be pure tedium premium rather
 * than a wall. There is no enchantments resource, and the items resource does not carry books, so
 * the API cannot answer which enchants combine or how far. This allowlist is a hardcoded wiki and
 * user-spec claim, validated against the live bazaar of 2026-08-20 only for the existence of every
 * target and at least one source. See {@code docs/adr/0003-combine-is-its-own-quote.md}.
 *
 * <p>Adding an entry is therefore a factual claim about the game, not an observation about a price.
 * The enchants deliberately left out - experiment, champion, compact, cultivating, expertise,
 * hecatomb, Scavenger, Growth, Power, Protection, Vampirism - are drop-gated or non-anvil and must
 * stay out however tempting their spread looks.
 *
 * <p>Each entry carries the source-tier range as well as the max, because some enchants are only
 * anvil-combinable from a tier above 1 ({@code QUANTUM} starts at 3, {@code TABASCO} at 2) and
 * feeding a drop-gated low tier into the solver as if it combined would price a trade the game does
 * not allow.
 */
public final class CombineTable {
	private CombineTable() {
	}

	/**
	 * One combinable enchant.
	 *
	 * @param enchantId      the middle of the bazaar id, e.g. {@code FEATHER_FALLING} in
	 *                       {@code ENCHANTMENT_FEATHER_FALLING_6}
	 * @param minSourceTier  the lowest tier the anvil combines upward, and so the lowest the solver
	 *                       may source from
	 * @param maxTier        the highest tier reachable by combining, and the one this flip sells
	 */
	public record Entry(String enchantId, int minSourceTier, int maxTier) {
		public Entry {
			if (enchantId == null || enchantId.isBlank()) {
				throw new IllegalArgumentException("combine entry with no enchant id");
			}

			if (minSourceTier < 1 || maxTier <= minSourceTier) {
				throw new IllegalArgumentException(
						enchantId + " has an empty source range [" + minSourceTier + ".." + maxTier + ")");
			}
		}

		/** The bazaar product id for this enchant at {@code tier}. */
		public String bookId(int tier) {
			return "ENCHANTMENT_" + enchantId + "_" + tier;
		}

		/** The book this flip sells: the top tier. */
		public String targetId() {
			return bookId(maxTier);
		}

		/** Every tier the solver may source from, low to high. It scans them; it does not assume one. */
		public List<Integer> sourceTiers() {
			List<Integer> tiers = new ArrayList<>(maxTier - minSourceTier);

			for (int tier = minSourceTier; tier < maxTier; tier++) {
				tiers.add(tier);
			}

			return tiers;
		}

		/** Books of {@code sourceTier} that combine into one top-tier book: {@code 2^(maxTier-tier)}. */
		public long booksPerOutput(int sourceTier) {
			return 1L << (maxTier - sourceTier);
		}

		/**
		 * Anvil combines to make one top-tier book from {@code sourceTier}: {@code 2^(maxTier-tier) - 1}.
		 *
		 * <p>This is the click cost the strategy is really spending, and the reason its honest ranking
		 * axis is net per combine rather than per hour - see {@code npc-flipping-clicks-are-the-cost}
		 * in memory. Sixteen Feather Falling 6 become one Feather Falling 10 in fifteen merges.
		 */
		public long combinesPerOutput(int sourceTier) {
			return booksPerOutput(sourceTier) - 1L;
		}
	}

	/**
	 * The shipped allowlist: 36 enchants, every one with a live target and source on 2026-08-20.
	 *
	 * <p>Grouped by max tier and source floor. The Vitality four ({@code MANA_VAMPIRE},
	 * {@code HARDENED_MANA}, {@code STRONG_MANA}, {@code FEROCIOUS_MANA}) combine from tier 1 but
	 * their only liquid source is usually tier 5, which is the solver's job to find, not the table's.
	 */
	public static List<Entry> all() {
		List<Entry> entries = new ArrayList<>();

		// Combine to 10, sourced from the bazaar-listed high tiers.
		entries.add(new Entry("FEATHER_FALLING", 6, 10));
		entries.add(new Entry("INFINITE_QUIVER", 6, 10));

		// The four Vitality enchants: combinable from tier 1, but scan for the liquid source.
		entries.add(new Entry("MANA_VAMPIRE", 1, 10));
		entries.add(new Entry("HARDENED_MANA", 1, 10));
		entries.add(new Entry("STRONG_MANA", 1, 10));
		entries.add(new Entry("FEROCIOUS_MANA", 1, 10));

		// Combine to 5 from tier 1.
		entries.add(new Entry("PRISTINE", 1, 5));
		entries.add(new Entry("CHARM", 1, 5));
		entries.add(new Entry("CORRUPTION", 1, 5));
		entries.add(new Entry("GREEN_THUMB", 1, 5));
		entries.add(new Entry("OVERLOAD", 1, 5));
		entries.add(new Entry("PROSPERITY", 1, 5));
		entries.add(new Entry("REFLECTION", 1, 5));
		entries.add(new Entry("REJUVENATE", 1, 5));
		entries.add(new Entry("SMARTY_PANTS", 1, 5));
		entries.add(new Entry("SMOLDERING", 1, 5));
		entries.add(new Entry("RESPITE", 1, 5));

		// The nine Turbo-crop enchants (TURBO_COCO, not COCOA), combine to 5 from tier 1.
		entries.add(new Entry("TURBO_CACTUS", 1, 5));
		entries.add(new Entry("TURBO_CANE", 1, 5));
		entries.add(new Entry("TURBO_CARROT", 1, 5));
		entries.add(new Entry("TURBO_COCO", 1, 5));
		entries.add(new Entry("TURBO_MELON", 1, 5));
		entries.add(new Entry("TURBO_MUSHROOMS", 1, 5));
		entries.add(new Entry("TURBO_POTATO", 1, 5));
		entries.add(new Entry("TURBO_PUMPKIN", 1, 5));
		entries.add(new Entry("TURBO_WARTS", 1, 5));

		// Combine to 5, but only from a tier above 1: the low tiers are drop-gated, not anvil.
		entries.add(new Entry("QUANTUM", 3, 5));
		entries.add(new Entry("VICIOUS", 3, 5));
		entries.add(new Entry("BIG_BRAIN", 3, 5));
		entries.add(new Entry("CAYENNE", 4, 5));
		entries.add(new Entry("TRANSYLVANIAN", 4, 5));

		// Combine to 3.
		entries.add(new Entry("DEDICATION", 1, 3));
		entries.add(new Entry("DIVINE_GIFT", 1, 3));
		entries.add(new Entry("MANA_STEAL", 1, 3));
		entries.add(new Entry("SUGAR_RUSH", 1, 3));
		entries.add(new Entry("TABASCO", 2, 3));

		return entries;
	}
}
