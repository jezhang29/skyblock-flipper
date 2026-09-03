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
package jeff.skyblockflipper.core.valuation;

import com.google.gson.Gson;

import jeff.skyblockflipper.core.item.DecodedItem;
import jeff.skyblockflipper.core.item.ItemDecoder;
import jeff.skyblockflipper.core.item.Rarity;
import jeff.skyblockflipper.core.model.ActiveListing;
import jeff.skyblockflipper.core.model.EndedAuction;
import jeff.skyblockflipper.core.model.dto.EndedAuctionsDto;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The scan is a cost-control device as much as a search: the expensive step is decoding an item
 * blob, and there are ~46,000 live listings. These tests pin both halves of that - that almost
 * nothing gets decoded, and that a coarse hit still has to survive an exact check before it counts.
 */
class UnderpricedScanTest {
	private static final Instant NOW = Instant.parse("2026-07-26T12:00:00Z");
	private static final Duration WINDOW = Duration.ofDays(2);

	private static String talismanBlob;
	private static String helmetBlob;

	@BeforeAll
	static void loadFixture() throws Exception {
		try (InputStream in = UnderpricedScanTest.class.getResourceAsStream("/item-bytes-sample.json")) {
			for (EndedAuction sale : new Gson().fromJson(
					new InputStreamReader(in, StandardCharsets.UTF_8), EndedAuctionsDto.class).auctions) {
				DecodedItem item = ItemDecoder.decode(sale.itemBytes()).orElseThrow();

				if (item.skyblockId().equals("ANITA_TALISMAN")) {
					talismanBlob = sale.itemBytes();
				} else if (item.skyblockId().equals("POWER_WITHER_HELMET")) {
					helmetBlob = sale.itemBytes();
				}
			}
		}
	}

	private static FairValueModel modelFrom(String blob, long price, int count) {
		List<EndedAuction> sales = new ArrayList<>();

		for (int i = 0; i < count; i++) {
			sales.add(new EndedAuction("sale-" + i, "s", "b",
					NOW.minus(Duration.ofHours(i + 1L)).toEpochMilli(), price, true, blob));
		}

		return FairValueModel.from(sales, NOW, WINDOW);
	}

	private static ActiveListing listing(String blob, long price) {
		DecodedItem item = ItemDecoder.decode(blob).orElseThrow();
		return new ActiveListing("auction-" + price, item.displayName(), item.rarity(), price, blob);
	}

	@Test
	void findsAListingWellBelowWhatItsConfigurationSellsFor() {
		FairValueModel model = modelFrom(talismanBlob, 3_000_000L, 8);
		UnderpricedScan scan = new UnderpricedScan(model, 0.15d, 100_000_000L);

		scan.offer(listing(talismanBlob, 2_000_000L));

		PricedListing found = scan.results().getFirst();
		assertEquals(2_000_000L, found.listing().price());
		assertEquals(0.333d, found.discount(), 0.01d);
		// The blob is dropped once decoded; keeping it would be 1.5KB per candidate for nothing.
		assertEquals("", found.listing().itemBytes());
	}

	@Test
	void leavesFairlyPricedListingsAlone() {
		FairValueModel model = modelFrom(talismanBlob, 3_000_000L, 8);
		UnderpricedScan scan = new UnderpricedScan(model, 0.15d, 100_000_000L);

		scan.offer(listing(talismanBlob, 2_900_000L));

		assertTrue(scan.results().isEmpty());
		// And crucially, it never paid to parse the blob to work that out.
		assertEquals(0, scan.decoded());
		assertEquals(1, scan.listingsSeen());
	}

	@Test
	void doesNotDecodeItemsItHasNeverSeenSell() {
		FairValueModel model = modelFrom(talismanBlob, 3_000_000L, 8);
		UnderpricedScan scan = new UnderpricedScan(model, 0.15d, 100_000_000L);

		// Most of the auction house is items with no recent comparable sales. Nothing can be said
		// about them, so nothing is spent on them.
		scan.offer(listing(helmetBlob, 1L));

		assertTrue(scan.results().isEmpty());
		assertEquals(0, scan.decoded());
	}

	@Test
	void doesNotDecodeListingsBeyondTheBankroll() {
		FairValueModel model = modelFrom(talismanBlob, 3_000_000L, 8);
		UnderpricedScan scan = new UnderpricedScan(model, 0.15d, 1_000_000L);

		scan.offer(listing(talismanBlob, 2_000_000L));

		assertTrue(scan.results().isEmpty());
		assertEquals(0, scan.decoded());
	}

	@Test
	void aCoarseBargainStillHasToSurviveTheExactCheck() {
		// Sales of the upgraded helmet only. A listing whose name and rarity match but whose
		// configuration does not is the classic money-losing snipe, and it must not survive.
		FairValueModel model = modelFrom(helmetBlob, 24_000_000L, 8);
		UnderpricedScan scan = new UnderpricedScan(model, 0.15d, 100_000_000L);

		DecodedItem helmet = ItemDecoder.decode(helmetBlob).orElseThrow();
		// Same display name and rarity, so it passes the cheap prune, but it is a talisman blob.
		scan.offer(new ActiveListing("mismatch", helmet.displayName(), helmet.rarity(),
				5_000_000L, talismanBlob));

		assertTrue(scan.results().isEmpty());
		assertEquals(1, scan.decoded());
		assertEquals(1, scan.rejectedOnExactValue());
	}

	@Test
	void ranksTheDeepestDiscountFirst() {
		FairValueModel model = modelFrom(talismanBlob, 3_000_000L, 8);
		UnderpricedScan scan = new UnderpricedScan(model, 0.15d, 100_000_000L);

		scan.offer(listing(talismanBlob, 2_400_000L));
		scan.offer(listing(talismanBlob, 900_000L));
		scan.offer(listing(talismanBlob, 2_000_000L));

		assertEquals(List.of(900_000L, 2_000_000L, 2_400_000L),
				scan.results().stream().map(found -> found.listing().price()).toList());
	}

	@Test
	void exactCheckUsesADecodeAnotherConsumerAlreadyNeeds() {
		DecodedItem plain = new DecodedItem("HOST", "Host", 1, Rarity.LEGENDARY, "", 0,
				false, 0, Map.of(), List.of(), Map.of(), Map.of(), null, null, null, "",
				false, 0L);
		DecodedItem upgraded = new DecodedItem("HOST", "Host", 1, Rarity.LEGENDARY, "", 0,
				false, 0, Map.of("ultimate_wise", 5), List.of(), Map.of(), Map.of(), null,
				null, null, "", false, 0L);
		FairValueModel.Builder builder = FairValueModel.builder(NOW, WINDOW);
		for (int i = 0; i < 20; i++) {
			builder.add(plain, 10_000_000.0d, NOW.minusSeconds(i).toEpochMilli());
		}
		for (int i = 0; i < 8; i++) {
			builder.add(upgraded, 100_000_000.0d, NOW.minusSeconds(i).toEpochMilli());
		}
		ActiveListing listing = new ActiveListing("upgraded", "Host", Rarity.LEGENDARY,
				80_000_000L, "unused");

		UnderpricedScan pruned = new UnderpricedScan(builder.build(), 0.15d, 100_000_000L);
		pruned.offerDecoded(listing, () -> Optional.of(upgraded));
		assertTrue(pruned.results().isEmpty());
		assertEquals(0, pruned.decoded());

		UnderpricedScan shared = new UnderpricedScan(builder.build(), 0.15d, 100_000_000L);
		shared.offerDecoded(listing, () -> Optional.of(upgraded), true);
		assertEquals(80_000_000L, shared.results().getFirst().listing().price());
		assertEquals(1, shared.decoded());
	}

	// One exact configuration - the item that lets us control samples and dispersion directly, which
	// a real fixture blob cannot. Its coarse (name-and-rarity) median and its exact-signature median
	// are the same number, so any gap between the gates is the threshold, not the data.
	private static final DecodedItem HOST = new DecodedItem("HOST", "Host", 1, Rarity.LEGENDARY,
			"", 0, false, 0, Map.of(), List.of(), Map.of(), Map.of(), null, null, null, "",
			false, 0L);

	private static FairValueModel hostModel(long... prices) {
		FairValueModel.Builder builder = FairValueModel.builder(NOW, WINDOW);
		for (int i = 0; i < prices.length; i++) {
			builder.add(HOST, (double) prices[i], NOW.minusSeconds(i * 60L).toEpochMilli());
		}
		return builder.build();
	}

	private static FairValueModel hostModel(int count, long price) {
		long[] prices = new long[count];
		java.util.Arrays.fill(prices, price);
		return hostModel(prices);
	}

	private static ActiveListing hostListing(long price) {
		return new ActiveListing("host-" + price, "Host", Rarity.LEGENDARY, price, "unused");
	}

	@Test
	void exactGateTakesTheTighterDiscountTheCoarseGateWalksPast() {
		// 20 tight sales at 100M, so the exact estimate is fully trusted. A listing 7% under would
		// clear neither gate at the 15% coarse margin; the 5% exact margin is what admits it.
		FairValueModel model = hostModel(20, 100_000_000L);
		ActiveListing listing = hostListing(93_000_000L);

		// Same 7% listing, forced past the coarse discount so only the exact gate decides. At the
		// coarse margin the exact gate still rejects it.
		UnderpricedScan atCoarseMargin = new UnderpricedScan(model, 0.15d, 0.15d, 200_000_000L);
		atCoarseMargin.offerDecoded(listing, () -> Optional.of(HOST), true);
		assertTrue(atCoarseMargin.results().isEmpty());
		assertEquals(1, atCoarseMargin.rejectedOnExactValue());

		// Lower the exact margin to 5% and the same well-backed 7% discount is a find.
		UnderpricedScan atExactMargin = new UnderpricedScan(model, 0.15d, 0.05d, 200_000_000L);
		atExactMargin.offerDecoded(listing, () -> Optional.of(HOST), true);
		assertEquals(93_000_000L, atExactMargin.results().getFirst().listing().price());
	}

	@Test
	void theTighterExactMarginNeedsAMarketQuieterThanTheDiscount() {
		// The same 7% discount, but the market disagrees with itself by more than the edge claimed:
		// ten sales at 78M and ten at 120M give a dispersion of 0.35, far above the 5% margin, so the
		// discount is inside the noise. Confidence and sample count still clear their bars, so the
		// discount-against-spread condition alone does the rejecting.
		long[] prices = new long[20];
		java.util.Arrays.fill(prices, 0, 10, 78_000_000L);
		java.util.Arrays.fill(prices, 10, 20, 120_000_000L);
		FairValueModel model = hostModel(prices);
		ActiveListing listing = hostListing(111_600_000L); // 7% under the 120M median

		UnderpricedScan scan = new UnderpricedScan(model, 0.15d, 0.05d, 200_000_000L);
		scan.offerDecoded(listing, () -> Optional.of(HOST), true);

		assertTrue(scan.results().isEmpty());
		assertEquals(1, scan.rejectedOnExactValue());
	}

	@Test
	void theTighterExactMarginNeedsEnoughSamples() {
		// Twelve tight sales: confidence just clears 0.8, but twelve is under the fifteen the tighter
		// margin asks for, so an otherwise-qualifying 7% discount is still refused.
		FairValueModel model = hostModel(12, 100_000_000L);
		ActiveListing listing = hostListing(93_000_000L);

		UnderpricedScan scan = new UnderpricedScan(model, 0.15d, 0.05d, 200_000_000L);
		scan.offerDecoded(listing, () -> Optional.of(HOST), true);

		assertTrue(scan.results().isEmpty());
		assertEquals(1, scan.rejectedOnExactValue());
	}

	@Test
	void theCoarseGateThresholdIsUnaffectedByTheExactMargin() {
		// The ordinary path still prunes on the coarse margin before any decode. A 7% listing fails
		// it and is never parsed, whatever the exact margin is set to - the lever cannot leak spend
		// back into the cheap prune.
		FairValueModel model = hostModel(20, 100_000_000L);

		UnderpricedScan scan = new UnderpricedScan(model, 0.15d, 0.05d, 200_000_000L);
		scan.offerDecoded(hostListing(93_000_000L), () -> Optional.of(HOST), false);

		assertTrue(scan.results().isEmpty());
		assertEquals(0, scan.decoded());
	}
}
