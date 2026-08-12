package jeff.skyblockflipper.core.config;

import java.util.Arrays;
import java.util.List;

/**
 * Which side of Hypixel's menu the bazaar panel sits on.
 *
 * <p>A setting rather than a constant because {@link #AUTO} - take whichever side has more room -
 * turned out to be the wrong default in play. Hypixel's menus are not all the same width, so the
 * side with more room changes as you move between the bazaar's own screens, and the panel appears to
 * jump from one edge to the other mid-trade. A panel you have to find again on every screen is worse
 * than a slightly narrower one that is always in the same place.
 *
 * <p>Stored in the config as a string for the same reason {@link HudAnchor} is: Gson turns an
 * unrecognised enum name into {@code null} without complaining, and a null here would fail on every
 * frame rather than on load.
 */
public enum OverlaySide {
	LEFT,
	RIGHT,

	/** Whichever side of the menu has more room this frame. Free width, unstable position. */
	AUTO;

	/** Falls back to the left for anything unrecognised, including null. */
	public static OverlaySide parse(String name) {
		if (name == null) {
			return LEFT;
		}

		for (OverlaySide side : values()) {
			if (side.name().equalsIgnoreCase(name.trim())) {
				return side;
			}
		}

		return LEFT;
	}

	/** The choices a settings UI offers, in the order they are declared. */
	public static List<String> names() {
		return Arrays.stream(values()).map(OverlaySide::name).toList();
	}

	/**
	 * Which side to draw on, given the room either side of the menu.
	 *
	 * <p>A fixed side is only overruled when the chosen side has no usable room at all and the other
	 * does. Being stubborn to the point of drawing nothing would be a worse answer than moving.
	 */
	public boolean drawOnLeft(int roomLeft, int roomRight, int minimum) {
		return switch (this) {
			case LEFT -> roomLeft >= minimum || roomRight < minimum;
			case RIGHT -> !(roomRight >= minimum || roomLeft < minimum);
			case AUTO -> roomLeft >= roomRight;
		};
	}
}
