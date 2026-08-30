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
package jeff.skyblockflipper.core.model.dto;

import com.google.gson.annotations.SerializedName;

import jeff.skyblockflipper.core.model.ItemCatalog;
import jeff.skyblockflipper.core.model.UpgradeCost;
import jeff.skyblockflipper.core.recipe.FusionTable;

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
		 * Present and true for some items that do not stack. Absent for everything else, so a plain
		 * boolean reading false is the correct default here.
		 *
		 * <p><b>Absent is not evidence that an item stacks.</b> Measured 2026-08-11: 515 of 5,646
		 * items carry the flag, and none of the 107 reforge stones does, nor does
		 * {@code JUNGLE_HEART} - all of which the bazaar caps at 256 units an order. Where the flag
		 * is set it has never been contradicted by the book, so it is worth keeping, but the answer
		 * comes from {@link jeff.skyblockflipper.core.model.Stacking} rather than from here.
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
					addEssenceEntries(item, entries);
				}
			}
		}

		addShardEntries(entries);

		return new ItemCatalog(entries);
	}

	/**
	 * Attribute shards are bazaar products the endpoint omits wholesale, the same gap essences have.
	 *
	 * <p>Measured 2026-08-29: the live {@code resources/skyblock/items} lists 5650 items and not one
	 * of the 320 {@code SHARD_*} the bazaar trades, so {@link ItemCatalog#displayName} falls back to
	 * the raw id and every fusion view prints {@code SHARD_MOLTENFISH} instead of {@code Molten
	 * Fish}. The names the endpoint withholds are bundled in {@link FusionTable} (from SkyShards), so
	 * synthesise the entry here at the single point the catalog is built, and the fusion job rows,
	 * the candidate list, the HUD and {@code find()} name-search all read the real name for free.
	 *
	 * <p>{@code computeIfAbsent} so a real shard row the endpoint ever does ship wins over the
	 * bundled one - today only the unrelated {@code SHARD_OF_THE_SHREDDED} is present.
	 */
	private static void addShardEntries(Map<String, ItemCatalog.Entry> entries) {
		for (FusionTable.Shard shard : FusionTable.bundled().shards()) {
			if (shard.name() == null || shard.name().isBlank()) {
				continue;
			}

			entries.computeIfAbsent(shard.id(),
					k -> new ItemCatalog.Entry(shard.id(), shard.name(), null));
		}
	}

	/**
	 * Essences are bazaar products but carry no row of their own in this endpoint, so a consumer
	 * that only ever meets them as an upgrade ingredient - like an NPC flip on {@code ESSENCE_CRIMSON}
	 * - would show the raw id and paste it into the bazaar search, which finds nothing. Their type
	 * appears here on the ingredient, so synthesise the entry the endpoint omits: {@code CRIMSON}
	 * becomes {@code Crimson Essence}, the name the bazaar itself uses.
	 */
	private static void addEssenceEntries(ItemDto item, Map<String, ItemCatalog.Entry> entries) {
		if (item.upgradeCosts == null) {
			return;
		}

		for (List<CostDto> level : item.upgradeCosts) {
			if (level == null) {
				continue;
			}

			for (CostDto cost : level) {
				if (cost == null || !"ESSENCE".equals(cost.type)
						|| cost.essenceType == null || cost.essenceType.isBlank()) {
					continue;
				}

				String id = "ESSENCE_" + cost.essenceType;
				entries.computeIfAbsent(id, k -> new ItemCatalog.Entry(
						id, essenceName(cost.essenceType), null));
			}
		}
	}

	/** {@code CRIMSON} to {@code Crimson Essence}. Essence types are single words, so this suffices. */
	private static String essenceName(String type) {
		String lower = type.toLowerCase(java.util.Locale.ROOT);
		return Character.toUpperCase(lower.charAt(0)) + lower.substring(1) + " Essence";
	}
}
