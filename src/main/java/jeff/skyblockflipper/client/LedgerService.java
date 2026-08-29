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
import jeff.skyblockflipper.core.ledger.Ledger;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Path;

/** Owns the ledger instance and supplies it the Minecraft-shaped path {@code core} will not look up. */
public final class LedgerService {
	private static final Ledger LEDGER = new Ledger(file());

	private LedgerService() {
	}

	public static Ledger ledger() {
		return LEDGER;
	}

	public static Path file() {
		return FabricLoader.getInstance().getConfigDir()
				.resolve(SkyblockFlipper.MOD_ID)
				.resolve("ledger.jsonl");
	}

	public static void load() {
		try {
			LEDGER.load();
			SkyblockFlipper.LOGGER.info("Ledger loaded: {} entries, {} still open",
					LEDGER.all().size(), LEDGER.openEntries().size());
		} catch (IOException e) {
			// An unreadable ledger costs history, not the session; the mod still ranks flips.
			SkyblockFlipper.LOGGER.error("Failed to load ledger from {}", file(), e);
		}
	}
}
