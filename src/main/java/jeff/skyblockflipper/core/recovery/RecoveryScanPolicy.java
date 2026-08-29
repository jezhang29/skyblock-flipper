package jeff.skyblockflipper.core.recovery;

import jeff.skyblockflipper.core.config.RecoverySettings;

/** Conservative scan gates; checkpoint 7 exposes these through validated configuration. */
public record RecoveryScanPolicy(double safetyBuffer, int minimumAhSamples,
		double minimumAhSalesPerDay, double maximumAhHoursToSell,
		double minimumBazaarInstantSellsPerHour, long minimumProfit,
		double minimumMargin, int maximumResults) {
	public RecoveryScanPolicy {
		if (!Double.isFinite(safetyBuffer) || safetyBuffer < 0.10d || safetyBuffer > 0.15d
				|| minimumAhSamples < 1 || !Double.isFinite(minimumAhSalesPerDay)
				|| minimumAhSalesPerDay < 0.0d || !Double.isFinite(maximumAhHoursToSell)
				|| maximumAhHoursToSell <= 0.0d
				|| !Double.isFinite(minimumBazaarInstantSellsPerHour)
				|| minimumBazaarInstantSellsPerHour < 0.0d
				|| minimumProfit < 0L || !Double.isFinite(minimumMargin)
				|| minimumMargin < 0.0d
				|| maximumResults < 1 || maximumResults > 1_000) {
			throw new IllegalArgumentException("invalid recovery scan policy");
		}
	}

	public static RecoveryScanPolicy conservativeDefaults() {
		return new RecoveryScanPolicy(0.15d, 6, 1.0d, 48.0d, 1.0d,
				500_000L, 0.15d, 200);
	}

	public static RecoveryScanPolicy from(RecoverySettings settings) {
		return new RecoveryScanPolicy(settings.safetyBuffer(), settings.minimumAhSamples(),
				settings.minimumAhSalesPerDay(), settings.maximumAhSellHours(),
				settings.minimumBazaarInstantSellsPerHour(), settings.minimumProfit(),
				settings.minimumMargin(), 200);
	}
}
