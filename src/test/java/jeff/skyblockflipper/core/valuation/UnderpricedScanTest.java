package jeff.skyblockflipper.core.valuation;

import com.google.gson.Gson;

import jeff.skyblockflipper.core.item.DecodedItem;
import jeff.skyblockflipper.core.item.ItemDecoder;
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
}
