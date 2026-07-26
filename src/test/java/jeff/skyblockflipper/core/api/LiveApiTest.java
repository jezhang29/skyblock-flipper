package jeff.skyblockflipper.core.api;

import jeff.skyblockflipper.core.item.DecodedItem;
import jeff.skyblockflipper.core.item.ItemDecoder;
import jeff.skyblockflipper.core.item.Rarity;
import jeff.skyblockflipper.core.model.ActiveListing;
import jeff.skyblockflipper.core.model.BazaarProduct;
import jeff.skyblockflipper.core.model.BazaarSnapshot;
import jeff.skyblockflipper.core.model.EndedAuction;
import jeff.skyblockflipper.core.model.MayorInfo;
import jeff.skyblockflipper.core.model.dto.AuctionsDto;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.Base64;
import java.util.List;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract tests against the real Hypixel API. Run with {@code ./gradlew test -PliveApi}.
 *
 * <p>Disabled by default on purpose. These assert things about <em>Hypixel's</em> behaviour rather
 * than ours, so a network outage or an API hiccup must not be able to fail an ordinary build. The
 * fixture-based tests cover our own code and always run.
 *
 * <p>What these are for: catching the day Hypixel renames a field, reorders an order book, or
 * changes the item blob format. All three would otherwise show up as quietly wrong prices.
 */
@EnabledIfSystemProperty(named = "skyblockflipper.liveApi", matches = "true")
class LiveApiTest {
	private final HypixelApi api = new HypixelApi();

	@Test
	void bazaarSidesAreStillInverted() throws Exception {
		BazaarSnapshot snapshot = api.fetchBazaar();

		assertFalse(snapshot.isEmpty());
		assertTrue(snapshot.products().size() > 1_000,
				"expected the full product list, got " + snapshot.products().size());

		int checked = 0;

		for (BazaarProduct product : snapshot.products().values()) {
			if (product.instantBuyPrice().isEmpty() || product.instantSellPrice().isEmpty()) {
				continue;
			}

			// If Hypixel ever swaps the meaning of buy_summary and sell_summary, this inverts
			// across the whole book at once and every spread in the mod silently flips sign.
			assertTrue(product.instantBuyPrice().getAsDouble() > product.instantSellPrice().getAsDouble(),
					product.productId() + " has ask below bid: the two sides may have been swapped");
			checked++;
		}

		assertTrue(checked > 500, "only validated " + checked + " books");
	}

	@Test
	void orderBooksStillArriveBestPriceFirst() throws Exception {
		BazaarProduct product = api.fetchBazaar().product("ENCHANTED_DIAMOND").orElseThrow();

		// Asks ascend, bids descend. Reversed sorting would make getFirst() the *worst* price.
		assertTrue(product.sellOffers().get(0).pricePerUnit() <= product.sellOffers().get(1).pricePerUnit());
		assertTrue(product.buyOrders().get(0).pricePerUnit() >= product.buyOrders().get(1).pricePerUnit());
	}

	@Test
	void endedAuctionsStillCarryDecodableItemBytes() throws Exception {
		List<EndedAuction> sales = api.fetchEndedAuctions();

		assertFalse(sales.isEmpty());

		EndedAuction sale = sales.getFirst();
		assertNotNull(sale.auctionId());
		assertNotNull(sale.itemBytes());

		// Still base64 of gzipped NBT after the 26.2 migration. The 0x1f8b magic is the check
		// that matters: a format change here breaks all valuation work downstream.
		byte[] decoded = Base64.getDecoder().decode(sale.itemBytes());
		assertTrue((decoded[0] & 0xFF) == 0x1F && (decoded[1] & 0xFF) == 0x8B,
				"item_bytes is no longer gzipped NBT");

		try (var in = new GZIPInputStream(new java.io.ByteArrayInputStream(decoded))) {
			byte[] head = in.readNBytes(4);
			// A root TAG_Compound (0x0A) with an empty name, then the "i" item list.
			assertTrue(head[0] == 0x0A, "unexpected NBT root tag " + head[0]);
		}
	}

	@Test
	void liveItemBlobsStillDecodeIntoPricedAttributes() throws Exception {
		List<EndedAuction> sales = api.fetchEndedAuctions();

		List<DecodedItem> decoded = sales.stream()
				.map(sale -> ItemDecoder.decode(sale.itemBytes()))
				.flatMap(java.util.Optional::stream)
				.toList();

		// A format change would show up here as a decode rate falling off a cliff rather than as
		// an exception, since a blob we cannot read is dropped rather than thrown.
		assertTrue(decoded.size() > sales.size() * 9 / 10,
				"only decoded " + decoded.size() + " of " + sales.size() + " live sales");

		assertTrue(decoded.stream().anyMatch(item -> item.rarity() != Rarity.UNKNOWN),
				"no live sale carried a readable rarity");

		// The attributes that carry most of the price on high-value items. If ExtraAttributes is
		// ever renamed, every item silently prices as if it were bare.
		assertTrue(decoded.stream().anyMatch(item -> !item.reforge().isEmpty()),
				"no live sale carried a reforge");
		assertTrue(decoded.stream().anyMatch(item -> !item.enchantments().isEmpty()),
				"no live sale carried enchantments");
	}

	@Test
	void itemCatalogParsesIncludingFractionalNpcPrices() throws Exception {
		// npc_sell_price is fractional for ~60 items (a torch sells for 0.3). Typing it as an
		// integer throws on the entire payload, not just that item, which would take the whole
		// catalog down and silently disable NPC flips.
		var catalog = api.fetchItems();

		assertTrue(catalog.items().size() > 5_000, "only got " + catalog.items().size() + " items");

		long fractional = catalog.items().values().stream()
				.map(entry -> entry.npcPrice().orElse(null))
				.filter(price -> price != null && price != Math.floor(price))
				.count();

		assertTrue(fractional > 0,
				"expected some sub-coin NPC prices; if this is now zero the field may have changed type");
	}

	@Test
	void activeAuctionPagesStillCarryPricedBinListings() throws Exception {
		// One page, not a sweep: a full sweep is ~51 pages and 70MB, which is not a reasonable
		// thing to spend on a contract check. Page 0 proves the shape and the paging metadata.
		AuctionsDto page = api.fetchAuctionPage(0);

		assertTrue(page.totalPages > 1, "expected a paged response, got " + page.totalPages);
		assertTrue(page.lastUpdated > 0L, "no lastUpdated stamp - sweeps could not be skipped");

		List<ActiveListing> bins = page.binListings();
		assertFalse(bins.isEmpty(), "no buy-it-now listings on page 0");

		for (ActiveListing listing : bins) {
			// BIN prices arrive as starting_bid; a zero here would make everything look free.
			assertTrue(listing.price() > 0L, listing.itemName() + " has no price");
		}

		// The name and rarity are what the sweep prunes on without decoding anything, so they
		// have to be present and meaningful on the listing itself.
		assertTrue(bins.stream().anyMatch(listing -> listing.rarity() != Rarity.UNKNOWN),
				"no listing carried a readable tier");
		assertTrue(ItemDecoder.decode(bins.getFirst().itemBytes()).isPresent(),
				"a live listing's item_bytes did not decode");
	}

	@Test
	void electionEndpointStillNamesAMayor() throws Exception {
		MayorInfo mayor = api.fetchMayor();

		assertTrue(mayor.isKnown());
		assertFalse(mayor.name().isBlank());
	}
}
