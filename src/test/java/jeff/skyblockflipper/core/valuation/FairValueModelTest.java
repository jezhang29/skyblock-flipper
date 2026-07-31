package jeff.skyblockflipper.core.valuation;

import com.google.gson.Gson;

import jeff.skyblockflipper.core.item.DecodedItem;
import jeff.skyblockflipper.core.item.ItemDecoder;
import jeff.skyblockflipper.core.item.PetInfo;
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
				upgraded.count(), Rarity.LEGENDARY, "", 0, false, 0, Map.of(), List.of(), Map.of(),
				Map.of(), null, null, null, "", false);

		assertTrue(model.valueOf(bare).isEmpty());

		// And the invisible upgrades are what isBare() guards: same name and rarity as the sales,
		// but hot potato books and a recombobulator the coarse key could never have seen.
		DecodedItem sameNameQuietlyUpgraded = new DecodedItem(upgraded.skyblockId(),
				upgraded.displayName(), upgraded.count(), upgraded.rarity(), upgraded.reforge(),
				upgraded.stars(), true, 10, Map.of(), List.of(), Map.of(), Map.of(), null, null,
				null, "", false);

		assertTrue(model.valueOf(sameNameQuietlyUpgraded).isEmpty());
	}

	@Test
	void usesTheCoarseValueOnlyForItemsWithNothingAddedToThem() {
		FairValueModel model = modelOf(sales("ANITA_TALISMAN", 3_000_000L, 3_000_000L, 3_000_000L,
				3_000_000L, 3_000_000L, 3_000_000L));

		DecodedItem bare = item("ANITA_TALISMAN");
		// Same name and rarity, but now carrying enchantments the coarse key cannot see.
		DecodedItem enchanted = new DecodedItem(bare.skyblockId(), bare.displayName(), bare.count(),
				bare.rarity(), "", 0, false, 0, Map.of("sharpness", 7), List.of(), Map.of(), Map.of(),
				null, null, null, "", false);

		assertTrue(model.valueOf(bare).isPresent());
		assertTrue(model.valueOf(enchanted).isEmpty());
	}

	@Test
	void willNotPriceAnAttributeRollOffTheCoarsePool() {
		FairValueModel model = modelOf(sales("ANITA_TALISMAN", 3_000_000L, 3_000_000L, 3_000_000L,
				3_000_000L, 3_000_000L, 3_000_000L));

		DecodedItem bare = item("ANITA_TALISMAN");
		// An attribute roll is not something a player bolted on, so it leaves no other trace: no
		// stars, no books, no gems. It is the one upgrade that would otherwise still look bare, and
		// on Crimson gear it is worth several times the item under it.
		DecodedItem rolled = new DecodedItem(bare.skyblockId(), bare.displayName(), bare.count(),
				bare.rarity(), "", 0, false, 0, Map.of(), List.of(),
				Map.of("mana_pool", 6, "mana_regeneration", 6), Map.of(), null, null, null, "", false);

		assertTrue(model.valueOf(bare).isPresent());
		assertTrue(model.valueOf(rolled).isEmpty());
	}

	@Test
	void willNotPriceAnEtherwarpMergeOffTheCoarsePool() {
		FairValueModel model = modelOf(sales("ANITA_TALISMAN", 3_000_000L, 3_000_000L, 3_000_000L,
				3_000_000L, 3_000_000L, 3_000_000L));

		DecodedItem bare = item("ANITA_TALISMAN");
		// The merge is the attribute-roll case again: 315 of the 516 merged sales on the tape carry
		// nothing else at all, and the display name the coarse key is built from never mentions it.
		// On the tape a merged Aspect of the Void fetches about 4x a plain one.
		DecodedItem merged = new DecodedItem(bare.skyblockId(), bare.displayName(), bare.count(),
				bare.rarity(), "", 0, false, 0, Map.of(), List.of(), Map.of(), Map.of(), null, null,
				null, "", true);

		assertTrue(model.valueOf(bare).isPresent());
		assertTrue(model.valueOf(merged).isEmpty());
	}

	/**
	 * The fixture pet is a level 100 Mole holding a mining skill boost. Six sales of it, so its
	 * exact-level key clears MIN_SAMPLES on its own.
	 */
	private static FairValueModel molesSoldAtLevelOneHundred() {
		return modelOf(sales("PET", 15_000_000L, 15_000_000L, 14_500_000L, 15_500_000L,
				15_000_000L, 15_200_000L));
	}

	private static DecodedItem moleAtLevel(int level) {
		DecodedItem real = item("PET");
		PetInfo pet = real.petInfo().orElseThrow();

		return new DecodedItem(real.skyblockId(), "[Lvl " + level + "] Mole", real.count(),
				real.rarity(), "", 0, false, 0, Map.of(), List.of(), Map.of(), Map.of(),
				new PetInfo(pet.type(), pet.tier(), pet.exp(), level, pet.heldItem(),
						pet.candyUsed(), pet.skin()),
				null, null, "", false);
	}

	@Test
	void pricesAPetAgainstItsOwnLevelBeforeAnythingWider() {
		FairValueModel model = molesSoldAtLevelOneHundred();

		// The item that actually sold: its own level has sales behind it, so nothing is widened.
		ValueEstimate exact = model.valueOf(item("PET")).orElseThrow();

		assertEquals(ValueEstimate.Basis.EXACT, exact.basis());
		assertTrue(exact.exact());
		assertEquals(15_000_000.0d, exact.median(), 100_000.0d);
	}

	@Test
	void willNotPriceALevelOnePetOffLevelOneHundredSalesWithoutSayingSo() {
		FairValueModel model = molesSoldAtLevelOneHundred();

		// This is the mistake the level rungs exist to stop being silent. A level 1 Mole is worth a
		// fraction of a level 100 one, and before the level was read they shared a single key and a
		// single median - so a fresh pet looked like a 15M item listed at 3M, which is not a snipe.
		ValueEstimate widened = model.valueOf(moleAtLevel(1)).orElseThrow();

		// It still gets a number, because no number at all prices nothing. What it does not get is
		// the claim that the sales behind it describe this pet.
		assertEquals(ValueEstimate.Basis.BANDED, widened.basis());
		assertFalse(widened.exact());

		// And the discount is real, not cosmetic: the same figures priced exactly would clear the
		// default minConfidence of 0.6, and widened they must be worth measurably less.
		assertTrue(widened.confidence() < widened.withBasis(ValueEstimate.Basis.EXACT).confidence());
	}

	@Test
	void fallsThroughTheLevelBandBeforeGivingUpOnTheLevelEntirely() {
		FairValueModel model = molesSoldAtLevelOneHundred();

		// Level 95 and level 99 share the 90-99 band, so a 95 can be priced off a 99 - close enough
		// to be evidence, and labelled as widened either way. Level 100 is a band of its own, so it
		// never lends its sales to a 99 through the band rung.
		DecodedItem ninetyFive = moleAtLevel(95);

		assertEquals(
				List.of("PET|MOLE|LEGENDARY|held=PET_ITEM_MINING_SKILL_BOOST_RARE|lvl=95",
						"PET|MOLE|LEGENDARY|held=PET_ITEM_MINING_SKILL_BOOST_RARE|lvlBand=90-99",
						"PET|MOLE|LEGENDARY|held=PET_ITEM_MINING_SKILL_BOOST_RARE"),
				ninetyFive.valuationKeys());

		// A band of one level adds a key with an identical pool behind it, so it is left out.
		assertEquals(2, moleAtLevel(100).valuationKeys().size());

		// The held item survives every rung. It can be worth more than the pet under it, so widening
		// the level must never quietly widen the pet as well.
		ninetyFive.valuationKeys()
				.forEach(key -> assertTrue(key.contains("held=PET_ITEM_MINING_SKILL_BOOST_RARE")));
	}

	@Test
	void aPetWithNoReadableLevelIsNeverCalledAnExactMatch() {
		FairValueModel model = molesSoldAtLevelOneHundred();

		DecodedItem unknownLevel = moleAtLevel(0);

		// Its only key is the levelless one, which pools every level of the pet. That is the single
		// median across a distribution with peaks at 1 and at 100, so calling it exact - which it
		// technically is, being the item's whole signature - would sell the worst estimate here as
		// the best kind.
		assertEquals(List.of("PET|MOLE|LEGENDARY|held=PET_ITEM_MINING_SKILL_BOOST_RARE"),
				unknownLevel.valuationKeys());
		assertFalse(unknownLevel.isFullyDescribed());
		assertEquals(ValueEstimate.Basis.BANDED, model.valueOf(unknownLevel).orElseThrow().basis());
	}

	@Test
	void nonPetsStillHaveExactlyOneKey() {
		// The ladder is a pet feature. Everything else must key on its signature and nothing else,
		// or a five-star helmet acquires a rung that prices it off the bare one.
		DecodedItem helmet = item("POWER_WITHER_HELMET");

		assertEquals(List.of(helmet.signature()), helmet.valuationKeys());
		assertTrue(helmet.isFullyDescribed());
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
				100.0d, 100.0d, 100.0d), 48.0d, ValueEstimate.Basis.EXACT);
		ValueEstimate scattered = ValueEstimate.of("k", List.of(10.0d, 50.0d, 100.0d, 150.0d,
				400.0d, 900.0d, 30.0d, 700.0d), 48.0d, ValueEstimate.Basis.EXACT);

		assertTrue(tight.confidence() > scattered.confidence());

		// A coarse estimate is penalised on top of everything else: it matched a name, not an item.
		ValueEstimate coarse = ValueEstimate.of("k", List.of(100.0d, 100.0d, 101.0d, 99.0d, 100.0d,
				100.0d, 100.0d, 100.0d), 48.0d, ValueEstimate.Basis.COARSE);
		assertTrue(coarse.confidence() < tight.confidence());
	}

	@Test
	void resaleTimeComesFromTheObservedSaleRate() {
		// Eight sales over two days is roughly one every six hours.
		ValueEstimate slow = ValueEstimate.of("k", List.of(1.0d, 1.0d, 1.0d, 1.0d, 1.0d, 1.0d,
				1.0d, 1.0d), 48.0d, ValueEstimate.Basis.EXACT);
		assertEquals(6.0d, slow.hoursToSell(), 0.01d);

		// Nothing is credited with selling faster than it takes to find, buy and relist.
		ValueEstimate fast = ValueEstimate.of("k",
				java.util.Collections.nCopies(1000, 1.0d), 1.0d, ValueEstimate.Basis.EXACT);
		assertEquals(0.25d, fast.hoursToSell(), 1e-9);
	}

	@Test
	void pricesPerUnitOnStackedSales() {
		// The fixture item is a single, so build the stacked case directly.
		ValueEstimate estimate = ValueEstimate.of("k", List.of(50.0d, 50.0d, 50.0d, 50.0d, 50.0d,
				50.0d), 48.0d, ValueEstimate.Basis.EXACT);

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
