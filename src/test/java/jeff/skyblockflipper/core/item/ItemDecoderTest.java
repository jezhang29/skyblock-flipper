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
 * <p>The fixture is twenty-one trimmed captures chosen to cover the cases that a hand-written sample
 * would not have thought of: an item with no tooltip style, stars under the legacy attribute name,
 * a pet, gemstones, hot potato books, a Kuudra attribute roll, two tiers of the same rune, a rune
 * applied to something else, three potions differing only in effect and perks, two dungeon drops
 * differing only in their floor tier, a stat boost with no tier under it, two Aspects of the Void
 * differing only in an Etherwarp merge, and a plain item with nothing on it at all. Everything here fails
 * loudly if Hypixel changes the blob format, which is the point - a decode that quietly drops an
 * attribute prices a five-star recombobulated item as if it were bare.
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

	private static DecodedItem decodeByName(String displayName) {
		return sales.stream()
				.map(sale -> ItemDecoder.decode(sale.itemBytes()))
				.flatMap(java.util.Optional::stream)
				.filter(item -> item.displayName().equals(displayName))
				.findFirst()
				.orElseThrow(() -> new AssertionError(displayName + " is not in the fixture"));
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
				helmet.runes(), helmet.pet(), helmet.potion(), helmet.quality(), "", false);

		assertEquals(helmet.signature(), sameAgain.signature());

		DecodedItem oneStarLess = new DecodedItem(helmet.skyblockId(), helmet.displayName(),
				helmet.count(), helmet.rarity(), helmet.reforge(), helmet.stars() - 1,
				helmet.recombobulated(), helmet.hotPotatoBooks(), helmet.enchantments(),
				helmet.gemstones(), helmet.attributes(), helmet.runes(), helmet.pet(),
				helmet.potion(), helmet.quality(), "", false);

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
				boots.gemstones(), Map.of(), boots.runes(), boots.pet(), boots.potion(),
				boots.quality(), "", false);

		// Rolled Crimson gear was asking several times what the bare item was. Sharing a signature
		// with it would price one off sales of the other in whichever direction happens to hurt.
		assertNotEquals(boots.signature(), unrolled.signature());
		assertTrue(boots.signature().contains("attrs=lifeline:4,mana_regeneration:5"));

		DecodedItem oneLevelLower = new DecodedItem(boots.skyblockId(), boots.displayName(),
				boots.count(), boots.rarity(), boots.reforge(), boots.stars(),
				boots.recombobulated(), boots.hotPotatoBooks(), boots.enchantments(),
				boots.gemstones(), Map.of("mana_regeneration", 4, "lifeline", 4), boots.runes(),
				boots.pet(), boots.potion(), boots.quality(), "", false);

		assertNotEquals(boots.signature(), oneLevelLower.signature());
	}

	@Test
	void readsAppliedRunesWithTheirTier() {
		DecodedItem rune = decodeByName("\u25c6 Music Rune I");

		// Every rune in the game is the item id RUNE, so this map is the only thing separating one
		// from another: on the tape a Music rune sells for 5,000,000 and a Gem rune for 2,000.
		assertEquals(Map.of("MUSIC", 1), rune.runes());
		assertEquals("RUNE", rune.skyblockId());
		assertTrue(rune.signature().contains("runes=MUSIC:1"));
	}

	@Test
	void aRuneTierIsADifferentItemFromTheSameRuneOneTierLower() {
		DecodedItem first = decodeByName("\u25c6 Music Rune I");
		DecodedItem third = decodeByName("\u25c6 Music Rune III");

		// Same id and rarity, 12x apart on the tape - median 5,000,000 against 59,500,000.
		assertEquals(first.skyblockId(), third.skyblockId());
		assertEquals(first.rarity(), third.rarity());
		assertNotEquals(first.signature(), third.signature());
	}

	@Test
	void readsAPotionsEffectTierAndPerks() {
		DecodedItem splashHealing = decodeByName("Healing VIII Splash Potion");
		PotionInfo healing = splashHealing.potionInfo().orElseThrow();

		assertEquals("POTION", splashHealing.skyblockId());
		assertEquals("healing", healing.type());
		assertEquals(8, healing.level());
		assertTrue(healing.splash());
		assertFalse(healing.enhanced());
		assertTrue(splashHealing.signature().contains("potion=healing:8,splash"));
	}

	@Test
	void twoPotionsAreOnlyTheSameItemIfEveryPerkMatches() {
		DecodedItem speed = decodeByName("Speed VIII Potion");
		DecodedItem cheese = decodeByName("Douce Pluie de Stinky Cheese I Potion");

		// Every potion in the game is the item id POTION, and on the tape these two sit either side
		// of the pooled median: Stinky Cheese at 950,000 coins and this Speed potion at 50,000.
		assertEquals(speed.skyblockId(), cheese.skyblockId());
		assertNotEquals(speed.signature(), cheese.signature());

		// The perks are the part no coarse key could recover, because Hypixel leaves them out of the
		// display name - this potion is enhanced and extended and still just reads "Speed VIII
		// Potion". On the tape that is 82,525 coins against 58,999 for the plain one.
		PotionInfo perks = speed.potionInfo().orElseThrow();
		assertTrue(perks.enhanced());
		assertTrue(perks.extended());
		assertFalse(perks.splash());
		assertEquals("speed:8,enhanced,extended", perks.signatureTerm());
	}

	@Test
	void anItemThatIsNotAPotionCarriesNoPotionDetail() {
		assertFalse(decode("GIANTS_SWORD").isPotion());
		assertTrue(decode("GIANTS_SWORD").potionInfo().isEmpty());
	}

	@Test
	void readsTheDungeonQualityRollAsAMaxedFlagAndAnExactTier() {
		DecodedItem tier10 = decodeQuality(10);
		DungeonQuality quality = tier10.dungeonQuality().orElseThrow();

		assertTrue(quality.maxedStats());
		assertTrue(quality.hasTier());
		assertEquals(10, quality.floorTier());
		assertEquals("maxed,tier=10", quality.signatureTerm());
		assertTrue(tier10.signature().contains("quality=maxed,tier=10"));
	}

	@Test
	void twoTiersOfTheSameDropAreDifferentItems() {
		DecodedItem tier10 = decodeQuality(10);

		// Both captures really are SKELETON_MASTER_CHESTPLATE, which is the item this whole term was
		// built for: on the tape its tier-10s sell at a 113,000,000 median against 2,000,000 for its
		// tier-7s, so pooling them makes half of them read as a 56x snipe. They differ in rarity as
		// well, though, so the tier is dropped onto one of them to isolate it.
		assertEquals(tier10.skyblockId(), decodeQuality(7).skyblockId());

		DecodedItem lowerFloor = withQuality(tier10, new DungeonQuality(true, 7));

		assertNotEquals(tier10.signature(), lowerFloor.signature());
		assertTrue(lowerFloor.signature().contains("quality=maxed,tier=7"));
	}

	@Test
	void anUnmaxedRollIsStillSeparatedByTheFloorItDroppedAt() {
		// The stat boost being unmaxed does not make the tier stop mattering - a floor 6 drop and a
		// floor 10 drop are different items whatever their rolls came out at.
		DecodedItem leggings = decode("ZOMBIE_SOLDIER_LEGGINGS");
		DungeonQuality quality = leggings.dungeonQuality().orElseThrow();

		assertFalse(quality.maxedStats());
		assertEquals(6, quality.floorTier());
		assertEquals("tier=6", quality.signatureTerm());
		assertTrue(leggings.signature().contains("quality=tier=6"));
	}

	@Test
	void aDropWithNoTierStillKeysOnWhetherItsStatsAreMaxed() {
		DecodedItem helmet = decode("SNIPER_HELMET");
		DungeonQuality quality = helmet.dungeonQuality().orElseThrow();

		// Not every item carrying a stat boost is a floor drop, so item_tier is often simply absent.
		// The term has to survive that rather than defaulting the tier to some number.
		assertFalse(quality.hasTier());
		assertEquals(DungeonQuality.NO_TIER, quality.floorTier());

		// This one rolled 21 out of 50. Every unmaxed value measured flat on the tape - medians
		// between 48,000 and 74,000 across all of 1 to 49 - so it contributes no term at all and the
		// item keeps the key it had before any of this was read.
		assertFalse(quality.maxedStats());
		assertEquals("", quality.signatureTerm());
		assertFalse(helmet.hasQuality());
		assertFalse(helmet.signature().contains("quality="));
	}

	@Test
	void anItemThatIsNotADungeonDropCarriesNoQualityRoll() {
		assertFalse(decode("GIANTS_SWORD").hasQuality());
		assertTrue(decode("GIANTS_SWORD").dungeonQuality().isEmpty());
	}

	/** The same item wearing a different quality roll, so a test can vary that term alone. */
	private static DecodedItem withQuality(DecodedItem item, DungeonQuality quality) {
		return new DecodedItem(item.skyblockId(), item.displayName(), item.count(), item.rarity(),
				item.reforge(), item.stars(), item.recombobulated(), item.hotPotatoBooks(),
				item.enchantments(), item.gemstones(), item.attributes(), item.runes(), item.pet(),
				item.potion(), quality, item.dye(), item.ethermerged());
	}

	@Test
	void readsTheNamedDyeIntoTheSignature() {
		DecodedItem dyed = decodeQuality(10);

		assertTrue(dyed.isDyed());
		assertEquals("DYE_CHARCOAL", dyed.dye());
		assertTrue(dyed.signature().contains("dye=DYE_CHARCOAL"),
				"the dye belongs in the key: nothing else about a dyed item states it, and the "
						+ "display name does not mention it either");

		// The same chestplate wearing no dye is a different item and must key as one.
		assertFalse(decodeQuality(7).isDyed());
		assertFalse(decodeQuality(7).signature().contains("dye="));
	}

	/**
	 * The Etherwarp Conduit is one bit, and it is worth about 4x on the item that carries it.
	 *
	 * <p>The two fixture captures are the case in miniature: the same sword, the same rarity, nothing
	 * else on either of them, sold within fourteen minutes of each other for 5,894,467 and 26,399,000
	 * coins. Unread they share a key and a coarse pool, so the model quotes one number for both.
	 */
	@Test
	void readsTheEtherwarpMergeIntoTheSignature() {
		DecodedItem merged = decodeAspectOfTheVoid(true);
		DecodedItem plain = decodeAspectOfTheVoid(false);

		assertTrue(merged.ethermerged());
		assertFalse(plain.ethermerged());
		assertTrue(merged.signature().contains("ethermerge"));
		assertFalse(plain.signature().contains("ethermerge"));
		assertNotEquals(plain.signature(), merged.signature(),
				"a merged Aspect of the Void is a different item from a plain one, and the display "
						+ "name is identical, so the signature is the only thing that can say so");
	}

	/** Neither capture carries anything but the merge, which is what makes them comparable. */
	private static DecodedItem decodeAspectOfTheVoid(boolean merged) {
		return sales.stream()
				.map(sale -> ItemDecoder.decode(sale.itemBytes()))
				.flatMap(java.util.Optional::stream)
				.filter(item -> item.skyblockId().equals("ASPECT_OF_THE_VOID"))
				.filter(item -> item.ethermerged() == merged)
				.findFirst()
				.orElseThrow(() -> new AssertionError(
						"no " + (merged ? "merged" : "plain") + " Aspect of the Void in the fixture"));
	}

	/**
	 * The raw {@code color} triple is read by nothing, on purpose.
	 *
	 * <p>Measured in {@code DyeSignatureBacktestTest}: it is near-unique per sale where it is dense,
	 * so an exact colour key prices nothing, and the coarse pool it falls into today is right about
	 * it. Keying it out would drop 191 held-out coloured sales to 5 to fix 2 overvaluations. If this
	 * test ever starts failing because somebody added the term, that measurement is what to redo.
	 */
	@Test
	void leavesTheRawColourOutOfTheSignature() {
		DecodedItem coloured = decode("TARANTULA_HELMET");

		assertFalse(coloured.isDyed(), "a colour is not a dye");
		assertFalse(coloured.signature().contains("252:243:255"));
		assertFalse(coloured.signature().contains("color"));
	}

	/** The fixture holds two {@code SKELETON_MASTER_CHESTPLATE}s that differ in their tier. */
	private static DecodedItem decodeQuality(int tier) {
		return sales.stream()
				.map(sale -> ItemDecoder.decode(sale.itemBytes()))
				.flatMap(java.util.Optional::stream)
				.filter(item -> item.dungeonQuality()
						.filter(quality -> quality.floorTier() == tier).isPresent())
				.findFirst()
				.orElseThrow(() -> new AssertionError("no tier " + tier + " drop in the fixture"));
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
