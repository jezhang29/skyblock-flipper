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
