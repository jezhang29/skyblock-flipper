package jeff.skyblockflipper.core.track;

import java.util.List;

/**
 * One item in a Hypixel menu, reduced to the parts a tracker could read.
 *
 * <p>{@code itemId} is the whole reason menu capture is worth doing: Hypixel stamps its own item id
 * into the stack's custom data, so a menu says {@code ENCHANTED_MELON_BLOCK} where chat only says
 * "Enchanted Melon". Chat's display names are ambiguous - 187 of 5549 item names are a strict prefix
 * of another - and this side-steps that entirely.
 *
 * @param index      slot position in the menu, which is how a parser will tell a row of orders from
 *                   the decoration around it
 * @param name       hover name with formatting codes stripped
 * @param lore       tooltip lines, stripped the same way; where the fill state of an order lives
 * @param customData the whole custom-data compound as SNBT, kept raw because step 0 does not yet
 *                   know which key matters and re-capturing costs another play session
 */
public record CapturedSlot(int index, String name, List<String> lore, String itemId, int count,
		String customData) {
	public CapturedSlot {
		lore = List.copyOf(lore);
	}
}
