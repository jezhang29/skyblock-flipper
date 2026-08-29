package jeff.skyblockflipper.core.recovery;

import jeff.skyblockflipper.core.model.BazaarProduct;
import jeff.skyblockflipper.core.pricing.Fees;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import java.util.Set;

/** Builds a fail-closed gemstone exit quote from visible Bazaar bid depth. */
public final class BazaarRecoveryExit {
	private BazaarRecoveryExit() {}

	public static Optional<RecoveryComponentQuote> quote(RecoveryAttachment attachment,
			BazaarProduct product, double minimumInstantSellsPerHour, double safetyBuffer, Fees fees) {
		if (attachment == null || product == null || fees == null
				|| !Double.isFinite(minimumInstantSellsPerHour)
				|| minimumInstantSellsPerHour < 0.0d) {
			return Optional.empty();
		}
		Optional<RecoveryComponentCatalog.Entry> found = RecoveryComponentCatalog.find(attachment);
		if (found.isEmpty() || found.orElseThrow().removalCost().isEmpty()) {
			RecoveryLeg zero = RecoveryComponentCatalog.uncredited(attachment);
			return RecoveryFloorCalculator.quote(zero, safetyBuffer, fees);
		}

		RecoveryComponentCatalog.Entry entry = found.orElseThrow();
		if (entry.exitVenue() != RecoveryExitVenue.BAZAAR
				|| !entry.stableComponentId().equals(product.productId())) {
			return RecoveryFloorCalculator.quote(RecoveryLeg.uncredited(attachment.kind(),
					attachment.stableComponentId(), entry.displayName(), attachment.quantity(),
					RecoveryWarning.UNKNOWN_MAPPING), safetyBuffer, fees);
		}
		if (product.instantSellsPerHour() < minimumInstantSellsPerHour) {
			return RecoveryFloorCalculator.quote(RecoveryLeg.uncredited(attachment.kind(),
					attachment.stableComponentId(), entry.displayName(), attachment.quantity(),
					RecoveryWarning.ILLIQUID), safetyBuffer, fees);
		}

		var proceeds = product.proceedsFromInstantSell(attachment.quantity());
		if (proceeds.isEmpty()) {
			return RecoveryFloorCalculator.quote(RecoveryLeg.uncredited(attachment.kind(),
					attachment.stableComponentId(), entry.displayName(), attachment.quantity(),
					RecoveryWarning.INSUFFICIENT_DEPTH), safetyBuffer, fees);
		}
		try {
			long gross = BigDecimal.valueOf(proceeds.getAsDouble()).setScale(0, RoundingMode.FLOOR)
					.longValueExact();
			RecoveryLeg leg = new RecoveryLeg(attachment.kind(), attachment.stableComponentId(),
					entry.displayName(), attachment.quantity(), RecoveryExitVenue.BAZAAR, gross,
					Math.multiplyExact(entry.removalCost().orElseThrow(), attachment.quantity()),
					0, product.instantSellsPerHour() * 24.0d,
					0L, attachment.quantity(), RecoveryConfidence.HIGH, true, Set.of());
			return RecoveryFloorCalculator.quote(leg, safetyBuffer, fees);
		} catch (ArithmeticException | IllegalArgumentException failure) {
			return Optional.empty();
		}
	}
}
