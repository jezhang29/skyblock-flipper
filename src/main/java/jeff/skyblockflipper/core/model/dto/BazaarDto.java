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

import jeff.skyblockflipper.core.model.BazaarProduct;
import jeff.skyblockflipper.core.model.BazaarQuickStatus;
import jeff.skyblockflipper.core.model.BazaarSnapshot;
import jeff.skyblockflipper.core.model.ActivityProxy;
import jeff.skyblockflipper.core.model.DepthCoverage;
import jeff.skyblockflipper.core.model.MarketObservation;
import jeff.skyblockflipper.core.model.OrderLevel;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Wire format of {@code /v2/skyblock/bazaar}, kept separate from the domain model.
 *
 * <p><b>This is the only place in the codebase allowed to mention {@code buy_summary} and
 * {@code sell_summary}.</b> Hypixel names those sides from the perspective of the order you
 * would place, not the side of the book they sit on, so they read backwards:
 *
 * <ul>
 *   <li>{@code buy_summary} holds <b>sell offers</b> (asks). These are what you buy from, and the
 *       best one is the lowest price. Verified against {@code quick_status.buyPrice}.</li>
 *   <li>{@code sell_summary} holds <b>buy orders</b> (bids). These are what you sell into, and the
 *       best one is the highest price. Verified against {@code quick_status.sellPrice}.</li>
 * </ul>
 *
 * <p>Both arrays arrive sorted best-price-first, so index 0 is top of book on each side.
 */
public final class BazaarDto {
	public boolean success;
	public long lastUpdated;
	public Map<String, ProductDto> products;

	public static final class ProductDto {
		@SerializedName("product_id")
		public String productId;

		/** Sell offers / asks, despite the name. Ascending: cheapest ask first. */
		@SerializedName("buy_summary")
		public List<SummaryDto> asks;

		/** Buy orders / bids, despite the name. Descending: highest bid first. */
		@SerializedName("sell_summary")
		public List<SummaryDto> bids;

		@SerializedName("quick_status")
		public QuickStatusDto quickStatus;
	}

	public static final class SummaryDto {
		public double pricePerUnit;
		public long amount;
		public int orders;
	}

	public static final class QuickStatusDto {
		public String productId;
		public double sellPrice;
		public long sellVolume;
		public long sellMovingWeek;
		public int sellOrders;
		public double buyPrice;
		public long buyVolume;
		public long buyMovingWeek;
		public int buyOrders;
	}

	/** Translates the wire format into the domain model, swapping the two sides exactly once. */
	public BazaarSnapshot toSnapshot() {
		return toObservation(Instant.EPOCH).snapshot();
	}

	/** Translates the complete wire response without conflating source and retrieval clocks. */
	public MarketObservation toObservation(Instant retrievedAt) {
		Map<String, BazaarProduct> mapped = new HashMap<>();

		if (products != null) {
			products.forEach((id, dto) -> {
				if (dto == null) {
					return;
				}

				List<OrderLevel> sellOffers = levels(dto.asks);
				List<OrderLevel> buyOrders = levels(dto.bids);
				BazaarQuickStatus quick = quickStatus(dto.quickStatus);
				ActivityProxy activity = dto.quickStatus == null
						? ActivityProxy.unavailable()
						: ActivityProxy.reported(dto.quickStatus.buyMovingWeek,
								dto.quickStatus.sellMovingWeek);

				mapped.put(id, new BazaarProduct(
						dto.productId != null ? dto.productId : id,
						sellOffers,
						buyOrders,
						new BazaarProduct.MovingWeek(activity.buyMovingWeek(),
								activity.sellMovingWeek()),
						coverage(dto.asks, sellOffers),
						coverage(dto.bids, buyOrders),
						quick,
						activity));
			});
		}

		return MarketObservation.bazaar(Instant.ofEpochMilli(lastUpdated), retrievedAt, mapped);
	}

	private static DepthCoverage coverage(List<SummaryDto> summaries, List<OrderLevel> levels) {
		return summaries == null ? DepthCoverage.absent() : DepthCoverage.returned(levels);
	}

	private static BazaarQuickStatus quickStatus(QuickStatusDto quick) {
		if (quick == null) {
			return BazaarQuickStatus.absent();
		}
		return new BazaarQuickStatus(BazaarQuickStatus.State.RETURNED, quick.productId,
				quick.sellPrice, quick.sellVolume, quick.sellMovingWeek, quick.sellOrders,
				quick.buyPrice, quick.buyVolume, quick.buyMovingWeek, quick.buyOrders);
	}

	private static List<OrderLevel> levels(List<SummaryDto> summaries) {
		if (summaries == null) {
			return List.of();
		}

		List<OrderLevel> out = new ArrayList<>(summaries.size());

		for (SummaryDto s : summaries) {
			out.add(new OrderLevel(s.pricePerUnit, s.amount, s.orders));
		}

		return out;
	}
}
