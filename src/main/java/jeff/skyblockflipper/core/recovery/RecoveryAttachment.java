package jeff.skyblockflipper.core.recovery;

import java.util.Objects;

/** One removable attachment decoded from an item's NBT, before any market value is assigned. */
public record RecoveryAttachment(
		RecoveryComponentKind kind,
		String slot,
		String stableComponentId,
		long quantity) {

	public RecoveryAttachment {
		Objects.requireNonNull(kind, "kind");
		slot = Objects.requireNonNull(slot, "slot");
		stableComponentId = Objects.requireNonNull(stableComponentId, "stableComponentId");
		if (slot.isBlank() || stableComponentId.isBlank() || quantity <= 0L) {
			throw new IllegalArgumentException("invalid recovery attachment");
		}
	}
}
