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
import jeff.skyblockflipper.client.command.FlipCommand;
import jeff.skyblockflipper.client.gui.FlipKeybinds;
import jeff.skyblockflipper.client.hud.AuctionOverlay;
import jeff.skyblockflipper.client.hud.BazaarOverlay;
import jeff.skyblockflipper.client.hud.FlipHud;
import jeff.skyblockflipper.client.track.CaptureService;
import jeff.skyblockflipper.client.track.MenuMemory;
import jeff.skyblockflipper.core.config.FlipperConfig;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Path;

public class SkyblockFlipperClient implements ClientModInitializer {
	private static FlipperConfig config = new FlipperConfig();

	public static FlipperConfig config() {
		return config;
	}

	public static Path configFile() {
		return FabricLoader.getInstance().getConfigDir()
				.resolve(SkyblockFlipper.MOD_ID)
				.resolve("config.json");
	}

	/** Re-reads the config from disk. Returns false if the file could not be read. */
	public static boolean reloadConfig() {
		try {
			config = FlipperConfig.load(configFile());
			return true;
		} catch (IOException e) {
			SkyblockFlipper.LOGGER.error("Failed to load config from {}", configFile(), e);
			return false;
		}
	}

	/** Writes the in-memory config back out, for the settings toggleable from a command. */
	public static boolean saveConfig() {
		try {
			config.save(configFile());
			return true;
		} catch (IOException e) {
			SkyblockFlipper.LOGGER.error("Failed to save config to {}", configFile(), e);
			return false;
		}
	}

	@Override
	public void onInitializeClient() {
		reloadConfig();
		LedgerService.load();
		FlipIntentsService.load();
		FlipCommand.register();
		CandidateFeed.register();
		RecoveryFeed.register();
		RecoveryAlertService.register();
		FlipHud.register();
		BazaarOverlay.register();
		AuctionOverlay.register();
		FlipKeybinds.register();
		CaptureService.register();
		MenuMemory.register();
		NpcCheckInService.register();
		NpcProbeService.register();
		MarketDataService.start();

		// Daemon poller threads would die with the JVM anyway; this just makes shutdown orderly
		// so an in-flight tape write is not cut off mid-line.
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> MarketDataService.stop());

		SkyblockFlipper.LOGGER.info("Skyblock Flipper ready. Run /flip in game.");
	}
}
