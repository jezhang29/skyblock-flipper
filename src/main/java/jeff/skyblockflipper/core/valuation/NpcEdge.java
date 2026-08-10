package jeff.skyblockflipper.core.valuation;

import jeff.skyblockflipper.core.model.BazaarProduct;

import java.time.Duration;

/**
 * How reliably one product's buy side has sat below the price an NPC pays for it.
 *
 * <p>Buying on the bazaar with a resting order and selling to an NPC is arbitrage against a constant:
 * the exit price cannot move, so the only question is whether the entry price is low enough and
 * whether it stays low enough to be worth an order slot. A single look at the book answers neither.
 * Measured over three days of the user's own tape, 260 products showed a bid under the NPC price and
 * <b>204 of the 223 liquid ones held that gap in at least 95% of samples</b> - these are standing
 * features of the book, not a race. The ones that flicker are the ones that waste a slot.
 *
 * <p><b>Every figure here is a screen, never a quote.</b> A plan prices off the live book at the
 * moment it is opened, the same rule the ledger holds to; a median taken over three days is what
 * decides whether the product is worth looking at at all.
 *
 * <p>The edge test is {@code bid + 0.1 < npcPrice}, and the {@code + 0.1} is not decoration: you
 * cannot buy at the bid, you buy at {@link BazaarProduct#outbidBuyOrder()}, one increment above it.
 *
 * @param productId         bazaar product id
 * @param npcPrice          what the NPC paid at the time this was computed, carried so the ratios
 *                          below can be read back as coins without a second catalog lookup
 * @param persistence       fraction of samples in which posting a buy order would have priced under
 *                          the NPC, i.e. how often the trade existed at all
 * @param medianMarginRatio median over the same samples of {@code (npcPrice - postPrice) / npcPrice},
 *                          <b>including samples where there was no edge</b>. A product whose gap is
 *                          usually absent should read as a thin median rather than as a fat one
 *                          measured over its good half
 * @param bidDriftPerHour   coins an hour of cumulative <i>upward</i> movement in the best bid - what
 *                          repricing to stay at the front of the book costs. See
 *                          {@link #chaseCostRatio(Duration)}
 * @param hoursObserved     hours of actual observation behind {@code bidDriftPerHour}, summed over
 *                          consecutive samples rather than taken end to end
 * @param intervals         how many consecutive sample pairs contributed to the drift
 * @param samples           samples with a live bid, which is what {@link #isUsable()} tests
 */
public record NpcEdge(
		String productId,
		double npcPrice,
		double persistence,
		double medianMarginRatio,
		double bidDriftPerHour,
		double hoursObserved,
		int intervals,
		int samples
) {
	/**
	 * Samples needed before any of this is reported.
	 *
	 * <p>Far above {@link PriceTrend#MIN_SAMPLES}, and for a different reason. A trend is asking
	 * which way the price is going now, so a short window is the honest one. This is asking whether
	 * a gap is a standing feature of the book, and the only way to be wrong about that is to look at
	 * too little of it. At the tape's five-minute cadence 200 samples is about seventeen hours, which
	 * spans a full daily cycle of when players are online. Measured against the live tape, 759
	 * products clear it.
	 */
	public static final int MIN_SAMPLES = 200;

	/** Enough of the tape behind these figures for them to be worth reading. */
	public boolean isUsable() {
		return samples >= MIN_SAMPLES;
	}

	/**
	 * Whether the gap was there often enough to be worth an order slot.
	 *
	 * <p>The threshold protects slots rather than coins, which is a distinction worth keeping
	 * straight. With a hard chase stop a vanished edge does not lose money - the order simply never
	 * fills, and an unfilled order is cancelled. What it costs is one of 21 slots for the length of a
	 * cycle. Measured on a holdout backtest: of 161 products above 95%, <b>none</b> realized a loss,
	 * against 2 of 22 in the 50-95% band.
	 */
	public boolean holdsEdge(double minPersistence) {
		return persistence >= minPersistence;
	}

	/**
	 * What chasing the book upward for {@code horizon} costs, as a share of the NPC price.
	 *
	 * <p>Directly comparable with {@link #medianMarginRatio()}, which is the comparison that decides
	 * whether an item is worth posting: measured over 8 hours, {@code CLIPPED_WINGS} costs 0.00%,
	 * {@code BEADY_EYES} 0.56% against a 34% margin, and {@code MANTID_CLAW} 14.4% against 30%.
	 *
	 * <p><b>This is the pessimistic reading of the drift, on purpose.</b> Summing every upward step
	 * charges for each one separately; a player who never reprices downward actually pays only the
	 * running maximum, and one who does reprice down pays the average. Both are smaller. Erring
	 * toward quoting less profit than the trade makes is the only safe direction here.
	 */
	public double chaseCostRatio(Duration horizon) {
		if (horizon == null || npcPrice <= 0.0d) {
			return 0.0d;
		}

		double hours = horizon.toMillis() / 3_600_000.0d;

		return hours <= 0.0d ? 0.0d : bidDriftPerHour * hours / npcPrice;
	}
}
