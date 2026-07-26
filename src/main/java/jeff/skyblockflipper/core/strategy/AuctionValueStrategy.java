package jeff.skyblockflipper.core.strategy;

import jeff.skyblockflipper.core.item.DecodedItem;
import jeff.skyblockflipper.core.text.Coins;
import jeff.skyblockflipper.core.valuation.PricedListing;
import jeff.skyblockflipper.core.valuation.ValueEstimate;

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
 * <p>What this cannot do is guarantee the listing is still there. The mod is advisory and does not
 * click; by the time a human reads a line and opens the auction house, a genuine underprice may
 * well have been taken. That is stated on every candidate rather than implied away.
 *
 * <p>Resale time comes from the observed sale rate of that configuration rather than from
 * optimism. Profit per hour on an item that sells twice a week is not the same business as the
 * same profit on one that sells hourly, and ranking them together without that division is how a
 * tool talks you into parking your bankroll in something illiquid.
 */
public final class AuctionValueStrategy implements FlipStrategy {
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
		long price = priced.listing().price();
		ValueEstimate value = priced.value();

		if (price > context.bankroll()) {
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

		return Optional.of(new FlipCandidate(
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
				risks(priced, hours)));
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

	private static List<String> risks(PricedListing priced, double hours) {
		List<String> risks = new ArrayList<>();

		// Always true, and the honest reason a "free" 40% discount is still sitting there.
		risks.add("The mod does not buy for you; a real underprice is often gone within seconds");

		if (!priced.value().exact()) {
			risks.add("Priced from name and rarity only - verify nothing was added to this item");
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

		if (priced.item().isPet()) {
			risks.add("Pet level is not modelled yet: check the level matches before buying");
		}

		return risks;
	}

	/** The attributes a human has to eyeball before clicking buy. */
	private static String describe(DecodedItem item) {
		List<String> parts = new ArrayList<>();

		parts.add(item.rarity().name().toLowerCase(java.util.Locale.ROOT));

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

		return String.join(", ", parts);
	}
}
