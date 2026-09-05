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
package jeff.skyblockflipper.core.strategy;

import jeff.skyblockflipper.core.item.DecodedItem;
import jeff.skyblockflipper.core.item.Rarity;
import jeff.skyblockflipper.core.model.BazaarSnapshot;
import jeff.skyblockflipper.core.model.ItemCatalog;
import jeff.skyblockflipper.core.model.TimedListing;
import jeff.skyblockflipper.core.pricing.Fees;
import jeff.skyblockflipper.core.text.Coins;
import jeff.skyblockflipper.core.valuation.PricedBid;
import jeff.skyblockflipper.core.valuation.ValueEstimate;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bid strategy is the sniper's twin on timed auctions, so these pin the two things that make it
 * different: it drops contested auctions (their margin is gone), and it states an exact bid ceiling.
 */
class AuctionBidStrategyTest {
	private static final long BANKROLL = 100_000_000L;
	private static final Instant NOW = Instant.parse("2026-09-05T00:00:00Z");

	private static DecodedItem item() {
		return new DecodedItem("MIDAS_SWORD", "Midas Sword", 1, Rarity.LEGENDARY, "", 0, false, 0,
				Map.of(), List.of(), Map.of(), Map.of(), null, null, null, List.of(), "", false, 0L, 0);
	}

	private static PricedBid bid(long startingBid, long highestBid, long endMillis, double median,
			ValueEstimate.Basis basis) {
		return new PricedBid(
				new TimedListing("uuid", startingBid, highestBid, endMillis, ""),
				item(),
				new ValueEstimate("key", median, 30, 0.05d, 1.0d, basis));
	}

	private static StrategyContext context(List<PricedBid> bids, double minConfidence, long minProfit) {
		return new StrategyContext(BazaarSnapshot.empty(), ItemCatalog.empty(), List.of(),
				new Fees(0, false), BANKROLL, minProfit, minConfidence).withPricedBids(bids);
	}

	private static List<FlipCandidate> candidates(StrategyContext context) {
		return new AuctionBidStrategy(() -> NOW).findCandidates(context);
	}

	private static long endIn(long minutes) {
		return NOW.plusSeconds(minutes * 60L).toEpochMilli();
	}

	@Test
	void surfacesAnUncontestedCheapAuctionWithItsBidCeiling() {
		PricedBid cheap = bid(6_000_000L, 0L, endIn(30), 10_000_000L, ValueEstimate.Basis.EXACT);

		FlipCandidate candidate = candidates(context(List.of(cheap), 0.0d, 0L)).getFirst();

		assertEquals(StrategyKind.AUCTION_BID, candidate.kind());
		// Uncontested: the bid to win is the starting bid.
		assertEquals(6_000_000.0d, candidate.unitBuyPrice());

		long ceiling = new Fees(0, false).binNetProceeds(10_000_000L);
		assertTrue(candidate.steps().stream().anyMatch(s ->
						s.contains("Bid up to " + Coins.format(ceiling))),
				"expected the exact bid ceiling in the steps, got " + candidate.steps());
	}

	@Test
	void dropsContestedAuctions() {
		// Same item, but a rival has already bid it up off the opening price.
		PricedBid contested = bid(6_000_000L, 6_500_000L, endIn(30), 10_000_000L,
				ValueEstimate.Basis.EXACT);

		assertTrue(candidates(context(List.of(contested), 0.0d, 0L)).isEmpty(),
				"a contested auction has given up its margin and must not be surfaced");
	}

	@Test
	void refusesToPriceInsideTheFinalMinutes() {
		PricedBid ending = bid(6_000_000L, 0L, endIn(1), 10_000_000L, ValueEstimate.Basis.EXACT);

		FlipCandidate candidate = candidates(context(List.of(ending), 0.0d, 0L)).getFirst();

		assertTrue(candidate.risks().stream().anyMatch(r -> r.contains("final 2 minutes")),
				"an auction in its last rounds must warn to read the live menu, got " + candidate.risks());
	}

	@Test
	void quarantinesADeepDiscountOnADearItem() {
		// 2M bid against a 30M median is a 93% discount - likelier a hidden upgrade than a bargain.
		PricedBid suspect = bid(2_000_000L, 0L, endIn(30), 30_000_000L, ValueEstimate.Basis.EXACT);

		FlipCandidate candidate = candidates(context(List.of(suspect), 0.0d, 0L)).getFirst();

		assertTrue(candidate.suspect(), "a deep discount on a dear item should be quarantined");
		assertTrue(candidate.risks().stream().anyMatch(r -> r.contains("hidden upgrade")),
				"the suspect risk should explain why, got " + candidate.risks());
	}

	@Test
	void skipsAThinDiscountBelowTheProfitFloor() {
		// A 9.9M bid against a 10M median cannot clear a 1M profit floor after fees.
		PricedBid thin = bid(9_900_000L, 0L, endIn(30), 10_000_000L, ValueEstimate.Basis.EXACT);

		assertTrue(candidates(context(List.of(thin), 0.0d, 1_000_000L)).isEmpty());
	}

	@Test
	void appliesTheConfidenceFloor() {
		PricedBid cheap = bid(6_000_000L, 0L, endIn(30), 10_000_000L, ValueEstimate.Basis.EXACT);
		double confidence = cheap.value().confidence();

		assertFalse(candidates(context(List.of(cheap), 0.0d, 0L)).isEmpty(),
				"a zero floor should let the estimate through");
		assertTrue(candidates(context(List.of(cheap), confidence + 0.01d, 0L)).isEmpty(),
				"a floor above the estimate's confidence should reject it");
	}

	@Test
	void surfacesAOneBidderAtFloorAtOneIncrementOverIt() {
		// highest_bid == starting_bid means exactly one rival has bid at the floor - not contested
		// (contested() is hb>start), so it surfaces, but the winning bid steps one increment over it.
		PricedBid loneBid = bid(6_000_000L, 6_000_000L, endIn(30), 10_000_000L,
				ValueEstimate.Basis.EXACT);

		List<FlipCandidate> candidates = candidates(context(List.of(loneBid), 0.0d, 0L));

		assertFalse(candidates.isEmpty(), "a lone bid at the floor is winnable one step up, not dropped");
		// ceil(6,000,000 x 1.025) = 6,150,000, never the tying bid Hypixel rejects.
		assertEquals(6_150_000.0d, candidates.getFirst().unitBuyPrice());
	}

	@Test
	void capsTheBidCeilingAtMaxCapitalPerFlip() {
		// A 60M bid against a 110M median: discount ~45% (not the >=60% suspect gate), and
		// binNetProceeds(110M) clears 100M, so the uncapped ceiling would exceed the per-flip limit.
		PricedBid dear = bid(60_000_000L, 0L, endIn(30), 110_000_000L, ValueEstimate.Basis.EXACT);

		FlipCandidate candidate = candidates(context(List.of(dear), 0.0d, 0L)).getFirst();

		long resaleNet = new Fees(0, false).binNetProceeds(110_000_000L);
		// Document that the cap actually bites: without it the ceiling would be this larger value.
		assertTrue(resaleNet > BANKROLL, "test only exercises the cap if net proceeds exceed the limit");
		assertTrue(candidate.steps().stream().anyMatch(s ->
						s.contains("Bid up to " + Coins.format(BANKROLL))),
				"the ceiling must be capped at maxCapitalPerFlip, got " + candidate.steps());
	}

	@Test
	void subtractsANonZeroProfitFloorExactlyWhenUncapped() {
		PricedBid cheap = bid(6_000_000L, 0L, endIn(30), 10_000_000L, ValueEstimate.Basis.EXACT);

		FlipCandidate candidate = candidates(context(List.of(cheap), 0.0d, 1_000_000L)).getFirst();

		// Well under maxCapitalPerFlip, so the ceiling is binNetProceeds(resale) minus the floor exactly.
		long ceiling = new Fees(0, false).binNetProceeds(10_000_000L) - 1_000_000L;
		assertTrue(candidate.steps().stream().anyMatch(s ->
						s.contains("Bid up to " + Coins.format(ceiling))),
				"the ceiling must subtract the profit floor exactly, got " + candidate.steps());
	}
}
