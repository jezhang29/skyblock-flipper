package jeff.skyblockflipper;

import net.minecraft.resources.Identifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared constants. This mod is client-only; the entrypoint lives in
 * {@link jeff.skyblockflipper.client.SkyblockFlipperClient}.
 */
public final class SkyblockFlipper {
	public static final String MOD_ID = "skyblock-flipper";

	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private SkyblockFlipper() {
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
