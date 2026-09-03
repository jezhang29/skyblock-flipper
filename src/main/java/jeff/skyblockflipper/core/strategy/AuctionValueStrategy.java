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
import jeff.skyblockflipper.core.pricing.UpgradePricing;
import jeff.skyblockflipper.core.text.Coins;
import jeff.skyblockflipper.core.valuation.PricedListing;
import jeff.skyblockflipper.core.valuation.ValueEstimate;
import jeff.skyblockflipper.core.valuation.WitherBladeValuationContainment;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Buying a listing for less than its exact configuration has been selling for.
 *
 * <p>The edge is valuation: someone listed an item without knowing, or without caring, what its
 * enchantments and stars are worth. Unlike the bazaar strategies, the risk here is not that the
 * spread closes - it is that the valuation is wrong, which is why nothing reaches this class until
 * it has been matched against realized sales of the same signature.
 *
 * <p>What this cannot do is guarantee the listing is still there.
 * By the time a human reads a line and opens the auction house, a genuine underprice may
 * well have been taken by bots. That is stated on every candidate rather than implied away.
 *
 * <p>Resale time comes from the observed sale rate of that configuration rather than from
 * optimism. Profit per hour on an item that sells twice a week is not the same business as the
 * same profit on one that sells hourly, and ranking them together without that division is how a
 * tool talks you into parking your bankroll in something illiquid.
 */
public final class AuctionValueStrategy implements FlipStrategy {
	/** Above this share of the price coming from star ingredients, it is essence you are buying. */
	private static final double ESSENCE_HEAVY = 0.3d;

	/**
	 * Past this discount on a {@link #SUSPECT_MIN_VALUE high-value} item, a flag is quarantined rather
	 * than ranked: a bargain that deep is likelier a hidden upgrade the signature does not read than a
	 * seller's mistake.
	 *
	 * <p>0.60 is the knee measured on the realized-P&L backtest ({@code SnipeProfitBacktestTest}).
	 * Resold at the concurrent market median, the 0.25-0.60 band is where the sniper's edge lives;
	 * past 0.60 the "discount" stops being one the resale median can vouch for, which is exactly where
	 * a signature miss hides - the gemstone slot found in play, and the live Hyperion-scroll alarm,
	 * both surface here. Ranking on profit floats them to the very top, because a wrong-high quote is
	 * the largest quote on the book, so the deep end has to be demoted by shape rather than by number.
	 */
	private static final double SUSPECT_MIN_DISCOUNT = 0.60d;

	/**
	 * The value above which a {@link #SUSPECT_MIN_DISCOUNT deep} discount is worth demoting over. Below
	 * it a hidden upgrade is cheap to verify and cheap to be wrong about; the confirmed misses - a
	 * locked Divan's piece around 60M, a plain Hyperion at 500M+ - clear it with room to spare.
	 */
	private static final long SUSPECT_MIN_VALUE = 25_000_000L;

	@Override
	public StrategyKind kind() {
		return StrategyKind.AUCTION_VALUE;
	}

	@Override
	public List<FlipCandidate> findCandidates(StrategyContext context) {
		List<FlipCandidate> candidates = new ArrayList<>();

		for (PricedListing priced : context.underpriced()) {
			evaluate(priced, context).ifPresent(candidates::add);
		}

		candidates.sort(null);
		return candidates;
	}

	private Optional<FlipCandidate> evaluate(PricedListing priced, StrategyContext context) {
		if (WitherBladeValuationContainment.suppresses(priced.item())) {
			// Defence in depth for callers supplying a precomputed listing during containment.
			return Optional.empty();
		}

		long price = priced.listing().price();
		ValueEstimate value = priced.value();

		// A single listing is one indivisible position, so the per-flip cap is the right ceiling:
		// a 200M BIN out of a 250M bankroll is affordable and still not a sane thing to suggest.
		if (price > context.maxCapitalPerFlip()) {
			return Optional.empty();
		}

		double confidence = value.confidence();

		// The one place the configured confidence floor applies: every other strategy prices from
		// a live order book, while this one is betting on an estimate.
		if (confidence < context.minConfidence()) {
			return Optional.empty();
		}

		// Resale at fair value, not at the best price ever seen. The listing fee and claim tax
		// both land on the way back out, and Derpy quadruples them.
		long resale = Math.round(value.median());
		long net = context.fees().binRoundTripProfit(price, resale);

		if (net < context.minProfitPerFlip()) {
			return Optional.empty();
		}

		double hours = value.hoursToSell();

		// Derived from the catalog's published star costs and the live book, so it is exact where
		// everything else on this candidate is a median of past sales.
		Optional<UpgradePricing.StarQuote> stars = quoteStars(priced, context);

		// The runtime backstop for the whole shared-id bug class. The estimate cannot say which
		// attribute it missed, only that a discount this deep on an item this dear is a bet against
		// the model, not a gift - so the candidate is kept but quarantined rather than ranked.
		boolean suspect = isSuspectDeepDiscount(priced);

		FlipCandidate candidate = new FlipCandidate(
				priced.item().skyblockId(),
				priced.item().displayName(),
				kind(),
				price,
				resale,
				net,
				1L,
				price,
				net / hours,
				confidence,
				steps(priced, resale),
				risks(priced, hours, stars, price, suspect),
				notes(stars, price));

		return Optional.of(suspect ? candidate.asSuspect() : candidate);
	}

	/**
	 * A discount so deep on so valuable an item that the likeliest explanation is an investment the
	 * signature does not read, not a seller's mistake.
	 *
	 * <p>This is the general form of the {@code COARSE} "verify nothing was added" warning, and it
	 * fires on every basis - a signature miss produces an {@code EXACT}-basis mirage, which is how the
	 * gemstone-slot bug reached the tape. The pricing pools the item with a costlier configuration, so
	 * its quote sits far above what the item is worth and it flags as a very deep snipe; the shape is
	 * the same whatever attribute leaked, which is why the guard keys on the shape and not on a named
	 * key the way the per-attribute probes do.
	 */
	private static boolean isSuspectDeepDiscount(PricedListing priced) {
		return priced.discount() >= SUSPECT_MIN_DISCOUNT
				&& priced.value().median() >= SUSPECT_MIN_VALUE;
	}

	/** The essence and materials bill for the stars this item already carries, if it has any. */
	private static Optional<UpgradePricing.StarQuote> quoteStars(PricedListing priced,
			StrategyContext context) {
		int stars = priced.item().stars();

		if (stars <= 0) {
			return Optional.empty();
		}

		return context.catalog().get(priced.item().skyblockId())
				.flatMap(entry -> UpgradePricing.quoteStars(entry, stars, context.bazaar()));
	}

	private static List<String> steps(PricedListing priced, long resale) {
		DecodedItem item = priced.item();
		List<String> steps = new ArrayList<>();

		steps.add("Auction House -> search \"" + item.displayName() + "\"");
		steps.add("Check the item matches: " + describe(item));
		steps.add("Buy at " + Coins.format(priced.listing().price())
				+ " (listed " + Math.round(priced.discount() * 100.0d) + "% under fair value)");
		steps.add("Relist at about " + Coins.format(resale) + ", which is the median of "
				+ priced.value().samples() + " recent sales");

		return steps;
	}

	/**
	 * What the stars are worth as ingredients.
	 *
	 * <p>Stated as a fact rather than folded into the valuation, because cost is not value: the
	 * market pays its own premium for the work of starring, and occasionally pays less than the
	 * essence came to. What it does let a player see is how much of the asking price is a commodity
	 * they could buy themselves, which is the difference between a mispriced item and a correctly
	 * priced pile of essence.
	 */
	private static List<String> notes(Optional<UpgradePricing.StarQuote> stars, long price) {
		if (stars.isEmpty()) {
			return List.of();
		}

		UpgradePricing.StarQuote quote = stars.get();
		List<String> notes = new ArrayList<>(2);

		notes.add(String.format("%d stars cost %s in essence and materials at current bazaar asks",
				quote.stars(), Coins.format(quote.coins())));

		if (price > 0L) {
			notes.add(String.format("That is %.0f%% of the asking price; a buy order for the essence "
					+ "would come in a few percent under it",
					100.0d * quote.coins() / price));
		}

		return notes;
	}

	private static List<String> risks(PricedListing priced, double hours,
			Optional<UpgradePricing.StarQuote> stars, long price, boolean suspect) {
		List<String> risks = new ArrayList<>();

		// First, because on a suspect flag it is the one that decides whether to click at all.
		if (suspect) {
			risks.add(String.format("A %.0f%% discount on an item worth %s is more often a hidden "
					+ "upgrade the pricing cannot see than a bargain - check every gemstone slot, "
					+ "scroll and attribute before buying",
					priced.discount() * 100.0d, Coins.format(Math.round(priced.value().median()))));
		}

		if (stars.isPresent() && price > 0L && stars.get().coins() > ESSENCE_HEAVY * price) {
			risks.add("Most of this price is star ingredients, so it revalues with the essence "
					+ "book rather than with the item");
		}

		// Always true, and the honest reason a "free" 40% discount is still sitting there.
		risks.add("The mod does not buy for you; a real underprice is often gone within seconds");

		switch (priced.value().basis()) {
			case COARSE -> risks.add(
					"Priced from name and rarity only - verify nothing was added to this item");
			case BANDED -> risks.add(bandedRisk(priced));
			case EXACT -> { }
		}

		if (priced.value().samples() < 12) {
			risks.add("Only " + priced.value().samples() + " comparable sales back this valuation");
		}

		if (hours >= 6.0d) {
			risks.add(String.format("This configuration sells about once every %.0f hours; "
					+ "your coins are parked until it does", hours));
		}

		if (priced.value().dispersion() > 0.4d) {
			risks.add("Comparable sales disagree by more than 40%, so the median is a weak guide");
		}

		return risks;
	}

	/**
	 * Why a widened match is weaker, in the terms of the thing that was widened.
	 *
	 * <p>Only pets reach this today. Which rung was used is readable off the key, and it matters:
	 * a pet priced from its level band is close, while one priced off every level of that pet is
	 * the weakest rung there is and should be said plainly rather than left to the confidence
	 * number to imply.
	 */
	private static String bandedRisk(PricedListing priced) {
		String key = priced.value().key();

		if (key.contains("lvlBand=")) {
			return "Priced from a range of pet levels, not this exact level - the closer it sits to "
					+ "the edge of the range, the weaker the estimate";
		}

		if (priced.item().isPet() && !priced.item().petInfo().map(p -> p.hasLevel()).orElse(false)) {
			return "This pet's level could not be read from its name, so it is priced off sales at "
					+ "every level - which for most pets spans a factor of two or more";
		}

		return "Priced off sales at every level of this pet, not its own - a level 1 and a level 100 "
				+ "of the same pet differ by 2x to 12x";
	}

	/** The attributes a human has to eyeball before clicking buy. */
	private static String describe(DecodedItem item) {
		List<String> parts = new ArrayList<>();

		parts.add(item.rarity().name().toLowerCase(java.util.Locale.ROOT));

		// First after the rarity, because for a pet it is most of the price.
		item.petInfo().filter(pet -> pet.hasLevel()).ifPresent(pet -> parts.add("level " + pet.level()));

		if (item.stars() > 0) {
			parts.add(item.stars() + " stars");
		}

		if (item.recombobulated()) {
			parts.add("recombobulated");
		}

		if (item.hotPotatoBooks() > 0) {
			parts.add(item.hotPotatoBooks() + " hot potato books");
		}

		if (!item.enchantments().isEmpty()) {
			parts.add(item.enchantments().size() + " enchantments");
		}

		if (!item.gemstones().isEmpty()) {
			parts.add(String.join(", ", item.gemstones()));
		}

		// Spelled out per level, because the level is most of what the item is worth.
		item.attributes().forEach((name, level) -> parts.add(name + " " + level));

		return String.join(", ", parts);
	}
}
