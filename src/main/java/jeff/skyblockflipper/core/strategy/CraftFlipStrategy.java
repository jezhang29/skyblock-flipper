package jeff.skyblockflipper.core.strategy;

import jeff.skyblockflipper.core.model.BazaarProduct;
import jeff.skyblockflipper.core.model.UpgradeCost;
import jeff.skyblockflipper.core.pricing.CraftQuote;
import jeff.skyblockflipper.core.pricing.CraftQuote.InputRoute;
import jeff.skyblockflipper.core.recipe.Recipe;
import jeff.skyblockflipper.core.recipe.RecipeBook;
import jeff.skyblockflipper.core.valuation.PriceTrend;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Buy materials on the bazaar, craft, and sell the result back into the same bazaar.
 *
 * <p>The edge is transformation: the recipe is game data, so the output's book and its ingredients'
 * books are priced by different crowds and do not have to agree. All this class does is walk every
 * recipe the bundled {@link RecipeBook} knows, hand each to {@link CraftQuote}, and turn what
 * survives into the shared candidate shape. The arithmetic, the gates and the route choice are all
 * {@code CraftQuote}'s, measured in {@code docs/craft-flipping.md}.
 *
 * <p>Two decisions belong here rather than there, because both are about the account rather than
 * about the recipe:
 *
 * <ul>
 *   <li><b>The slot budget.</b> A quote reports {@link CraftQuote#orderSlots()}, and on the live
 *       book the best eight craft plans together wanted 19 of the 21 slots a Bazaar Flipper 1
 *       account has. Those are the same slots the NPC basket - the user's daily driver - needs, and
 *       that strategy has already measured that slots bind where coins do not. A plan over
 *       {@link CraftContext#maxOrderSlots()} is re-quoted on {@link InputRoute#INSTANT_BUY}, which
 *       rests nothing but the sell offer, rather than dropped: a seven-slot resting plan is usually
 *       still a flip at one slot, just a slower and dearer one.</li>
 *   <li><b>The budget is per plan, not across the list.</b> This produces a menu the player picks
 *       one row from, not a basket they execute whole, so spending a shared budget down the ranking
 *       would hide row five because of four plans nobody placed.</li>
 *   <li><b>Where the margin is going, not only where it is.</b> A craft quote is two sides of a
 *       trade that move independently: the output can be crashing while the materials hold, or the
 *       materials can be climbing while the output holds, and both close the margin the quote was
 *       measured at. So the recorded drift of every book in the recipe is combined into one
 *       {@link #marginDrift} - the output's drift less each ingredient's, weighted by its share of
 *       the bill - and a recipe whose margin is closing faster than
 *       {@link StrategyContext#maxAdverseDrift()} is refused. Neither side alone answers this: an
 *       output down 4% whose materials are down 6% is a widening margin, and rejecting it on the
 *       output's drift would throw away the better half of the strategy.</li>
 * </ul>
 *
 * <p>The recipe table is a field rather than part of {@link StrategyContext} because it is shipped
 * game data, not market state: it is the same on every client and it never changes between polls.
 */
public final class CraftFlipStrategy implements FlipStrategy {
	private final RecipeBook book;

	/** The shipped table, which is what every caller outside a test wants. */
	public CraftFlipStrategy() {
		this(RecipeBook.bundled());
	}

	public CraftFlipStrategy(RecipeBook book) {
		this.book = book == null ? RecipeBook.empty() : book;
	}

	@Override
	public StrategyKind kind() {
		return StrategyKind.CRAFT;
	}

	@Override
	public List<FlipCandidate> findCandidates(StrategyContext context) {
		if (!context.craft().enabled() || book.isEmpty()) {
			return List.of();
		}

		List<FlipCandidate> candidates = new ArrayList<>();

		for (Recipe recipe : book.all()) {
			// Cheapest possible rejection first. Only 307 of the 2,550 recipes have their output on
			// the bazaar at all, and quoting the rest would price ingredient books for an output
			// that cannot be sold on this venue.
			if (context.bazaar().product(recipe.outputId()).isEmpty()) {
				continue;
			}

			evaluate(recipe, context).ifPresent(candidates::add);
		}

		candidates.sort(null);
		return candidates;
	}

	/**
	 * The job for one crafted item, re-planned against the book as it is now.
	 *
	 * <p>What the bazaar overlay follows while the player works a flip. Re-planned rather than
	 * frozen at the moment they picked the row: the prices they are about to type move every poll,
	 * and a panel quoting the book from twenty minutes ago is worse than no panel, because it looks
	 * exactly as authoritative.
	 *
	 * <p>Empty where the flip has stopped being one - the margin closed, the book thinned, the
	 * materials moved - which is the overlay's cue to say so rather than to keep showing the last
	 * good numbers.
	 */
	public Optional<CraftJob> job(String outputId, StrategyContext context) {
		if (outputId == null || !context.craft().enabled()) {
			return Optional.empty();
		}

		Optional<CraftJob> best = Optional.empty();

		// An output can have more than one recipe. The ranking picked whichever paid best, so this
		// has to make the same choice or the panel would follow a different plan than the row did.
		for (Recipe recipe : book.forOutput(outputId)) {
			Optional<CraftJob> planned = plan(recipe, context).map(Planned::job);

			if (planned.isPresent() && (best.isEmpty()
					|| planned.get().profitPerHour() > best.get().profitPerHour())) {
				best = planned;
			}
		}

		return best;
	}

	/** A job together with the trend reading the candidate needs, which the job has no use for. */
	private record Planned(CraftJob job, double marginDrift) {
	}

	private Optional<FlipCandidate> evaluate(Recipe recipe, StrategyContext context) {
		return plan(recipe, context).map(planned -> candidate(planned, context));
	}

	private FlipCandidate candidate(Planned planned, StrategyContext context) {
		CraftJob job = planned.job();
		CraftQuote quote = job.quote();
		Recipe recipe = quote.recipe();
		BazaarProduct output = context.bazaar().product(recipe.outputId()).orElseThrow();

		return new FlipCandidate(
				recipe.outputId(),
				job.displayName(),
				kind(),
				quote.inputCostPerCraft() / recipe.outputCount(),
				quote.unitSellPrice(),
				quote.netPerCraft() / recipe.outputCount(),
				quote.outputUnits(),
				quote.capitalRequired(),
				quote.profitPerHour(),
				confidence(quote, output, planned.marginDrift()),
				steps(job),
				unlock(recipe),
				List.of(),
				quote.fill());
	}

	private Optional<Planned> plan(Recipe recipe, StrategyContext context) {
		CraftQuote.FillHistory history =
				productId -> context.trends().fillStatsFor(productId).orElse(null);

		CraftJob job = job(recipe, context, history, null).orElse(null);

		if (job == null) {
			return Optional.empty();
		}

		if (job.orderSlots() > context.craft().maxOrderSlots()) {
			// The resting route is what spends slots, so the instant route is the same recipe with
			// nothing resting but the sell offer. It is dearer and slower to source and it may well
			// not clear the profit floor below, in which case this recipe is not a flip inside the
			// budget. The offer itself can still overrun it on an item that does not stack, and
			// there is no third route to fall back to, so that one is refused.
			job = job(recipe, context, history, InputRoute.INSTANT_BUY).orElse(null);

			if (job == null || job.orderSlots() > context.craft().maxOrderSlots()) {
				return Optional.empty();
			}
		}

		CraftQuote quote = job.quote();

		if (quote.netPerCraft() <= 0.0d || quote.profitPerHour() <= 0.0d) {
			return Optional.empty();
		}

		if (quote.totalNetProfit() < context.minProfitPerFlip()) {
			return Optional.empty();
		}

		// Zero where nothing in the recipe has been recorded long enough, which reads as "no signal"
		// and lets the flip through. That is the same convention the bazaar strategy uses: a fresh
		// install has no history either, and treating unmeasured as adverse would empty the list.
		double marginDrift = marginDrift(quote, context);

		if (context.maxAdverseDrift() > 0.0d && marginDrift < -context.maxAdverseDrift()) {
			return Optional.empty();
		}

		return Optional.of(new Planned(job, marginDrift));
	}

	/** One recipe priced and laid out as clicks, on {@code route} or on whichever pays better. */
	private static Optional<CraftJob> job(Recipe recipe, StrategyContext context,
			CraftQuote.FillHistory history, InputRoute route) {
		Optional<CraftQuote> quoted = route == null
				? CraftQuote.quote(recipe, context.bazaar(), context.fees(), history,
						context.fillHorizon(), context.maxCapitalPerFlip())
				: CraftQuote.quote(recipe, context.bazaar(), context.fees(), history,
						context.fillHorizon(), context.maxCapitalPerFlip(),
						CraftQuote.DEFAULT_FLOW_SHARE, route);

		return quoted.flatMap(quote -> CraftJob.of(quote, context.catalog(), context.bazaar()));
	}

	/**
	 * Which way the margin itself is moving, as a fraction over the trend window.
	 *
	 * <p>The output's drift less the cost-weighted drift of the materials. Weighted because a
	 * recipe's bill is rarely evenly split: an ingredient that is 3% of the cost moving 20% matters
	 * far less than the one that is 80% of it moving 5%, and an unweighted average says they are the
	 * same. Weights come from the same top-of-book asks the bill is quoted from, which is enough
	 * precision for a weight even where the plan actually rests its orders.
	 *
	 * <p>Ingredients with no usable history contribute nothing rather than a guessed zero drift
	 * against a full weight, so a partly-recorded recipe is judged on the part that was recorded.
	 */
	private static double marginDrift(CraftQuote quote, StrategyContext context) {
		double outputDrift = context.trends().trendFor(quote.recipe().outputId())
				.map(PriceTrend::drift)
				.orElse(0.0d);

		double weighted = 0.0d;
		double weight = 0.0d;

		for (UpgradeCost.Ingredient ingredient : quote.recipe().ingredients()) {
			BazaarProduct input = context.bazaar().product(ingredient.productId()).orElse(null);
			PriceTrend trend = context.trends().trendFor(ingredient.productId()).orElse(null);

			if (input == null || trend == null) {
				continue;
			}

			double cost = input.instantBuyPrice().orElse(0.0d) * ingredient.amount();

			weighted += trend.drift() * cost;
			weight += cost;
		}

		return weight <= 0.0d ? outputDrift : outputDrift - weighted / weight;
	}

	/**
	 * How much the quote deserves to be trusted, on the three things that can make it wrong.
	 *
	 * <p>Depth, because every price in the bill is a top-of-book price that a handful of orders can
	 * move. Whether the sell leg's rate was measured from the tape rather than assumed at a flat
	 * share of flow. And a closing margin, which is the quote going stale while you work it.
	 *
	 * <p>An unmeasured rate is not penalised beyond that, and neither is a margin that is holding or
	 * widening: the tape covers what it happens to cover, and docking confidence for having been
	 * measured would tilt the ranking toward whatever nobody has recorded yet.
	 */
	private static double confidence(CraftQuote quote, BazaarProduct output, double marginDrift) {
		int depth = Math.min(output.sellOfferCount(), output.buyOrderCount());
		double depthScore = Math.min(1.0d, depth / 60.0d);
		double base = 0.45d + 0.35d * depthScore + (quote.fillMeasured() ? 0.15d : 0.0d);
		double driftPenalty = marginDrift < 0.0d ? Math.min(0.30d, -marginDrift * 4.0d) : 0.0d;

		return Math.clamp(base - driftPenalty, 0.05d, 1.0d);
	}

	/**
	 * The clicks, in the order to make them, from the one place that knows them.
	 *
	 * <p>Rendered from {@link CraftJob} rather than written here, so the flip screen, the bazaar
	 * overlay and {@code /flip craft} cannot disagree about a price or a quantity. That is the same
	 * rule {@code NpcWorklist} exists to enforce for the basket.
	 */
	private static List<String> steps(CraftJob job) {
		List<String> steps = new ArrayList<>();

		for (CraftJob.Row row : job.rows()) {
			steps.add(row.describe());
		}

		return steps;
	}

	/**
	 * The one thing the player must know before buying anything, or nothing at all.
	 *
	 * <p>This used to carry four notes and up to five risks - the input route, the slot cost, which
	 * leg bound the rate, the crafts available, that materials might not fill, that crafting takes
	 * clicks. All of it was true and none of it changed what the player clicked next, which is the
	 * only thing that earns a line in front of someone who is mid-trade.
	 *
	 * <p>A recipe unlock is the exception, because nothing here reads the player's collections and
	 * an unowned recipe is not a smaller flip, it is no flip at all: the materials get bought and
	 * then sit there.
	 */
	private static List<String> unlock(Recipe recipe) {
		return recipe.unlockText().isBlank() ? List.of() : List.of(recipe.unlockText());
	}
}
