package jeff.skyblockflipper.core.strategy;

/**
 * The fusion-specific settings, bundled for the same reason {@link CombineContext} and
 * {@link CraftContext} are: they are read as a set, and a strategy reaching for them one at a time
 * would grow the shared context every time fusion learned a parameter.
 *
 * <p>The crocodile level is here rather than folded into a multiplier because it is a player perk the
 * mod cannot read from the game, so it comes in as a setting. It defaults to 0 - no bonus - because a
 * profit-flattering multiplier ships as an off-by-default setting, never as a baked-in default, the
 * same rule the NPC drift premium was held to. See {@code docs/fusion-flipping.md}.
 *
 * @param enabled         whether fusion candidates are produced at all
 * @param crocodileLevel  the player's Pure Reptile (crocodile) perk level, 0-10, which doubles
 *                        reptile-family fusion output by 2% per level. 0 means no bonus
 */
public record FusionContext(boolean enabled, int crocodileLevel) {
	/** What a caller with no opinion gets: fusion on, no reptile bonus. */
	public static FusionContext defaults() {
		return new FusionContext(true, 0);
	}

	/** Fusion off, for the callers and tests that want the ranking without it. */
	public static FusionContext off() {
		return new FusionContext(false, 0);
	}
}
