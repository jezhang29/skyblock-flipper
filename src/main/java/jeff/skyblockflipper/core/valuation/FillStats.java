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
package jeff.skyblockflipper.core.valuation;

/**
 * How often a resting order at the top of one product's book stops being at the top.
 *
 * <p>A quoted spread assumes both legs fill, and the thing that stops them filling is not usually
 * the price being wrong - it is someone posting inside you. Your buy order at bid + 0.1 collects
 * the flow of players dumping into the book only for as long as nobody outbids it; after that it
 * sits behind their order earning nothing. The flow is already known from {@code quick_status}, so
 * this is the missing half: the rate at which you get displaced.
 *
 * <p><b>Deliberately not part of {@link PriceTrend}</b>, whose figures are all midpoints. Both are
 * computed from the same series in the same pass, but a midpoint cannot answer this question: a
 * book whose bid and ask both step up has repriced without displacing anyone on the buy side, and
 * one whose bid alone steps up has displaced you without moving the midpoint much.
 *
 * <p><b>Every rate here is a lower bound.</b> The tape samples at around five minutes, so any churn
 * that happens and reverses inside one sample is invisible. An estimate built on this is therefore
 * optimistic about how long you stay at the front - but it is measured, which the alternative
 * (a hardcoded guess at what share of the flow you capture) is not.
 *
 * <p><b>Time the client was closed is not time the book stood still.</b> Both figures cover only
 * intervals short enough to be consecutive samples; a gap where nobody was watching contributes
 * neither a count nor an hour. Measured against a real tape this is the difference between a rate
 * and a fiction: with a single overnight gap in the series, {@code WHEAT} scored 0.23 outbids an
 * hour counted naively against 4.86 counted over the intervals actually observed. The naive figure
 * would have told the mod that a heavily contested book was quiet, which is the exact direction of
 * error this class exists to remove.
 *
 * @param productId       bazaar product id
 * @param bidLiftsPerHour times an hour the best bid rose past where you would have posted, i.e.
 *                        how often a resting buy order gets outbid
 * @param askDropsPerHour times an hour the best ask fell past where you would have posted, i.e.
 *                        how often a resting sell offer gets undercut
 * @param hoursObserved   hours of actual observation behind the counts, summed over consecutive
 *                        samples rather than taken end to end
 * @param intervals       how many consecutive sample pairs contributed. At the nominal five-minute
 *                        cadence the {@link PriceTrend#MIN_SAMPLES} bar is about an hour of
 *                        uninterrupted watching, which is what it takes for a rate to mean anything
 */
public record FillStats(
		String productId,
		double bidLiftsPerHour,
		double askDropsPerHour,
		double hoursObserved,
		int intervals
) {
	/** Enough uninterrupted observation for the rates above to be worth reading. */
	public boolean isUsable() {
		return intervals >= PriceTrend.MIN_SAMPLES && hoursObserved > 0.0d;
	}
}
