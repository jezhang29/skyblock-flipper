package jeff.skyblockflipper.core.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Static item definitions from {@code /v2/resources/skyblock/items}.
 *
 * <p>Changes only with game updates, so it is fetched rarely.
 *
 * <p>A class rather than a record because it precomputes the shadow index described on
 * {@link #shadowedBy(String)}: that costs one pass over five and a half thousand names, which is
 * fine once per fetch and far too expensive per candidate.
 */
public final class ItemCatalog {
	/**
	 * @param id            Skyblock item id, e.g. {@code ENCHANTED_DIAMOND}
	 * @param npcSellPrice  fixed price an NPC pays, or null if no NPC buys it
	 * @param upgradeCosts  what each star level costs, cheapest first; empty for the great majority
	 *                      of items, which cannot be starred at all
	 */
	public record Entry(String id, String name, Double npcSellPrice, List<UpgradeCost> upgradeCosts) {
		public Entry {
			upgradeCosts = List.copyOf(upgradeCosts);
		}

		/** For the items and tests that have no star costs to state. */
		public Entry(String id, String name, Double npcSellPrice) {
			this(id, name, npcSellPrice, List.of());
		}

		/** Fractional for cheap items, so this stays a double all the way through the math. */
		public Optional<Double> npcPrice() {
			return Optional.ofNullable(npcSellPrice);
		}

		/**
		 * How many stars this item can take. Not always five: master stars push the common case to
		 * ten, and a handful of items define fifteen levels.
		 */
		public int maxStars() {
			return upgradeCosts.size();
		}
	}

	private final Map<String, Entry> items;

	/** Every known name, lower-cased and sorted, so a prefix lookup is a binary search. */
	private final List<String> sortedNames;

	public ItemCatalog(Map<String, Entry> items) {
		this.items = Map.copyOf(items);
		this.sortedNames = indexNames(this.items);
	}

	public static ItemCatalog empty() {
		return new ItemCatalog(Map.of());
	}

	public Map<String, Entry> items() {
		return items;
	}

	public Optional<Entry> get(String id) {
		return Optional.ofNullable(items.get(id));
	}

	/** Display name if known, otherwise the raw id so the UI always shows something. */
	public String displayName(String id) {
		return get(id).map(Entry::name).filter(n -> n != null && !n.isBlank()).orElse(id);
	}

	public boolean isEmpty() {
		return items.isEmpty();
	}

	/**
	 * Another item whose name starts with this item's whole name, if there is one.
	 *
	 * <p>This is the "which Enchanted Melon?" problem, and it costs real money. Hypixel renamed the
	 * melons to match modern Minecraft: {@code ENCHANTED_MELON_BLOCK} is now called
	 * <i>Enchanted Melon</i> and {@code ENCHANTED_MELON} is <i>Enchanted Melon Slice</i>. Typing the
	 * former into the bazaar search returns both, one of them priced at 51k and the other at 341,
	 * and buying the wrong one is a total loss rather than a bad flip.
	 *
	 * <p>Measured against the live catalog: 187 of 5549 entries are shadowed this way. The mod does
	 * not try to invent a better name for them - the id words that a name is missing are mostly
	 * legacy Minecraft junk ({@code DOUBLE_PLANT} is "Sunflower", {@code INK_SACK:3} is "Cocoa
	 * Beans"), so a generated qualifier would be wrong more often than it helped. It says out loud
	 * that the search is ambiguous instead, and lets the price and the id settle it.
	 *
	 * @return the shadowing name, or empty when the search is unambiguous
	 */
	public Optional<String> shadowedBy(String id) {
		String name = displayName(id);
		String prefix = name.toLowerCase(Locale.ROOT) + " ";
		int found = Collections.binarySearch(sortedNames, prefix);
		// Not found is the normal case - the prefix ends in a space, so it is the insertion point
		// that matters: the first name that sorts after it is the only one that can extend it.
		int at = found >= 0 ? found : -found - 1;

		if (at >= sortedNames.size() || !sortedNames.get(at).startsWith(prefix)) {
			return Optional.empty();
		}

		// Search by lower-cased key, report the name as it is actually spelled in game.
		String shadowing = sortedNames.get(at);

		return items.values().stream()
				.map(Entry::name)
				.filter(n -> n != null && n.toLowerCase(Locale.ROOT).equals(shadowing))
				.findFirst();
	}

	/**
	 * The identity lines a candidate should carry: always the id, plus a warning when the name alone
	 * would not pick the item out of a bazaar search.
	 */
	public List<String> identityNotes(String id) {
		List<String> notes = new ArrayList<>(2);
		notes.add("Item id " + id);

		shadowedBy(id).ifPresent(other -> notes.add(
				"\"" + displayName(id) + "\" also matches \"" + other + "\" in the search box - "
						+ "check the price before you buy"));

		return notes;
	}

	private static List<String> indexNames(Map<String, Entry> items) {
		List<String> names = new ArrayList<>(items.size());

		for (Entry entry : items.values()) {
			if (entry.name() != null && !entry.name().isBlank()) {
				names.add(entry.name().toLowerCase(Locale.ROOT));
			}
		}

		names.sort(null);
		return List.copyOf(names);
	}
}
