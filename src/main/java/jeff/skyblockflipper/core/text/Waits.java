package jeff.skyblockflipper.core.text;

import java.time.Duration;

/**
 * How long something takes, rendered at the precision a trading decision actually turns on: 40s,
 * 6m, 2h 10m, 3h+.
 *
 * <p>Same argument as {@link Coins}. A fill time is quoted in the chat renderer, the flip screen
 * and the guide, and two views of the same wait that round differently read as two different
 * numbers.
 *
 * <p>Long waits are deliberately flattened to {@code 3h+}: the estimates behind them come from a
 * displacement rate measured on five-minute samples, and printing "4h 37m" from that would claim a
 * precision the measurement does not have. Past a few hours the only decision the number informs is
 * "not while I am watching", which one bucket serves as well as twelve.
 */
public final class Waits {
	/** Past this, the answer is "long enough that you would not wait for it". */
	private static final Duration LONG = Duration.ofHours(3);

	private Waits() {
	}

	public static String format(Duration duration) {
		if (duration == null || duration.isNegative() || duration.isZero()) {
			return "instantly";
		}

		if (duration.compareTo(LONG) >= 0) {
			return LONG.toHours() + "h+";
		}

		long minutes = duration.toMinutes();

		if (minutes < 1L) {
			return duration.toSeconds() + "s";
		}

		if (minutes < 60L) {
			return minutes + "m";
		}

		long remainder = minutes % 60L;
		return remainder == 0L ? minutes / 60L + "h" : minutes / 60L + "h " + remainder + "m";
	}

	/** For the optional case a fill estimate returns when a leg never clears at all. */
	public static String formatOrNever(Duration duration) {
		return duration == null ? "never at this size" : format(duration);
	}
}
