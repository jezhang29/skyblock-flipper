package jeff.skyblockflipper.core.recovery;

import jeff.skyblockflipper.core.item.DetailedDecodedItem;
import jeff.skyblockflipper.core.item.ItemDecoder;
import jeff.skyblockflipper.core.model.EndedAuction;
import jeff.skyblockflipper.core.valuation.FairValueModel;
import jeff.skyblockflipper.core.valuation.Keying;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Ordinary and recovery valuation models built from one decode of each streamed ended BIN sale. */
public record RecoveryValuationModels(FairValueModel ordinary, RecoveryValueModel recovery) {
	public RecoveryValuationModels {
		Objects.requireNonNull(ordinary, "ordinary");
		Objects.requireNonNull(recovery, "recovery");
	}

	public static Builder builder(Instant now, Duration window) {
		return new Builder(now, window);
	}

	public static final class Builder {
		private final long cutoff;
		private final FairValueModel.Builder ordinary;
		private final RecoveryValueModel.Builder recovery;

		private Builder(Instant now, Duration window) {
			Objects.requireNonNull(now, "now");
			Objects.requireNonNull(window, "window");
			this.cutoff = now.minus(window).toEpochMilli();
			this.ordinary = FairValueModel.builder(now, window, Keying.PRODUCTION);
			this.recovery = new RecoveryValueModel.Builder(window);
		}

		public void add(EndedAuction sale) {
			if (sale == null || !sale.bin() || sale.price() <= 0L || sale.timestamp() < cutoff) {
				return;
			}
			Optional<DetailedDecodedItem> decoded = ItemDecoder.decodeDetailed(sale.itemBytes());
			if (decoded.isEmpty()) {
				return;
			}
			DetailedDecodedItem detailed = decoded.get();
			double unitPrice = (double) sale.price() / Math.max(1, detailed.item().count());
			ordinary.add(detailed.item(), unitPrice, sale.timestamp());
			recovery.add(detailed, unitPrice);
		}

		public RecoveryValuationModels build() {
			return new RecoveryValuationModels(ordinary.build(), recovery.build());
		}
	}
}
