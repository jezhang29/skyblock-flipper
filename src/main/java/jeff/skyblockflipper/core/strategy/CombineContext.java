package jeff.skyblockflipper.core.strategy;

/**
 * The combine-specific settings, bundled for the same reason {@link CraftContext} and
 * {@link NpcContext} are: they are read as a set, and a strategy reaching for them one at a time
 * would grow the shared context every time combining learned a parameter.
 *
 * <p>There is only one so far. A combine plan rests at most two orders - the source and the sell
 * offer - so the order-slot budget that constrains the craft and NPC baskets does not bind here, and
 * no measured setting exists to add. See {@code docs/combine-flipping.md}.
 *
 * @param enabled whether combine candidates are produced at all
 */
public record CombineContext(boolean enabled) {
	/** What a caller with no opinion gets: combining on. */
	public static CombineContext defaults() {
		return new CombineContext(true);
	}

	/** Combining off, for the callers and tests that want the ranking without it. */
	public static CombineContext off() {
		return new CombineContext(false);
	}
}
