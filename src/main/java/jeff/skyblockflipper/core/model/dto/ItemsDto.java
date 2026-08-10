package jeff.skyblockflipper.core.model.dto;

import com.google.gson.annotations.SerializedName;

import jeff.skyblockflipper.core.model.ItemCatalog;
import jeff.skyblockflipper.core.model.UpgradeCost;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Wire format of {@code /v2/resources/skyblock/items}.
 *
 * <p><b>Recipes are not declared here, and that is not an omission.</b> Exactly one item in the
 * live 5549-entry catalog carries a {@code recipes} field ({@code PRECURSOR_APPARATUS}), and no
 * item carries a singular {@code recipe} at all, so there is no craft graph to read out of this
 * endpoint however many fields are added to this class.
 */
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

		/**
		 * Present and true for items that do not stack. Absent for everything else, which is the
		 * overwhelming majority, so a plain boolean reading false is the correct default here.
		 *
		 * <p>Worth 64x the carrying capacity of a trip: an inventory holds 2,304 of a stackable item
		 * and 36 of one of these. 19 of the 816 bazaar products with an NPC price carry the flag.
		 */
		public boolean unstackable;

		/**
		 * Cost of each star level, cheapest first. Absent for the ~90% of items that cannot be
		 * starred.
		 *
		 * <p>Length varies: 5 for a plain dungeon item, 10 where master stars are also defined, and
		 * a handful of items run to 15. Nothing should assume five.
		 */
		@SerializedName("upgrade_costs")
		public List<List<CostDto>> upgradeCosts;

		List<UpgradeCost> toUpgradeCosts() {
			if (upgradeCosts == null) {
				return List.of();
			}

			List<UpgradeCost> levels = new ArrayList<>(upgradeCosts.size());

			for (List<CostDto> level : upgradeCosts) {
				if (level == null) {
					continue;
				}

				List<UpgradeCost.Ingredient> ingredients = new ArrayList<>(level.size());

				for (CostDto cost : level) {
					if (cost != null) {
						cost.productId().ifPresent(id ->
								ingredients.add(new UpgradeCost.Ingredient(id, cost.amount)));
					}
				}

				levels.add(new UpgradeCost(ingredients));
			}

			return List.copyOf(levels);
		}
	}

	/** One ingredient of one star level. */
	public static final class CostDto {
		/** {@code ESSENCE} or {@code ITEM}; which of the two id fields is populated follows from it. */
		public String type;

		@SerializedName("essence_type")
		public String essenceType;

		@SerializedName("item_id")
		public String itemId;

		public int amount;

		/**
		 * The bazaar product this ingredient is bought as.
		 *
		 * <p>An essence names itself by bare type - {@code SPIDER} - while the bazaar trades it as
		 * {@code ESSENCE_SPIDER}. Prefixing here, at the single point the payload is translated, is
		 * what lets every consumer treat both ingredient kinds as one thing.
		 *
		 * @return empty for a cost this class does not understand, rather than a guessed product id
		 */
		Optional<String> productId() {
			if ("ESSENCE".equals(type)) {
				return essenceType == null || essenceType.isBlank()
						? Optional.empty()
						: Optional.of("ESSENCE_" + essenceType);
			}

			if ("ITEM".equals(type)) {
				return itemId == null || itemId.isBlank()
						? Optional.empty()
						: Optional.of(itemId);
			}

			return Optional.empty();
		}
	}

	public ItemCatalog toCatalog() {
		Map<String, ItemCatalog.Entry> entries = new HashMap<>();

		if (items != null) {
			for (ItemDto item : items) {
				if (item != null && item.id != null) {
					entries.put(item.id, new ItemCatalog.Entry(item.id, item.name,
							item.npcSellPrice, item.unstackable, item.toUpgradeCosts()));
				}
			}
		}

		return new ItemCatalog(entries);
	}
}
