package jeff.skyblockflipper.core.valuation;

import com.google.gson.Gson;

import jeff.skyblockflipper.core.item.DecodedItem;
import jeff.skyblockflipper.core.item.ItemDecoder;
import jeff.skyblockflipper.core.model.EndedAuction;
import jeff.skyblockflipper.core.model.dto.EndedAuctionsDto;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Keying#PRODUCTION} still describes items the way the model did before the seam existed.
 *
 * <p>This is a pinning test, not a behaviour test. {@code isBare} and the key ladder used to be a
 * private static and a method on {@link DecodedItem}; extracting them behind an interface could have
 * changed what ships without failing anything, because the eight tests that would have noticed are
 * opt-in and need a recorded tape. These assertions run in an ordinary build.
 *
 * <p>The keys and the bid key are checked against their original sources, which still exist. The
 * bareness clauses have no original left to check against - they moved wholesale - so they are
 * checked against the property they encode: an item is bare exactly when its signature says nothing
 * a coarse key of name and rarity could not.
 */
class KeyingTest {
	private static List<DecodedItem> items;

	@BeforeAll
	static void loadFixture() throws Exception {
		items = new ArrayList<>();

		try (InputStream in = KeyingTest.class.getResourceAsStream("/item-bytes-sample.json")) {
			for (EndedAuction sale : new Gson().fromJson(
					new InputStreamReader(in, StandardCharsets.UTF_8), EndedAuctionsDto.class).auctions) {
				ItemDecoder.decode(sale.itemBytes()).ifPresent(items::add);
			}
		}

		assertFalse(items.isEmpty(), "the fixture decoded no items");
	}

	@Test
	void productionKeysAreTheItemsOwnValuationKeys() {
		for (DecodedItem item : items) {
			assertEquals(item.valuationKeys(), Keying.PRODUCTION.keys(item),
					"PRODUCTION must price an item under the keys the item itself reports, and "
							+ item.skyblockId() + " disagreed");
		}
	}

	@Test
	void onlyABidCarryingItemHasARatioKeyAndItIsTheExactSignature() {
		for (DecodedItem item : items) {
			Optional<String> ratioKey = Keying.PRODUCTION.bidRatioKey(item);

			assertEquals(item.hasWinningBid(), ratioKey.isPresent(),
					"the ratio index applies exactly to items carrying a Dark Auction bid, and "
							+ item.skyblockId() + " disagreed");

			// No widened rung is precise enough to scale a bid by, so this must never be a later rung.
			ratioKey.ifPresent(key -> assertEquals(item.signature(), key,
					"a bid ratio must be pooled under the exact signature"));
		}
	}

	/**
	 * The thirteen clauses, stated as the property they exist to enforce.
	 *
	 * <p>An item is bare exactly when its signature carries nothing beyond the id and the rarity -
	 * which are the two things the coarse key is built from. Anything else in the signature is
	 * something name and rarity could not have known, and pricing off the coarse pool would miss it.
	 * A pet is the one item whose signature has no rarity term of its own, so it is checked directly.
	 *
	 * <p><b>One signature term has no clause behind it.</b> A reforge is in the key and not in
	 * {@code isBare}, deliberately: Hypixel writes it into the display name - "Heroic Aspect of the
	 * End" - so the coarse key of name and rarity already separates a reforged item from a plain one,
	 * the argument that also keeps runes cheap. This test used to state the property without that
	 * exception and passed anyway, because the fixture holds no reforged-but-otherwise-bare item; the
	 * same wrong rule in a backtest cost hundreds of taped sales their coarse fallback. See
	 * {@code BarenessTest}.
	 *
	 * <p><b>One clause has no signature term behind it</b>, and this test exists partly to keep saying
	 * so: a Dark Auction bid is excluded from the coarse index but is not part of the signature, so
	 * {@code MIDAS_STAFF|LEGENDARY} looks bare and is not. The exclusion is deliberate - "Midas Staff"
	 * is the display name at every bid, so the coarse pool behind that name mixes a 3,000,000 coin
	 * staff with a 100,000,000 coin one, and unlike the exact index no ratio can rescue it. The bid
	 * reaches the estimate through {@link Keying#bidRatioKey} instead.
	 */
	@Test
	void anItemIsBareExactlyWhenItsSignatureAddsNothingToNameAndRarity() {
		int bare = 0;

		for (DecodedItem item : items) {
			String withoutReforge = item.reforge().isEmpty()
					? item.signature()
					: item.signature().replace("|reforge=" + item.reforge(), "");
			boolean nothingBeyondIdAndRarity = !item.isPet()
					&& !item.hasWinningBid()
					&& withoutReforge.equals(item.skyblockId() + "|" + item.rarity().name());

			assertEquals(nothingBeyondIdAndRarity, Keying.PRODUCTION.isBare(item),
					item.skyblockId() + " has signature '" + item.signature() + "', which "
							+ (nothingBeyondIdAndRarity ? "adds nothing to" : "adds something to")
							+ " name and rarity, but isBare said "
							+ Keying.PRODUCTION.isBare(item));

			if (nothingBeyondIdAndRarity) {
				bare++;
			}
		}

		assertTrue(bare > 0, "the fixture holds no bare item, so this test proved nothing");
	}

	@Test
	void aPetIsNeverBareWhateverItsSignatureSays() {
		for (DecodedItem item : items) {
			if (item.isPet()) {
				assertFalse(Keying.PRODUCTION.isBare(item),
						"a pet pools every level behind one display name, so it must never reach the "
								+ "coarse index");
			}
		}
	}
}
