package jeff.skyblockflipper.core.model.dto;

import com.google.gson.annotations.SerializedName;

import jeff.skyblockflipper.core.model.BazaarProduct;
import jeff.skyblockflipper.core.model.BazaarSnapshot;
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
		/** Units instantly bought over the last week. */
		public long buyMovingWeek;
		/** Units instantly sold over the last week. */
		public long sellMovingWeek;
	}

	/** Translates the wire format into the domain model, swapping the two sides exactly once. */
	public BazaarSnapshot toSnapshot() {
		Map<String, BazaarProduct> mapped = new HashMap<>();

		if (products != null) {
			products.forEach((id, dto) -> {
				if (dto == null) {
					return;
				}

				long instantBought = dto.quickStatus != null ? dto.quickStatus.buyMovingWeek : 0L;
				long instantSold = dto.quickStatus != null ? dto.quickStatus.sellMovingWeek : 0L;

				mapped.put(id, new BazaarProduct(
						dto.productId != null ? dto.productId : id,
						levels(dto.asks),
						levels(dto.bids),
						new BazaarProduct.MovingWeek(instantBought, instantSold)));
			});
		}

		return new BazaarSnapshot(Instant.ofEpochMilli(lastUpdated), mapped);
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
