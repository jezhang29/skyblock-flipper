package jeff.skyblockflipper.core.text;

/**
 * Coin amounts rendered the way Skyblock players read them: 12.5M, 340k, 900.
 *
 * <p>Lives in {@code core} so chat, the HUD and the ledger all abbreviate identically. Two views of
 * the same number that round differently read as two different numbers.
 */
public final class Coins {
	private Coins() {
	}

	public static String format(long amount) {
		if (amount < 0L) {
			// Realized losses are a normal ledger entry, so the sign has to survive abbreviation.
			return "-" + format(-amount);
		}

		if (amount >= 1_000_000_000L) {
			return String.format("%.2fB", amount / 1_000_000_000.0d);
		} else if (amount >= 1_000_000L) {
			return String.format("%.2fM", amount / 1_000_000.0d);
		} else if (amount >= 1_000L) {
			return String.format("%.1fk", amount / 1_000.0d);
		}

		return String.valueOf(amount);
	}

	/** Convenience for the many places holding profit as a double. */
	public static String format(double amount) {
		return format(Math.round(amount));
	}
}
