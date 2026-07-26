package jeff.skyblockflipper.client;

import jeff.skyblockflipper.SkyblockFlipper;
import jeff.skyblockflipper.core.api.HypixelApi;
import jeff.skyblockflipper.core.api.MarketData;
import jeff.skyblockflipper.core.api.MarketPoller;
import jeff.skyblockflipper.core.tape.SalesTape;

import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;

/**
 * Owns the polling lifecycle on the client side.
 *
 * <p>All this layer does is supply the Minecraft-shaped values ({@code core} refuses to look them
 * up itself) and start or stop the loop.
 */
public final class MarketDataService {
	private static final int TAPE_RETENTION_DAYS = 30;

	private static final MarketData DATA = new MarketData();
	private static final HypixelApi API = new HypixelApi();

	private static MarketPoller poller;
	private static SalesTape tape;

	private MarketDataService() {
	}

	public static MarketData data() {
		return DATA;
	}

	public static SalesTape tape() {
		return tape;
	}

	public static boolean isRunning() {
		return poller != null && poller.isRunning();
	}

	public static Path tapeDirectory() {
		return FabricLoader.getInstance().getConfigDir()
				.resolve(SkyblockFlipper.MOD_ID)
				.resolve("tape");
	}

	public static synchronized void start() {
		if (poller != null) {
			return;
		}

		if (!SkyblockFlipperClient.config().pollingEnabled) {
			SkyblockFlipper.LOGGER.info("Polling disabled in config; no market data will be fetched.");
			return;
		}

		tape = new SalesTape(tapeDirectory(), TAPE_RETENTION_DAYS);
		poller = new MarketPoller(API, DATA, tape, SkyblockFlipper.LOGGER::info);
		poller.start();

		SkyblockFlipper.LOGGER.info("Market poller started; sales tape at {}", tapeDirectory());
	}

	public static synchronized void stop() {
		if (poller != null) {
			poller.close();
			poller = null;
		}
	}

	/** Applies a config change without restarting the game. */
	public static synchronized void restart() {
		stop();
		start();
	}
}
