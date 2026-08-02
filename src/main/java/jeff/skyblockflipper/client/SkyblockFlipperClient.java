package jeff.skyblockflipper.client;

import jeff.skyblockflipper.SkyblockFlipper;
import jeff.skyblockflipper.client.command.FlipCommand;
import jeff.skyblockflipper.client.gui.FlipKeybinds;
import jeff.skyblockflipper.client.hud.FlipHud;
import jeff.skyblockflipper.client.track.CaptureService;
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
		FlipCommand.register();
		CandidateFeed.register();
		FlipHud.register();
		FlipKeybinds.register();
		CaptureService.register();
		MarketDataService.start();

		// Daemon poller threads would die with the JVM anyway; this just makes shutdown orderly
		// so an in-flight tape write is not cut off mid-line.
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> MarketDataService.stop());

		SkyblockFlipper.LOGGER.info("Skyblock Flipper ready. Run /flip in game.");
	}
}
