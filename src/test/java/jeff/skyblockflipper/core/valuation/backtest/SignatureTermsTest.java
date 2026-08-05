package jeff.skyblockflipper.core.valuation.backtest;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The signature surgery the counterfactual arms rest on.
 *
 * <p>Worth its own test because the code it replaces had none. Three backtests stripped a term with
 * {@code replaceFirst} and a lookahead, and a wrong strip does not fail - it produces a key that
 * pools the wrong sales and a measurement that looks fine.
 */
class SignatureTermsTest {
	private static final String BARE = "ASPECT_OF_THE_VOID|LEGENDARY";
	private static final String MERGED = "ASPECT_OF_THE_VOID|LEGENDARY|ethermerge";
	private static final String LOADED =
			"ASPECT_OF_THE_VOID|LEGENDARY|stars=5|recomb|ench=sharpness:6|ethermerge|dye=SPOOK";

	@Test
	void dropsAWholeTermWhereverItSits() {
		assertEquals(BARE, SignatureTerms.without(MERGED, "ethermerge"));
		assertEquals("ASPECT_OF_THE_VOID|LEGENDARY|stars=5|recomb|ench=sharpness:6|dye=SPOOK",
				SignatureTerms.without(LOADED, "ethermerge"));
	}

	@Test
	void dropsAValueCarryingTermByItsPrefix() {
		assertEquals("ASPECT_OF_THE_VOID|LEGENDARY|stars=5|recomb|ench=sharpness:6|ethermerge",
				SignatureTerms.without(LOADED, "dye="));
	}

	@Test
	void leavesASignatureWithoutTheTermAlone() {
		assertEquals(BARE, SignatureTerms.without(BARE, "ethermerge"));
		assertEquals(BARE, SignatureTerms.without(BARE, "dye="));
	}

	/**
	 * The trap the {@code replaceFirst} version was one term away from. A prefix strip must not eat a
	 * term that merely starts with the same letters.
	 */
	@Test
	void doesNotConfuseOneTermForAnotherThatStartsTheSameWay() {
		String signature = "SOME_ITEM|RARE|stars=5|starsAndStripes";

		assertEquals("SOME_ITEM|RARE|starsAndStripes", SignatureTerms.without(signature, "stars="));
		assertEquals("SOME_ITEM|RARE|stars=5", SignatureTerms.without(signature, "starsAndStripes"));
		assertEquals(signature, SignatureTerms.without(signature, "ethermerge"));
	}

	@Test
	void appendsATermAndIgnoresAnEmptyOne() {
		assertEquals(MERGED + "|tuned=4", SignatureTerms.plus(MERGED, "tuned=4"));
		assertEquals(MERGED, SignatureTerms.plus(MERGED, ""));
		assertEquals(MERGED, SignatureTerms.plus(MERGED, null));
	}

	@Test
	void reportsWhetherATermIsCarried() {
		assertTrue(SignatureTerms.carries(LOADED, "ethermerge"));
		assertTrue(SignatureTerms.carries(LOADED, "dye="));
		assertFalse(SignatureTerms.carries(BARE, "ethermerge"));
		assertFalse(SignatureTerms.carries(MERGED, "dye="));
	}

	/** Stripping every term of a fully-loaded item must leave exactly the coarse key's two fields. */
	@Test
	void strippingEveryTermLeavesIdAndRarity() {
		String stripped = LOADED;

		for (String term : new String[] {"stars=", "recomb", "ench=", "ethermerge", "dye="}) {
			stripped = SignatureTerms.without(stripped, term);
		}

		assertEquals(BARE, stripped);
	}
}
