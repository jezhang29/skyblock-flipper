package jeff.skyblockflipper.core.config;

/** Immutable recovery settings read fresh for each sweep or client tick. */
public record RecoverySettings(
		boolean alertsEnabled,
		boolean chatNotifications,
		boolean toastNotifications,
		boolean sound,
		long minimumProfit,
		double minimumMargin,
		double safetyBuffer,
		int minimumAhSamples,
		double minimumAhSalesPerDay,
		double maximumAhSellHours,
		double minimumBazaarInstantSellsPerHour,
		int maximumAgeSeconds,
		boolean gemstones,
		boolean drills,
		boolean rods,
		boolean legacy) {
}
