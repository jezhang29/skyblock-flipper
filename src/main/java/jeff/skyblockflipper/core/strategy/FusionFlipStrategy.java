package jeff.skyblockflipper.core.strategy;

import jeff.skyblockflipper.core.model.BazaarProduct;
import jeff.skyblockflipper.core.model.ItemCatalog;
import jeff.skyblockflipper.core.pricing.CraftQuote;
import jeff.skyblockflipper.core.pricing.FusionQuote;
import jeff.skyblockflipper.core.pricing.FusionQuote.Leaf;
import jeff.skyblockflipper.core.recipe.FusionTable;
import jeff.skyblockflipper.core.valuation.PriceTrend;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Buy cheap attribute shards, fuse them up to a dearer shard, sell the output.
 *
 * <p>Combine's twin: the edge is transformation, the exit is a taxed sell offer under the best ask,
 * and the source is a resting buy order at the bid. What differs is that the transform is a tree - an
 * input is sourced at the cheaper of buying it or fusing it, recursively - so all this class does is
 * hand every graph output to {@link FusionQuote}'s min-cost solver and turn what clears into the
 * shared candidate shape. The arithmetic, the depth cap and the one-sided exit gate are the quote's,
 * measured in {@code docs/fusion-flipping.md}.
 *
 * <p>The graph is a field, not part of {@link StrategyContext}, for the same reason the combine table
 * is: it is game data, the same on every client, unchanged between polls.
 *
 * <p>The candidate list is ordered by profit per hour, the shared axis, unlike combine which orders
 * by net per click. The per-effort figure (net per fusion click, total clicks, the 5:1 input-output
 * haul) rides in the notes, where a click-limited player can read it. The {@code /flip fusion} view
 * and the Fusion tab may re-sort to total profit; the unified {@code /flip} list always compares on
 * profit per hour.
 */
public final class FusionFlipStrategy implements FlipStrategy {
	private final FusionTable table;

	/** The shipped graph, which is what every caller outside a test wants. */
	public FusionFlipStrategy() {
		this(FusionTable.bundled());
	}

	public FusionFlipStrategy(FusionTable table) {
		this.table = table == null ? FusionTable.empty() : table;
	}

	@Override
	public StrategyKind kind() {
		return StrategyKind.FUSION;
	}

	@Override
	public List<FlipCandidate> findCandidates(StrategyContext context) {
		if (!context.fusion().enabled() || table.isEmpty()) {
			return List.of();
		}

		FusionQuote.Solver solver = FusionQuote.solver(table, context.bazaar(),
				context.fusion().crocodileLevel());
		List<FusionQuote> quotes = new ArrayList<>();

		for (String output : table.outputs()) {
			evaluate(solver, output, context).ifPresent(quotes::add);
		}

		// Ranked by profit per hour, the shared axis: fusion's per-click return is enormous, so ranking
		// it on net-per-click would float rare deep trees to the top of the list a player scans.
		quotes.sort(Comparator.comparingDouble(FusionQuote::profitPerHour).reversed());

		List<FlipCandidate> candidates = new ArrayList<>(quotes.size());

		for (FusionQuote quote : quotes) {
			candidates.add(candidate(quote, context));
		}

		return candidates;
	}

	/**
	 * The plan for the output shard {@code outputId}, re-priced against the current book, or empty.
	 *
	 * <p>What the bazaar overlay follows once the player picks a fusion row. Re-quoted here rather than
	 * frozen, exactly as {@link BazaarCombineStrategy#job}: the prices in it are what the player is
	 * about to type, and the book moves every poll. Empty when the flip no longer clears its gates.
	 */
	public Optional<FusionJob> job(String outputId, StrategyContext context) {
		if (outputId == null || !context.fusion().enabled() || table.isEmpty()) {
			return Optional.empty();
		}

		FusionQuote.Solver solver = FusionQuote.solver(table, context.bazaar(),
				context.fusion().crocodileLevel());

		return evaluate(solver, outputId, context)
				.flatMap(quote -> FusionJob.of(quote, context.catalog(), context.bazaar()));
	}

	private Optional<FusionQuote> evaluate(FusionQuote.Solver solver, String output,
			StrategyContext context) {
		// Cheapest rejection first: no output shard on the bazaar, no flip to price.
		if (context.bazaar().product(output).isEmpty()) {
			return Optional.empty();
		}

		CraftQuote.FillHistory history =
				productId -> context.trends().fillStatsFor(productId).orElse(null);

		FusionQuote quote = FusionQuote.quote(solver, output, context.fees(), history,
				context.fillHorizon(), context.maxCapitalPerFlip()).orElse(null);

		if (quote == null || quote.totalNetProfit() < context.minProfitPerFlip()) {
			return Optional.empty();
		}

		double marginDrift = marginDrift(quote, context);

		if (context.maxAdverseDrift() > 0.0d && marginDrift < -context.maxAdverseDrift()) {
			return Optional.empty();
		}

		return Optional.of(quote);
	}

	private FlipCandidate candidate(FusionQuote quote, StrategyContext context) {
		double marginDrift = marginDrift(quote, context);
		FusionJob job = FusionJob.of(quote, context.catalog(), context.bazaar()).orElse(null);
		String name = job != null ? job.displayName() : nameOf(quote.outputId(), context.catalog());

		return new FlipCandidate(
				quote.outputId(),
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
	 * The clicks, in the order to make them, drawn from the {@link FusionJob} so the flip screen and
	 * the overlay quote one set of numbers. Empty where the book emptied out from under the quote.
	 */
	private static List<String> steps(FusionJob job) {
		if (job == null) {
			return List.of();
		}

		List<String> steps = new ArrayList<>(job.rows().size());

		for (FusionJob.Row row : job.rows()) {
			steps.add(row.describe());
		}

		return steps;
	}

	/**
	 * The per-effort economics the shared candidate cannot carry: net per fusion click, the plan's
	 * total clicks, and the input-to-output haul that makes a fusion flip carry more than a spread.
	 */
	private static List<String> notes(FusionQuote quote) {
		long shardsIn = 0L;

		for (Leaf leaf : quote.leaves()) {
			shardsIn += quote.shardsToBuy(leaf);
		}

		List<String> notes = new ArrayList<>();
		notes.add(String.format(Locale.ROOT, "%,d net per fusion click, %,d clicks for the plan",
				Math.round(quote.netPerFusion()), quote.totalFusions()));
		notes.add(String.format(Locale.ROOT, "%,d shards bought for %,d output; sold via offer, "
				+ "so it never spends the NPC cap", shardsIn, quote.outputs()));

		return notes;
	}

	/**
	 * Which way the margin is moving: the output's drift less the cost-weighted drift of the inputs.
	 *
	 * <p>A fusion has many inputs, so a single subtraction will not do; each leaf's drift is weighted
	 * by its share of the source cost. An unrecorded shard contributes nothing rather than a guessed
	 * zero, the same convention combine and craft use so a fresh install is not judged adverse.
	 */
	private static double marginDrift(FusionQuote quote, StrategyContext context) {
		double outputDrift = context.trends().trendFor(quote.outputId())
				.map(PriceTrend::drift)
				.orElse(0.0d);

		double weightedInputDrift = 0.0d;
		double totalWeight = 0.0d;

		for (Leaf leaf : quote.leaves()) {
			double weight = leaf.unitsPerOutput() * leaf.unitPrice();
			Optional<PriceTrend> trend = context.trends().trendFor(leaf.shardId());

			if (trend.isPresent()) {
				weightedInputDrift += trend.get().drift() * weight;
				totalWeight += weight;
			}
		}

		return totalWeight > 0.0d ? outputDrift - weightedInputDrift / totalWeight : outputDrift;
	}

	/**
	 * How much the quote deserves to be trusted, on the output's ask depth, whether the fill was
	 * measured, and a closing margin. Reads the ask side only, the side a fusion sells into.
	 */
	private static double confidence(FusionQuote quote, StrategyContext context, double marginDrift) {
		BazaarProduct target = context.bazaar().product(quote.outputId()).orElseThrow();
		double depthScore = Math.min(1.0d, target.sellOfferCount() / 60.0d);
		double base = 0.45d + 0.35d * depthScore + (quote.fillMeasured() ? 0.15d : 0.0d);
		double driftPenalty = marginDrift < 0.0d ? Math.min(0.30d, -marginDrift * 4.0d) : 0.0d;

		return Math.clamp(base - driftPenalty, 0.05d, 1.0d);
	}

	/** The shard's display name from the catalog, or its bazaar id where the catalog has none. */
	static String nameOf(String shardId, ItemCatalog catalog) {
		return catalog == null ? shardId : catalog.displayName(shardId);
	}
}
