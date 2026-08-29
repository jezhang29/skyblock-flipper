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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
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
	 * @param unstackable   true for items the resource says occupy one inventory slot each. Never
	 *                      read directly: the flag is missing from whole classes of item that do
	 *                      not stack, so {@link Stacking} decides this from the order book and only
	 *                      consults the flag as a second route to the same answer
	 * @param upgradeCosts  what each star level costs, cheapest first; empty for the great majority
	 *                      of items, which cannot be starred at all
	 */
	public record Entry(String id, String name, Double npcSellPrice, boolean unstackable,
			List<UpgradeCost> upgradeCosts) {
		public Entry {
			upgradeCosts = List.copyOf(upgradeCosts);
		}

		/** For the items and tests that have no star costs to state. */
		public Entry(String id, String name, Double npcSellPrice) {
			this(id, name, npcSellPrice, false, List.of());
		}

		/** The shape before stacking was carried, kept so star-cost callers need not restate it. */
		public Entry(String id, String name, Double npcSellPrice, List<UpgradeCost> upgradeCosts) {
			this(id, name, npcSellPrice, false, upgradeCosts);
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
	 * What a typed item turned into: the one item it can only mean, or everything it could mean.
	 *
	 * @param resolved   the single item the query names, or null when it named none or several
	 * @param candidates every item the query matched, best first, so a caller with nothing resolved
	 *                   has something to offer instead of an error
	 */
	public record Lookup(String resolved, List<String> candidates) {
		public Lookup {
			candidates = List.copyOf(candidates);
		}

		public static Lookup none() {
			return new Lookup(null, List.of());
		}

		/** The item to act on, present only when the query was unambiguous. */
		public Optional<String> only() {
			return Optional.ofNullable(resolved);
		}

		public boolean isEmpty() {
			return candidates.isEmpty();
		}
	}

	/**
	 * The item a player meant by what they typed, by id or by the name they read in game.
	 *
	 * <p>Ids cannot be guessed from names and the mod must stop pretending they can. Nether Wart is
	 * {@code NETHER_STALK}, so Nether Wart Distillate is {@code NETHER_STALK_DISTILLATE}; the same
	 * legacy Minecraft vocabulary that makes {@code DOUBLE_PLANT} a sunflower runs through the whole
	 * catalog. A player reading the bazaar sees only the name, so the name has to be a way in.
	 *
	 * <p>Matching runs in tiers - id, whole name, name prefix, all words present, id substring - and
	 * a query resolves only when the best tier it reaches holds exactly one item. That is what keeps
	 * the Enchanted Melon problem from being decided by ranking: "enchanted melon" is the whole name
	 * of one item and the prefix of another, so it resolves to the exact one, while a query matching
	 * both loosely resolves to neither and is handed back as a choice. See {@link #shadowedBy}.
	 *
	 * @param query      an id, a display name, or part of either. Spaces and case do not matter
	 * @param restrictTo the ids worth offering at all, usually the bazaar's products; empty means
	 *                   the whole catalog. Ids outside the catalog still match on their own text,
	 *                   so this works before the item resource has ever been fetched
	 */
	public Lookup find(String query, Collection<String> restrictTo) {
		if (query == null || query.isBlank()) {
			return Lookup.none();
		}

		String trimmed = query.trim();
		String asId = trimmed.toUpperCase(Locale.ROOT).replace(' ', '_');
		String lower = trimmed.toLowerCase(Locale.ROOT);
		String[] words = lower.split("\\s+");
		Collection<String> pool = pool(restrictTo);

		// An exact id beats everything, but only when what was typed could be one. A query with a
		// space in it was read off the screen, and "Enchanted Melon" is the whole name of the 51k
		// item as surely as it is the id of the 341 one - so spaces mean the name wins.
		if (words.length == 1 && pool.contains(asId)) {
			return new Lookup(asId, List.of(asId));
		}

		List<List<String>> tiers = List.of(
				new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>());

		for (String id : pool) {
			String name = displayName(id).toLowerCase(Locale.ROOT);

			if (name.equals(lower)) {
				tiers.get(0).add(id);
			} else if (name.startsWith(lower)) {
				tiers.get(1).add(id);
			} else if (containsEveryWord(name, words)) {
				tiers.get(2).add(id);
			} else if (id.contains(asId)) {
				tiers.get(3).add(id);
			}
		}

		String resolved = null;
		List<String> candidates = new ArrayList<>();

		for (List<String> tier : tiers) {
			tier.sort(Comparator.comparing(this::displayName).thenComparing(Comparator.naturalOrder()));

			// The first tier that matched anything is the only one allowed to decide. A lower tier
			// exists to be offered, never to break a tie the better tier already failed to break.
			if (resolved == null && candidates.isEmpty() && tier.size() == 1) {
				resolved = tier.getFirst();
			}

			candidates.addAll(tier);
		}

		return new Lookup(resolved, candidates);
	}

	/** {@link #find(String, Collection)} across the whole catalog. */
	public Lookup find(String query) {
		return find(query, List.of());
	}

	/**
	 * Everything worth matching against: the restriction when there is one, plus the catalog.
	 *
	 * <p>The union rather than the intersection, because the two disagree in both directions - the
	 * bazaar sells products the item resource has never heard of, and the resource lists thousands
	 * of items no bazaar trades. A restricted search stays restricted; an unrestricted one sees
	 * everything known.
	 */
	private Collection<String> pool(Collection<String> restrictTo) {
		if (restrictTo == null || restrictTo.isEmpty()) {
			return items.keySet();
		}

		return new LinkedHashSet<>(restrictTo);
	}

	private static boolean containsEveryWord(String name, String[] words) {
		for (String word : words) {
			if (!word.isEmpty() && !name.contains(word)) {
				return false;
			}
		}

		return true;
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
