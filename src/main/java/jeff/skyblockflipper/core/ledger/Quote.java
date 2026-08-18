package jeff.skyblockflipper.core.ledger;

import jeff.skyblockflipper.core.strategy.FlipCandidate;
import jeff.skyblockflipper.core.strategy.StrategyKind;

/**
 * What the mod promised about one item, in the shape the ledger needs to hold it to account.
 *
 * <p>A {@link FlipCandidate} carries steps, risks, notes and a fill estimate, none of which the
 * ledger reads; a {@code NpcBasket.Line} carries a plan sized against shared budgets and is not a
 * candidate at all. Both are quotes. This is the part they have in common, so a basket line can be
 * held to its promise without being dressed up as something it is not.
 *
 * <p><b>{@link #kind()} is the strategy that made the promise, not the venue the buy happened at.</b>
 * An NPC flip is bought on the bazaar like any other bazaar trade, so deciding the kind from the
 * settlement books it as a bazaar spread - which is how the NPC daily cap came to read zero
 * forever, since {@code Ledger.npcCoinsReceivedSince} counts {@code NPC_FLIP} entries and nothing
 * ever wrote one.
 *
 * @param unitNetProfit profit per unit the strategy promised, which is the number the capture rate
 *                      is measured against and the one thing that must never be recomputed later
 */
public record Quote(
		String itemId,
		String displayName,
		StrategyKind kind,
		double unitBuyPrice,
		double unitNetProfit,
		long units,
		long capitalRequired
) {
	public static Quote of(FlipCandidate candidate) {
		return new Quote(candidate.itemId(), candidate.displayName(), candidate.kind(),
				candidate.unitBuyPrice(), candidate.unitNetProfit(), candidate.units(),
				candidate.capitalRequired());
	}
}
