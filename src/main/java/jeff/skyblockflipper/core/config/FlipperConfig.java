package jeff.skyblockflipper.core.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * User settings, persisted as JSON.
 *
 * <p>Deliberately free of any {@code net.minecraft} import: everything under
 * {@code core} must be runnable (and testable) without Minecraft on the classpath.
 * The caller supplies the path, so this class never needs to know about FabricLoader.
 *
 * <p>Fields are mutable with sane defaults rather than a record, because Gson calls the
 * implicit no-arg constructor and then overwrites only the keys actually present in the
 * file. That means adding a new setting later does not invalidate existing configs.
 */
public final class FlipperConfig {
	/**
	 * Hypixel API key. Optional: every endpoint this mod needs (bazaar, auctions,
	 * auctions_ended, items, election) is public and unauthenticated. Only set this if we
	 * later add profile-aware features.
	 */
	public String apiKey = "";

	/** Coins available to deploy. Candidates needing more capital than this are hidden. */
	public long bankroll = 10_000_000L;

	/**
	 * Bazaar Flipper perk level (0-6). Each level cuts the 1.25% bazaar sales tax by
	 * 0.125%, to a floor of 1%. Wrong value here silently biases every bazaar margin,
	 * so it is worth setting accurately.
	 */
	public int bazaarFlipperLevel = 0;

	/** Hide any candidate whose expected net profit is below this. */
	public long minProfitPerFlip = 50_000L;

	/**
	 * Hide any candidate whose valuation confidence is below this (0.0-1.0). Confidence
	 * comes from how many comparable realized sales back the estimate.
	 */
	public double minConfidence = 0.6d;

	/** Render the top-candidates HUD overlay. */
	public boolean hudEnabled = true;

	/** How many candidates the HUD lists. Kept short; the full list is what {@code /flip} is for. */
	public int hudLines = 3;

	/** Corner the HUD hangs from: TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT or BOTTOM_RIGHT. */
	public String hudAnchor = HudAnchor.TOP_LEFT.name();

	/** Distance from the anchored corner, in scaled GUI pixels. */
	public int hudMarginX = 4;
	public int hudMarginY = 4;

	/** Poll the Hypixel API. Turning this off freezes all market data. */
	public boolean pollingEnabled = true;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	public static FlipperConfig load(Path file) throws IOException {
		if (!Files.exists(file)) {
			FlipperConfig fresh = new FlipperConfig();
			fresh.save(file);
			return fresh;
		}

		try (var reader = Files.newBufferedReader(file)) {
			FlipperConfig loaded = GSON.fromJson(reader, FlipperConfig.class);
			// An empty or `null` file parses to null rather than throwing.
			return loaded != null ? loaded.validated() : new FlipperConfig();
		}
	}

	public void save(Path file) throws IOException {
		Files.createDirectories(file.getParent());

		try (var writer = Files.newBufferedWriter(file)) {
			GSON.toJson(this, writer);
		}
	}

	/** Resolved once per frame by the HUD, so parsing stays out of the render path's way. */
	public HudAnchor anchor() {
		return HudAnchor.parse(hudAnchor);
	}

	/** Clamps hand-edited values into ranges the rest of the mod can rely on. */
	public FlipperConfig validated() {
		bankroll = Math.max(0L, bankroll);
		bazaarFlipperLevel = Math.clamp(bazaarFlipperLevel, 0, 6);
		minProfitPerFlip = Math.max(0L, minProfitPerFlip);
		minConfidence = Math.clamp(minConfidence, 0.0d, 1.0d);
		hudLines = Math.clamp(hudLines, 1, 10);
		// A margin larger than the window would park the overlay off-screen with no way to
		// discover why, short of hand-editing the file again.
		hudMarginX = Math.clamp(hudMarginX, 0, 400);
		hudMarginY = Math.clamp(hudMarginY, 0, 400);
		hudAnchor = anchor().name();
		return this;
	}
}
