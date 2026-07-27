package jeff.skyblockflipper.core.strategy;

import jeff.skyblockflipper.core.item.DecodedItem;
import jeff.skyblockflipper.core.item.Rarity;
import jeff.skyblockflipper.core.model.ActiveListing;
import jeff.skyblockflipper.core.model.BazaarProduct;
import jeff.skyblockflipper.core.model.BazaarSnapshot;
import jeff.skyblockflipper.core.model.ItemCatalog;
import jeff.skyblockflipper.core.model.OrderLevel;
import jeff.skyblockflipper.core.model.UpgradeCost;
import jeff.skyblockflipper.core.pricing.Fees;
import jeff.skyblockflipper.core.valuation.PricedListing;
import jeff.skyblockflipper.core.valuation.ValueEstimate;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Auction flips are the only candidates resting on an estimate rather than on a live order book,
 * so most of this is about the strategy refusing to pretend the estimate is better than it is.
 */
class AuctionValueStrategyTest {
	private static final long BANKROLL = 100_000_000L;

	private static DecodedItem item() {
		return new DecodedItem("MIDAS_SWORD", "Midas Sword", 1, Rarity.LEGENDARY, "", 0, false, 0,
				Map.of(), List.of(), Map.of(), null);
	}

	private static PricedListing priced(long price, double median, int samples, double salesPerHour,
			boolean exact) {
		return new PricedListing(
				new ActiveListing("uuid", "Midas Sword", Rarity.LEGENDARY, price, ""),
				item(),
				new ValueEstimate("key", median, samples, 0.05d, salesPerHour, exact));
	}

	private static StrategyContext context(List<PricedListing> listings, double minConfidence,
			long minProfit, boolean derpy) {
		return new StrategyContext(BazaarSnapshot.empty(), ItemCatalog.empty(), listings,
				new Fees(0, derpy), BANKROLL, minProfit, minConfidence);
	}

	private static List<FlipCandidate> candidates(StrategyContext context) {
		return new AuctionValueStrategy().findCandidates(context);
	}

	/** Arack's real ladder against a spider-essence book asking 1510: 15+25+35 = 113250 coins. */
	private static StrategyContext starContext(long price, long median) {
		DecodedItem starred = new DecodedItem("ARACK", "Arack", 1, Rarity.EPIC, "", 3, false, 0,
				Map.of(), List.of(), Map.of(), null);

		PricedListing listing = new PricedListing(
				new ActiveListing("uuid", "Arack ✪✪✪", Rarity.EPIC, price, ""),
				starred,
				new ValueEstimate("key", median, 30, 0.05d, 1.0d, true));

		ItemCatalog catalog = new ItemCatalog(Map.of("ARACK", new ItemCatalog.Entry(
				"ARACK", "Arack", 5_000.0d, List.of(
						new UpgradeCost(List.of(new UpgradeCost.Ingredient("ESSENCE_SPIDER", 15))),
						new UpgradeCost(List.of(new UpgradeCost.Ingredient("ESSENCE_SPIDER", 25))),
						new UpgradeCost(List.of(new UpgradeCost.Ingredient("ESSENCE_SPIDER", 35))),
						new UpgradeCost(List.of(new UpgradeCost.Ingredient("ESSENCE_SPIDER", 45))),
						new UpgradeCost(List.of(new UpgradeCost.Ingredient("ESSENCE_SPIDER", 65)))))));

		BazaarSnapshot bazaar = new BazaarSnapshot(Instant.now(), Map.of(
				"ESSENCE_SPIDER", new BazaarProduct("ESSENCE_SPIDER",
						List.of(new OrderLevel(1_510.0d, 100_000L, 4)),
						List.of(new OrderLevel(1_443.3d, 100_000L, 4)),
						new BazaarProduct.MovingWeek(7_652_536L, 7_652_536L))));

		return new StrategyContext(bazaar, catalog, List.of(listing), new Fees(0, false),
				BANKROLL, 0L, 0.0d);
	}

	@Test
	void quotesWhatTheStarsOnAnItemCostInEssence() {
		FlipCandidate candidate = candidates(starContext(6_000_000L, 10_000_000L)).getFirst();

		assertTrue(candidate.notes().stream().anyMatch(n -> n.startsWith("3 stars cost 113.3k")),
				"expected the star bill on the candidate, got " + candidate.notes());

		// 113250 against a 6M listing is under 2%, so the item is what is being bought here.
		assertTrue(candidate.risks().stream().noneMatch(r -> r.contains("essence book")),
				"a barely-starred item should not be called essence-heavy: " + candidate.risks());
	}

	@Test
	void warnsWhenTheAskingPriceIsMostlyEssence() {
		// 113250 of stars inside a 200k listing: the stars are the item, and they revalue with the
		// essence book rather than with the sword.
		FlipCandidate candidate = candidates(starContext(200_000L, 400_000L)).getFirst();

		assertTrue(candidate.risks().stream().anyMatch(r -> r.contains("essence book")),
				"expected an essence-heavy warning, got " + candidate.risks());
	}

	@Test
	void saysNothingAboutStarsOnAnItemThatHasNone() {
		FlipCandidate candidate = candidates(context(
				List.of(priced(6_000_000L, 10_000_000L, 30, 1.0d, true)), 0.0d, 0L, false))
				.getFirst();

		assertTrue(candidate.notes().isEmpty(),
				"a bare item has no star bill to quote: " + candidate.notes());
	}

	@Test
	void proposesBuyingBelowFairValueAndReportsProfitAfterAuctionFees() {
		FlipCandidate candidate = candidates(context(
				List.of(priced(6_000_000L, 10_000_000L, 30, 1.0d, true)), 0.0d, 0L, false))
				.getFirst();

		assertEquals(StrategyKind.AUCTION_VALUE, candidate.kind());
		assertEquals(6_000_000L, candidate.capitalRequired());

		// Resale at fair value pays the 2% listing fee on a 10M BIN and the 1% claim tax.
		assertEquals(10_000_000L - 200_000L - 100_000L - 6_000_000L, candidate.unitNetProfit(), 1e-6);
	}

	@Test
	void refusesCandidatesBelowTheConfidenceFloor() {
		// Six sales that agree is a usable estimate but not a confident one, and this is the only
		// strategy where a wrong valuation is the whole risk.
		List<PricedListing> thin = List.of(priced(6_000_000L, 10_000_000L, 6, 1.0d, true));

		assertTrue(candidates(context(thin, 0.9d, 0L, false)).isEmpty());
		assertTrue(candidates(context(thin, 0.0d, 0L, false)).size() == 1);
	}

	@Test
	void ranksBySpeedOfResaleRatherThanBySizeOfDiscount() {
		// Same profit, but one configuration sells hourly and the other twice a week. Ranking on
		// the discount alone would park the bankroll in the slow one.
		List<FlipCandidate> found = candidates(context(List.of(
				priced(6_000_000L, 10_000_000L, 30, 0.01d, true),
				priced(6_000_000L, 10_000_000L, 30, 4.0d, true)), 0.0d, 0L, false));

		assertEquals(2, found.size());
		assertTrue(found.getFirst().profitPerHour() > found.getLast().profitPerHour());
		assertEquals(found.getFirst().totalNetProfit(), found.getLast().totalNetProfit(), 1e-6);
	}

	@Test
	void derpyQuadruplesTheFeesAndCanKillTheFlipOutright() {
		List<PricedListing> marginal = List.of(priced(9_400_000L, 10_000_000L, 30, 1.0d, true));

		assertTrue(candidates(context(marginal, 0.0d, 1L, false)).size() == 1);
		// 4x listing fee and claim tax turn a 300k edge into a loss.
		assertTrue(candidates(context(marginal, 0.0d, 1L, true)).isEmpty());
	}

	@Test
	void skipsListingsBeyondTheBankroll() {
		assertTrue(candidates(context(
				List.of(priced(BANKROLL + 1L, 500_000_000L, 30, 1.0d, true)), 0.0d, 0L, false))
				.isEmpty());
	}

	@Test
	void respectsTheMinimumProfitPerFlip() {
		assertTrue(candidates(context(
				List.of(priced(9_800_000L, 10_000_000L, 30, 1.0d, true)), 0.0d, 1_000_000L, false))
				.isEmpty());
	}

	@Test
	void everyCandidateSaysItCannotGuaranteeTheListingIsStillThere() {
		FlipCandidate candidate = candidates(context(
				List.of(priced(6_000_000L, 10_000_000L, 30, 1.0d, true)), 0.0d, 0L, false))
				.getFirst();

		assertTrue(candidate.risks().stream().anyMatch(risk -> risk.contains("does not buy for you")),
				"a snipe the mod cannot execute must say so: " + candidate.risks());
		// And the steps have to tell a human how to verify they are buying the right item.
		assertTrue(candidate.steps().stream().anyMatch(step -> step.startsWith("Check the item")));
	}

	@Test
	void warnsWhenTheValuationIsCoarseOrThinlyEvidenced() {
		FlipCandidate coarse = candidates(context(
				List.of(priced(4_000_000L, 10_000_000L, 8, 1.0d, false)), 0.0d, 0L, false))
				.getFirst();

		assertTrue(coarse.risks().stream().anyMatch(risk -> risk.contains("name and rarity only")));
		assertTrue(coarse.risks().stream().anyMatch(risk -> risk.contains("comparable sales")));
	}

	@Test
	void warnsWhenCoinsWouldBeParkedForAges() {
		FlipCandidate slow = candidates(context(
				List.of(priced(4_000_000L, 10_000_000L, 30, 0.02d, true)), 0.0d, 0L, false))
				.getFirst();

		assertTrue(slow.risks().stream().anyMatch(risk -> risk.contains("parked")));
	}
}
