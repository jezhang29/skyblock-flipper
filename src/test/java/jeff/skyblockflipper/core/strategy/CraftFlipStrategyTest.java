package jeff.skyblockflipper.core.strategy;

import jeff.skyblockflipper.core.model.BazaarProduct;
import jeff.skyblockflipper.core.model.BazaarSnapshot;
import jeff.skyblockflipper.core.model.ItemCatalog;
import jeff.skyblockflipper.core.model.OrderLevel;
import jeff.skyblockflipper.core.model.UpgradeCost;
import jeff.skyblockflipper.core.pricing.Fees;
import jeff.skyblockflipper.core.recipe.Recipe;
import jeff.skyblockflipper.core.recipe.RecipeBook;
import jeff.skyblockflipper.core.valuation.PriceTrend;
import jeff.skyblockflipper.core.valuation.TrendSnapshot;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the strategy layer adds on top of {@link jeff.skyblockflipper.core.pricing.CraftQuote}: the
 * order-slot budget, the profit floor, and the candidate shape the UI ranks everything on.
 *
 * <p>The quote's own arithmetic and its refusals are covered by {@code CraftQuoteTest} and are not
 * re-tested here.
 */
class CraftFlipStrategyTest {
	private static final Duration HOUR = Duration.ofHours(1);
	private static final long BANKROLL = 1_000_000_000L;

	private final Map<String, BazaarProduct> products = new HashMap<>();
	private final Map<String, PriceTrend> trends = new HashMap<>();

	/** A recorded series for one product, drifting by {@code drift} over the window. */
	private void drift(String id, double drift) {
		trends.put(id, new PriceTrend(id, 100.0d, 100.0d * (1.0d + drift), 100.0d, 0.002d, 0.01d, 288));
	}

	/** A book deep enough on both sides to rest an order against, at a believable spread. */
	private void book(String id, double ask, double bid, long weeklyBought, long weeklySold) {
		book(id, ask, bid, weeklyBought, weeklySold, 20, 20);
	}

	private void book(String id, double ask, double bid, long weeklyBought, long weeklySold,
			int askOrders, int bidOrders) {
		products.put(id, new BazaarProduct(id,
				List.of(new OrderLevel(ask, 10_000_000L, askOrders)),
				List.of(new OrderLevel(bid, 10_000_000L, bidOrders)),
				new BazaarProduct.MovingWeek(weeklyBought, weeklySold)));
	}

	private static Recipe recipe(String output, int count, Object... idsAndAmounts) {
		return gated(output, count, "", idsAndAmounts);
	}

	/** The same, with NEU's collection requirement attached. Named apart so varargs cannot confuse it. */
	private static Recipe gated(String output, int count, String unlock, Object... idsAndAmounts) {
		List<UpgradeCost.Ingredient> ingredients = new ArrayList<>();

		for (int i = 0; i < idsAndAmounts.length; i += 2) {
			ingredients.add(new UpgradeCost.Ingredient((String) idsAndAmounts[i],
					(Integer) idsAndAmounts[i + 1]));
		}

		return new Recipe(output, count, ingredients, unlock);
	}

	private StrategyContext context(CraftContext craft, long minProfitPerFlip) {
		return context(craft, minProfitPerFlip, 0.0d);
	}

	private StrategyContext context(CraftContext craft, long minProfitPerFlip,
			double maxAdverseDrift) {
		return new StrategyContext(
				new BazaarSnapshot(Instant.now(), Map.copyOf(products)),
				ItemCatalog.empty(),
				List.of(),
				new TrendSnapshot(Map.copyOf(trends), Map.of(), Map.of(),
						Duration.ofHours(24), 288, Instant.now()),
				new Fees(1, false),
				BANKROLL,
				minProfitPerFlip,
				0.0d,
				maxAdverseDrift,
				HOUR,
				StrategyContext.UNCAPPED,
				NpcContext.unlimited(),
				craft);
	}

	private StrategyContext context() {
		return context(CraftContext.defaults(), 0L);
	}

	/** A recipe that clears easily: one cheap input, an output worth ten times the bill. */
	private Recipe profitable() {
		book("OUTPUT", 1_000.0d, 900.0d, 5_000_000L, 5_000_000L);
		book("INPUT", 100.0d, 90.0d, 5_000_000L, 5_000_000L);

		return recipe("OUTPUT", 1, "INPUT", 1);
	}

	private List<FlipCandidate> candidates(Recipe recipe, StrategyContext context) {
		return new CraftFlipStrategy(RecipeBook.of(List.of(recipe))).findCandidates(context);
	}

	// ---- the candidate shape --------------------------------------------------------------

	@Test
	void producesACraftCandidateForAProfitableRecipe() {
		List<FlipCandidate> found = candidates(profitable(), context());

		assertEquals(1, found.size(), "one recipe, one candidate");

		FlipCandidate candidate = found.getFirst();

		assertEquals(StrategyKind.CRAFT, candidate.kind());
		assertEquals("OUTPUT", candidate.itemId());
		assertTrue(candidate.profitPerHour() > 0.0d, "a profitable recipe must rank above zero");
		assertTrue(candidate.unitNetProfit() > 0.0d);
	}

	/**
	 * Units are output units, not crafts, because that is what the sell offer is denominated in and
	 * what every other strategy's {@code units} means.
	 */
	@Test
	void quotesUnitsInOutputUnitsNotCrafts() {
		book("OUTPUT", 1_000.0d, 900.0d, 5_000_000L, 5_000_000L);
		book("INPUT", 10.0d, 9.0d, 5_000_000L, 5_000_000L);

		FlipCandidate single = candidates(recipe("OUTPUT", 1, "INPUT", 1), context()).getFirst();
		FlipCandidate batched = candidates(recipe("OUTPUT", 4, "INPUT", 1), context()).getFirst();

		assertEquals(0L, batched.units() % 4L,
				"a recipe yielding four at a time can only produce multiples of four");
		assertTrue(single.units() > 0L);
	}

	// ---- the slot budget ------------------------------------------------------------------

	/**
	 * The measured reason this budget exists: on the live book the best eight craft plans wanted 19
	 * of the 21 slots a Bazaar Flipper 1 account has, and those are the same slots the NPC basket -
	 * the daily driver - needs.
	 */
	@Test
	void aPlanOverTheSlotBudgetFallsBackToTheInstantRouteRatherThanBeingDropped() {
		book("OUTPUT", 10_000.0d, 9_000.0d, 5_000_000L, 5_000_000L);
		book("A", 100.0d, 90.0d, 5_000_000L, 5_000_000L);
		book("B", 100.0d, 90.0d, 5_000_000L, 5_000_000L);
		book("C", 100.0d, 90.0d, 5_000_000L, 5_000_000L);

		Recipe six = recipe("OUTPUT", 1, "A", 1, "B", 1, "C", 1);

		// Four slots on the resting route: three ingredients plus the sell offer.
		FlipCandidate roomy = candidates(six, context(new CraftContext(true, 6), 0L)).getFirst();
		List<FlipCandidate> tight = candidates(six, context(new CraftContext(true, 2), 0L));

		assertTrue(roomy.steps().stream().anyMatch(step -> step.startsWith("Buy Order:")),
				"with slots to spare the cheaper resting route should win");
		assertEquals(1, tight.size(), "a slot-hungry plan is re-quoted, not dropped");
		assertTrue(tight.getFirst().steps().stream().noneMatch(step -> step.startsWith("Buy Order:")),
				"inside a tight budget the plan must rest nothing but its sell offer");
		assertTrue(tight.getFirst().steps().stream().anyMatch(step -> step.startsWith("Buy Instantly:")),
				"the materials still have to be bought, just at the ask");
	}

	/**
	 * The panel used to carry four notes and up to five risks, none of which changed what the player
	 * clicked next. The steps are the deliverable; a recipe unlock is the one fact that can make the
	 * whole plan void, so it is the one thing left.
	 */
	@Test
	void saysNothingBeyondTheStepsAndAnUnlock() {
		FlipCandidate candidate = candidates(profitable(), context()).getFirst();

		assertEquals(List.of(), candidate.notes());
		assertEquals(List.of(), candidate.risks(), "this fixture's recipe has no unlock");
		assertFalse(candidate.steps().isEmpty());
	}

	// ---- the filters ----------------------------------------------------------------------

	@Test
	void producesNothingWhenCraftingIsTurnedOff() {
		assertTrue(candidates(profitable(), context(CraftContext.off(), 0L)).isEmpty());
	}

	@Test
	void skipsRecipesWhoseOutputIsNotOnTheBazaar() {
		book("INPUT", 100.0d, 90.0d, 5_000_000L, 5_000_000L);

		assertTrue(candidates(recipe("NOT_A_PRODUCT", 1, "INPUT", 1), context()).isEmpty());
	}

	@Test
	void dropsPlansUnderTheProfitFloor() {
		Recipe recipe = profitable();

		assertFalse(candidates(recipe, context(CraftContext.defaults(), 0L)).isEmpty(),
				"the fixture has to clear a zero floor for this test to mean anything");
		assertTrue(candidates(recipe, context(CraftContext.defaults(), Long.MAX_VALUE)).isEmpty());
	}

	@Test
	void dropsRecipesThatLoseMoney() {
		book("OUTPUT", 100.0d, 90.0d, 5_000_000L, 5_000_000L);
		book("INPUT", 1_000.0d, 900.0d, 5_000_000L, 5_000_000L);

		assertTrue(candidates(recipe("OUTPUT", 1, "INPUT", 1), context()).isEmpty());
	}

	// ---- what it tells the player ----------------------------------------------------------

	/** Nothing here reads the player's collections, so an unlock is a risk and not a footnote. */
	@Test
	void carriesTheRecipeUnlockAndNothingElse() {
		book("OUTPUT", 1_000.0d, 900.0d, 5_000_000L, 5_000_000L);
		book("INPUT", 100.0d, 90.0d, 5_000_000L, 5_000_000L);

		FlipCandidate candidate =
				candidates(gated("OUTPUT", 1, "Requires: Iron Ingot IX", "INPUT", 1), context())
						.getFirst();

		assertEquals(List.of("Requires: Iron Ingot IX"), candidate.risks());
	}

	@Test
	void stepsEndWithTheSellOfferAndNameEveryIngredient() {
		book("OUTPUT", 1_000.0d, 900.0d, 5_000_000L, 5_000_000L);
		book("A", 100.0d, 90.0d, 5_000_000L, 5_000_000L);
		book("B", 100.0d, 90.0d, 5_000_000L, 5_000_000L);

		List<String> steps = candidates(recipe("OUTPUT", 1, "A", 1, "B", 1), context())
				.getFirst().steps();

		assertEquals(4, steps.size(), "two materials, the craft, and the offer");
		assertTrue(steps.get(0).contains("A"));
		assertTrue(steps.get(1).contains("B"));
		assertTrue(steps.get(2).startsWith("Craft:"));
		assertTrue(steps.getLast().startsWith("Sell Offer:"),
				"the exit is always a resting offer, so it is always the last click");
	}

	// ---- the job the bazaar panel follows ---------------------------------------------------

	/**
	 * The panel and the row have to be the same plan. They are built from separate calls - the row
	 * from the ranking, the job re-planned every poll while the player works it - so the thing worth
	 * pinning is that the second call makes the same decisions as the first.
	 */
	@Test
	void planningOneItemAgreesWithItsRankedRow() {
		Recipe recipe = profitable();
		CraftFlipStrategy strategy = new CraftFlipStrategy(RecipeBook.of(List.of(recipe)));
		StrategyContext context = context();

		FlipCandidate row = strategy.findCandidates(context).getFirst();
		CraftJob job = strategy.job("OUTPUT", context).orElseThrow();

		assertEquals(row.steps(), job.rows().stream().map(CraftJob.Row::describe).toList());
		assertEquals(row.profitPerHour(), job.profitPerHour());
		assertEquals(row.capitalRequired(), job.capital());
	}

	@Test
	void planningNothingWhenCraftingIsOffOrTheItemIsNotACraft() {
		Recipe recipe = profitable();
		CraftFlipStrategy strategy = new CraftFlipStrategy(RecipeBook.of(List.of(recipe)));

		assertTrue(strategy.job("OUTPUT", context(CraftContext.off(), 0L)).isEmpty());
		assertTrue(strategy.job("NOT_A_PRODUCT", context()).isEmpty());
		assertTrue(strategy.job(null, context()).isEmpty());
	}

	// ---- the trend ------------------------------------------------------------------------

	/**
	 * A crashing output closes the margin while you are still buying the materials for it, so it is
	 * refused rather than ranked. The quote itself cannot see this: the book it prices is a snapshot.
	 */
	@Test
	void refusesARecipeWhoseOutputIsCrashing() {
		Recipe recipe = profitable();
		drift("OUTPUT", -0.20d);

		assertTrue(candidates(recipe, context(CraftContext.defaults(), 0L, 0.05d)).isEmpty());
	}

	/** The same move on the material side, which closes the margin from the other end. */
	@Test
	void refusesARecipeWhoseMaterialsAreClimbing() {
		Recipe recipe = profitable();
		drift("INPUT", 0.20d);

		assertTrue(candidates(recipe, context(CraftContext.defaults(), 0L, 0.05d)).isEmpty());
	}

	/**
	 * The reason the two sides are combined rather than judged apart: an output down 4% whose
	 * materials are down 20% is a widening margin, and rejecting it on the output alone would throw
	 * away the better half of the strategy.
	 */
	@Test
	void keepsARecipeWhoseMarginIsWideningEvenAsTheOutputFalls() {
		Recipe recipe = profitable();
		drift("OUTPUT", -0.04d);
		drift("INPUT", -0.20d);

		assertFalse(candidates(recipe, context(CraftContext.defaults(), 0L, 0.05d)).isEmpty());
	}

	/** No history is no signal, not an adverse one: a fresh install would otherwise show nothing. */
	@Test
	void treatsAnUnrecordedRecipeAsNeutral() {
		assertFalse(candidates(profitable(), context(CraftContext.defaults(), 0L, 0.05d)).isEmpty());
	}

	/** An empty book is a fresh install, not a broken one. */
	@Test
	void handlesAnEmptyBazaarWithoutThrowing() {
		assertTrue(new CraftFlipStrategy(RecipeBook.of(List.of(recipe("OUTPUT", 1, "INPUT", 1))))
				.findCandidates(context()).isEmpty());
	}
}
