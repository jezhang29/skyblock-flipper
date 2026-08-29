/*
 * Skyblock Flipper - a Hypixel Skyblock flipping advisor mod.
 * Copyright (C) 2026 SoupChugger
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package jeff.skyblockflipper.client;

import jeff.skyblockflipper.SkyblockFlipper;
import jeff.skyblockflipper.core.ledger.FlipIntents;
import jeff.skyblockflipper.core.strategy.NpcReprice;
import jeff.skyblockflipper.core.strategy.StrategyKind;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * Owns the {@link FlipIntents} register and supplies it the Minecraft-shaped path {@code core} will
 * not look up, exactly as {@link LedgerService} owns the ledger.
 *
 * <p>The one place the mod writes down whose flip a buy order is, so the NPC side can tell a combine
 * source order or a craft ingredient apart from a real NPC position on the same book. Written at the
 * act-on points - a basket placed, a candidate taken, a craft or combine followed - and read back
 * when {@code NpcWorklist} decides which resting orders are the NPC side's to touch.
 */
public final class FlipIntentsService {
	/**
	 * How long an intent stays good with nothing refreshing it. Generous because a craft or combine
	 * hold runs to hours or days, unlike the resting window a quote lives on; an actively followed job
	 * re-records every poll, so this only bounds an item the player has stopped flipping.
	 */
	private static final Duration TTL = Duration.ofDays(3);

	private static final FlipIntents INTENTS = new FlipIntents(file(), () -> TTL);

	private FlipIntentsService() {
	}

	public static FlipIntents intents() {
		return INTENTS;
	}

	public static Path file() {
		return FabricLoader.getInstance().getConfigDir()
				.resolve(SkyblockFlipper.MOD_ID)
				.resolve("flip-intents.json");
	}

	public static void load() {
		try {
			INTENTS.load();
			SkyblockFlipper.LOGGER.info("Flip intents loaded: {} items", INTENTS.size());
		} catch (IOException e) {
			// A lost register costs the shielding of other strategies' orders for a session, not the
			// session: the NPC side falls back to reviewing every resting buy order, which is what it
			// did before this existed.
			SkyblockFlipper.LOGGER.error("Failed to load flip intents from {}", file(), e);
		}
	}

	/**
	 * Records the strategy the player is running this item under, swallowing a write failure the same
	 * way the callers of this are render and tick paths that cannot surface one.
	 */
	public static void record(String itemId, StrategyKind kind, long now) {
		try {
			INTENTS.record(itemId, kind, now);
		} catch (IOException e) {
			SkyblockFlipper.LOGGER.error("Failed to record flip intent for {}", itemId, e);
		}
	}

	/** Items being flipped under a non-NPC strategy, so the NPC side leaves their orders alone. */
	public static Set<String> foreign(long now) {
		return INTENTS.foreignItems(now);
	}

	/**
	 * The resting orders that are the NPC side's to review, with any belonging to another strategy
	 * removed.
	 *
	 * <p>For the round and the check-in reminder, which review orders directly rather than through
	 * {@code NpcWorklist} and so cannot lean on its foreign split. A foreign order must not enter a
	 * round - the round would freeze a reprice on it - nor make the reminder chime. Slot reservation is
	 * {@code NpcWorklist}'s job and neither of these plans a basket, so dropping them outright here is
	 * correct.
	 */
	public static List<NpcReprice.Order> mine(List<NpcReprice.Order> orders, long now) {
		Set<String> foreign = INTENTS.foreignItems(now);

		return foreign.isEmpty()
				? orders
				: orders.stream().filter(order -> !foreign.contains(order.itemId())).toList();
	}
}
