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
package jeff.skyblockflipper.core.pricing;

import jeff.skyblockflipper.core.model.BazaarProduct;
import jeff.skyblockflipper.core.valuation.FillStats;

import java.time.Duration;
import java.util.Optional;

/**
 * How many units a resting bazaar order actually fills in an hour.
 *
 * <p>Every quoted spread in this mod assumes both legs fill, and until this existed the throughput
 * behind that assumption was a hardcoded guess - a flat "assume a modest share of the flow routes
 * through your orders". Nothing measured whether it did. Two rates decide it, and both are now
 * available:
 *
 * <ul>
 *   <li><b>Arrival flow.</b> How fast other players dump into the bid side, or lift the ask side.
 *       Straight off {@code quick_status} via {@link BazaarProduct#instantSellsPerHour()} and
 *       {@link BazaarProduct#instantBuysPerHour()}. This is the ceiling: an order fills no faster
 *       than people trade with it, however good the price looks.</li>
 *   <li><b>Displacement.</b> How fast someone posts inside you, at which point you stop collecting
 *       that flow entirely. Measured from the bazaar tape - see {@link FillStats}.</li>
 * </ul>
 *
 * <p>Treating displacement as a Poisson process at rate {@code d}, an order posted now spends
 * {@code (1 - e^(-d*H)) / H} of the horizon {@code H} at the front of the book on average, and
 * collects the arrival flow only during it. That gives
 * {@code expectedUnitsPerHour = flow * (1 - e^(-d*H)) / (d * H)}, which degenerates the way it
 * should at both ends: an order nobody ever outbids collects the whole flow, and one displaced
 * constantly collects almost none of it.
 *
 * <p><b>This deliberately models a player who does not chase.</b> Repricing after every undercut
 * would restore the full flow at the cost of buying higher each time, which is the undercut spiral
 * {@code BazaarSpreadStrategy} already warns about and whose steps already say not to do. Modelling
 * the disciplined version keeps the number and the advice consistent.
 *
 * <p>Queue position ahead of you is not modelled, because at post time there is none:
 * {@link BazaarProduct#outbidBuyOrder()} prices you strictly inside the current best. What happens
 * after that is exactly what the displacement rate measures.
 *
 * <p>Pure and static, like {@link Fees}: same inputs, same answer, no clock and no state.
 */
public final class FillModel {
	private FillModel() {
	}

	/**
	 * What an order at the top of the book is expected to fill, per hour, on each side.
	 *
	 * @param buyUnitsPerHour  units a resting buy order takes in per hour
	 * @param sellUnitsPerHour units a resting sell offer sheds per hour
	 * @param outbidsPerHour   how often the buy side gets displaced, carried through so the UI can
	 *                         say why a fill is slow rather than only that it is
	 * @param measured         false when this is the unmeasured fallback rather than a figure taken
	 *                         from recorded history. Every caller must be able to say which it has:
	 *                         presenting a guess as a measurement is the failure mode this whole
	 *                         class exists to remove
	 */
	public record FillEstimate(
			double buyUnitsPerHour,
			double sellUnitsPerHour,
			double outbidsPerHour,
			boolean measured
	) {
		/**
		 * Round-trip throughput: a market maker is only as fast as their slower leg.
		 *
		 * <p>Buying 400 units an hour is worth nothing if the sell side only clears 30 - the
		 * remainder is not profit, it is inventory, and inventory is the position the strategy is
		 * trying to avoid holding.
		 */
		public double throughputPerHour() {
			return Math.min(buyUnitsPerHour, sellUnitsPerHour);
		}

		/** How long the buy leg takes to take in {@code units}, or empty if it never does. */
		public Optional<Duration> buyTimeToFill(long units) {
			return timeToFill(units, buyUnitsPerHour);
		}

		/** How long the sell leg takes to shed {@code units}, or empty if it never does. */
		public Optional<Duration> sellTimeToFill(long units) {
			return timeToFill(units, sellUnitsPerHour);
		}

		private static Optional<Duration> timeToFill(long units, double unitsPerHour) {
			if (units <= 0L || unitsPerHour <= 0.0d) {
				return Optional.empty();
			}

			return Optional.of(Duration.ofSeconds(Math.round(units / unitsPerHour * 3600.0d)));
		}
	}

	/**
	 * Estimates both legs for a product, from recorded displacement where there is any.
	 *
	 * @param stats         measured displacement, or null when the tape has not seen this product
	 *                      for long enough. Null is expected, not exceptional: a fresh install has
	 *                      no history at all and must still rank candidates
	 * @param horizon       how long the player is willing to leave an order resting. A longer
	 *                      horizon means more chances to be displaced, so the average fill rate over
	 *                      it falls - which is the honest shape, not a modelling artefact
	 * @param fallbackShare the fraction of flow to assume when {@code stats} is null. Supplied by
	 *                      the caller rather than fixed here because the two strategies that need it
	 *                      inherited different assumptions, and quietly unifying them would change
	 *                      today's rankings under cover of a refactor
	 */
	public static FillEstimate estimate(BazaarProduct product, FillStats stats, Duration horizon,
			double fallbackShare) {
		double buyFlow = product.instantSellsPerHour();
		double sellFlow = product.instantBuysPerHour();

		if (stats == null || !stats.isUsable()) {
			return new FillEstimate(buyFlow * fallbackShare, sellFlow * fallbackShare, 0.0d, false);
		}

		double hours = hoursOf(horizon);
		double bidLifts = stats.bidLiftsPerHour();

		return new FillEstimate(
				expectedUnitsPerHour(buyFlow, bidLifts, hours),
				expectedUnitsPerHour(sellFlow, stats.askDropsPerHour(), hours),
				bidLifts,
				true);
	}

	/**
	 * Average fill rate over the horizon for one leg.
	 *
	 * <p>The closed form is the expected fraction of the horizon spent at the front of the book,
	 * times the arrival flow. Written out rather than left implicit because the two limits are what
	 * make it trustworthy: as {@code d} approaches zero the fraction approaches one and the whole
	 * flow is collected, and as it grows the fraction approaches {@code 1 / (d * H)}, so an order
	 * outbid twice a minute is credited with almost nothing.
	 */
	private static double expectedUnitsPerHour(double flowPerHour, double displacementsPerHour,
			double horizonHours) {
		if (flowPerHour <= 0.0d || horizonHours <= 0.0d) {
			return 0.0d;
		}

		if (displacementsPerHour <= 0.0d) {
			// Never observed being displaced. Not "displaced infinitely slowly" as a modelling
			// convenience - over a window of hundreds of samples it is the measurement.
			return flowPerHour;
		}

		double exponent = displacementsPerHour * horizonHours;
		double fractionAtTop = -Math.expm1(-exponent) / exponent;

		return flowPerHour * fractionAtTop;
	}

	private static double hoursOf(Duration horizon) {
		return horizon == null ? 0.0d : horizon.toMillis() / 3_600_000.0d;
	}
}
