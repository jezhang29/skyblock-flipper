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

import jeff.skyblockflipper.core.api.TimedListingSink;
import jeff.skyblockflipper.core.item.DecodedItem;
import jeff.skyblockflipper.core.item.ItemDecoder;
import jeff.skyblockflipper.core.model.TimedListing;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * The timed-auction twin of {@link UnderpricedScan}: it rides the same active-auction sweep and
 * keeps the ending-soon timed listings whose <em>bid to win</em> sits below the item's BIN value
 * (docs/auction-bidding-plan.md, Phase 1).
 *
 * <p>Two things differ from the BIN scan. First, a timed listing has no cheap name-and-rarity prune
 * to run before decoding - the wire format exposes no item name - so every ending-soon listing is
 * decoded. That is affordable only because the window narrows the population to a few hundred per
 * sweep. Second, the price being called cheap is not the current price but the bid it takes to win
 * ({@link PricedBid#bidToWin()}), which on an uncontested listing is the starting bid.
 *
 * <p>The discount gate is the sniper's, reused verbatim: a coarse floor, plus a tighter margin that
 * fires only for a trusted exact estimate. What survives is handed to {@code AuctionBidStrategy},
 * which applies the confidence, profit-floor, contested and suspect rules.
 */
public final class UnderpricedTimedScan implements TimedListingSink {
	/** A sweep that finds thousands of biddable bargains has found a bug, not a market. */
	private static final int MAX_RESULTS = 200;

	private static final double EXACT_GATE_MIN_CONFIDENCE = 0.80d;
	private static final int EXACT_GATE_MIN_SAMPLES = 15;

	private final FairValueModel model;
	private final double minDiscount;
	private final double exactMinDiscount;
	private final long windowEnd;
	private final Function<String, Optional<DecodedItem>> decoder;

	private final List<PricedBid> found = new ArrayList<>();

	private int seen;
	private int withinWindow;
	private int decoded;
	private int decodeFailures;

	public UnderpricedTimedScan(FairValueModel model, double minDiscount, double exactMinDiscount,
			Instant now, Duration window) {
		this(model, minDiscount, exactMinDiscount, now, window, ItemDecoder::decode);
	}

	UnderpricedTimedScan(FairValueModel model, double minDiscount, double exactMinDiscount,
			Instant now, Duration window, Function<String, Optional<DecodedItem>> decoder) {
		this.model = model;
		this.minDiscount = minDiscount;
		this.exactMinDiscount = exactMinDiscount;
		this.windowEnd = now.plus(window).toEpochMilli();
		this.decoder = decoder;
	}

	@Override
	public void offer(TimedListing listing) {
		seen++;

		// Only auctions actually ending soon are flips; a 14-day listing is not.
		if (listing.end() > windowEnd || found.size() >= MAX_RESULTS) {
			return;
		}

		withinWindow++;

		Optional<DecodedItem> item = decode(listing.itemBytes());

		if (item.isEmpty()) {
			decodeFailures++;
			return;
		}

		decoded++;
		if (WitherBladeValuationContainment.suppresses(item.get())) {
			return;
		}

		Optional<ValueEstimate> value = model.valueOf(item.get());

		if (value.isEmpty()) {
			return;
		}

		long toWin = Bids.nextBid(listing.startingBid(), listing.highestBidAmount());

		if (!clearsGate(toWin, value.get())) {
			return;
		}

		found.add(new PricedBid(listing, item.get(), value.get()));
	}

	private Optional<DecodedItem> decode(String itemBytes) {
		try {
			return decoder.apply(itemBytes);
		} catch (RuntimeException e) {
			return Optional.empty();
		}
	}

	/** Cheapest relative to value first. */
	public List<PricedBid> results() {
		return found.stream()
				.sorted(Comparator.comparingDouble(PricedBid::discount).reversed())
				.toList();
	}

	public int seen() {
		return seen;
	}

	public int withinWindow() {
		return withinWindow;
	}

	public int decoded() {
		return decoded;
	}

	public int decodeFailures() {
		return decodeFailures;
	}

	public String describe() {
		return seen + " timed listings, " + withinWindow + " ending soon, " + found.size()
				+ " under fair value, " + decodeFailures + " undecodable";
	}

	private boolean clearsGate(long toWin, ValueEstimate value) {
		if (isDiscounted(toWin, value, minDiscount)) {
			return true;
		}
		return trusted(value) && isDiscounted(toWin, value, exactMinDiscount);
	}

	private static boolean isDiscounted(long price, ValueEstimate value, double discount) {
		return value.median() > 0.0d && price <= value.median() * (1.0d - discount);
	}

	private boolean trusted(ValueEstimate value) {
		return value.confidence() > EXACT_GATE_MIN_CONFIDENCE
				&& value.samples() >= EXACT_GATE_MIN_SAMPLES
				&& value.dispersion() < exactMinDiscount;
	}
}
