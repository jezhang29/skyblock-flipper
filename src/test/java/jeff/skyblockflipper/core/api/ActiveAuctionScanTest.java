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
import jeff.skyblockflipper.core.item.DecodedItem;
import jeff.skyblockflipper.core.item.DetailedDecodedItem;
import jeff.skyblockflipper.core.item.ItemDecoder;
import jeff.skyblockflipper.core.model.ActiveListing;
import jeff.skyblockflipper.core.model.BazaarSnapshot;
import jeff.skyblockflipper.core.model.EndedAuction;
import jeff.skyblockflipper.core.model.dto.EndedAuctionsDto;
import jeff.skyblockflipper.core.pricing.Fees;
import jeff.skyblockflipper.core.recovery.RecoveryListingScan;
import jeff.skyblockflipper.core.recovery.RecoveryAttachment;
import jeff.skyblockflipper.core.recovery.RecoveryComponentKind;
import jeff.skyblockflipper.core.recovery.RecoveryMetadata;
import jeff.skyblockflipper.core.recovery.RecoveryScanPolicy;
import jeff.skyblockflipper.core.recovery.RecoveryValuationModels;
import jeff.skyblockflipper.core.recovery.RecoveryValueModel;
import jeff.skyblockflipper.core.valuation.FairValueModel;
import jeff.skyblockflipper.core.valuation.UnderpricedScan;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

	@Test
	void recoveryInspectsABlobOrdinaryValuationAlreadyDecoded() throws Exception {
		EndedAuction fixture;
		try (InputStream in = getClass().getResourceAsStream("/item-bytes-sample.json")) {
			fixture = new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8),
					EndedAuctionsDto.class).auctions.getFirst();
		}
		DetailedDecodedItem decoded = ItemDecoder.decodeDetailed(fixture.itemBytes()).orElseThrow();
		Instant now = Instant.ofEpochMilli(fixture.timestamp() + Duration.ofHours(1).toMillis());
		RecoveryValuationModels.Builder builder = RecoveryValuationModels.builder(now,
				Duration.ofDays(2));
		for (int i = 0; i < 8; i++) {
			builder.add(new EndedAuction("sale-" + i, "s", "b", fixture.timestamp() + i,
					3_000_000L, true, fixture.itemBytes()));
		}
		DetailedDecodedItem attached = new DetailedDecodedItem(decoded.item(), new RecoveryMetadata(
				List.of(new RecoveryAttachment(RecoveryComponentKind.GEMSTONE, "COMBAT_0",
						"FINE_RUBY_GEM", 1L)), Map.of(), Set.of()));
		UnderpricedScan ordinary = new UnderpricedScan(builder.build().ordinary(), 0.15d,
				10_000_000L);
		RecoveryListingScan recovery = new RecoveryListingScan(RecoveryValueModel.empty(),
				BazaarSnapshot.empty(), Fees.none(), RecoveryScanPolicy.conservativeDefaults(), now);
		ActiveAuctionScan combined = new ActiveAuctionScan(ordinary, recovery,
				ignored -> java.util.Optional.of(attached));

		combined.offer(new ActiveListing("active", decoded.item().displayName(),
				decoded.item().rarity(), 1_000_000L, "shared"));

		assertEquals(1, combined.ordinaryResults().size());
		assertEquals(1, combined.recovery().decoded());
		assertEquals(1, combined.decodedBlobs());
	}

	@Test
	void recoveryDecodeLetsOrdinaryReachAHighValueExactConfiguration() throws Exception {
		EndedAuction fixture;
		try (InputStream in = getClass().getResourceAsStream("/item-bytes-sample.json")) {
			fixture = new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8),
					EndedAuctionsDto.class).auctions.getFirst();
		}
		DetailedDecodedItem decoded = ItemDecoder.decodeDetailed(fixture.itemBytes()).orElseThrow();
		Instant now = Instant.ofEpochMilli(fixture.timestamp() + Duration.ofHours(1).toMillis());
		RecoveryValuationModels.Builder recoveryBuilder = RecoveryValuationModels.builder(now,
				Duration.ofDays(2));
		for (int i = 0; i < 8; i++) {
			recoveryBuilder.add(new EndedAuction("recovery-" + i, "s", "b",
					fixture.timestamp() + i, 3_000_000L, true, fixture.itemBytes()));
		}
		DecodedItem plain = new DecodedItem("HOST", decoded.item().displayName(), 1,
				decoded.item().rarity(), "", 0, false, 0, Map.of(), List.of(), Map.of(),
				Map.of(), null, null, null, "", false, 0L);
		DecodedItem upgraded = new DecodedItem("HOST", decoded.item().displayName(), 1,
				decoded.item().rarity(), "", 0, false, 0, Map.of("ultimate_wise", 5),
				List.of(), Map.of(), Map.of(), null, null, null, "", false, 0L);
		FairValueModel.Builder ordinaryBuilder = FairValueModel.builder(now, Duration.ofDays(2));
		for (int i = 0; i < 20; i++) {
			ordinaryBuilder.add(plain, 10_000_000.0d, now.minusSeconds(i).toEpochMilli());
		}
		for (int i = 0; i < 8; i++) {
			ordinaryBuilder.add(upgraded, 100_000_000.0d,
					now.minusSeconds(i).toEpochMilli());
		}
		UnderpricedScan ordinary = new UnderpricedScan(ordinaryBuilder.build(), 0.15d,
				100_000_000L);
		RecoveryListingScan recovery = new RecoveryListingScan(recoveryBuilder.build().recovery(),
				BazaarSnapshot.empty(), Fees.none(), RecoveryScanPolicy.conservativeDefaults(), now);
		ActiveAuctionScan combined = new ActiveAuctionScan(ordinary, recovery,
				ignored -> java.util.Optional.of(new DetailedDecodedItem(upgraded,
						RecoveryMetadata.EMPTY)));

		combined.offer(new ActiveListing("upgraded", upgraded.displayName(), upgraded.rarity(),
				80_000_000L, "shared"));

		assertEquals(80_000_000L,
				combined.ordinaryResults().getFirst().listing().price());
		assertEquals(1, combined.decodedBlobs());
	}
}
