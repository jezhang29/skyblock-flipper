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
		try {
			ordinary.offerDecoded(listing, () -> decoded.get().map(DetailedDecodedItem::item));
			if (recovery.mightUse(listing)) {
				decoded.get().ifPresent(value -> recovery.offerDecoded(listing, value));
			}
		} catch (RuntimeException failure) {
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
		private Optional<DetailedDecodedItem> value;

		private MemoizedDecode(String blob) {
			this.blob = blob;
		}

		private Optional<DetailedDecodedItem> get() {
			if (value == null) {
				decodedBlobs++;
				value = decoder.apply(blob);
			}
			return value;
		}
	}
}
