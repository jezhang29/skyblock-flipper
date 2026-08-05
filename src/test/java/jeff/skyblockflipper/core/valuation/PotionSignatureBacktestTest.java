package jeff.skyblockflipper.core.valuation;

import jeff.skyblockflipper.core.item.DecodedItem;
import jeff.skyblockflipper.core.valuation.backtest.Backtest;
import jeff.skyblockflipper.core.valuation.backtest.CounterfactualKeying;
import jeff.skyblockflipper.core.valuation.backtest.TapeFixture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What reading the potion behind {@code POTION} is worth, measured. Opt-in, needs a recorded tape:
 * {@code ./gradlew test -PtapeBacktest --tests '*PotionSignatureBacktestTest'}.
 *
 * <p>The third market to hide behind a shared item id, after {@code PET} and {@code RUNE}, and the
 * most expensive of the three: 2,758 BIN sales worth 2.1 billion coins in two days, all keyed as
 * {@code POTION|<rarity>}. Because Stinky Cheese and Harvest Harbinger are 70% of the count, that
 * one median sat at 918,000 coins, and every cheap potion in the game - splash Healing at 25,000,
 * Combat XP Boost at 500 - read as a large discount on itself. That is not a noisy valuation, it is
 * a generator of fake snipes, which is the same thing runes were.
 *
 * <p>Re-measured against the model that ships rather than the hand-built pair of maps the finding was
 * first taken on. The counterfactual arm unreads the {@code potion=} term, which also lets a potion
 * back into the coarse index - the state the model was actually in before this shipped, since
 * {@code isBare}'s potion clause and the signature term arrived together.
 *
 * <p>On a 24h holdout of every potion sale: fake snipes 454 pooled against 62 keyed, p90 |log err|
 * 3.534 against 0.561, median 0.152 against 0.118, for 60 valuations in 1,570.
 */
@EnabledIfSystemProperty(named = "skyblockflipper.tapeBacktest", matches = "true")
class PotionSignatureBacktestTest {
	private static final long HOLDOUT_HOURS = 24L;

	/** Longer than any tape, so training is the unbounded replay these findings were measured on. */
	private static final Duration WHOLE_TAPE = Duration.ofDays(3650);

	private static final String POTION = "potion=";

	@Test
	void readingThePotionMakesPotionsPriceable() throws Exception {
		long cutoff = TapeFixture.newestTimestamp() - HOLDOUT_HOURS * 3_600_000L;

		Backtest.Result pooled = Backtest.holdout(CounterfactualKeying.withoutTerm(POTION),
				cutoff, WHOLE_TAPE, DecodedItem::isPotion);
		Backtest.Result keyed = Backtest.holdout(Keying.PRODUCTION,
				cutoff, WHOLE_TAPE, DecodedItem::isPotion);

		System.out.printf("%nheld-out potion sales:%n  %-26s %s%n  %-26s %s%n",
				"keyed on rarity alone", pooled, "keyed on the potion", keyed);

		// Deliberately not asserted on the median error. The median sale is a Stinky Cheese or a
		// Harvest Harbinger - together 70% of the count - and both sit near the pooled median, so
		// pooling scores *better* there by being accidentally right about the two items that drown
		// out the rest. The damage is entirely in the tail, and the tail is what this model is for:
		// a valuation is only ever acted on when it sits far above the asking price, so an
		// overvaluation is a flip taken and an undervaluation is one skipped.
		assertTrue(keyed.overvaluedBy(2.0d) * 4 < pooled.overvaluedBy(2.0d),
				"pooling every potion into one median should invent far more fake snipes than "
						+ "keying on the potion, but it went from " + pooled.overvaluedBy(2.0d)
						+ " to " + keyed.overvaluedBy(2.0d));

		assertTrue(keyed.p90LogError() < pooled.p90LogError() / 2.0d, "reading the potion should at "
				+ "least halve the p90 error, but it went from " + pooled.p90LogError() + " to "
				+ keyed.p90LogError());
	}
}
