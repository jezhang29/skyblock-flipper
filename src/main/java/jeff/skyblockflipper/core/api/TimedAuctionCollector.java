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
package jeff.skyblockflipper.core.api;

import jeff.skyblockflipper.core.item.DecodedItem;
import jeff.skyblockflipper.core.item.ItemDecoder;
import jeff.skyblockflipper.core.model.TimedAuctionSample;
import jeff.skyblockflipper.core.model.TimedListing;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * The sink for the Phase 0b reachability collection (docs/auction-bidding-plan.md): turns the timed
 * listings of one sweep into the small {@link TimedAuctionSample} rows the {@code TimedAuctionTape}
 * keeps.
 *
 * <p>Two filters keep it lightweight, which is the whole point - the plan is explicit that this must
 * <b>not</b> tape the whole active house:
 * <ul>
 *   <li><b>Ending soon.</b> Only listings whose {@code end} is within {@code window} of now are kept.
 *       A 14-day auction sampled every minute would be pure waste, and the strategy only ever bids
 *       on auctions about to end, so the window is both the disk control and the right population.
 *       This is checked <i>before</i> decoding, so a far-future listing costs nothing.</li>
 *   <li><b>Decodable.</b> Only listings whose blob decodes to a signature are kept, because the
 *       signature is the valuation model's key and a listing with none cannot be priced.</li>
 * </ul>
 *
 * <p>Accumulates in memory for one sweep - a few hundred small rows, no blobs - and the caller
 * writes them in one append after the sweep, rather than opening the tape per listing.
 */
public final class TimedAuctionCollector implements TimedListingSink {
	private final long sampledAtMillis;
	private final long windowMillis;
	private final Function<String, Optional<DecodedItem>> decoder;

	private final List<TimedAuctionSample> samples = new ArrayList<>();
	private int seen;
	private int withinWindow;
	private int decodeFailures;

	public TimedAuctionCollector(Instant sampledAt, Duration window) {
		this(sampledAt, window, ItemDecoder::decode);
	}

	public TimedAuctionCollector(Instant sampledAt, Duration window,
			Function<String, Optional<DecodedItem>> decoder) {
		this.sampledAtMillis = sampledAt.toEpochMilli();
		this.windowMillis = Math.max(0L, window.toMillis());
		this.decoder = decoder;
	}

	@Override
	public void offer(TimedListing listing) {
		seen++;

		// end - now <= window, so a listing already at or past its scheduled end (staleness, or an
		// anti-snipe extension not yet reflected) is kept, and a far-future one is dropped before
		// the decode it would otherwise pay for.
		if (listing.end() - sampledAtMillis > windowMillis) {
			return;
		}
		withinWindow++;

		Optional<DecodedItem> decoded;
		try {
			decoded = decoder.apply(listing.itemBytes());
		} catch (RuntimeException failure) {
			decoded = Optional.empty();
		}

		if (decoded.isEmpty()) {
			decodeFailures++;
			return;
		}

		samples.add(new TimedAuctionSample(
				listing.uuid(),
				decoded.get().signature(),
				Math.max(1, decoded.get().count()),
				listing.end(),
				listing.startingBid(),
				listing.highestBidAmount(),
				sampledAtMillis));
	}

	/** The rows to append to the tape, one per ending-soon decodable listing this sweep saw. */
	public List<TimedAuctionSample> samples() {
		return samples;
	}

	/** All timed listings offered, before any filter. */
	public int seen() {
		return seen;
	}

	/** Timed listings ending within the window, the ones worth decoding. */
	public int withinWindow() {
		return withinWindow;
	}

	/** Ending-soon listings whose blob would not decode, so no signature could be keyed. */
	public int decodeFailures() {
		return decodeFailures;
	}

	/** A one-line summary for the poller log. */
	public String describe() {
		return seen + " timed listings, " + withinWindow + " ending soon, " + samples.size()
				+ " taped, " + decodeFailures + " undecodable";
	}
}
