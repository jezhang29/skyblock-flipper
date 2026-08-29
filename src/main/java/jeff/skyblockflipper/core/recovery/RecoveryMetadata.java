package jeff.skyblockflipper.core.recovery;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Recovery-only facts decoded beside, but never added to, the ordinary valuation item. */
public record RecoveryMetadata(
		List<RecoveryAttachment> attachments,
		Map<String, Integer> legacyAttributes,
		Set<RecoveryWarning> warnings) {

	public static final RecoveryMetadata EMPTY = new RecoveryMetadata(List.of(), Map.of(), Set.of());

	public RecoveryMetadata {
		attachments = Objects.requireNonNull(attachments, "attachments").stream()
				.sorted(Comparator.comparing((RecoveryAttachment attachment) -> attachment.kind().name())
						.thenComparing(RecoveryAttachment::slot)
						.thenComparing(RecoveryAttachment::stableComponentId))
				.toList();
		legacyAttributes = Collections.unmodifiableMap(new TreeMap<>(
				Objects.requireNonNull(legacyAttributes, "legacyAttributes")));
		warnings = Set.copyOf(Objects.requireNonNull(warnings, "warnings"));
	}

	public boolean hasRecoverableParts() {
		return !attachments.isEmpty() || !legacyAttributes.isEmpty();
	}

	public boolean previewRequired() {
		return !legacyAttributes.isEmpty() && warnings.contains(RecoveryWarning.PREVIEW_REQUIRED);
	}
}
