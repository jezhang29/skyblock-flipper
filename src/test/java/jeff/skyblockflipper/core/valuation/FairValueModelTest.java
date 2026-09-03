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
				Map.of(), null, null, null, List.of(), "", false, 0L, 0);

		assertTrue(model.valueOf(bare).isEmpty());

		// And the invisible upgrades are what isBare() guards: same name and rarity as the sales,
		// but hot potato books and a recombobulator the coarse key could never have seen.
		DecodedItem sameNameQuietlyUpgraded = new DecodedItem(upgraded.skyblockId(),
				upgraded.displayName(), upgraded.count(), upgraded.rarity(), upgraded.reforge(),
				upgraded.stars(), true, 10, Map.of(), List.of(), Map.of(), Map.of(), null, null,
				null, List.of(), "", false, 0L, 0);

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
				null, null, null, List.of(), "", false, 0L, 0);

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
				Map.of("mana_pool", 6, "mana_regeneration", 6), Map.of(), null, null, null, List.of(), "", false, 0L, 0);

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
				null, List.of(), "", true, 0L, 0);

		assertTrue(model.valueOf(bare).isPresent());
		assertTrue(model.valueOf(merged).isEmpty());
	}

	/**
	 * A locked-slot item is not priced off the pool of unlocked-slot sales.
	 *
	 * <p>The bug found in play 2026-09-02: a Divan's Helmet with its gemstone slots shut, quoted at
	 * ~60M and flagged as a snipe against sales whose slots were paid open. Same id, rarity, reforge
	 * and recomb, so before the {@code slots} term the two shared one exact key and one median - and
	 * with unlocked pieces dominating a mixed pool, the locked one was quoted at the unlocked price.
	 */
	@Test
	void willNotPriceALockedSlotItemOffUnlockedSlotSales() {
		// Six sales of a fully-unlocked, recombobulated Jaded Divan's Helmet, and nothing else.
		FairValueModel.Builder builder = FairValueModel.builder(NOW, WINDOW);

		for (int i = 0; i < 6; i++) {
			builder.add(divanHelmet(5), 62_000_000.0d,
					NOW.minus(Duration.ofHours(i + 1L)).toEpochMilli());
		}

		FairValueModel model = builder.build();

		// The slot count is the only thing between them, and it is now in the key.
		assertEquals("DIVAN_HELMET|MYTHIC|reforge=jaded|recomb", divanHelmet(0).signature());
		assertEquals("DIVAN_HELMET|MYTHIC|reforge=jaded|recomb|slots", divanHelmet(5).signature());

		// The unlocked piece prices off its own sales.
		assertEquals(62_000_000.0d, model.valueOf(divanHelmet(5)).orElseThrow().median(), 1e-6);

		// The locked piece no longer shares that key, and recomb plus a reforge keep it out of the
		// coarse pool too, so it gets no quote rather than the unlocked median - the snipe is gone.
		assertTrue(model.valueOf(divanHelmet(0)).isEmpty());
	}

	/**
	 * An unlocked-but-empty slot does not fall back to the coarse pool, and the exact-gate refuses it.
	 *
	 * <p>The other half of the same bug, and where {@code UnderpricedScan}'s exact re-check earns its
	 * place. A slot paid open but left empty places no gem, so before the {@code slots} term the item
	 * read as bare and priced off the coarse pool of gemmed and unlocked sales. The coarse hit still
	 * exists - name and rarity match, which is what the scan prunes on before decoding - but a paid-open
	 * slot is not bare, so the exact re-check no longer lets that pool value it.
	 */
	@Test
	void willNotPriceAnUnlockedSlotItemOffTheCoarsePool() {
		FairValueModel model = modelOf(sales("ANITA_TALISMAN", 3_000_000L, 3_000_000L, 3_000_000L,
				3_000_000L, 3_000_000L, 3_000_000L));

		DecodedItem bare = item("ANITA_TALISMAN");
		// Same name and rarity as the sales, but two gemstone slots paid open - an investment the
		// display name the coarse key is built from never mentions.
		DecodedItem unlocked = new DecodedItem(bare.skyblockId(), bare.displayName(), bare.count(),
				bare.rarity(), "", 0, false, 0, Map.of(), List.of(), Map.of(), Map.of(), null, null,
				null, List.of(), "", false, 0L, 2);

		// The coarse hit the scan prunes on is present for both - it is keyed on name and rarity.
		assertTrue(model.roughValueOf(bare.displayName(), bare.rarity()).isPresent());

		// The bare item takes the coarse value; the unlocked one is refused rather than quoted off it.
		assertTrue(model.valueOf(bare).isPresent());
		assertTrue(model.valueOf(unlocked).isEmpty());
	}

	/**
	 * A Midas weapon is priced per coin bid for it, not from the pool of every bid.
	 *
	 * <p>The fixture sale is a real one: a {@code MIDAS_STAFF} bought at the Dark Auction for
	 * 117,360,000 coins and resold for 100,000,000. Its signature says nothing about the bid - the
	 * bid is deliberately not a key term, because it is continuous - so under a pooled median every
	 * staff on the market is quoted at whatever the last few staffs happened to be worth, whatever
	 * was burned on them.
	 */
	@Test
	void pricesADarkAuctionItemAgainstTheBidItCarries() {
		FairValueModel model = modelOf(sales("MIDAS_STAFF", 100_000_000L, 100_000_000L,
				100_000_000L, 100_000_000L, 100_000_000L, 100_000_000L));

		DecodedItem sold = item("MIDAS_STAFF");
		assertEquals(117_360_000L, sold.winningBid());

		// The item the sales are of prices at what they fetched, the same as any other estimate.
		assertEquals(100_000_000.0d, model.valueOf(sold).orElseThrow().median(), 1.0d);

		// And one bought for twice as much is worth about twice as much, off exactly the same sales.
		// A pooled median would quote it at 100,000,000 and call the other 100,000,000 profit.
		DecodedItem twiceTheBid = withBid(sold, sold.winningBid() * 2L);
		ValueEstimate value = model.valueOf(twiceTheBid).orElseThrow();

		assertEquals(200_000_000.0d, value.median(), 1.0d);
		assertEquals(6, value.samples());
		assertTrue(value.exact());
	}

	/**
	 * A Dark Auction item never falls back to the coarse pool, which mixes every bid under one name.
	 *
	 * <p>Constructed rather than taped, and deliberately so: on six days of tape this clause never
	 * decided a lookup, because a bid-carrying item's own signature always had sales of its own. It
	 * is kept on the maxed dungeon flag's footing - it costs no coverage, and what it names is that
	 * the display name is identical at every bid, so a coarse quote for one of these is a pool of
	 * items that have nothing to do with each other. The case that would reach it is a second item
	 * id wearing the same name, which is how {@code STARRED_MIDAS_STAFF} and {@code MIDAS_STAFF}
	 * already sit in the tape.
	 */
	@Test
	void willNotPriceADarkAuctionItemOffTheCoarsePool() {
		FairValueModel model = modelOf(sales("MIDAS_STAFF", 100_000_000L, 100_000_000L,
				100_000_000L, 100_000_000L, 100_000_000L, 100_000_000L));

		DecodedItem sold = item("MIDAS_STAFF");
		// Same display name and rarity as the sales, so the coarse key matches; a different item id,
		// so the exact key does not. That is the only way into the coarse pool.
		DecodedItem otherId = withId(sold, "STARRED_MIDAS_STAFF");

		assertTrue(model.valueOf(withBid(otherId, 0L)).isPresent());
		assertTrue(model.valueOf(otherId).isEmpty());
	}

	private static DecodedItem withBid(DecodedItem item, long bid) {
		return new DecodedItem(item.skyblockId(), item.displayName(), item.count(), item.rarity(),
				item.reforge(), item.stars(), item.recombobulated(), item.hotPotatoBooks(),
				item.enchantments(), item.gemstones(), item.attributes(), item.runes(), item.pet(),
				item.potion(), item.quality(), item.abilityScrolls(), item.dye(), item.ethermerged(), bid,
				item.unlockedSlots());
	}

	/** A recombobulated Jaded Divan's Helmet with the given number of gemstone slots paid open. */
	private static DecodedItem divanHelmet(int unlockedSlots) {
		return new DecodedItem("DIVAN_HELMET", "Jaded Divan's Helmet", 1, Rarity.MYTHIC, "jaded", 0,
				true, 0, Map.of(), List.of(), Map.of(), Map.of(), null, null, null, List.of(), "", false, 0L,
				unlockedSlots);
	}

	private static DecodedItem withId(DecodedItem item, String skyblockId) {
		return new DecodedItem(skyblockId, item.displayName(), item.count(), item.rarity(),
				item.reforge(), item.stars(), item.recombobulated(), item.hotPotatoBooks(),
				item.enchantments(), item.gemstones(), item.attributes(), item.runes(), item.pet(),
				item.potion(), item.quality(), item.abilityScrolls(), item.dye(), item.ethermerged(), item.winningBid(),
				item.unlockedSlots());
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
				null, null, List.of(), "", false, 0L, 0);
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
