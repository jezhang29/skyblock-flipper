package jeff.skyblockflipper.core.config;

import java.util.Arrays;
import java.util.List;

/**
 * Which resource the NPC basket ranks candidates against.
 *
 * <p>A greedy allocator is only as good as its ranking key, and the key should be profit per unit of
 * whatever the allocator runs out of. This trade has two answers to that, and they disagree by more
 * than a third.
 *
 * <p><b>Order slots are what runs out.</b> Every sweep recorded in {@code docs/npc-flipping.md} came
 * back {@code SLOTS}, at every setting tried, with hauling using 34 of 864 available inventory loads
 * and the bankroll barely touched. On the live book on 2026-08-14, at the user's settings - 21 slots,
 * a 20% floor, 800M - ranking on the slot is worth 65.5M a cycle against 47.3M on the load.
 *
 * <p><b>Inventory loads are what the player runs out of.</b> The same measurement puts that 65.5M at
 * 363 loads against 114, and a load is 35 inventory slots carried to a shop by hand. Buying the extra
 * 18M costs about 9,000 clicks, which is the cost no model in this repo has ever priced because it is
 * not paid in coins.
 *
 * <p>So neither is the right answer in general and the setting exists to say which budget is scarcer.
 * {@link #LOAD} is the default because clicking is what a player runs out of first.
 *
 * <p>Stored in the config as a string for the same reason {@link OverlaySide} is: Gson turns an
 * unrecognised enum name into {@code null} without complaining, and a null here would fail at plan
 * time rather than on load.
 */
public enum NpcRanking {
	/** Profit per inventory load. Fewer, denser trips to the NPC; fewer coins. */
	LOAD,

	/** Profit per bazaar order slot, which is the resource that actually binds. More hauling. */
	ORDER_SLOT;

	/** Falls back to the shipped key for anything unrecognised, including null. */
	public static NpcRanking parse(String name) {
		if (name == null) {
			return LOAD;
		}

		for (NpcRanking ranking : values()) {
			if (ranking.name().equalsIgnoreCase(name.trim())) {
				return ranking;
			}
		}

		return LOAD;
	}

	/** The choices a settings UI offers, in the order they are declared. */
	public static List<String> names() {
		return Arrays.stream(values()).map(NpcRanking::name).toList();
	}
}
