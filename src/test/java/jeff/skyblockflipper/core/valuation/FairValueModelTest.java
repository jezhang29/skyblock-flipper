package jeff.skyblockflipper.core.valuation;

import com.google.gson.Gson;

import jeff.skyblockflipper.core.item.DecodedItem;
import jeff.skyblockflipper.core.item.ItemDecoder;
import jeff.skyblockflipper.core.item.Rarity;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The model decides what "underpriced" means, so these tests are about the ways a valuation can be
 * confidently wrong: outliers dragging an estimate, stale sales outvoting current ones, and - the
 * expensive one - pricing an upgraded item off sales of the bare version.
 */
class FairValueModelTest {
	private static final Instant NOW = Instant.parse("2026-07-26T12:00:00Z");
	private static final Duration WINDOW = Duration.ofDays(2);

	/** Real blobs, so the signatures under test are the ones the game actually produces. */
	private static Map<String, String> blobs;

	@BeforeAll
	static void loadFixture() throws Exception {
		blobs = new java.util.HashMap<>();

		try (InputStream in = FairValueModelTest.class.getResourceAsStream("/item-bytes-sample.json")) {
			for (EndedAuction sale : new Gson().fromJson(
					new InputStreamReader(in, StandardCharsets.UTF_8), EndedAuctionsDto.class).auctions) {
				ItemDecoder.decode(sale.itemBytes())
						.ifPresent(item -> blobs.put(item.skyblockId(), sale.itemBytes()));
			}
		}
	}

	private static EndedAuction sale(String skyblockId, long price, Instant when, boolean bin) {
		return new EndedAuction("id-" + price + "-" + when.toEpochMilli(), "seller", "buyer",
				when.toEpochMilli(), price, bin, blobs.get(skyblockId));
	}

	private static List<EndedAuction> sales(String skyblockId, long... prices) {
		List<EndedAuction> out = new ArrayList<>();

		for (long price : prices) {
			out.add(sale(skyblockId, price, NOW.minus(Duration.ofHours(out.size() + 1L)), true));
		}

		return out;
	}

	private static DecodedItem item(String skyblockId) {
		return ItemDecoder.decode(blobs.get(skyblockId)).orElseThrow();
	}

	private static FairValueModel modelOf(List<EndedAuction> sales) {
		return FairValueModel.from(sales, NOW, WINDOW);
	}

	@Test
	void pricesAConfigurationFromItsOwnRealizedSales() {
		FairValueModel model = modelOf(sales("ANITA_TALISMAN", 3_000_000L, 3_100_000L, 2_900_000L,
				3_000_000L, 3_050_000L, 2_950_000L));

		ValueEstimate value = model.valueOf(item("ANITA_TALISMAN")).orElseThrow();

		assertEquals(3_000_000.0d, value.median(), 1e-6);
		assertEquals(6, value.samples());
		assertTrue(value.exact());
	}

	@Test
	void oneAbsurdSaleDoesNotMoveTheEstimate() {
		// A mean would land near 20M off the back of a single fat-fingered listing, and every
		// correctly priced item on the market would then look like a bargain.
		FairValueModel model = modelOf(sales("ANITA_TALISMAN", 3_000_000L, 3_000_000L, 3_000_000L,
				3_000_000L, 3_000_000L, 120_000_000L));

		assertEquals(3_000_000.0d, model.valueOf(item("ANITA_TALISMAN")).orElseThrow().median(), 1e-6);
	}

	@Test
	void refusesToPriceAnItemItHasBarelySeen() {
		FairValueModel model = modelOf(sales("ANITA_TALISMAN", 3_000_000L, 3_000_000L, 3_000_000L));

		// Three sales is an anecdote. Publishing a median off it would be a confident guess.
		assertTrue(model.valueOf(item("ANITA_TALISMAN")).isEmpty());
	}

	@Test
	void ignoresSalesOlderThanTheWindow() {
		List<EndedAuction> stale = new ArrayList<>();

		for (int i = 0; i < 10; i++) {
			stale.add(sale("ANITA_TALISMAN", 3_000_000L, NOW.minus(Duration.ofDays(9)), true));
		}

		assertTrue(modelOf(stale).valueOf(item("ANITA_TALISMAN")).isEmpty());
	}

	@Test
	void ignoresBidAuctions() {
		List<EndedAuction> bids = new ArrayList<>();

		for (int i = 0; i < 10; i++) {
			// An auction that ended on one uncontested bid says what nobody else was awake for,
			// not what the item is worth.
			bids.add(sale("ANITA_TALISMAN", 100_000L, NOW.minus(Duration.ofHours(1)), false));
		}

		assertTrue(modelOf(bids).valueOf(item("ANITA_TALISMAN")).isEmpty());
	}

	@Test
	void willNotPriceAnUpgradedItemFromSalesOfADifferentConfiguration() {
		// Sales of a five-star recombobulated helmet, and nothing else.
		FairValueModel model = modelOf(sales("POWER_WITHER_HELMET", 24_000_000L, 24_500_000L,
				23_000_000L, 24_000_000L, 25_000_000L, 24_200_000L));

		DecodedItem upgraded = item("POWER_WITHER_HELMET");
		assertTrue(model.valueOf(upgraded).isPresent());

		// The plain version is a different item at a different price. Reforge and stars show up in
		// the name ("Ancient Necron's Helmet ✪✪✪✪✪"), so it does not even share a coarse key.
		DecodedItem bare = new DecodedItem(upgraded.skyblockId(), "Necron's Helmet",
				upgraded.count(), Rarity.LEGENDARY, "", 0, false, 0, Map.of(), List.of(), null);

		assertTrue(model.valueOf(bare).isEmpty());

		// And the invisible upgrades are what isBare() guards: same name and rarity as the sales,
		// but hot potato books and a recombobulator the coarse key could never have seen.
		DecodedItem sameNameQuietlyUpgraded = new DecodedItem(upgraded.skyblockId(),
				upgraded.displayName(), upgraded.count(), upgraded.rarity(), upgraded.reforge(),
				upgraded.stars(), true, 10, Map.of(), List.of(), null);

		assertTrue(model.valueOf(sameNameQuietlyUpgraded).isEmpty());
	}

	@Test
	void usesTheCoarseValueOnlyForItemsWithNothingAddedToThem() {
		FairValueModel model = modelOf(sales("ANITA_TALISMAN", 3_000_000L, 3_000_000L, 3_000_000L,
				3_000_000L, 3_000_000L, 3_000_000L));

		DecodedItem bare = item("ANITA_TALISMAN");
		// Same name and rarity, but now carrying enchantments the coarse key cannot see.
		DecodedItem enchanted = new DecodedItem(bare.skyblockId(), bare.displayName(), bare.count(),
				bare.rarity(), "", 0, false, 0, Map.of("sharpness", 7), List.of(), null);

		assertTrue(model.valueOf(bare).isPresent());
		assertTrue(model.valueOf(enchanted).isEmpty());
	}

	@Test
	void roughValuesAreAvailableWithoutDecodingAnything() {
		FairValueModel model = modelOf(sales("ANITA_TALISMAN", 3_000_000L, 3_000_000L, 3_000_000L,
				3_000_000L, 3_000_000L, 3_000_000L));

		// This is the lookup that lets a sweep discard ~46,000 listings before parsing any of them.
		ValueEstimate rough = model.roughValueOf("Anita's Talisman", Rarity.COMMON).orElseThrow();

		assertEquals(3_000_000.0d, rough.median(), 1e-6);
		assertFalse(rough.exact());
		assertTrue(model.roughValueOf("Anita's Talisman", Rarity.LEGENDARY).isEmpty());
	}

	@Test
	void confidenceFallsWithDisagreementAndRisesWithSamples() {
		ValueEstimate tight = ValueEstimate.of("k", List.of(100.0d, 100.0d, 101.0d, 99.0d, 100.0d,
				100.0d, 100.0d, 100.0d), 48.0d, true);
		ValueEstimate scattered = ValueEstimate.of("k", List.of(10.0d, 50.0d, 100.0d, 150.0d,
				400.0d, 900.0d, 30.0d, 700.0d), 48.0d, true);

		assertTrue(tight.confidence() > scattered.confidence());

		// A coarse estimate is penalised on top of everything else: it matched a name, not an item.
		ValueEstimate coarse = ValueEstimate.of("k", List.of(100.0d, 100.0d, 101.0d, 99.0d, 100.0d,
				100.0d, 100.0d, 100.0d), 48.0d, false);
		assertTrue(coarse.confidence() < tight.confidence());
	}

	@Test
	void resaleTimeComesFromTheObservedSaleRate() {
		// Eight sales over two days is roughly one every six hours.
		ValueEstimate slow = ValueEstimate.of("k", List.of(1.0d, 1.0d, 1.0d, 1.0d, 1.0d, 1.0d,
				1.0d, 1.0d), 48.0d, true);
		assertEquals(6.0d, slow.hoursToSell(), 0.01d);

		// Nothing is credited with selling faster than it takes to find, buy and relist.
		ValueEstimate fast = ValueEstimate.of("k",
				java.util.Collections.nCopies(1000, 1.0d), 1.0d, true);
		assertEquals(0.25d, fast.hoursToSell(), 1e-9);
	}

	@Test
	void pricesPerUnitOnStackedSales() {
		// The fixture item is a single, so build the stacked case directly.
		ValueEstimate estimate = ValueEstimate.of("k", List.of(50.0d, 50.0d, 50.0d, 50.0d, 50.0d,
				50.0d), 48.0d, true);

		assertEquals(50.0d, estimate.median(), 1e-9);
	}

	@Test
	void survivesUndecodableSalesInTheTape() {
		List<EndedAuction> mixed = new ArrayList<>(sales("ANITA_TALISMAN", 3_000_000L, 3_000_000L,
				3_000_000L, 3_000_000L, 3_000_000L, 3_000_000L));
		mixed.add(new EndedAuction("broken", "s", "b", NOW.toEpochMilli(), 1L, true, "not-base64!!"));
		mixed.add(new EndedAuction("null-blob", "s", "b", NOW.toEpochMilli(), 1L, true, null));

		assertEquals(6, modelOf(mixed).salesConsidered());
	}
}
