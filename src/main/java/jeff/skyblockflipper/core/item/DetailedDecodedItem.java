package jeff.skyblockflipper.core.item;

import jeff.skyblockflipper.core.recovery.RecoveryMetadata;

import java.util.Objects;

/** Ordinary valuation decode and recovery metadata derived from the same parsed NBT tree. */
public record DetailedDecodedItem(DecodedItem item, RecoveryMetadata recovery) {
	public DetailedDecodedItem {
		Objects.requireNonNull(item, "item");
		Objects.requireNonNull(recovery, "recovery");
	}
}
