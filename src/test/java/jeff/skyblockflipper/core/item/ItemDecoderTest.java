package jeff.skyblockflipper.core.item;

import com.google.gson.Gson;

import jeff.skyblockflipper.core.model.EndedAuction;
import jeff.skyblockflipper.core.model.dto.EndedAuctionsDto;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decoding is checked against real blobs, not synthetic ones.
 *
 * <p>The fixture is nine trimmed captures chosen to cover the cases that a hand-written sample
 * would not have thought of: an item with no tooltip style, stars under the legacy attribute name,
 * a pet, gemstones, hot potato books, a Kuudra attribute roll, and a plain item with nothing on it
 * at all. Everything here
 * fails loudly if Hypixel changes the blob format, which is the point - a decode that quietly
 * drops an attribute prices a five-star recombobulated item as if it were bare.
 */
class ItemDecoderTest {
	private static List<EndedAuction> sales;

	@BeforeAll
	static void loadFixture() throws Exception {
		try (InputStream in = ItemDecoderTest.class.getResourceAsStream("/item-bytes-sample.json")) {
			sales = new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8),
					EndedAuctionsDto.class).auctions;
		}
	}

	private static DecodedItem decode(String skyblockId) {
		return sales.stream()
				.map(sale -> ItemDecoder.decode(sale.itemBytes()))
				.flatMap(java.util.Optional::stream)
				.filter(item -> item.skyblockId().equals(skyblockId))
				.findFirst()
				.orElseThrow(() -> new AssertionError(skyblockId + " is not in the fixture"));
	}

	@Test
	void decodesEveryItemInTheFixture() {
		assertEquals(sales.size(), sales.stream()
				.map(sale -> ItemDecoder.decode(sale.itemBytes()))
				.filter(java.util.Optional::isPresent)
				.count());
	}

	@Test
	void readsEverythingThatWasPaidForOnAFullyUpgradedItem() {
		DecodedItem sword = decode("GIANTS_SWORD");

		assertEquals(Rarity.MYTHIC, sword.rarity());
		assertEquals("withered", sword.reforge());
		assertEquals(5, sword.stars());
		assertTrue(sword.recombobulated());
		assertEquals(10, sword.hotPotatoBooks());
		assertEquals(List.of("JASPER=FINE", "JASPER=FINE"), sword.gemstones());
		assertEquals(6, sword.enchantments().get("sharpness"));
		assertEquals(9, sword.enchantments().get("champion"));

		// This one sold for 88M. Missing any of the above prices it as a bare Giant's Sword.
		assertEquals("Withered Giant's Sword ✪✪✪✪✪", sword.displayName());
	}

	@Test
	void readsStarsFromTheLegacyAttributeToo() {
		// Older items store stars as dungeon_item_level rather than upgrade_level. Reading only
		// the modern name prices a five-star helmet as bare.
		DecodedItem helmet = decode("TARANTULA_HELMET");

		assertEquals(5, helmet.stars());
		assertEquals("ancient", helmet.reforge());
	}

	@Test
	void fallsBackToLoreWhenTheTooltipStyleIsMissing() {
		// Roughly one item in forty carries no components tooltip style at all, and the only
		// remaining statement of its rarity is the last line of the lore.
		DecodedItem necklace = decode("DULL_SHARK_TOOTH_NECKLACE");

		assertEquals(Rarity.UNCOMMON, necklace.rarity());
	}

	@Test
	void readsRarityThroughObfuscationCodes() {
		// A mythic's lore line reads "§d§l§ka§r §d§lMYTHIC ACCESSORY §d§l§ka", so the rarity word
		// is not the first token even after the codes come off.
		assertEquals(Rarity.MYTHIC, Rarity.fromLoreLine(ItemDecoder.stripFormatting(
				"§d§l§ka§r §d§lMYTHIC ACCESSORY §d§l§ka")));
	}

	@Test
	void unpacksThePetHiddenInsideAJsonString() {
		DecodedItem pet = decode("PET");

		assertTrue(pet.isPet());
		PetInfo info = pet.petInfo().orElseThrow();

		// Every pet in the game shares the item id PET. Without this, the whole pet market looks
		// like one item trading between 10k and 500M.
		assertEquals("MOLE", info.type());
		assertEquals(Rarity.LEGENDARY, info.tier());
		assertEquals("PET_ITEM_MINING_SKILL_BOOST_RARE", info.heldItem());
		assertFalse(info.hasCandy());
		assertTrue(info.exp() > 25_000_000.0d);
	}

	@Test
	void readsThePetLevelOffTheNameRatherThanTheExperienceCurve() {
		PetInfo info = decode("PET").petInfo().orElseThrow();

		// The level is what a pet is worth - the same pet at level 1 and level 100 differs by 2x to
		// 12x - and deriving it from exp would mean bundling a curve per tier and per pet. Hypixel
		// states it in the name instead, on all 2484 pet sales of the recorded tape.
		assertEquals(100, info.level());
		assertTrue(info.hasLevel());
	}

	@Test
	void survivesANameWithNoUsableLevel() {
		// A level that cannot be read must cost the level, not the pet. Every one of these falls
		// back to 0, which drops the level rungs of the valuation ladder and prices the pet the way
		// every pet was priced before there was a level at all.
		assertEquals(0, ItemDecoder.levelFromName("Mole"));
		assertEquals(0, ItemDecoder.levelFromName("[Lvl ] Mole"));
		assertEquals(0, ItemDecoder.levelFromName("[Lvl abc] Mole"));
		assertEquals(0, ItemDecoder.levelFromName("[Lvl 100 Mole"));
		assertEquals(0, ItemDecoder.levelFromName(""));

		assertEquals(1, ItemDecoder.levelFromName("[Lvl 1] Sheep"));
		// Golden Dragon is the one pet that goes past 100, so the parse must not cap at three digits
		// or at the level every other pet stops at.
		assertEquals(200, ItemDecoder.levelFromName("[Lvl 200] Golden Dragon"));
	}

	@Test
	void bandsLevelsWithOneAndOneHundredAloneBecauseThatIsWhereThePetsAre() {
		// 1455 of 2484 taped pet sales were level 1 and 548 were level 100. Banding either with its
		// neighbours would put a median between two peaks, which is wrong for both.
		assertEquals("1", band(1));
		assertEquals("2-29", band(2));
		assertEquals("2-29", band(29));
		assertEquals("30-59", band(30));
		assertEquals("60-89", band(89));
		assertEquals("90-99", band(90));
		assertEquals("100", band(100));

		// Open-ended above 100: only Golden Dragon is up here, and it has too few sales to split.
		assertEquals("101+", band(101));
		assertEquals("101+", band(200));

		assertTrue(pet(1).bandIsJustThisLevel());
		assertTrue(pet(100).bandIsJustThisLevel());
		assertFalse(pet(50).bandIsJustThisLevel());

		// An unknown level has no band to fall back to.
		assertEquals("", pet(0).levelBand());
	}

	private static PetInfo pet(int level) {
		return new PetInfo("MOLE", Rarity.LEGENDARY, 0.0d, level, "", 0, "");
	}

	private static String band(int level) {
		return pet(level).levelBand();
	}

	@Test
	void plainItemsDecodeToPlainSignatures() {
		DecodedItem talisman = decode("ANITA_TALISMAN");

		assertEquals("", talisman.reforge());
		assertEquals(0, talisman.stars());
		assertFalse(talisman.recombobulated());
		assertTrue(talisman.enchantments().isEmpty());
		assertEquals("ANITA_TALISMAN|COMMON", talisman.signature());
	}

	@Test
	void recombobulationShowsUpInTheSignatureNotJustTheRarity() {
		DecodedItem necklace = decode("RAZOR_SHARP_SHARK_TOOTH_NECKLACE");

		// The rarity already reads MYTHIC because the recombobulator moved it there. Whether it
		// was born mythic or bought its way up is a different item at a different price.
		assertEquals(Rarity.MYTHIC, necklace.rarity());
		assertTrue(necklace.recombobulated());
		assertTrue(necklace.signature().contains("recomb"));
	}

	@Test
	void signaturesGroupIdenticalConfigurationsAndSeparateDifferentOnes() {
		DecodedItem helmet = decode("POWER_WITHER_HELMET");

		DecodedItem sameAgain = new DecodedItem(helmet.skyblockId(), helmet.displayName(),
				helmet.count(), helmet.rarity(), helmet.reforge(), helmet.stars(),
				helmet.recombobulated(), helmet.hotPotatoBooks(),
				// Same enchantments, different iteration order.
				new java.util.HashMap<>(helmet.enchantments()), helmet.gemstones(), helmet.attributes(),
				helmet.pet());

		assertEquals(helmet.signature(), sameAgain.signature());

		DecodedItem oneStarLess = new DecodedItem(helmet.skyblockId(), helmet.displayName(),
				helmet.count(), helmet.rarity(), helmet.reforge(), helmet.stars() - 1,
				helmet.recombobulated(), helmet.hotPotatoBooks(), helmet.enchantments(),
				helmet.gemstones(), helmet.attributes(), helmet.pet());

		assertNotEquals(helmet.signature(), oneStarLess.signature());
	}

	@Test
	void enchantmentsAreReadAsAMap() {
		Map<String, Integer> enchantments = decode("ZOMBIE_SOLDIER_LEGGINGS").enchantments();

		assertEquals(Map.of("thorns", 3, "protection", 5, "growth", 5), enchantments);
	}

	@Test
	void readsKuudraAttributeRollsWithTheirLevels() {
		DecodedItem boots = decode("TERROR_BOOTS");

		// The two levels differ, so reading the names without their levels - or collapsing them to
		// one number the way stars are - would still fail here.
		assertEquals(Map.of("mana_regeneration", 5, "lifeline", 4), boots.attributes());
		assertEquals("ancient", boots.reforge());
	}

	@Test
	void anAttributeRollIsADifferentItemFromTheSameGearWithoutOne() {
		DecodedItem boots = decode("TERROR_BOOTS");

		DecodedItem unrolled = new DecodedItem(boots.skyblockId(), boots.displayName(),
				boots.count(), boots.rarity(), boots.reforge(), boots.stars(),
				boots.recombobulated(), boots.hotPotatoBooks(), boots.enchantments(),
				boots.gemstones(), Map.of(), boots.pet());

		// Rolled Crimson gear was asking several times what the bare item was. Sharing a signature
		// with it would price one off sales of the other in whichever direction happens to hurt.
		assertNotEquals(boots.signature(), unrolled.signature());
		assertTrue(boots.signature().contains("attrs=lifeline:4,mana_regeneration:5"));

		DecodedItem oneLevelLower = new DecodedItem(boots.skyblockId(), boots.displayName(),
				boots.count(), boots.rarity(), boots.reforge(), boots.stars(),
				boots.recombobulated(), boots.hotPotatoBooks(), boots.enchantments(),
				boots.gemstones(), Map.of("mana_regeneration", 4, "lifeline", 4), boots.pet());

		assertNotEquals(boots.signature(), oneLevelLower.signature());
	}

	@Test
	void garbageDecodesToEmptyRatherThanThrowing() {
		// The tape is replayed in bulk; one unreadable blob must cost one sale, not the model.
		assertTrue(ItemDecoder.decode("not base64 at all!!").isEmpty());
		assertTrue(ItemDecoder.decode("").isEmpty());
		assertTrue(ItemDecoder.decode(null).isEmpty());
		assertTrue(ItemDecoder.decode("H4sIAAAAAAAA/wMAAAAAAAAAAAA=").isEmpty());
	}
}
