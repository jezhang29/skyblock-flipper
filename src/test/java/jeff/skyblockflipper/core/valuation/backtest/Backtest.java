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
package jeff.skyblockflipper.core.valuation.backtest;

import jeff.skyblockflipper.core.item.DecodedItem;
import jeff.skyblockflipper.core.valuation.FairValueModel;
import jeff.skyblockflipper.core.valuation.Keying;
import jeff.skyblockflipper.core.valuation.ValueEstimate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Predicate;

/**
 * Prices a held-out slice of the tape with the model that ships.
 *
 * <p>This is how every valuation claim in the repo is justified: train on everything older than a
 * cutoff, price what is newer, and compare the quote to what the sale actually fetched. The reason
 * it is a module is that eight backtests used to do it by hand, and the hand-rolled copies were not
 * the model. They kept every sample where the model keeps the most recent 200, walked one key where
 * the model walks a ladder, had no price-to-bid ratio index at all, and each carried its own
 * retyping of the bareness test with one clause taken out. A finding measured against that is a
 * finding about a program nobody runs.
 *
 * <p>The counterfactual arms those tests exist to score - <i>what if this attribute were unread</i>,
 * <i>what if a finer term shipped</i> - are expressed as a {@link Keying}, so the model around them
 * stays the real one. See {@code CONTEXT.md}.
 *
 * <p>Returns one row per priced sale rather than a summary, because every caller counts something
 * different: merged sales, parted ones, sales carrying a bid, sales overvalued by half rather than
 * by double. A summary wide enough for all of them would have to grow on each new question, and the
 * rows answer questions nobody has asked yet - {@code MidasBidBacktestTest} reconstructs a floored
 * variant production has no rule for by joining two runs on {@link Priced#saleKey()}.
 */
public final class Backtest {
	private Backtest() {
	}

	/**
	 * One held-out sale that got a quote.
	 *
	 * <p>Held-out sales are kept decoded, which is the one thing here that costs memory. It is bounded
	 * by the holdout length rather than by the tape: the blobs are dropped as they stream past and
	 * only the decoded scalars survive.
	 *
	 * @param actual   what the sale fetched, per unit
	 * @param estimate what the model quoted, per unit
	 */
	public record Priced(String saleKey, DecodedItem item, long timestamp, double actual,
			double estimate, ValueEstimate.Basis basis) {
		/** Absolute log error, the scale-free way to average a 2x miss on a cheap and a dear item. */
		public double logError() {
			return Math.abs(Math.log(estimate / actual));
		}

		public boolean overvaluedBy(double factor) {
			return estimate / actual >= factor;
		}
	}

	/**
	 * @param priced   held-out sales the model could quote, in tape order
	 * @param heldOut  held-out sales it saw, quoted or not - the denominator for coverage
	 * @param trained  sales that reached the model's indices
	 */
	public record Result(List<Priced> priced, int heldOut, int trained) {
		public double medianLogError() {
			List<Double> sorted = sortedLogErrors();
			return sorted.isEmpty() ? Double.NaN : sorted.get(sorted.size() / 2);
		}

		public double p90LogError() {
			List<Double> sorted = sortedLogErrors();
			return sorted.isEmpty() ? Double.NaN : sorted.get(sorted.size() * 9 / 10);
		}

		/** Quotes at or above {@code factor} times what the sale fetched - the fake snipes. */
		public int overvaluedBy(double factor) {
			return (int) priced.stream().filter(p -> p.overvaluedBy(factor)).count();
		}

		/** For the counters each caller defines itself: merged, parted, scrolled, bid-carrying. */
		public int count(Predicate<DecodedItem> subset) {
			return (int) priced.stream().filter(p -> subset.test(p.item())).count();
		}

		public int within(double factor, Predicate<DecodedItem> subset) {
			return (int) priced.stream()
					.filter(p -> subset.test(p.item()))
					.filter(p -> p.estimate() / p.actual() <= factor
							&& p.actual() / p.estimate() <= factor)
					.count();
		}

		private List<Double> sortedLogErrors() {
			return priced.stream().map(Priced::logError).sorted().toList();
		}

		@Override
		public String toString() {
			return String.format("%,5d priced of %,d held out, %,d over 2x, median |log err| %.3f, "
							+ "p90 %.3f",
					priced.size(), heldOut, overvaluedBy(2.0d), medianLogError(), p90LogError());
		}
	}

	/**
	 * Trains under {@code keying} on everything older than {@code cutoff}, then prices what is newer.
	 *
	 * <p>One pass over the tape. Each sale is decoded once, and whether it trains the model or gets
	 * priced by it is decided on its timestamp alone - no sale does both, which is the whole point of
	 * a holdout.
	 *
	 * @param window  how far back of training the model may see. Pass something longer than the tape
	 *                to reproduce an unbounded replay; pass the shipped valuation window to ask what
	 *                the model as configured would have done
	 * @param include which sales are in the measurement at all, on both sides of the cutoff. Usually
	 *                the item ids that ever carry the attribute under test
	 */
	public static Result holdout(Keying keying, long cutoff, Duration window,
			Predicate<DecodedItem> include) throws Exception {
		return holdout(keying, cutoff, window, include, (item, extra, timestamp, unitPrice) -> {
		});
	}

	/**
	 * As above, showing every included sale to {@code observer} first.
	 *
	 * <p>The hook exists for {@link UnreadTerms}: an attribute nothing decodes has to be read off the
	 * raw blob during this pass, because the decoded items it is remembered against are created here
	 * and exist nowhere else.
	 */
	public static Result holdout(Keying keying, long cutoff, Duration window,
			Predicate<DecodedItem> include, TapeFixture.SaleVisitor observer) throws Exception {
		FairValueModel.Builder builder =
				FairValueModel.builder(Instant.ofEpochMilli(cutoff), window, keying);
		List<Held> held = new ArrayList<>();

		TapeFixture.forEachSale((item, extra, timestamp, unitPrice) -> {
			if (unitPrice <= 0.0d || !include.test(item)) {
				return;
			}

			observer.accept(item, extra, timestamp, unitPrice);

			if (timestamp >= cutoff) {
				held.add(new Held(item, timestamp, unitPrice));
			} else {
				builder.add(item, unitPrice, timestamp);
			}
		});

		FairValueModel model = builder.build();
		List<Priced> priced = new ArrayList<>();

		for (Held sale : held) {
			Optional<ValueEstimate> estimate = model.valueOf(sale.item());

			if (estimate.isEmpty() || estimate.get().median() <= 0.0d) {
				continue;
			}

			priced.add(new Priced(sale.saleKey(), sale.item(), sale.timestamp(), sale.unitPrice(),
					estimate.get().median(), estimate.get().basis()));
		}

		return new Result(List.copyOf(priced), held.size(), model.salesConsidered());
	}

	/** Everything on the tape, priced under one keying. */
	public static Result holdout(Keying keying, long cutoff, Duration window) throws Exception {
		return holdout(keying, cutoff, window, item -> true);
	}

	/**
	 * The pooled median of a set of prices, at the model's sample floor.
	 *
	 * <p>Here rather than on {@link TapeFixture} because it is the model's answer, not the tape's.
	 */
	public static OptionalDouble quotableMedian(List<Double> prices) {
		return TapeFixture.median(prices, ValueEstimate.MIN_SAMPLES);
	}

	private record Held(DecodedItem item, long timestamp, double unitPrice) {
		/**
		 * Identifies a held-out sale well enough to join two runs of the same tape.
		 *
		 * <p>Not Hypixel's auction id, which {@link TapeFixture}'s visitor does not carry. Two distinct
		 * sales of one configuration at the same millisecond for the same price collide here, and that
		 * is harmless for the only thing this is used for: such rows are interchangeable, since every
		 * field a caller could join on is equal.
		 */
		String saleKey() {
			return item.signature() + "@" + timestamp + "#" + Double.doubleToLongBits(unitPrice);
		}
	}
}
