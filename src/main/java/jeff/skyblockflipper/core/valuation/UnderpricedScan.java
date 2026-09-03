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

import jeff.skyblockflipper.core.api.ListingSink;
import jeff.skyblockflipper.core.item.DecodedItem;
import jeff.skyblockflipper.core.item.ItemDecoder;
import jeff.skyblockflipper.core.model.ActiveListing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Finds live listings priced below what their configuration actually sells for.
 *
 * <p>Two stages, because the expensive step has to be earned. A listing is first checked against
 * the coarse name-and-rarity value, which needs no decoding; almost everything fails there and its
 * blob is dropped immediately. The exception is a composed sweep where recovery already needs the
 * same blob. In that case the exact check is free and the coarse median is not allowed to hide an
 * upgraded configuration. Every decoded listing still has to clear the bar against the value of
 * its exact signature.
 *
 * <p>That second check is the one that matters. The coarse value mixes every version of an item
 * together, so a bare helmet looks like a bargain next to sales of five-star recombobulated ones -
 * and that false positive is exactly the shape of a snipe that loses money. A listing that cannot
 * be matched to its exact configuration is dropped rather than guessed at.
 */
public final class UnderpricedScan implements ListingSink {
	/** A sweep that somehow found thousands of bargains has found a bug, not a market. */
	private static final int MAX_RESULTS = 200;

	/**
	 * Before the exact gate will trust its smaller margin, the estimate has to be well-backed on two
	 * counts that no margin can substitute for: a high confidence off enough sales. A high confidence
	 * from a handful of sales is still an anecdote. The third condition - that the market is quiet
	 * enough for the discount to mean something - is not a fixed number but the margin itself; see
	 * {@link #clearsExactGate}.
	 */
	private static final double EXACT_GATE_MIN_CONFIDENCE = 0.80d;
	private static final int EXACT_GATE_MIN_SAMPLES = 15;

	private final FairValueModel model;
	private final double minDiscount;
	private final double exactMinDiscount;
	private final long maxPrice;

	private final List<PricedListing> found = new ArrayList<>();

	private int listingsSeen;
	private int decoded;
	private int rejectedOnExactValue;

	/**
	 * @param minDiscount how far under fair value a listing has to be before it is worth a look
	 * @param maxPrice    listings above this are not actionable, so not worth decoding either
	 */
	public UnderpricedScan(FairValueModel model, double minDiscount, long maxPrice) {
		this(model, minDiscount, minDiscount, maxPrice);
	}

	/**
	 * @param minDiscount      the coarse gate's margin: how far under the name-and-rarity value a
	 *                         listing has to be to be worth decoding at all. Name-and-rarity mixes
	 *                         every configuration together, so it needs a wide margin
	 * @param exactMinDiscount the exact gate's margin, applied only after a listing is matched to
	 *                         its full decoded signature and only when that estimate is trusted
	 *                         (confidence, samples, dispersion). Usually smaller than
	 *                         {@code minDiscount}: a well-backed exact match is priced closely, so a
	 *                         smaller discount on it is already a real opportunity. Pass the same
	 *                         value as {@code minDiscount} to leave the exact gate unchanged
	 * @param maxPrice         listings above this are not actionable, so not worth decoding either
	 */
	public UnderpricedScan(FairValueModel model, double minDiscount, double exactMinDiscount,
			long maxPrice) {
		this.model = model;
		this.minDiscount = minDiscount;
		this.exactMinDiscount = exactMinDiscount;
		this.maxPrice = maxPrice;
	}

	@Override
	public void offer(ActiveListing listing) {
		offerDecoded(listing, () -> ItemDecoder.decode(listing.itemBytes()));
	}

	/**
	 * Runs the unchanged ordinary scan while allowing a composed sweep to share one decoded blob.
	 * The supplier stays lazy, so the existing coarse rejection still avoids parsing almost every
	 * listing.
	 */
	public void offerDecoded(ActiveListing listing,
			Supplier<Optional<DecodedItem>> decodedItem) {
		offerDecoded(listing, decodedItem, false);
	}

	/**
	 * Runs the exact check when another consumer has already decided this blob must be decoded.
	 *
	 * <p>The family median is only a cost-control gate. It can sit far below an upgraded exact
	 * configuration, so it is allowed to reject that configuration only while doing so actually
	 * saves a decode. A composed scan passes {@code decodeAlreadyRequired} when recovery needs the
	 * same blob; exact valuation then gets the missing coverage at no additional parsing cost.
	 */
	public void offerDecoded(ActiveListing listing,
			Supplier<Optional<DecodedItem>> decodedItem, boolean decodeAlreadyRequired) {
		listingsSeen++;

		if (listing.price() > maxPrice || found.size() >= MAX_RESULTS) {
			return;
		}

		Optional<ValueEstimate> rough = model.roughValueOf(listing.itemName(), listing.rarity());

		// Never sold in the window: there is nothing to call it cheap against.
		if (rough.isEmpty()
				|| (!decodeAlreadyRequired && !isDiscounted(listing.price(), rough.get()))) {
			return;
		}

		Optional<DecodedItem> item = decodedItem.get();

		if (item.isEmpty()) {
			return;
		}

		decoded++;
		if (WitherBladeValuationContainment.suppresses(item.get())) {
			// Temporary incident containment. The corrected signature still trains behind this gate.
			rejectedOnExactValue++;
			return;
		}
		Optional<ValueEstimate> exact = model.valueOf(item.get());

		if (exact.isEmpty() || !clearsExactGate(listing.price(), exact.get())) {
			// The coarse hit was an illusion: this configuration is not actually cheap, or we
			// cannot price it at all.
			rejectedOnExactValue++;
			return;
		}

		found.add(new PricedListing(listing.withoutBlob(), item.get(), exact.get()));
	}

	/** Cheapest relative to value first. */
	public List<PricedListing> results() {
		return found.stream()
				.sorted(Comparator.comparingDouble(PricedListing::discount).reversed())
				.toList();
	}

	public int listingsSeen() {
		return listingsSeen;
	}

	/** How many blobs the sweep actually had to parse, which is the cost this design manages. */
	public int decoded() {
		return decoded;
	}

	/** Coarse hits that the exact check threw out - the false positives this avoids acting on. */
	public int rejectedOnExactValue() {
		return rejectedOnExactValue;
	}

	private boolean isDiscounted(long price, ValueEstimate value) {
		return isDiscounted(price, value, minDiscount);
	}

	private static boolean isDiscounted(long price, ValueEstimate value, double discount) {
		return price <= value.median() * (1.0d - discount);
	}

	/**
	 * The bar an exact-signature estimate has to clear.
	 *
	 * <p>Always passes at the coarse margin, so nothing that cleared the old exact check stops
	 * clearing it. On top of that, a listing under fair value by the smaller {@code exactMinDiscount}
	 * counts when the exact estimate is trusted - matched to the full decoded signature, off enough
	 * well-agreeing sales that the median is not a guess. That is the opportunity the wide coarse
	 * margin walks past: an item priced a shade under what its exact configuration actually sells for.
	 *
	 * <p>Trusted means three things. Confidence and sample count are fixed bars. The third is the
	 * discount against the market's own spread: the claimed edge has to be bigger than how much the
	 * market disagrees with itself ({@link ValueEstimate#dispersion}, the interquartile range over the
	 * median), because a discount smaller than the spread is inside the noise, not below it. The
	 * measured floor under {@code exactMinDiscount} exists for the same reason - a 5% discount lost to
	 * fees more than half the time on the tape because the model's own median carries ~10% error, so a
	 * discount has to clear that error to be real. Backtest: {@code ExactMarginBacktestTest}.
	 */
	private boolean clearsExactGate(long price, ValueEstimate exact) {
		if (isDiscounted(price, exact, minDiscount)) {
			return true;
		}
		return exactEstimateTrusted(exact) && isDiscounted(price, exact, exactMinDiscount);
	}

	private boolean exactEstimateTrusted(ValueEstimate exact) {
		return exact.confidence() > EXACT_GATE_MIN_CONFIDENCE
				&& exact.samples() >= EXACT_GATE_MIN_SAMPLES
				&& exact.dispersion() < exactMinDiscount;
	}
}
