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

import jeff.skyblockflipper.core.model.BazaarProduct;
import jeff.skyblockflipper.core.pricing.CombineQuote;
import jeff.skyblockflipper.core.pricing.CraftQuote;
import jeff.skyblockflipper.core.strategy.CombineTable.Entry;
import jeff.skyblockflipper.core.valuation.PriceTrend;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Buy low-tier enchanted books, combine them up to a dearer tier at the anvil, sell the top tier.
 *
 * <p>The edge is transformation, like crafting, but the recipe is the anvil's fixed rule rather than
 * a table: {@code 2^(T-k)} books of tier {@code k} become one of tier {@code T}, and the two tiers
 * are priced by different crowds. All this class does is walk the {@link CombineTable} allowlist,
 * hand each enchant to {@link CombineQuote}, and turn what survives into the shared candidate shape.
 * The arithmetic, the one-sided exit gate and the source-tier scan are all {@code CombineQuote}'s,
 * measured in {@code docs/combine-flipping.md}.
 *
 * <p>Two things are this class's own:
 *
 * <ul>
 *   <li><b>The click cost is the honest axis.</b> A combine's whole cost for a player who cannot
 *       grind is anvil merges - fifteen of them per Feather Falling 10 - and the shared
 *       {@link FlipCandidate} has no field for that, ranking everything on profit per hour so a
 *       combine can be compared against an NPC flip. So the net-per-combine figure and the plan's
 *       total merge count go in the notes, where the value the combine view is actually for stays
 *       visible. See {@code npc-flipping-clicks-are-the-cost}.</li>
 *   <li><b>Where the margin is going.</b> A combine is two books that move independently, so the
 *       recorded drift of the source is subtracted from the output's, and an enchant whose margin is
 *       closing faster than {@link StrategyContext#maxAdverseDrift()} is refused - the same guard the
 *       craft and spread strategies apply.</li>
 * </ul>
 *
 * <p>The click steps come from a {@link CombineJob}, the same object the bazaar overlay follows, so
 * the flip screen and the panel beside Hypixel's menu cannot disagree about a price - the reason
 * craft routes its steps through {@link CraftJob}.
 *
 * <p>The table is a field rather than part of {@link StrategyContext} because it is game data, not
 * market state: the same on every client, and it never changes between polls.
 */
public final class BazaarCombineStrategy implements FlipStrategy {
	private final List<Entry> table;

	/** The shipped allowlist, which is what every caller outside a test wants. */
	public BazaarCombineStrategy() {
		this(CombineTable.all());
	}

	public BazaarCombineStrategy(List<Entry> table) {
		this.table = table == null ? List.of() : List.copyOf(table);
	}

	@Override
	public StrategyKind kind() {
		return StrategyKind.COMBINE;
	}

	@Override
	public List<FlipCandidate> findCandidates(StrategyContext context) {
		if (!context.combine().enabled()) {
			return List.of();
		}

		List<CombineQuote> quotes = new ArrayList<>();

		for (Entry entry : table) {
			evaluate(entry, context).ifPresent(quotes::add);
		}

		// Ranked by net per anvil combine, not by profit per hour: this list is read on /flip combine
		// and the Combine tab, where the whole point is the per-click return. The unified /flip list
		// re-sorts everything by profit per hour, which is why each candidate still carries the honest
		// hourly figure - a combine simply ranks low there, on purpose.
		quotes.sort(Comparator.comparingDouble(CombineQuote::netPerCombine).reversed());

		List<FlipCandidate> candidates = new ArrayList<>(quotes.size());

		for (CombineQuote quote : quotes) {
			candidates.add(candidate(quote, context));
		}

		return candidates;
	}

	/**
	 * The plan for the top-tier book {@code targetId}, re-priced against the current book, or empty.
	 *
	 * <p>What the bazaar overlay follows once the player picks a combine row. Re-quoted here rather
	 * than frozen at selection time, exactly as {@link CraftFlipStrategy#job}: the prices in it are
	 * what the player is about to type, and the book moves every twenty seconds. Empty when the flip
	 * no longer clears its own gates, which the overlay says rather than showing stale numbers.
	 */
	public Optional<CombineJob> job(String targetId, StrategyContext context) {
		if (targetId == null || !context.combine().enabled()) {
			return Optional.empty();
		}

		for (Entry entry : table) {
			if (!entry.targetId().equals(targetId)) {
				continue;
			}

			return evaluate(entry, context)
					.flatMap(quote -> CombineJob.of(quote, context.catalog(), context.bazaar()));
		}

		return Optional.empty();
	}

	private Optional<CombineQuote> evaluate(Entry entry, StrategyContext context) {
		// Cheapest rejection first: no target book on the bazaar, no flip to price.
		if (context.bazaar().product(entry.targetId()).isEmpty()) {
			return Optional.empty();
		}

		CraftQuote.FillHistory history =
				productId -> context.trends().fillStatsFor(productId).orElse(null);

		CombineQuote quote = CombineQuote.quote(entry, context.bazaar(), context.fees(), history,
				context.fillHorizon(), context.maxCapitalPerFlip()).orElse(null);

		if (quote == null || quote.totalNetProfit() < context.minProfitPerFlip()) {
			return Optional.empty();
		}

		// Zero where nothing in the pair has been recorded, which reads as "no signal" and lets the
		// flip through - the same convention craft and spread use, because a fresh install has no
		// history and treating unmeasured as adverse would empty the list.
		double marginDrift = marginDrift(quote, context);

		if (context.maxAdverseDrift() > 0.0d && marginDrift < -context.maxAdverseDrift()) {
			return Optional.empty();
		}

		return Optional.of(quote);
	}

	private FlipCandidate candidate(CombineQuote quote, StrategyContext context) {
		Entry entry = quote.entry();
		double marginDrift = marginDrift(quote, context);
		CombineJob job = CombineJob.of(quote, context.catalog(), context.bazaar()).orElse(null);

		String name = job != null
				? job.displayName()
				: CombineJob.nameOf(entry, entry.maxTier(), context.catalog());

		return new FlipCandidate(
				entry.targetId(),
				name,
				kind(),
				quote.sourceCostPerOutput(),
				quote.unitSellPrice(),
				quote.netPerOutput(),
				quote.outputs(),
				quote.capitalRequired(),
				quote.profitPerHour(),
				confidence(quote, context, marginDrift),
				steps(job),
				List.of(),
				notes(quote),
				quote.fill());
	}

	/**
	 * The clicks, in the order to make them, drawn from the {@link CombineJob} so the flip screen and
	 * the overlay quote one set of numbers. Empty where the book emptied out from under the quote.
	 */
	private static List<String> steps(CombineJob job) {
		if (job == null) {
			return List.of();
		}

		List<String> steps = new ArrayList<>(job.rows().size());

		for (CombineJob.Row row : job.rows()) {
			steps.add(row.describe());
		}

		return steps;
	}

	/**
	 * The per-click economics, which is the one thing the shared candidate cannot carry.
	 *
	 * <p>Profit per hour ranks a combine against the other strategies, but it is not why a
	 * click-limited player runs this one. Net per anvil combine is, and it lives here beside the
	 * plan's total merge count so the cost of the flip is not hidden.
	 */
	private static List<String> notes(CombineQuote quote) {
		List<String> notes = new ArrayList<>();

		notes.add(String.format("%,d net per anvil combine, %,d merges for the plan",
				Math.round(quote.netPerCombine()), quote.totalCombines()));
		notes.add(String.format("Sourced from tier %d; sold via offer, so it never spends the NPC cap",
				quote.sourceTier()));

		return notes;
	}

	/**
	 * Which way the margin is moving: the output's drift less the source's.
	 *
	 * <p>A single source, so no weighting is needed. An unrecorded book contributes nothing rather
	 * than a guessed zero, so a pair only one side of which is on the tape is judged on that side.
	 */
	private static double marginDrift(CombineQuote quote, StrategyContext context) {
		double outputDrift = context.trends().trendFor(quote.entry().targetId())
				.map(PriceTrend::drift)
				.orElse(0.0d);

		Optional<PriceTrend> source =
				context.trends().trendFor(quote.entry().bookId(quote.sourceTier()));

		return source.map(trend -> outputDrift - trend.drift()).orElse(outputDrift);
	}

	/**
	 * How much the quote deserves to be trusted, on the target's ask depth, whether the fill was
	 * measured, and a closing margin.
	 *
	 * <p>Reads the target's ask side only, because that is the side a combine sells into and the only
	 * one it is quoted against; the bid side is empty by design on the books this strategy wants.
	 */
	private static double confidence(CombineQuote quote, StrategyContext context, double marginDrift) {
		BazaarProduct target = context.bazaar().product(quote.entry().targetId()).orElseThrow();
		double depthScore = Math.min(1.0d, target.sellOfferCount() / 60.0d);
		double base = 0.45d + 0.35d * depthScore + (quote.fillMeasured() ? 0.15d : 0.0d);
		double driftPenalty = marginDrift < 0.0d ? Math.min(0.30d, -marginDrift * 4.0d) : 0.0d;

		return Math.clamp(base - driftPenalty, 0.05d, 1.0d);
	}
}
