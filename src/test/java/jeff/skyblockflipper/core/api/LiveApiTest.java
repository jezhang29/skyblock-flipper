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

import jeff.skyblockflipper.core.item.DecodedItem;
import jeff.skyblockflipper.core.item.ItemDecoder;
import jeff.skyblockflipper.core.item.Rarity;
import jeff.skyblockflipper.core.model.ActiveListing;
import jeff.skyblockflipper.core.model.BazaarProduct;
import jeff.skyblockflipper.core.model.BazaarSnapshot;
import jeff.skyblockflipper.core.model.EndedAuction;
import jeff.skyblockflipper.core.model.ItemCatalog;
import jeff.skyblockflipper.core.model.MayorInfo;
import jeff.skyblockflipper.core.model.UpgradeCost;
import jeff.skyblockflipper.core.model.dto.AuctionsDto;
import jeff.skyblockflipper.core.pricing.CombineQuote;
import jeff.skyblockflipper.core.pricing.CraftQuote;
import jeff.skyblockflipper.core.pricing.Fees;
import jeff.skyblockflipper.core.pricing.FusionQuote;
import jeff.skyblockflipper.core.recipe.FusionTable;
import jeff.skyblockflipper.core.strategy.CombineJob;
import jeff.skyblockflipper.core.strategy.CombineTable;
import jeff.skyblockflipper.core.strategy.FusionJob;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
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
	void everyStarIngredientIsStillABazaarProduct() throws Exception {
		// The assumption UpgradePricing rests on. Measured when it was written: 544 items carry
		// upgrade costs, drawing on 9 essence types and 43 distinct item ingredients, and all 43 of
		// those items trade on the bazaar - so every star tier in the game is priceable from the
		// book. If Hypixel ever adds an ingredient that is not bazaar-traded, star quotes for that
		// item stop appearing rather than come out wrong, which is a failure nobody would notice.
		var catalog = api.fetchItems();
		BazaarSnapshot bazaar = api.fetchBazaar();

		List<ItemCatalog.Entry> starrable = catalog.items().values().stream()
				.filter(entry -> entry.maxStars() > 0)
				.toList();

		assertTrue(starrable.size() > 400,
				"only " + starrable.size() + " items carry upgrade_costs; the field may have moved");

		for (ItemCatalog.Entry entry : starrable) {
			for (UpgradeCost level : entry.upgradeCosts()) {
				assertFalse(level.ingredients().isEmpty(),
						entry.id() + " has a star level with no ingredients");

				for (UpgradeCost.Ingredient ingredient : level.ingredients()) {
					assertTrue(bazaar.product(ingredient.productId()).isPresent(),
							entry.id() + " needs " + ingredient.productId()
									+ ", which is not a bazaar product");
				}
			}
		}
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

	/**
	 * Prints the shipped combine picks against the live book, one row per allowlist enchant, so
	 * {@code docs/combine-flipping.md}'s table can be re-measured. Not an assertion of Hypixel
	 * behaviour like the others: it exists to answer "which tier does the current selection rule buy,
	 * and what does it pay", which only the live book can settle. Read the stdout, update the doc.
	 */
	@Test
	void printLiveCombinePicks() throws Exception {
		BazaarSnapshot bazaar = api.fetchBazaar();
		ItemCatalog catalog = api.fetchItems();
		Fees fees = new Fees(1, false);
		CraftQuote.FillHistory history = CraftQuote.FillHistory.none();
		Duration horizon = Duration.ofHours(1);
		long maxCapital = 1_000_000_000L;

		System.out.println("=== live combine picks, Bazaar Flipper 1, 1h horizon, 5% flow ===");
		System.out.printf("%-26s %-8s %-11s %6s %14s %14s %12s%n",
				"enchant", "src->T", "route", "merges", "net/combine", "net/output", "profit/hr");

		List<CombineQuote> picks = new ArrayList<>();
		List<String> rejected = new ArrayList<>();

		for (CombineTable.Entry entry : CombineTable.all()) {
			BazaarProduct target = bazaar.product(entry.targetId()).orElse(null);
			int askOrders = target == null ? 0 : target.sellOfferCount();
			CombineQuote quote = CombineQuote
					.quote(entry, bazaar, fees, history, horizon, maxCapital)
					.orElse(null);

			if (quote == null) {
				rejected.add(String.format("%-26s target ask orders=%d (gate %d), no clearing plan",
						CombineJob.nameOf(entry, entry.maxTier(), catalog), askOrders,
						CombineQuote.MIN_TARGET_ASK_ORDERS));
				continue;
			}

			picks.add(quote);
		}

		picks.sort(Comparator.comparingDouble(CombineQuote::netPerCombine).reversed());

		for (CombineQuote q : picks) {
			CombineTable.Entry e = q.entry();
			System.out.printf("%-26s %-8s %-11s %6d %14s %14s %12s%n",
					CombineJob.nameOf(e, e.maxTier(), catalog),
					q.sourceTier() + "->" + e.maxTier(),
					q.route(),
					e.combinesPerOutput(q.sourceTier()),
					String.format("%,d", Math.round(q.netPerCombine())),
					String.format("%,d", Math.round(q.netPerOutput())),
					String.format("%,d", Math.round(q.profitPerHour())));
		}

		System.out.println("--- rejected (no plan clears the gate) ---");
		rejected.forEach(System.out::println);

		assertFalse(bazaar.isEmpty());
	}

	/**
	 * Prints the shipped fusion picks against the live book, so {@code docs/fusion-flipping.md}'s table
	 * can be re-measured. The twin of {@link #printLiveCombinePicks}: it answers "which output shards
	 * clear, at what depth, and what do they pay", which only the live book can settle. Read the
	 * stdout, update the doc. Crocodile level 0 (no reptile bonus), the conservative default.
	 */
	@Test
	void printLiveFusionPicks() throws Exception {
		BazaarSnapshot bazaar = api.fetchBazaar();
		ItemCatalog catalog = api.fetchItems();
		Fees fees = new Fees(1, false);
		FusionTable table = FusionTable.bundled();
		CraftQuote.FillHistory history = CraftQuote.FillHistory.none();
		Duration horizon = Duration.ofHours(1);
		long maxCapital = 1_000_000_000L;

		System.out.println("=== live fusion picks, Bazaar Flipper 1, 1h horizon, 5% flow, croc 0 ===");
		System.out.println("missing bazaar products: " + table.missingProducts(bazaar));
		System.out.printf("%-24s %6s %6s %14s %14s %12s%n",
				"output", "leaves", "clicks", "net/click", "net/output", "profit/hr");

		FusionQuote.Solver solver = FusionQuote.solver(table, bazaar, 0);
		List<FusionQuote> picks = new ArrayList<>();

		for (String output : table.outputs()) {
			FusionQuote.quote(solver, output, fees, history, horizon, maxCapital)
					.filter(q -> q.netPerOutput() > 0.0d)
					.ifPresent(picks::add);
		}

		picks.sort(Comparator.comparingDouble(FusionQuote::netPerOutput).reversed());

		int singleStep = 0;

		for (FusionQuote q : picks) {
			if (q.fusions().size() == 1) {
				singleStep++;
			}

			System.out.printf("%-24s %6d %6d %14s %14s %12s%n",
					FusionJob.nameOf(q.outputId(), catalog),
					q.leaves().size(),
					q.totalFusions(),
					String.format("%,d", Math.round(q.netPerFusion())),
					String.format("%,d", Math.round(q.netPerOutput())),
					String.format("%,d", Math.round(q.profitPerHour())));
		}

		System.out.printf("%d outputs clear, %d of them single-step. First play-test a single-step "
				+ "row before trusting a deep tree.%n", picks.size(), singleStep);

		assertFalse(bazaar.isEmpty());
	}
}
