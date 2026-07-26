package jeff.skyblockflipper.core.model;

import java.util.Map;
import java.util.Optional;

/**
 * Static item definitions from {@code /v2/resources/skyblock/items}.
 *
 * <p>Changes only with game updates, so it is fetched rarely.
 *
 * @param id           Skyblock item id, e.g. {@code ENCHANTED_DIAMOND}
 * @param npcSellPrice fixed price an NPC pays, or null if no NPC buys it
 */
public record ItemCatalog(Map<String, Entry> items) {
	public record Entry(String id, String name, Double npcSellPrice) {
		/** Fractional for cheap items, so this stays a double all the way through the math. */
		public Optional<Double> npcPrice() {
			return Optional.ofNullable(npcSellPrice);
		}
	}

	public ItemCatalog {
		items = Map.copyOf(items);
	}

	public static ItemCatalog empty() {
		return new ItemCatalog(Map.of());
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
}
