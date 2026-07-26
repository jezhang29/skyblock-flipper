package jeff.skyblockflipper.core.model.dto;

import com.google.gson.annotations.SerializedName;

import jeff.skyblockflipper.core.model.ItemCatalog;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Wire format of {@code /v2/resources/skyblock/items}. */
public final class ItemsDto {
	public boolean success;
	public List<ItemDto> items;

	public static final class ItemDto {
		public String id;
		public String name;

		/**
		 * Absent for items no NPC will buy. Boxed on purpose: 0 is a legitimate price for junk
		 * items, so it must stay distinguishable from "no NPC buys this".
		 *
		 * <p>Fractional despite being a coin price - 60 items in the catalog sell for less than a
		 * coin each (a torch is 0.3). Parsing this as an integer type throws on the whole payload.
		 */
		@SerializedName("npc_sell_price")
		public Double npcSellPrice;
	}

	public ItemCatalog toCatalog() {
		Map<String, ItemCatalog.Entry> entries = new HashMap<>();

		if (items != null) {
			for (ItemDto item : items) {
				if (item != null && item.id != null) {
					entries.put(item.id, new ItemCatalog.Entry(item.id, item.name, item.npcSellPrice));
				}
			}
		}

		return new ItemCatalog(entries);
	}
}
