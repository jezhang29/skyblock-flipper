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
package jeff.skyblockflipper.core.api;

import com.google.gson.Gson;
import jeff.skyblockflipper.core.item.DetailedDecodedItem;
import jeff.skyblockflipper.core.item.ItemDecoder;
import jeff.skyblockflipper.core.model.ActiveListing;
import jeff.skyblockflipper.core.model.BazaarSnapshot;
import jeff.skyblockflipper.core.model.EndedAuction;
import jeff.skyblockflipper.core.model.dto.EndedAuctionsDto;
import jeff.skyblockflipper.core.pricing.Fees;
import jeff.skyblockflipper.core.recovery.RecoveryListingScan;
import jeff.skyblockflipper.core.recovery.RecoveryScanPolicy;
import jeff.skyblockflipper.core.recovery.RecoveryValuationModels;
import jeff.skyblockflipper.core.valuation.UnderpricedScan;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ActiveAuctionScanTest {
	@Test
	void ordinaryOutputIsUnchangedAndTwoConsumersShareOneDecode() throws Exception {
		EndedAuction fixture;
		try (InputStream in = getClass().getResourceAsStream("/item-bytes-sample.json")) {
			fixture = new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8),
					EndedAuctionsDto.class).auctions.getFirst();
		}
		DetailedDecodedItem decoded = ItemDecoder.decodeDetailed(fixture.itemBytes()).orElseThrow();
		Instant now = Instant.ofEpochMilli(fixture.timestamp() + Duration.ofHours(1).toMillis());
		List<EndedAuction> sales = new ArrayList<>();
		for (int i = 0; i < 8; i++) {
			sales.add(new EndedAuction("sale-" + i, "s", "b", fixture.timestamp() + i,
					3_000_000L, true, fixture.itemBytes()));
		}
		RecoveryValuationModels.Builder builder = RecoveryValuationModels.builder(now,
				Duration.ofDays(2));
		sales.forEach(builder::add);
		RecoveryValuationModels models = builder.build();
		ActiveListing listing = new ActiveListing("active", decoded.item().displayName(),
				decoded.item().rarity(), 1_000_000L, fixture.itemBytes());

		UnderpricedScan baseline = new UnderpricedScan(models.ordinary(), 0.15d, 10_000_000L);
		baseline.offer(listing);
		UnderpricedScan ordinary = new UnderpricedScan(models.ordinary(), 0.15d, 10_000_000L);
		RecoveryListingScan recovery = new RecoveryListingScan(models.recovery(),
				BazaarSnapshot.empty(), Fees.none(), RecoveryScanPolicy.conservativeDefaults(), now);
		AtomicInteger decoderCalls = new AtomicInteger();
		ActiveAuctionScan combined = new ActiveAuctionScan(ordinary, recovery, blob -> {
			decoderCalls.incrementAndGet();
			if (blob.equals("broken")) {
				throw new IllegalStateException("fixture failure");
			}
			return ItemDecoder.decodeDetailed(blob);
		});

		combined.offer(new ActiveListing("broken", listing.itemName(), listing.rarity(),
				listing.price(), "broken"));
		combined.offer(listing);

		assertEquals(baseline.results(), combined.ordinaryResults());
		assertEquals(baseline.decoded(), combined.ordinary().decoded());
		assertEquals(baseline.rejectedOnExactValue(),
				combined.ordinary().rejectedOnExactValue());
		assertEquals(2, decoderCalls.get());
		assertEquals(2, combined.decodedBlobs());
		assertEquals(1, combined.failures());
	}
}
