package jeff.skyblockflipper.core.pricing;

import jeff.skyblockflipper.core.model.BazaarProduct;
import jeff.skyblockflipper.core.model.BazaarSnapshot;
import jeff.skyblockflipper.core.pricing.CraftQuote.FillHistory;
import jeff.skyblockflipper.core.pricing.FillModel.FillEstimate;
import jeff.skyblockflipper.core.recipe.FusionTable;
import jeff.skyblockflipper.core.recipe.FusionTable.Route;
import jeff.skyblockflipper.core.recipe.FusionTable.Shard;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * What one attribute-shard fusion is worth per hour and per fusion click at the current order book.
 *
 * <p>Fusion is combine's twin - buy cheap inputs, transform them, rest a sell offer on a dearer
 * output - with one difference that shapes the whole quote: the transform is a <b>tree</b>, not a
 * doubling. An input is sourced at the cheaper of buying it on the bazaar or fusing it from cheaper
 * shards still, recursively, up to a depth cap. So this is a min-cost relaxation over the fusion
 * graph rather than combine's single geometric leg. See {@code docs/fusion-flipping.md} and read
 * {@code docs/combine-flipping.md} for the shared frame.
 *
 * <h2>The cost formula</h2>
 *
 * <p>From SkyShards' {@code calculationService.ts}, the cost of one fusion producing output units:
 *
 * <pre>
 *   totalCost = minCost(inputA) * inputA.fuse_amount + minCost(inputB) * inputB.fuse_amount
 *   effQty    = isReptile ? outputQuantity * crocodileMultiplier : outputQuantity
 *   costPerOutputUnit = totalCost / effQty
 * </pre>
 *
 * <p>{@code minCost(shard)} is the recursion: the cheaper of the bazaar acquire cost and the best
 * fusion. Cost only rises going up a tree - each fusion adds its inputs' cost - so cycles never lower
 * it and a depth budget is the only guard the relaxation needs. {@link Solver} computes the whole
 * memo once against a book so all 300-odd outputs price off one pass.
 *
 * <h2>Source and exit, both combine's</h2>
 *
 * <p>A leaf shard is acquired the way a combine sources a book: a resting buy order at the bid where
 * it has at least {@link #MIN_TARGET_ASK_ORDERS} bid orders, else an instant buy at the ask. The
 * output is sold into an offer one increment under the best ask, taxed, and never dumped into the
 * bid - shard spreads are enormous. The exit gate is one-sided, the target's resting ask count, for
 * the same reason it is on a combine: the buyers of a shard fuse it themselves and rest no bids.
 *
 * @param outputId            the shard this flip sells
 * @param outputs             output shards planned over the horizon, after every limit
 * @param outputsPerHour      sustainable rate: the smaller of the sell demand and the slowest leaf
 * @param sourceCostPerOutput coins to source and fuse one output, summed over the flattened leaves
 * @param unitSellPrice       coins per output before tax: one increment under the best ask
 * @param netPerOutput        profit per output after tax on the sale
 * @param profitPerHour       the shared ranking axis
 * @param bound               which side holds the rate down
 * @param fill                the sell leg's estimate, carried so a candidate can say whether the
 *                            rate was measured or assumed
 * @param leaves              the base shards bought, aggregated by id, with their per-output count
 * @param fusions             the fusion clicks, bottom-up, each with its per-output frequency
 * @param fusionsPerOutput    total fusion clicks to make one output across the whole tree
 */
public record FusionQuote(
		String outputId,
		long outputs,
		double outputsPerHour,
		double sourceCostPerOutput,
		double unitSellPrice,
		double netPerOutput,
		double profitPerHour,
		Bound bound,
		FillEstimate fill,
		List<Leaf> leaves,
		List<Fusion> fusions,
		double fusionsPerOutput
) {
	/** How a base shard is acquired. */
	public enum SourceRoute {
		/** Rest a buy order one increment above the best bid. Cheaper, waits on dumps. */
		BUY_ORDER,
		/** Take the ask now, for a shard with no bid side to rest against. */
		INSTANT_BUY
	}

	/** What holds the plan's rate down. */
	public enum Bound {
		/** The output's sell offer sheds slower than the inputs arrive. */
		SELL_LEG,
		/** The slowest base shard arrives slower than the output sells. */
		SOURCE_SUPPLY,
		/** Coins ran out first. */
		CAPITAL
	}

	/**
	 * One base shard the flip buys.
	 *
	 * @param shardId       the bazaar id
	 * @param route         how it is acquired
	 * @param unitPrice     coins per shard: the bid to rest at, or the ask to take
	 * @param unitsPerOutput how many of it one output shard needs, summed across every branch of the
	 *                      tree that reaches it
	 */
	public record Leaf(String shardId, SourceRoute route, double unitPrice, double unitsPerOutput) {
	}

	/**
	 * One fusion click in the tree.
	 *
	 * @param outputId        the shard this fusion produces
	 * @param inputA          first input shard id
	 * @param amountA         how many of input A one click consumes ({@code fuse_amount})
	 * @param inputB          second input shard id
	 * @param amountB         how many of input B one click consumes
	 * @param outputQuantity  raw units produced per click, 1 or 2, before the reptile perk
	 * @param reptile         whether the perk applies, i.e. either input is a Reptile family
	 * @param fusionsPerOutput how many of this click one output shard needs
	 */
	public record Fusion(String outputId, String inputA, int amountA, String inputB, int amountB,
			int outputQuantity, boolean reptile, double fusionsPerOutput) {
	}

	/** Resting orders a side must show before its price is believed. Combine's gate, the same 15. */
	public static final int MIN_TARGET_ASK_ORDERS = CraftQuote.MIN_ORDERS_PER_SIDE;

	/**
	 * The deepest a fusion tree may go: three fusion levels below the output. Settled in the spec;
	 * within a few levels the click budget does not bind, so a cap trades away only rare deep trees.
	 */
	public static final int DEPTH_CAP = 3;

	/**
	 * The min-cost engine over one book. Built once, queried per output.
	 *
	 * <p>The memo {@code cost[shard][d]} is the cheapest per-unit cost of {@code shard} when at most
	 * {@code d} fusion levels are allowed below it; {@code d = 0} is the bare bazaar acquire cost.
	 * Each level depends only on the one below it, so there are no cycles to guard and one bottom-up
	 * pass fills the table. The decision that produced each cell is kept so a tree can be rebuilt.
	 */
	public static final class Solver {
		private final FusionTable table;
		private final BazaarSnapshot bazaar;
		private final double crocMultiplier;
		private final Map<String, Acquire> acquire = new HashMap<>();
		private final Map<String, double[]> cost = new HashMap<>();
		private final Map<String, Route[]> chosen = new HashMap<>();

		private Solver(FusionTable table, BazaarSnapshot bazaar, int crocodileLevel) {
			this.table = table;
			this.bazaar = bazaar;
			this.crocMultiplier = 1.0d + 0.02d * Math.max(0, crocodileLevel);
			build();
		}

		private void build() {
			// Level 0: every shard costs what it costs to buy, or infinity if it is not a product.
			for (String id : table.shardIds()) {
				Acquire buy = acquireOf(id);

				if (buy != null) {
					acquire.put(id, buy);
				}

				// Every depth starts at the buy cost, so a shard that is never fused (a pure leaf, or
				// one with no route) keeps its buy cost - infinity when it has no product - at every
				// level. Leaving the upper cells at their default 0.0 would price an unbuyable leaf as
				// free and let a route through that reconstruction then cannot source.
				double[] byDepth = new double[DEPTH_CAP + 1];
				Arrays.fill(byDepth, buy == null ? Double.POSITIVE_INFINITY : buy.unitPrice());
				cost.put(id, byDepth);
				chosen.put(id, new Route[DEPTH_CAP + 1]);
			}

			// Levels 1..cap: a shard may instead be fused, if that beats buying it.
			for (int depth = 1; depth <= DEPTH_CAP; depth++) {
				for (String output : table.outputs()) {
					double[] cells = cost.get(output);
					double best = cells[0];
					Route bestRoute = null;

					for (Route route : table.recipesFor(output)) {
						double fused = fuseUnitCost(route, depth - 1);

						if (fused < best) {
							best = fused;
							bestRoute = route;
						}
					}

					cells[depth] = best;
					chosen.get(output)[depth] = bestRoute;
				}
			}
		}

		/** The per-output-unit cost of fusing {@code route}, with children solved at {@code childDepth}. */
		private double fuseUnitCost(Route route, int childDepth) {
			double a = cost.getOrDefault(route.inputA(), INFINITE)[childDepth];
			double b = cost.getOrDefault(route.inputB(), INFINITE)[childDepth];

			if (!Double.isFinite(a) || !Double.isFinite(b)) {
				return Double.POSITIVE_INFINITY;
			}

			return (a * fuseAmount(route.inputA()) + b * fuseAmount(route.inputB()))
					/ effectiveQuantity(route);
		}

		private double effectiveQuantity(Route route) {
			return route.reptile() ? route.outputQty() * crocMultiplier : route.outputQty();
		}

		private int fuseAmount(String shardId) {
			return table.shard(shardId).map(Shard::fuseAmount).orElse(1);
		}

		/**
		 * The flattened cheapest tree for {@code outputId}, or empty when it cannot be made.
		 *
		 * <p>The root is forced to fuse - buying and reselling the same shard is not a flip - so its
		 * children are solved with one fewer level than the cap. Below the root each node takes the
		 * memo's decision: buy where buying was cheapest, fuse otherwise.
		 */
		public Optional<Tree> tree(String outputId) {
			Route rootRoute = null;
			double best = Double.POSITIVE_INFINITY;

			for (Route route : table.recipesFor(outputId)) {
				double fused = fuseUnitCost(route, DEPTH_CAP - 1);

				if (fused < best) {
					best = fused;
					rootRoute = route;
				}
			}

			if (rootRoute == null || !Double.isFinite(best)) {
				return Optional.empty();
			}

			Node root = new Node(outputId, rootRoute,
					node(rootRoute.inputA(), DEPTH_CAP - 1), node(rootRoute.inputB(), DEPTH_CAP - 1));
			Accumulator acc = new Accumulator();
			acc.walk(root, 1.0d);

			List<Leaf> leaves = new ArrayList<>(acc.leaves.size());

			for (Map.Entry<String, Double> entry : acc.leaves.entrySet()) {
				Acquire buy = acquire.get(entry.getKey());

				if (buy == null) {
					return Optional.empty();
				}

				leaves.add(new Leaf(entry.getKey(), buy.route(), buy.unitPrice(), entry.getValue()));
			}

			return Optional.of(new Tree(leaves, acc.fusions, acc.costPerOutput, acc.fusionsPerOutput));
		}

		/** The node for {@code shardId} at {@code depth}: a buy leaf, or a fusion the memo chose. */
		private Node node(String shardId, int depth) {
			Route route = depth >= 1 ? chosen.get(shardId)[depth] : null;

			return route == null
					? new Node(shardId, null, null, null)
					: new Node(shardId, route,
							node(route.inputA(), depth - 1), node(route.inputB(), depth - 1));
		}

		/** Accumulates leaf counts, fusion frequencies and cost as it walks one output's tree. */
		private final class Accumulator {
			private final Map<String, Double> leaves = new HashMap<>();
			private final List<Fusion> fusions = new ArrayList<>();
			private double costPerOutput;
			private double fusionsPerOutput;

			/** {@code unitsNeeded} = output units of {@code node} required per one root output. */
			private void walk(Node node, double unitsNeeded) {
				if (node.route() == null) {
					leaves.merge(node.shardId(), unitsNeeded, Double::sum);

					// A leaf the memo priced always has an acquire; the null guard is only so a book
					// that changed under a reconstruction fails into tree()'s empty check, not an NPE.
					Acquire buy = acquire.get(node.shardId());

					if (buy != null) {
						costPerOutput += unitsNeeded * buy.unitPrice();
					}

					return;
				}

				Route route = node.route();
				double clicks = unitsNeeded / effectiveQuantity(route);
				int amountA = fuseAmount(route.inputA());
				int amountB = fuseAmount(route.inputB());

				// Children first, so the fusion list reads bottom-up: make the inputs, then fuse them.
				walk(node.a(), clicks * amountA);
				walk(node.b(), clicks * amountB);

				fusionsPerOutput += clicks;
				fusions.add(new Fusion(node.shardId(), route.inputA(), amountA, route.inputB(),
						amountB, route.outputQty(), route.reptile(), clicks));
			}
		}

		private Acquire acquireOf(String shardId) {
			BazaarProduct product = bazaar == null ? null : bazaar.product(shardId).orElse(null);

			if (product == null || product.sellOffers().isEmpty()) {
				return null;
			}

			double bid = product.outbidBuyOrder().orElse(-1.0d);

			if (bid > 0.0d && product.buyOrderCount() >= MIN_TARGET_ASK_ORDERS) {
				return new Acquire(SourceRoute.BUY_ORDER, bid);
			}

			double ask = product.instantBuyPrice().orElse(-1.0d);

			return ask > 0.0d ? new Acquire(SourceRoute.INSTANT_BUY, ask) : null;
		}

		BazaarSnapshot bazaar() {
			return bazaar;
		}
	}

	private static final double[] INFINITE = {
			Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
			Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY
	};

	private record Acquire(SourceRoute route, double unitPrice) {
	}

	private record Node(String shardId, Route route, Node a, Node b) {
	}

	/** A solved cheapest tree: what to buy, what to fuse, and what one output costs. */
	public record Tree(List<Leaf> leaves, List<Fusion> fusions, double costPerOutput,
			double fusionsPerOutput) {
	}

	/** Builds the min-cost engine for one book. */
	public static Solver solver(FusionTable table, BazaarSnapshot bazaar, int crocodileLevel) {
		return new Solver(table, bazaar, crocodileLevel);
	}

	/**
	 * Prices one output against the book the solver was built on, or empty when it does not clear.
	 *
	 * @param solver     the min-cost engine, built once for the whole book
	 * @param outputId   the shard to sell
	 * @param history    measured displacement per product
	 * @param horizon    how long an order may rest
	 * @param maxCapital the most coins this one plan may tie up
     * @param flowShare  fraction of flow to assume where the tape has not measured it
	 */
	public static Optional<FusionQuote> quote(Solver solver, String outputId, Fees fees,
			FillHistory history, Duration horizon, long maxCapital, double flowShare) {
		if (solver == null || outputId == null || fees == null || maxCapital <= 0L) {
			return Optional.empty();
		}

		BazaarSnapshot bazaar = solver.bazaar();
		BazaarProduct target = bazaar == null ? null : bazaar.product(outputId).orElse(null);

		if (target == null || target.sellOffers().isEmpty()
				|| target.sellOfferCount() < MIN_TARGET_ASK_ORDERS) {
			return Optional.empty();
		}

		double sellPrice = target.undercutSellOffer().orElse(-1.0d);

		if (sellPrice <= 0.0d) {
			return Optional.empty();
		}

		Tree tree = solver.tree(outputId).orElse(null);

		if (tree == null || tree.leaves().isEmpty()) {
			return Optional.empty();
		}

		FillHistory lookup = history == null ? FillHistory.none() : history;
		FillEstimate fill = FillModel.estimate(target, lookup.forProduct(outputId), horizon, flowShare);
		double exitRate = fill.sellUnitsPerHour();

		if (exitRate <= 0.0d) {
			return Optional.empty();
		}

		// The slowest base shard sets the source rate: its fill rate divided by how many of it one
		// output needs. A shard that both rests and is many-per-output is what holds the plan back.
		double sourceRate = Double.POSITIVE_INFINITY;

		for (Leaf leaf : tree.leaves()) {
			BazaarProduct product = bazaar.product(leaf.shardId()).orElse(null);

			if (product == null) {
				return Optional.empty();
			}

			double unitsPerHour = leaf.route() == SourceRoute.BUY_ORDER
					? FillModel.estimate(product, null, horizon, flowShare).buyUnitsPerHour()
					: product.instantBuysPerHour() * flowShare;
			double outputsFromLeaf = unitsPerHour / leaf.unitsPerOutput();

			sourceRate = Math.min(sourceRate, outputsFromLeaf);
		}

		double outputsPerHour = Math.min(exitRate, sourceRate);

		if (outputsPerHour <= 0.0d) {
			return Optional.empty();
		}

		Bound bound = sourceRate < exitRate ? Bound.SOURCE_SUPPLY : Bound.SELL_LEG;
		double horizonHours = hoursOf(horizon);
		long outputs = Math.max(1L, (long) (outputsPerHour * horizonHours));

		double costPerOutput = tree.costPerOutput();
		long affordable = (long) (maxCapital / costPerOutput);

		if (affordable <= 0L) {
			return Optional.empty();
		}

		if (affordable < outputs) {
			outputs = affordable;
			bound = Bound.CAPITAL;
		}

		double netPerOutput = fees.bazaarSaleProceeds(sellPrice) - costPerOutput;

		if (netPerOutput <= 0.0d) {
			return Optional.empty();
		}

		double ratePerHour = Math.min(outputsPerHour, outputs / horizonHours);

		return Optional.of(new FusionQuote(outputId, outputs, outputsPerHour, costPerOutput, sellPrice,
				netPerOutput, netPerOutput * ratePerHour, bound, fill, List.copyOf(tree.leaves()),
				List.copyOf(tree.fusions()), tree.fusionsPerOutput()));
	}

	/** {@link #quote} at the shared 5% flow share. */
	public static Optional<FusionQuote> quote(Solver solver, String outputId, Fees fees,
			FillHistory history, Duration horizon, long maxCapital) {
		return quote(solver, outputId, fees, history, horizon, maxCapital, CraftQuote.DEFAULT_FLOW_SHARE);
	}

	/** Base shards the whole plan buys of one leaf. */
	public long shardsToBuy(Leaf leaf) {
		return (long) Math.ceil(leaf.unitsPerOutput() * outputs);
	}

	/** Fusion clicks the whole plan makes, the click cost it is really spending. */
	public long totalFusions() {
		return Math.round(fusionsPerOutput * outputs);
	}

	/** Profit per fusion click, the honest per-effort figure for a click-limited player. */
	public double netPerFusion() {
		return fusionsPerOutput <= 0.0d ? netPerOutput : netPerOutput / fusionsPerOutput;
	}

	public long capitalRequired() {
		return Math.round(sourceCostPerOutput * outputs);
	}

	public double totalNetProfit() {
		return netPerOutput * outputs;
	}

	/** Whether the sell leg's rate came from recorded history rather than an assumed share. */
	public boolean fillMeasured() {
		return fill != null && fill.measured();
	}

	private static double hoursOf(Duration horizon) {
		double hours = horizon == null ? 0.0d : horizon.toMillis() / 3_600_000.0d;

		return hours > 0.0d ? hours : 1.0d;
	}
}
