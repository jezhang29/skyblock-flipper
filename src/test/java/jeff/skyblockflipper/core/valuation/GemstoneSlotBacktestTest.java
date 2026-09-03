package jeff.skyblockflipper.core.valuation;

import jeff.skyblockflipper.core.item.DecodedItem;
import jeff.skyblockflipper.core.valuation.backtest.Backtest;
import jeff.skyblockflipper.core.valuation.backtest.CounterfactualKeying;
import jeff.skyblockflipper.core.valuation.backtest.SignatureTerms;
import jeff.skyblockflipper.core.valuation.backtest.TapeFixture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Why the gemstone unlocked-slot bit belongs in the signature, measured. Opt-in, needs a recorded
 * tape: {@code ./gradlew test -PtapeBacktest --tests '*GemstoneSlotBacktestTest'}.
 *
 * <p>It is the attribute {@code UnreadAttributeProbeTest} was blind to: {@code unlocked_slots} lives
 * inside the {@code gems} compound, which the probe marked read for its placed gems, so the settled
 * "no further shared-id-shaped gap on this tape" claim was never tested against it. It is the same
 * low-cardinality investment split that let {@code ethermerge} and {@code item_tier} through: an
 * unlocked slot costs real coins to open, and a Divan's Helmet with its slots shut is worth a
 * fraction of one with them open. Found in play 2026-09-02, quoting locked Divan pieces at ~60M and
 * flagging them as snipes.
 *
 * <p>The bug hides where a locked item is not merely bare. Two Divan pieces with identical id,
 * rarity, reforge, recomb and enchants but different slot-unlock state shared an exact signature
 * before this term, so the locked one was priced off the unlocked one's sales. Where the locked item
 * <i>is</i> otherwise bare, the second half of the same bug fired: it read bare (the placed-gem list
 * is empty) and priced off the coarse name-and-rarity pool, which holds every gemmed and unlocked
 * sale. Keying the slot bit, and making a paid-open slot non-bare, closes both.
 *
 * <p>Mirrors {@code EthermergeBacktestTest}: the term now ships, so the counterfactual arm unreads it
 * with {@link CounterfactualKeying#withoutTerm} and the keyed arm is {@link Keying#PRODUCTION}
 * itself.
 *
 * <p><b>Verdict, on the user's tape (2026-09-02):</b> it ships as one bit, not the count. On a 24h
 * holdout of the 283 ids that ever unlock a slot, keying the bit and keying the exact count were
 * indistinguishable on fake snipes (622 against 621 of 18,656 priced) and on error (median |log err|
 * 0.126, p90 0.545), and the bit kept more coverage (18,554 priced against 18,529; 229 open-slot
 * sales against 204), because intermediate slot counts (1-4) are too sparse on the tape to form a
 * pool. Against the count unread the bit takes fake snipes 632 -> 622 for 102 valuations, mostly by
 * refusing to quote the locked minority of a mixed pool rather than mispricing it.
 */
@EnabledIfSystemProperty(named = "skyblockflipper.tapeBacktest", matches = "true")
class GemstoneSlotBacktestTest {
	private static final long HOLDOUT_HOURS = 24L;

	/** Longer than any tape, so training is the unbounded replay these findings were measured on. */
	private static final Duration WHOLE_TAPE = Duration.ofDays(3650);

	/** The shipped term, a bare bit; unreading it recovers the pool as it stood before it shipped. */
	private static final String SLOTS = "slots";
	private static final String NONE = "(none)";

	/**
	 * The market, and which way its mixed pools are wrong.
	 *
	 * <p>The direction is the whole question, as it was for the merge. A pool that undervalues its
	 * open-slot sales costs nothing - an item quoted below its worth is one nobody buys - while a pool
	 * that quotes a locked item at an unlocked one's price is a fake snipe, and that is the error money
	 * is lost on. Here the locked side is the {@code (none)} variant.
	 */
	@Test
	void mixedPoolsOvervalueTheirLockedSales() throws Exception {
		Map<String, Map<String, List<Double>>> perSignature = new HashMap<>();
		Map<String, int[]> byId = new TreeMap<>();
		long[] coins = {0L};
		int[] bare = {0};

		TapeFixture.forEachSale((item, extra, timestamp, unitPrice) -> {
			if (item.unlockedSlots() > 0) {
				byId.computeIfAbsent(item.skyblockId(), k -> new int[1])[0]++;
				coins[0] += (long) unitPrice;

				if (bareApartFromSlots(item)) {
					bare[0]++;
				}
			}

			perSignature
					.computeIfAbsent(unslotted(item), k -> new TreeMap<>())
					.computeIfAbsent(item.unlockedSlots() > 0 ? SLOTS : NONE, k -> new ArrayList<>())
					.add(unitPrice);
		});

		int mixed = 0;
		int quotable = 0;
		int lockedOvervalued = 0;
		int openOvervalued = 0;
		List<String> table = new ArrayList<>();

		for (Map.Entry<String, Map<String, List<Double>>> entry : perSignature.entrySet()) {
			Map<String, List<Double>> variants = entry.getValue();

			if (variants.size() < 2 || variants.keySet().stream().noneMatch(v -> !v.equals(NONE))) {
				continue;
			}

			mixed++;
			OptionalDouble quoted = Backtest.quotableMedian(
					variants.values().stream().flatMap(List::stream).toList());

			if (quoted.isEmpty()) {
				continue;
			}

			quotable++;
			StringBuilder detail = new StringBuilder();
			double worst = 1.0d;

			for (Map.Entry<String, List<Double>> variant : variants.entrySet()) {
				double med = TapeFixture.median(variant.getValue(), 1).orElseThrow();
				double ratio = quoted.getAsDouble() / med;
				worst = Math.max(worst, Math.max(ratio, 1.0d / ratio));

				if (ratio >= 2.0d) {
					if (variant.getKey().equals(NONE)) {
						lockedOvervalued++;
					} else {
						openOvervalued++;
					}
				}

				detail.append(String.format("%s n=%d med %,.0f; ",
						variant.getKey(), variant.getValue().size(), med));
			}

			table.add(String.format("  %5.1fx  quoted %,15.0f  %-46s %s",
					worst, quoted.getAsDouble(), trim(entry.getKey()), detail));
		}

		System.out.printf("%n%,d sales carry an unlocked slot, carrying %,d coins, %,d of them "
						+ "otherwise bare; ids: %s%n",
				byId.values().stream().mapToInt(c -> c[0]).sum(), coins[0], bare[0],
				byId.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()[0]).toList());
		System.out.printf("%n%,d production signatures mix locked and unlocked sales, %,d of them "
				+ "quotable:%n", mixed, quotable);
		table.stream().sorted().forEach(System.out::println);
		System.out.printf("  quotable variants the pooled median overvalues 2x+: %,d locked, %,d open%n",
				lockedOvervalued, openOvervalued);

		// The finding. The mixed pools are wrong in the expensive direction: the locked sale is the one
		// quoted high, and a locked sale listed cheap is what a flip would be bought on.
		assertTrue(lockedOvervalued > 0, "the slot bit is only worth keying because its mixed pools "
				+ "quote locked sales above what locked sales fetch, and none did");
	}

	/**
	 * Keying the bit prices the open-slot sales, stops the fake snipes, and costs few valuations -
	 * measured against the model that ships, with the term unread as the baseline it has to beat.
	 *
	 * <p>Trains on everything older than the newest 24 hours and prices what is in it, restricted to
	 * the item ids that ever unlock a slot. Two arms: today's key with the slot bit unread, and the
	 * shipped keying.
	 */
	@Test
	void keyingTheSlotBitFixesMoreThanItCosts() throws Exception {
		Set<String> ids = idsThatEverUnlockASlot();
		long cutoff = TapeFixture.newestTimestamp() - HOLDOUT_HOURS * 3_600_000L;

		Backtest.Result pooled = Backtest.holdout(CounterfactualKeying.withoutTerm(SLOTS),
				cutoff, WHOLE_TAPE, item -> ids.contains(item.skyblockId()));
		Backtest.Result keyed = Backtest.holdout(Keying.PRODUCTION,
				cutoff, WHOLE_TAPE, item -> ids.contains(item.skyblockId()));

		System.out.printf("%n%,d item ids ever unlock a gemstone slot%n", ids.size());
		System.out.printf("held-out sales of those ids:%n  %-26s %s%n  %-26s %s%n",
				"today (slot bit unread)", pooled, "with the slot bit keyed", keyed);
		System.out.printf("  open-slot sales priced: %,d unread, %,d keyed%n",
				pooled.count(open()), keyed.count(open()));
		System.out.printf("  overvaluations by how the quote was matched:%n    unread %s%n    keyed  %s%n",
				overvaluedByBasis(pooled), overvaluedByBasis(keyed));

		// The finding: keying the slot bit removes fake snipes.
		assertTrue(keyed.overvaluedBy(2.0d) < pooled.overvaluedBy(2.0d), "keying the slot bit is "
				+ "expected to remove fake snipes, but overvaluations went from " + pooled.overvaluedBy(2.0d)
				+ " to " + keyed.overvaluedBy(2.0d));

		// Coverage is the price of every signature term, and the bit is expected to be cheap.
		assertTrue(keyed.priced().size() * 10 > pooled.priced().size() * 9, "the slot bit is expected "
				+ "to cost few valuations, but priced sales went from " + pooled.priced().size() + " to "
				+ keyed.priced().size());
	}

	/** Which item ids the question is even about: a separate streamed pass, so no day sits in memory. */
	private static Set<String> idsThatEverUnlockASlot() throws Exception {
		Set<String> ids = new HashSet<>();

		TapeFixture.forEachSale((item, extra, timestamp, unitPrice) -> {
			if (item.unlockedSlots() > 0) {
				ids.add(item.skyblockId());
			}
		});

		assertTrue(!ids.isEmpty(), "no unlocked-slot sales on the tape at " + TapeFixture.tapeDir());
		return ids;
	}

	private static Predicate<DecodedItem> open() {
		return item -> item.unlockedSlots() > 0;
	}

	/** The signature this item has with the slot bit unread, which is the baseline being measured. */
	private static String unslotted(DecodedItem item) {
		return SignatureTerms.without(item.signature(), SLOTS);
	}

	/** Bare once the bit is unread - the sales that would join the coarse pool of locked ones. */
	private static boolean bareApartFromSlots(DecodedItem item) {
		return CounterfactualKeying.withoutTerm(SLOTS).isBare(item);
	}

	/**
	 * Which index produced each fake snipe - the coarse index pools on name and rarity alone, so a run
	 * whose overvaluations are mostly {@code COARSE} is saying the fallback pool is contaminated, which
	 * is the second half of this bug rather than the exact-key half.
	 */
	private static Map<ValueEstimate.Basis, Long> overvaluedByBasis(Backtest.Result result) {
		return result.priced().stream()
				.filter(priced -> priced.overvaluedBy(2.0d))
				.collect(java.util.stream.Collectors.groupingBy(Backtest.Priced::basis,
						TreeMap::new, java.util.stream.Collectors.counting()));
	}

	private static String trim(String key) {
		return key.length() <= 46 ? key : key.substring(0, 43) + "...";
	}
}
