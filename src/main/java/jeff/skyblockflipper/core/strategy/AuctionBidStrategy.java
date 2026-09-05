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

import jeff.skyblockflipper.core.text.Coins;
import jeff.skyblockflipper.core.valuation.Bids;
import jeff.skyblockflipper.core.valuation.PricedBid;
import jeff.skyblockflipper.core.valuation.ValueEstimate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Bidding on a timed auction ending soon, to win it below its BIN value (docs/auction-bidding-plan.md,
 * Phase 1).
 *
 * <p>The twin of {@link AuctionValueStrategy}: the edge is the same mispricing, but the item is
 * behind an auction the player must win rather than a buy-it-now they can take instantly. Two things
 * follow from that and drive the whole class.
 *
 * <p><b>The bid ceiling is the point.</b> Hypixel's 2.5% minimum increment makes the "bid up to X, no
 * higher" number exact, and enforcing it is the single most useful thing the mod can do here - a
 * bidding war otherwise walks the price up to fair value through the anti-snipe timer and eats the
 * margin. Every candidate leads with that ceiling.
 *
 * <p><b>Only uncontested listings carry surplus.</b> Once a rival has bid, the anti-snipe timer
 * ratchets the price towards the second-highest bidder's max, so a contested auction has already
 * given its margin away. Those are dropped rather than ranked; what survives is auctions nobody else
 * has bid on, winnable at the starting bid by simple presence at {@code end}.
 *
 * <p>Resale truth is the BIN median, never the timed-sale price, for the reason the sniper resells at
 * BIN medians: a timed price is the very thing being called cheap.
 */
public final class AuctionBidStrategy implements FlipStrategy {
	/** Mirrors {@link AuctionValueStrategy}: past this discount on a dear item, quarantine not rank. */
	private static final double SUSPECT_MIN_DISCOUNT = 0.60d;
	private static final long SUSPECT_MIN_VALUE = 25_000_000L;

	/**
	 * Inside this band before {@code end}, the auction is in its final rounds and the mod refuses to
	 * quote a paper profit off 60-second-stale data - it says to check the live menu instead.
	 */
	private static final double FINAL_MINUTES = 2.0d / 60.0d;

	private final Supplier<Instant> clock;

	public AuctionBidStrategy() {
		this(Instant::now);
	}

	/** For tests that need a fixed clock, since the end-window checks read the wall clock. */
	AuctionBidStrategy(Supplier<Instant> clock) {
		this.clock = clock;
	}

	@Override
	public StrategyKind kind() {
		return StrategyKind.AUCTION_BID;
	}

	@Override
	public List<FlipCandidate> findCandidates(StrategyContext context) {
		Instant now = clock.get();
		List<FlipCandidate> candidates = new ArrayList<>();

		for (PricedBid bid : context.pricedBids()) {
			evaluate(bid, context, now).ifPresent(candidates::add);
		}

		candidates.sort(null);
		return candidates;
	}

	private Optional<FlipCandidate> evaluate(PricedBid bid, StrategyContext context, Instant now) {
		// A contested auction has already surrendered its margin to the anti-snipe timer.
		if (bid.contested()) {
			return Optional.empty();
		}

		long toWin = bid.bidToWin();
		ValueEstimate value = bid.value();

		if (toWin > context.maxCapitalPerFlip()) {
			return Optional.empty();
		}

		if (value.confidence() < context.minConfidence()) {
			return Optional.empty();
		}

		long resale = Math.round(value.median());
		long net = context.fees().binRoundTripProfit(toWin, resale);

		if (net < context.minProfitPerFlip()) {
			return Optional.empty();
		}

		// The bid ceiling: fees land only on the resale leg and the bid is paid exactly, so the
		// highest bid that still clears the floor is an exact number, not a fitted one. Cap it at the
		// player's per-flip limit so a bidding war can never walk the advice past their own bankroll.
		long ceiling = Math.min(
				context.fees().binNetProceeds(resale) - context.minProfitPerFlip(),
				context.maxCapitalPerFlip());

		double hours = value.hoursToSell();
		boolean suspect = bid.discount() >= SUSPECT_MIN_DISCOUNT && value.median() >= SUSPECT_MIN_VALUE;

		FlipCandidate candidate = new FlipCandidate(
				bid.item().skyblockId(),
				bid.item().displayName(),
				kind(),
				toWin,
				resale,
				net,
				1L,
				toWin,
				net / hours,
				value.confidence(),
				steps(bid, ceiling, resale, now),
				risks(bid, hours, suspect, now),
				List.of());

		return Optional.of(suspect ? candidate.asSuspect() : candidate);
	}

	private List<String> steps(PricedBid bid, long ceiling, long resale, Instant now) {
		List<String> steps = new ArrayList<>();

		steps.add("Auction House -> Ending Soon -> search \"" + bid.item().displayName() + "\"");
		steps.add(String.format("Ends in about %.0f min; the winning bid is decided in the last 2 "
				+ "minutes (each late bid resets the clock to 2:00)", bid.hoursLeft(now) * 60.0d));
		steps.add("Bid " + Coins.format(bid.bidToWin()) + " to take the lead ("
				+ Math.round(bid.discount() * 100.0d) + "% under fair value)");
		steps.add("Bid up to " + Coins.format(ceiling) + " and no higher - past that the flip does "
				+ "not clear its fee floor, so walk away");
		steps.add("If you win, relist at about " + Coins.format(resale) + ", the median of "
				+ bid.value().samples() + " recent BIN sales");

		return steps;
	}

	private List<String> risks(PricedBid bid, double hours, boolean suspect, Instant now) {
		List<String> risks = new ArrayList<>();

		if (suspect) {
			risks.add(String.format("A %.0f%% discount on an item worth %s is more often a hidden "
					+ "upgrade the pricing cannot see than a bargain - check every gemstone slot, "
					+ "scroll and attribute before bidding",
					bid.discount() * 100.0d, Coins.format(Math.round(bid.value().median()))));
		}

		if (bid.hoursLeft(now) <= FINAL_MINUTES) {
			risks.add("This auction is in its final 2 minutes - do not trust a 60-second-stale "
					+ "price, open the live menu and read the current top bid before bidding");
		}

		// Always true, and the honest reason a cheap auction is still open: someone may bid it up,
		// or take it, before a human reading a line reaches the menu.
		risks.add("The mod does not bid for you; another bidder may lift this past your ceiling, and "
				+ "your coins are escrowed while you lead. The per-hour figure counts only the "
				+ "resale wait, not the hours until this auction ends");

		switch (bid.value().basis()) {
			case COARSE -> risks.add(
					"Priced from name and rarity only - verify nothing was added to this item");
			case BANDED -> risks.add("Priced from a band, not this exact configuration");
			case EXACT -> { }
		}

		if (bid.value().samples() < 12) {
			risks.add("Only " + bid.value().samples() + " comparable BIN sales back this valuation");
		}

		if (hours >= 6.0d) {
			risks.add(String.format("This configuration resells about once every %.0f hours; "
					+ "your coins are parked until it does", hours));
		}

		if (bid.value().dispersion() > 0.4d) {
			risks.add("Comparable sales disagree by more than 40%, so the median is a weak guide");
		}

		return risks;
	}

	/** The minimum increment, exposed so callers can explain the ceiling arithmetic. */
	public static double minIncrement() {
		return Bids.MIN_INCREMENT;
	}
}
