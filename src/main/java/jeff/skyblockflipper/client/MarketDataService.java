package jeff.skyblockflipper.client;

import jeff.skyblockflipper.SkyblockFlipper;
import jeff.skyblockflipper.core.api.HypixelApi;
import jeff.skyblockflipper.core.api.MarketData;
import jeff.skyblockflipper.core.api.MarketPoller;
import jeff.skyblockflipper.core.tape.BazaarTape;
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
	private static final MarketData DATA = new MarketData();
	private static final HypixelApi API = new HypixelApi();

	private static MarketPoller poller;
	private static SalesTape tape;
	private static BazaarTape bazaarTape;

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

	public static BazaarTape bazaarTape() {
		return bazaarTape;
	}

	public static Path tapeDirectory() {
		return FabricLoader.getInstance().getConfigDir()
				.resolve(SkyblockFlipper.MOD_ID)
				.resolve("tape");
	}

	/**
	 * A sibling of the sales tape, never a child of it.
	 *
	 * <p>The two have their own retention windows and their own prune passes, and a shared
	 * directory would let either one delete the other's files.
	 */
	public static Path bazaarTapeDirectory() {
		return FabricLoader.getInstance().getConfigDir()
				.resolve(SkyblockFlipper.MOD_ID)
				.resolve("bazaar-tape");
	}

	public static synchronized void start() {
		if (poller != null) {
			return;
		}

		if (!SkyblockFlipperClient.config().pollingEnabled) {
			SkyblockFlipper.LOGGER.info("Polling disabled in config; no market data will be fetched.");
			return;
		}

		tape = new SalesTape(tapeDirectory(), SkyblockFlipperClient.config().tapeRetentionDays);
		bazaarTape = new BazaarTape(bazaarTapeDirectory(),
				SkyblockFlipperClient.config().bazaarTapeRetentionDays);
		// The settings are read through a supplier so /flip reload reaches the next sweep without
		// restarting the poller.
		poller = new MarketPoller(API, DATA, tape, bazaarTape,
				() -> SkyblockFlipperClient.config().scanSettings(), SkyblockFlipper.LOGGER::info);
		poller.start();

		SkyblockFlipper.LOGGER.info("Market poller started; sales tape at {}, bazaar tape at {}",
				tapeDirectory(), bazaarTapeDirectory());
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
