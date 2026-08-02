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
	 * comes from how many comparable realized sales back the estimate, and how much those
	 * sales agree. Applies to auction flips only: the bazaar strategies price from a live
	 * order book rather than from an estimate.
	 */
	public double minConfidence = 0.6d;

	/**
	 * Sweep the auction house for listings below fair value. Roughly 70MB of JSON per sweep, so
	 * it is worth turning off on a metered connection - the bazaar strategies do not need it.
	 */
	public boolean scanAuctions = true;

	/**
	 * How far under fair value a listing has to be listed before it is worth looking at (0-1).
	 * Also the prune that keeps a sweep affordable: almost every listing fails it before its
	 * item data is ever parsed.
	 */
	public double snipeMinDiscount = 0.15d;

	/**
	 * How many days of realized sales to value items from. Longer means more samples per item;
	 * shorter means a price move last week is not still being averaged into today's estimate.
	 *
	 * <p>Two days is where the trade stops paying, measured rather than guessed:
	 * {@code ValuationWindowBacktestTest} over 402,333 taped sales priced the same held-out 6
	 * hours from every window, and coverage went 87.6% of sales at 24h, 88.9% at 48h, 89.3% at
	 * 120h, while the median absolute log error sat at 0.106 throughout. By coins - the figure
	 * that matters, since the unpriceable sales are the expensive ones - 60.3%, 63.7%, 64.9%.
	 * Past two days another day buys a few tenths of a percent, because what is left unpriced is
	 * configurations nobody trades often, not configurations the window happened to miss.
	 */
	public int valuationWindowDays = 2;

	/**
	 * How many days of sales tape to keep. At observed volumes a day of sales is a few hundred
	 * megabytes, so this is a disk budget as much as a data one.
	 *
	 * <p>Deliberately longer than {@link #valuationWindowDays}, which is the only thing pricing
	 * reads: the extra days are kept for measuring changes to the model against, and a day of
	 * {@code auctions_ended} that was not recorded cannot be bought back at any price.
	 */
	public int tapeRetentionDays = 7;

	/**
	 * Record bazaar top-of-book to disk. Without it the mod has no memory of prices at all, and
	 * cannot tell a wide spread on a liquid item from one on an item that is crashing.
	 */
	public boolean bazaarTapeEnabled = true;

	/**
	 * How many days of bazaar tape to keep. Roughly 40MB a day at the default sampling, so two
	 * weeks is about 565MB - far cheaper than the sales tape, and long enough to cover a full
	 * mayor term, which is when price regimes actually shift.
	 */
	public int bazaarTapeRetentionDays = 14;

	/**
	 * How far back the trend indicators look. The recent sub-window they compare against is an
	 * eighth of this, so the default 24 hours is measured against the last 3.
	 */
	public int trendWindowHours = 24;

	/**
	 * How often to refetch the bazaar book.
	 *
	 * <p>The default keeps the book fresh enough to act on, which is what a playing client needs.
	 * It is also the mod's largest ongoing download once the auction sweep is off: the book is
	 * about 434KB, so 20 seconds is roughly 56GB a month. A headless collector that only wants the
	 * tape should raise this to the 5-minute tape cadence and spend about 4GB instead - the extra
	 * fetches are deduped away before they reach disk.
	 */
	public int bazaarPollSeconds = 20;

	/**
	 * Reject bazaar candidates whose price has drifted down by more than this fraction.
	 *
	 * <p>Market making into a decline is the standard way a quoted margin becomes a realized loss:
	 * buy orders fill fastest exactly while people are dumping. Zero disables the filter.
	 */
	public double maxAdverseDrift = 0.05d;

	/**
	 * How long you are willing to leave an order resting before you would rather have the coins
	 * back.
	 *
	 * <p>Sizes every bazaar plan: throughput is what the book is expected to fill inside this
	 * window, not what it would eventually fill given forever. A longer horizon accepts slower
	 * items and ranks them higher; a shorter one keeps only what fills while you watch. It changes
	 * the ranking without changing the book, so edits to it must invalidate the candidate cache.
	 */
	public int fillHorizonMinutes = 60;

	/** Open the flip screen with a keybind. The screen is also reachable however you like via chat. */
	public boolean guiKeybindEnabled = true;

	/**
	 * How far to shrink the flip screen relative to the game's GUI scale. 0 picks a factor that
	 * fits the layout, which is what most people want.
	 *
	 * <p>The screen is a dense table beside a panel of prose, and at GUI scale 5 or 6 there are
	 * only about 330 scaled pixels of width to put it in - the columns collide and the reasoning
	 * runs off the bottom. Rather than making the player drop their whole interface to a scale that
	 * suits one screen, this screen draws itself at a fraction of it. Auto targets
	 * {@code 480x280} of layout space and never shrinks past 0.5.
	 */
	public double guiZoom = 0.0d;

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

	/**
	 * Record the Hypixel chat lines and menu contents a trade produces, to
	 * {@code chat-capture.jsonl} beside this file.
	 *
	 * <p>Scaffolding for building automatic trade tracking, and off by default: nothing reads the
	 * file at runtime, it only exists so the parser that will read chat can be written against
	 * measured text rather than remembered text. Turn it on for a session of real trading, then off.
	 */
	public boolean tradeCaptureEnabled = false;

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

	/** What the background sweep should do, read fresh so a reload takes effect on the next one. */
	public ScanSettings scanSettings() {
		return new ScanSettings(scanAuctions, valuationWindowDays, snipeMinDiscount, bankroll,
				bazaarTapeEnabled, bazaarTapeRetentionDays, trendWindowHours, bazaarPollSeconds);
	}

	/** Clamps hand-edited values into ranges the rest of the mod can rely on. */
	public FlipperConfig validated() {
		bankroll = Math.max(0L, bankroll);
		bazaarFlipperLevel = Math.clamp(bazaarFlipperLevel, 0, 6);
		minProfitPerFlip = Math.max(0L, minProfitPerFlip);
		minConfidence = Math.clamp(minConfidence, 0.0d, 1.0d);
		hudLines = Math.clamp(hudLines, 1, 10);
		// A zero or negative discount would call every listing at fair value a bargain and hand
		// the sweep tens of thousands of blobs to decode.
		snipeMinDiscount = Math.clamp(snipeMinDiscount, 0.01d, 0.95d);
		valuationWindowDays = Math.clamp(valuationWindowDays, 1, 30);
		tapeRetentionDays = Math.clamp(tapeRetentionDays, 1, 60);
		bazaarTapeRetentionDays = Math.clamp(bazaarTapeRetentionDays, 1, 60);
		// Under a few hours the two averages overlap enough that drift is always near zero; past
		// three days the ring would hold more than the memory budget this was sized for.
		trendWindowHours = Math.clamp(trendWindowHours, 3, 72);
		// Faster than ten seconds is below Hypixel's own cache and spends rate limit re-downloading
		// identical bytes; past ten minutes the book on screen is older than the spread it describes.
		bazaarPollSeconds = Math.clamp(bazaarPollSeconds, 10, 600);
		maxAdverseDrift = Math.clamp(maxAdverseDrift, 0.0d, 1.0d);
		// Under five minutes nothing but the very deepest books clears anything, and past twelve
		// hours the horizon is longer than a session and the throughput it implies is fiction.
		fillHorizonMinutes = Math.clamp(fillHorizonMinutes, 5, 720);
		// Zero means auto. Anything under half is unreadable at any GUI scale, and above 1 the
		// screen would draw larger than the window and clip against it with no way back.
		guiZoom = guiZoom <= 0.0d ? 0.0d : Math.clamp(guiZoom, 0.5d, 1.0d);
		// A margin larger than the window would park the overlay off-screen with no way to
		// discover why, short of hand-editing the file again.
		hudMarginX = Math.clamp(hudMarginX, 0, 400);
		hudMarginY = Math.clamp(hudMarginY, 0, 400);
		hudAnchor = anchor().name();
		return this;
	}
}
