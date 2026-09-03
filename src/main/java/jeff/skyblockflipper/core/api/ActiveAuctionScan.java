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

import jeff.skyblockflipper.core.item.DetailedDecodedItem;
import jeff.skyblockflipper.core.item.ItemDecoder;
import jeff.skyblockflipper.core.model.ActiveListing;
import jeff.skyblockflipper.core.recovery.RecoveryListingScan;
import jeff.skyblockflipper.core.recovery.RecoveryOpportunity;
import jeff.skyblockflipper.core.valuation.PricedListing;
import jeff.skyblockflipper.core.valuation.UnderpricedScan;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * One sink for the existing network sweep. A blob requested by both consumers is decoded once.
 * Failures are counted per listing and never abort publication of the rest of the house.
 */
public final class ActiveAuctionScan implements ListingSink {
	private final UnderpricedScan ordinary;
	private final RecoveryListingScan recovery;
	private final Function<String, Optional<DetailedDecodedItem>> decoder;
	private int failures;
	private int decodedBlobs;

	public ActiveAuctionScan(UnderpricedScan ordinary, RecoveryListingScan recovery) {
		this(ordinary, recovery, ItemDecoder::decodeDetailed);
	}

	public ActiveAuctionScan(UnderpricedScan ordinary, RecoveryListingScan recovery,
			Function<String, Optional<DetailedDecodedItem>> decoder) {
		this.ordinary = ordinary;
		this.recovery = recovery;
		this.decoder = decoder;
	}

	@Override
	public void offer(ActiveListing listing) {
		MemoizedDecode decoded = new MemoizedDecode(listing.itemBytes());
		boolean recoveryMightUse = false;
		boolean failed = false;
		try {
			recoveryMightUse = recovery.mightUse(listing);
		} catch (RuntimeException failure) {
			failed = true;
		}
		try {
			// The family-wide median can hide an upgraded exact configuration. When recovery
			// already needs this blob, let ordinary valuation inspect it at no extra decode cost.
			ordinary.offerDecoded(listing, () -> decoded.get().map(DetailedDecodedItem::item),
					recoveryMightUse);
		} catch (RuntimeException failure) {
			failed = true;
		}
		try {
			// Likewise, a decoded ordinary candidate may carry a recoverable attachment even when
			// no recent attached sale put its display family into recovery's cheap prefilter.
			Optional<DetailedDecodedItem> value = recoveryMightUse
					? decoded.get() : decoded.resolvedValue();
			value.ifPresent(item -> recovery.offerDecoded(listing, item));
		} catch (RuntimeException failure) {
			failed = true;
		}
		if (failed) {
			failures++;
		}
	}

	public List<PricedListing> ordinaryResults() {
		return ordinary.results();
	}

	public List<RecoveryOpportunity> recoveryResults() {
		return recovery.results();
	}

	public UnderpricedScan ordinary() {
		return ordinary;
	}

	public RecoveryListingScan recovery() {
		return recovery;
	}

	public int failures() {
		return failures;
	}

	public int decodedBlobs() {
		return decodedBlobs;
	}

	private final class MemoizedDecode {
		private final String blob;
		private Optional<DetailedDecodedItem> value = Optional.empty();
		private boolean resolved;

		private MemoizedDecode(String blob) {
			this.blob = blob;
		}

		private Optional<DetailedDecodedItem> get() {
			if (!resolved) {
				resolved = true;
				decodedBlobs++;
				value = Optional.ofNullable(decoder.apply(blob)).orElse(Optional.empty());
			}
			return value;
		}

		private Optional<DetailedDecodedItem> resolvedValue() {
			return resolved ? value : Optional.empty();
		}
	}
}
