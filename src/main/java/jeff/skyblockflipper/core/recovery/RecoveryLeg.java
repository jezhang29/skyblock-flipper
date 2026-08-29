package jeff.skyblockflipper.core.recovery;

import java.util.Objects;
import java.util.Set;

/**
 * Evidence-backed gross value before the recovery safety buffer and venue fees.
 *
 * <p>An uncredited leg is explicit rather than absent so the UI can say why it contributes zero.
 */
public record RecoveryLeg(
		RecoveryComponentKind kind,
		String stableComponentId,
		String displayName,
		long quantity,
		RecoveryExitVenue exitVenue,
		long grossQuickSale,
		long removalCost,
		int sampleCount,
		double salesPerDay,
		long medianSellingTimeMillis,
		long quotedDepth,
		RecoveryConfidence confidence,
		boolean credited,
		Set<RecoveryWarning> warnings) {

	public RecoveryLeg {
		Objects.requireNonNull(kind, "kind");
		stableComponentId = Objects.requireNonNull(stableComponentId, "stableComponentId");
		displayName = Objects.requireNonNull(displayName, "displayName");
		Objects.requireNonNull(exitVenue, "exitVenue");
		Objects.requireNonNull(confidence, "confidence");
		warnings = Set.copyOf(Objects.requireNonNull(warnings, "warnings"));
		if (stableComponentId.isBlank() || displayName.isBlank() || quantity <= 0L
				|| grossQuickSale < 0L || removalCost < 0L || sampleCount < 0
				|| !Double.isFinite(salesPerDay) || salesPerDay < 0.0d
				|| medianSellingTimeMillis < 0L || quotedDepth < 0L) {
			throw new IllegalArgumentException("invalid recovery leg");
		}
		if (credited && (exitVenue == RecoveryExitVenue.NONE || grossQuickSale <= 0L
				|| confidence == RecoveryConfidence.NONE)) {
			throw new IllegalArgumentException("credited leg lacks value evidence");
		}
		if (!credited && (exitVenue != RecoveryExitVenue.NONE || grossQuickSale != 0L
				|| removalCost != 0L || confidence != RecoveryConfidence.NONE || warnings.isEmpty())) {
			throw new IllegalArgumentException("uncredited leg must be an explained zero");
		}
	}

	public static RecoveryLeg uncredited(RecoveryComponentKind kind, String stableComponentId,
			String displayName, long quantity, RecoveryWarning warning) {
		return new RecoveryLeg(kind, stableComponentId, displayName, quantity,
				RecoveryExitVenue.NONE, 0L, 0L, 0, 0.0d, 0L, 0L,
				RecoveryConfidence.NONE, false, Set.of(warning));
	}
}
