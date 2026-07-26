package jeff.skyblockflipper.core.ledger;

import jeff.skyblockflipper.core.strategy.FlipCandidate;
import jeff.skyblockflipper.core.strategy.StrategyKind;

/**
 * One flip the player actually took, and what it actually did.
 *
 * <p>The quoted numbers are frozen at open time on purpose. Comparing a fill against the book as
 * it looks now is how you talk yourself into believing a plan worked: the book has already moved,
 * usually in the direction that made your fill worse.
 *
 * @param quotedUnitNet   net profit per unit the strategy promised when the position was opened
 * @param unitsSold       how many units actually came back out, which is rarely all of them
 * @param realizedUnitNet net profit per unit actually achieved, on the same fee basis as the quote
 */
public record LedgerEntry(
		String id,
		String itemId,
		String displayName,
		StrategyKind kind,
		Status status,
		long openedAt,
		long units,
		double unitBuyPrice,
		double quotedUnitNet,
		long capital,
		long closedAt,
		long unitsSold,
		double unitSellPrice,
		double realizedUnitNet
) {
	public enum Status {
		/** Capital is committed and the outcome is not yet known. */
		OPEN,
		/** Closed with a reported fill. Only these carry a realized number. */
		CLOSED,
		/**
		 * Given up on - cancelled, or the order never filled. Carries no realized price, but the
		 * units that never sold are exactly the evidence a plan was unrealistic, so it is recorded
		 * rather than deleted.
		 */
		ABANDONED
	}

	public static LedgerEntry open(String id, FlipCandidate candidate, long openedAt) {
		return new LedgerEntry(
				id,
				candidate.itemId(),
				candidate.displayName(),
				candidate.kind(),
				Status.OPEN,
				openedAt,
				candidate.units(),
				candidate.unitBuyPrice(),
				candidate.unitNetProfit(),
				candidate.capitalRequired(),
				0L,
				0L,
				0.0d,
				0.0d);
	}

	public LedgerEntry closed(long closedAt, long unitsSold, double unitSellPrice, double realizedUnitNet) {
		return new LedgerEntry(id, itemId, displayName, kind, Status.CLOSED, openedAt, units,
				unitBuyPrice, quotedUnitNet, capital,
				closedAt, Math.clamp(unitsSold, 0L, units), unitSellPrice, realizedUnitNet);
	}

	public LedgerEntry abandoned(long closedAt) {
		return new LedgerEntry(id, itemId, displayName, kind, Status.ABANDONED, openedAt, units,
				unitBuyPrice, quotedUnitNet, capital, closedAt, 0L, 0.0d, 0.0d);
	}

	public boolean isOpen() {
		return status == Status.OPEN;
	}

	/** What the plan said this would make, on the units that actually transacted. */
	public double quotedOnFilled() {
		return quotedUnitNet * unitsSold;
	}

	/** What it did make. Units that never sold are inventory, not a loss, so they are not counted. */
	public double realizedTotal() {
		return realizedUnitNet * unitsSold;
	}
}
