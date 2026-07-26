package jeff.skyblockflipper.core.config;

/**
 * Which corner the HUD overlay hangs from.
 *
 * <p>Stored in the config as a string rather than as an enum-typed field: Gson turns an
 * unrecognised enum name into {@code null} without complaining, and a null here would NPE on
 * every frame rather than on load.
 */
public enum HudAnchor {
	TOP_LEFT,
	TOP_RIGHT,
	BOTTOM_LEFT,
	BOTTOM_RIGHT;

	/** Falls back to the top-left corner for anything unrecognised, including null. */
	public static HudAnchor parse(String name) {
		if (name == null) {
			return TOP_LEFT;
		}

		for (HudAnchor anchor : values()) {
			if (anchor.name().equalsIgnoreCase(name.trim())) {
				return anchor;
			}
		}

		return TOP_LEFT;
	}

	public boolean isRight() {
		return this == TOP_RIGHT || this == BOTTOM_RIGHT;
	}

	public boolean isBottom() {
		return this == BOTTOM_LEFT || this == BOTTOM_RIGHT;
	}
}
