package jeff.skyblockflipper.core.valuation.backtest;

import jeff.skyblockflipper.core.item.DecodedItem;

import java.util.ArrayList;
import java.util.List;

/**
 * Editing a signature string, for the counterfactual arms only.
 *
 * <p>{@link DecodedItem#signature()} joins its terms with {@code |}, id and rarity first, so a term
 * can be dropped or added by splitting on that separator. Nothing in production does this and
 * nothing should: it is here because a backtest has to ask what a key would have looked like before
 * a term shipped, and the method that builds keys offers no way to ask.
 *
 * <p>Split-and-rejoin rather than a regex. The three {@code replaceFirst} calls this replaces each
 * had to reason about what could follow their term - one of them documented that it was safe only
 * because the dye clause is written last - which made a test's regex load-bearing on the field order
 * of a production method.
 */
public final class SignatureTerms {
	private static final String SEPARATOR = "|";

	private SignatureTerms() {
	}

	/**
	 * The signature without {@code term}, unchanged if it was not there.
	 *
	 * @param term a whole term ({@code "ethermerge"}) or a prefix ending in {@code =} to drop a term
	 *             carrying a value ({@code "dye="})
	 */
	public static String without(String signature, String term) {
		List<String> kept = new ArrayList<>();

		for (String part : signature.split("\\|", -1)) {
			if (!matches(part, term)) {
				kept.add(part);
			}
		}

		return String.join(SEPARATOR, kept);
	}

	/** The signature with {@code term} appended, unchanged if the term is empty. */
	public static String plus(String signature, String term) {
		return term == null || term.isEmpty() ? signature : signature + SEPARATOR + term;
	}

	/** Whether the signature carries {@code term}, by the same rule {@link #without} drops it. */
	public static boolean carries(String signature, String term) {
		for (String part : signature.split("\\|", -1)) {
			if (matches(part, term)) {
				return true;
			}
		}

		return false;
	}

	private static boolean matches(String part, String term) {
		return term.endsWith("=") ? part.startsWith(term) : part.equals(term);
	}
}
