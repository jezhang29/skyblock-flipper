package jeff.skyblockflipper.core.strategy;

import jeff.skyblockflipper.core.model.BazaarProduct;
import jeff.skyblockflipper.core.model.Stacking;
import jeff.skyblockflipper.core.model.UpgradeCost;
import jeff.skyblockflipper.core.pricing.CraftQuote;
import jeff.skyblockflipper.core.recipe.Recipe;
import jeff.skyblockflipper.core.recipe.RecipeBook;
import jeff.skyblockflipper.core.valuation.PriceTrend;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
 *       {@link CraftContext#maxOrderSlots()} gives up its most slot-hungry resting leg and is
 *       re-priced, one leg at a time, rather than dropped or moved wholesale onto the ask: a
 *       seven-slot plan is usually still a flip at four, and cheaper than the same plan bought
 *       entirely at the ask.</li>
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

		// One row per crafted item, not per recipe. Several items are craftable more than one way,
		// and the overlay follows whichever way pays best, so a second row for the same item would
		// be a row the panel refuses to follow.
		Map<String, FlipCandidate> best = new LinkedHashMap<>();

		for (Recipe recipe : book.all()) {
			// Cheapest possible rejection first. Only 307 of the 2,550 recipes have their output on
			// the bazaar at all, and quoting the rest would price ingredient books for an output
			// that cannot be sold on this venue.
			if (context.bazaar().product(recipe.outputId()).isEmpty()) {
				continue;
			}

			evaluate(recipe, context).ifPresent(candidate -> best.merge(candidate.itemId(), candidate,
					(a, b) -> a.profitPerHour() >= b.profitPerHour() ? a : b));
		}

		List<FlipCandidate> candidates = new ArrayList<>(best.values());

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

		CraftJob job = fitToSlots(
				job(recipe, context, history, Set.of(), Long.MAX_VALUE).orElse(null),
				recipe, context, history).orElse(null);

		if (job == null) {
			return Optional.empty();
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

	/** One recipe priced and laid out as clicks, no larger than {@code maxCrafts}. */
	private static Optional<CraftJob> job(Recipe recipe, StrategyContext context,
			CraftQuote.FillHistory history, Set<String> instantOnly, long maxCrafts) {
		return CraftQuote.quote(recipe, context.bazaar(), context.fees(), history,
						context.fillHorizon(), context.maxCapitalPerFlip(),
						CraftQuote.DEFAULT_FLOW_SHARE, instantOnly, maxCrafts)
				.flatMap(quote -> CraftJob.of(quote, context.catalog(), context.bazaar()));
	}

	/**
	 * The same plan, cut down until its real orders fit the slot budget, or empty when they cannot.
	 *
	 * <p><b>A plan over budget is nearly always the right flip at the wrong size, and it used to be
	 * answered by buying the whole bill at the ask instead.</b> That gives every slot back at once
	 * and pays the ask on materials that were never the problem, and on a farmed material the ask
	 * side supplies almost nothing - nobody instant-sells wheat. Measured on the live book of
	 * 2026-08-20, the two plans that overran the shipped six-slot budget were
	 * {@code ENCHANTED_MITHRIL} at 3.13M coins an hour and {@code ENCHANTED_WHEAT} at 5.18M, and
	 * the old fallback quoted them at <b>1,123 and 4,774</b>. Both wanted their whole overrun for a
	 * single material: eighteen orders of mithril ore, thirty-three of wheat.
	 *
	 * <p>So the first answer is a smaller plan. Sizing the same routes down to what six slots can
	 * rest at once keeps the margin per craft exactly and takes the share of the flow those slots
	 * hold, which is worth hundreds of times the instant corner on both of those rows.
	 *
	 * <p>Giving up a leg is the second answer, for the plan that wants more <i>distinct</i> resting
	 * materials than the player has slots - a size cut cannot help there, because every leg costs
	 * one order however small it is. The most slot-hungry leg goes first and the recipe is
	 * re-priced, so the plan keeps the cheap orders it can afford to keep.
	 *
	 * <p>Empty when even resting nothing fits, which happens when the sell offer alone needs more
	 * orders than the player has slots. There is nothing further to give back.
	 */
	private static Optional<CraftJob> fitToSlots(CraftJob job, Recipe recipe,
			StrategyContext context, CraftQuote.FillHistory history) {
		int budget = context.craft().maxOrderSlots();

		if (job == null || job.orderSlots() <= budget) {
			return Optional.ofNullable(job);
		}

		Set<String> instantOnly = new HashSet<>();
		CraftJob best = null;

		// Walk the plan down one resting leg at a time, and at every step also take the same routes
		// sized to the budget. Neither answer wins everywhere - a small plan that keeps a cheap
		// order beats the ask on a farmed material, and the ask beats a plan cut to almost nothing
		// on a material the bid side barely trades - so both are priced and the better one ships.
		while (job != null) {
			best = better(best, sizedToFit(job, recipe, context, history, instantOnly, budget));

			if (job.orderSlots() <= budget) {
				return Optional.of(better(best, job));
			}

			String give = hungriest(job);

			if (give == null || !instantOnly.add(give)) {
				break;
			}

			job = job(recipe, context, history, instantOnly, Long.MAX_VALUE).orElse(null);
		}

		// The walk can die on a step that will not price at all - an ingredient the visible ask book
		// cannot cover in one go refuses rather than quoting part of a bill - so the corner it was
		// heading for is tried directly. Buying the whole bill at the ask rests only the sell offer,
		// which is the smallest claim on slots any plan can make.
		CraftJob instant = job(recipe, context, history, everyIngredient(recipe), Long.MAX_VALUE)
				.orElse(null);

		return Optional.ofNullable(instant != null && instant.orderSlots() <= budget
				? better(best, instant)
				: best);
	}

	/** Every id the recipe consumes, i.e. the plan that rests nothing but its sell offer. */
	private static Set<String> everyIngredient(Recipe recipe) {
		Set<String> ids = new HashSet<>();

		for (UpgradeCost.Ingredient ingredient : recipe.ingredients()) {
			ids.add(ingredient.productId());
		}

		return ids;
	}

	/** The same routes cut down to what the budget can rest at once, or null if nothing fits. */
	private static CraftJob sizedToFit(CraftJob job, Recipe recipe, StrategyContext context,
			CraftQuote.FillHistory history, Set<String> instantOnly, int budget) {
		long fits = craftsThatFit(job, context, budget);

		if (fits < 1L) {
			return null;
		}

		CraftJob sized = job(recipe, context, history, instantOnly, fits).orElse(null);

		// The cut cannot change which legs rest, so this holds; checked rather than assumed because
		// a plan over the budget is exactly what this method exists to stop returning.
		return sized != null && sized.orderSlots() <= budget ? sized : null;
	}

	private static CraftJob better(CraftJob first, CraftJob second) {
		if (first == null) {
			return second;
		}

		if (second == null) {
			return first;
		}

		return first.profitPerHour() >= second.profitPerHour() ? first : second;
	}

	/**
	 * The largest plan of this shape whose orders fit {@code budget}, or 0 when none does.
	 *
	 * <p>Searched rather than solved because the cost is a sum of ceilings: every resting leg rounds
	 * up to a whole order, so the orders a plan needs is a step function of its size. It only ever
	 * climbs, which is what makes the search valid.
	 */
	private static long craftsThatFit(CraftJob job, StrategyContext context, int budget) {
		long low = 0L;
		long high = job.quote().crafts();

		while (low < high) {
			long mid = low + (high - low + 1L) / 2L;

			if (ordersFor(job, context, mid) <= budget) {
				low = mid;
			} else {
				high = mid - 1L;
			}
		}

		return low;
	}

	/** Bazaar orders a plan of {@code crafts} would rest, on the routes this job already chose. */
	private static int ordersFor(CraftJob job, StrategyContext context, long crafts) {
		Recipe recipe = job.quote().recipe();
		int orders = orders(context, recipe.outputId(), crafts * recipe.outputCount());

		for (UpgradeCost.Ingredient ingredient : recipe.ingredients()) {
			if (job.quote().restingBuyOrders().contains(ingredient.productId())) {
				orders += orders(context, ingredient.productId(), crafts * ingredient.amount());
			}
		}

		return orders;
	}

	private static int orders(StrategyContext context, String productId, long units) {
		long perOrder = Stacking.unitsPerOrder(context.catalog().get(productId).orElse(null),
				context.bazaar().product(productId).orElse(null));

		return (int) Math.max(1L, (units + perOrder - 1L) / perOrder);
	}

	/** The resting ingredient row costing the most orders, which is the one worth giving up first. */
	private static String hungriest(CraftJob job) {
		String worst = null;
		int orders = 0;

		for (CraftJob.Row row : job.rows()) {
			if (row.action() == CraftJob.Action.BUY_ORDER && row.orders() > orders) {
				orders = row.orders();
				worst = row.itemId();
			}
		}

		return worst;
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
