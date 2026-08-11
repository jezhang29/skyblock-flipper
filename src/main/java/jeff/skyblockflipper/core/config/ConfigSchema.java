package jeff.skyblockflipper.core.config;

import jeff.skyblockflipper.core.pricing.Fees;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.ObjDoubleConsumer;
import java.util.function.ObjIntConsumer;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;
import java.util.function.ToIntFunction;
import java.util.function.ToLongFunction;

/**
 * Every setting in {@link FlipperConfig}, described well enough to build a settings UI from.
 *
 * <p>Before this existed a setting was spelled out in three places that had no way of disagreeing
 * loudly: the field and its javadoc, a clamp buried in {@link FlipperConfig#validated()}, and
 * whatever a UI happened to hardcode. The javadoc is not readable at runtime and the clamps are not
 * enumerable, so any settings screen had to restate both and would quietly drift from them.
 *
 * <p>Lives in {@code core} with no Minecraft on the classpath, so the bounds a UI offers are the
 * same bounds a test can check against {@code validated()} - see {@code ConfigSchemaTest}, which
 * fails if a field is added without an entry here.
 *
 * <p>Entries are a sealed hierarchy rather than one record with an untyped value, so a consumer
 * switches over the kinds and the compiler tells it when a new kind appears.
 */
public final class ConfigSchema {
	/** One setting: how to name it, how to explain it, and how to read and write it. */
	public sealed interface Entry {
		/** The JSON key, which is also the field name. */
		String key();

		/** Short name for a label or a button. */
		String label();

		/** One or two sentences on what it does and what setting it wrong costs. */
		String help();

		record Flag(String key, String label, String help,
				Predicate<FlipperConfig> get, BiConsumer<FlipperConfig, Boolean> set)
				implements Entry {
		}

		record IntRange(String key, String label, String help, int min, int max, int step,
				ToIntFunction<FlipperConfig> get, ObjIntConsumer<FlipperConfig> set)
				implements Entry {
		}

		record LongRange(String key, String label, String help, long min, long max, long step,
				ToLongFunction<FlipperConfig> get, BiConsumer<FlipperConfig, Long> set)
				implements Entry {
		}

		record Ratio(String key, String label, String help, double min, double max, double step,
				ToDoubleFunction<FlipperConfig> get, ObjDoubleConsumer<FlipperConfig> set)
				implements Entry {
		}

		/**
		 * A fixed set of choices, carried as strings.
		 *
		 * <p>The underlying field need not be a string: {@code guiZoom} is a double whose zero means
		 * "auto", which is a choice to a player and a sentinel to the layout code. Mapping happens in
		 * the getter and setter so the UI never has to know.
		 */
		record Choice(String key, String label, String help, List<String> options,
				Function<FlipperConfig, String> get, BiConsumer<FlipperConfig, String> set)
				implements Entry {
			public Choice {
				options = List.copyOf(options);
			}
		}

		record Text(String key, String label, String help,
				Function<FlipperConfig, String> get, BiConsumer<FlipperConfig, String> set)
				implements Entry {
		}
	}

	/** Settings that belong together on one screen or under one category header. */
	public record Group(String title, List<Entry> entries) {
		public Group {
			entries = List.copyOf(entries);
		}
	}

	/** The value {@code guiZoom} takes when the screen should size itself. */
	public static final String ZOOM_AUTO = "Auto";

	private ConfigSchema() {
	}

	public static List<Group> groups() {
		return List.of(MONEY, NPC, SCANNING, DISPLAY, CONNECTION, COLLECTOR, TRACKING);
	}

	/** Every entry, in group order. Useful for lookups and for the test that nothing is missing. */
	public static List<Entry> entries() {
		return groups().stream().flatMap(g -> g.entries().stream()).toList();
	}

	private static final Group MONEY = new Group("Money", List.of(
			new Entry.LongRange("bankroll", "Bankroll",
					"Coins available to deploy. Candidates needing more capital than this are hidden.",
					0L, 1_000_000_000_000L, 1_000_000L,
					c -> c.bankroll, (c, v) -> c.bankroll = v),
			new Entry.IntRange("bazaarFlipperLevel", "Bazaar Flipper level",
					"Your Bazaar Flipper perk level, 0-2. Each level cuts the 1.25% bazaar sales tax "
							+ "by 0.125%, down to 1%, and raises your bazaar order limit by 7 from a "
							+ "base of 14. A wrong value silently biases every bazaar margin.",
					0, Fees.MAX_BAZAAR_FLIPPER_LEVEL, 1,
					c -> c.bazaarFlipperLevel, (c, v) -> c.bazaarFlipperLevel = v),
			new Entry.Ratio("maxCapitalShare", "Maximum capital per flip",
					"The largest share of your bankroll any one plan may ask for. Profit per hour "
							+ "rises with position size, so without this the ranking always prefers "
							+ "the biggest plan your coins allow - it once sized a single order at "
							+ "99.7% of a 250M bankroll. 0.25 leaves room for four positions at once.",
					0.01d, 1.0d, 0.05d,
					c -> c.maxCapitalShare, (c, v) -> c.maxCapitalShare = v),
			new Entry.LongRange("minProfitPerFlip", "Minimum profit per flip",
					"Hide any candidate whose expected net profit is below this.",
					0L, 1_000_000_000L, 50_000L,
					c -> c.minProfitPerFlip, (c, v) -> c.minProfitPerFlip = v),
			new Entry.Ratio("minConfidence", "Minimum confidence",
					"Hide auction candidates the valuation is less sure of than this. Confidence "
							+ "rises with the number of comparable realized sales and how much they "
							+ "agree. Bazaar strategies ignore it - they price off a live book.",
					0.0d, 1.0d, 0.05d,
					c -> c.minConfidence, (c, v) -> c.minConfidence = v),
			new Entry.Ratio("maxAdverseDrift", "Maximum adverse drift",
					"Reject bazaar candidates whose price has already fallen by more than this "
							+ "fraction. Buy orders fill fastest exactly while people are dumping, "
							+ "which is how a quoted margin becomes a realized loss. 0 disables it.",
					0.0d, 1.0d, 0.01d,
					c -> c.maxAdverseDrift, (c, v) -> c.maxAdverseDrift = v),
			new Entry.IntRange("fillHorizonMinutes", "Fill horizon (minutes)",
					"How long you are willing to leave an order resting. Bazaar plans are sized on "
							+ "what the book is expected to fill inside this window, so a longer "
							+ "horizon ranks slower items higher and a shorter one keeps only what "
							+ "fills while you watch.",
					5, 720, 5,
					c -> c.fillHorizonMinutes, (c, v) -> c.fillHorizonMinutes = v)));

	/**
	 * These size NPC plans and none of them means anything to the other strategies, which is why
	 * they are their own group rather than more coins and more patience under Money.
	 *
	 * <p>{@code npcRestingHours} is an {@link Entry.Ratio} despite being hours. That record is a
	 * double range with bounds and a step; only its name says otherwise.
	 */
	private static final Group NPC = new Group("NPC flipping", List.of(
			new Entry.LongRange("npcDailyCapCoins", "Daily NPC coin cap",
					"Gross coins NPCs will pay you per day across all items, before they stop buying. "
							+ "Counts what the NPC hands over, not your profit, so an expensive item "
							+ "burns through it far faster than a cheap one. 500M is the current "
							+ "value; it is a setting because the API does not carry it.",
					1_000_000L, 100_000_000_000L, 10_000_000L,
					c -> c.npcDailyCapCoins, (c, v) -> c.npcDailyCapCoins = v),
			new Entry.Ratio("npcMinMarginRatio", "Minimum NPC margin",
					"How far under the NPC's price your buy order has to sit before the trade is "
							+ "worth an order slot, as a fraction of that price. The same number "
							+ "stops the chase: an order is never repriced above it. 0.15 is the "
							+ "peak of a measured sweep - lower is eaten by repricing, higher "
							+ "leaves slots empty.",
					0.02d, 0.50d, 0.01d,
					c -> c.npcMinMarginRatio, (c, v) -> c.npcMinMarginRatio = v),
			new Entry.IntRange("npcCheckInMinutes", "Check in every (minutes)",
					"How often you come back to reprice resting NPC buy orders. Plans are sized on "
							+ "what fills between check-ins and charged for the outbidding that "
							+ "takes, so coming back more often buys fills with repricing.",
					5, 480, 5,
					c -> c.npcCheckInMinutes, (c, v) -> c.npcCheckInMinutes = v),
			new Entry.Ratio("npcRestingHours", "Order resting window (hours)",
					"How long NPC buy orders are left resting before the coins would rather be "
							+ "elsewhere. There is no price risk in waiting - the exit price is "
							+ "fixed, so an order fills at your price or is cancelled - so this is "
							+ "about how long capital may be tied up, not about danger.",
					0.5d, 24.0d, 0.5d,
					c -> c.npcRestingHours, (c, v) -> c.npcRestingHours = v),
			new Entry.IntRange("npcMaxOrderSlots", "NPC order slots",
					"How many bazaar order slots the NPC basket may fill, or 0 for all of them. "
							+ "Slots are what limits this trade rather than coins, so lowering it "
							+ "is how you leave room for spread flipping. Asking for more than your "
							+ "Bazaar Flipper level allows changes nothing.",
					0, Fees.MAX_BAZAAR_ORDER_SLOTS, 1,
					c -> c.npcMaxOrderSlots, (c, v) -> c.npcMaxOrderSlots = v),
			new Entry.Flag("npcRepriceReminder", "Remind me to reprice",
					"Say so in chat when your resting NPC buy orders have been outbid, at most once "
							+ "per check-in interval. An order only fills while it is the best bid, "
							+ "and a basket left alone for a cycle makes about a fifth of one that is "
							+ "worked. Needs automatic tracking on, since that is what knows which "
							+ "orders you have resting.",
					c -> c.npcRepriceReminder, (c, v) -> c.npcRepriceReminder = v)));

	private static final Group SCANNING = new Group("Scanning", List.of(
			new Entry.Flag("scanAuctions", "Scan auctions",
					"Sweep the auction house for underpriced listings. Roughly 70MB of JSON per "
							+ "sweep, so it is worth turning off on a metered connection - the bazaar "
							+ "strategies do not need it.",
					c -> c.scanAuctions, (c, v) -> c.scanAuctions = v),
			new Entry.Ratio("snipeMinDiscount", "Minimum snipe discount",
					"How far under fair value a listing must be listed before it is worth looking "
							+ "at. Also what keeps a sweep affordable: almost every listing fails "
							+ "this before its item data is ever parsed.",
					0.01d, 0.95d, 0.01d,
					c -> c.snipeMinDiscount, (c, v) -> c.snipeMinDiscount = v),
			new Entry.IntRange("valuationWindowDays", "Valuation window (days)",
					"How many days of realized sales to value items from. Longer means more samples "
							+ "per item; shorter means last week's price move is not still being "
							+ "averaged into today's estimate.",
					1, 30, 1,
					c -> c.valuationWindowDays, (c, v) -> c.valuationWindowDays = v),
			new Entry.IntRange("tapeRetentionDays", "Sales tape retention (days)",
					"How many days of realized sales to keep on disk. A day is a few hundred "
							+ "megabytes at observed volumes, so this is a disk budget.",
					1, 60, 1,
					c -> c.tapeRetentionDays, (c, v) -> c.tapeRetentionDays = v),
			new Entry.Flag("bazaarTapeEnabled", "Record bazaar history",
					"Record bazaar top-of-book to disk. Without it the mod has no memory of prices "
							+ "and cannot tell a wide spread on a liquid item from one on an item "
							+ "that is crashing.",
					c -> c.bazaarTapeEnabled, (c, v) -> c.bazaarTapeEnabled = v),
			new Entry.IntRange("bazaarTapeRetentionDays", "Bazaar tape retention (days)",
					"How many days of bazaar history to keep. About 40MB a day, and a mayor term is "
							+ "when price regimes actually shift.",
					1, 60, 1,
					c -> c.bazaarTapeRetentionDays, (c, v) -> c.bazaarTapeRetentionDays = v),
			new Entry.IntRange("trendWindowHours", "Trend window (hours)",
					"How far back the trend indicators look. The recent sub-window they compare "
							+ "against is an eighth of this, so 24 hours is measured against the "
							+ "last 3.",
					3, 72, 1,
					c -> c.trendWindowHours, (c, v) -> c.trendWindowHours = v)));

	private static final Group DISPLAY = new Group("Display", List.of(
			new Entry.Flag("hudEnabled", "Show HUD",
					"Render the top-candidates overlay in the corner of the screen.",
					c -> c.hudEnabled, (c, v) -> c.hudEnabled = v),
			new Entry.Choice("strategyFilter", "Show only",
					"Which strategy /flip, the HUD and the flip screen's opening tab list. The "
							+ "per-strategy commands and tabs still show whatever you ask them for.",
					FlipperConfig.strategyFilterOptions(),
					c -> c.strategyFilter, (c, v) -> c.strategyFilter = v),
			new Entry.IntRange("hudLines", "HUD lines",
					"How many candidates the HUD lists. Kept short; the full list is what the flip "
							+ "screen is for.",
					1, 10, 1,
					c -> c.hudLines, (c, v) -> c.hudLines = v),
			new Entry.Choice("hudAnchor", "HUD corner",
					"Which corner the HUD hangs from.",
					HudAnchor.names(),
					c -> c.anchor().name(), (c, v) -> c.hudAnchor = v),
			new Entry.IntRange("hudMarginX", "HUD margin X",
					"Distance from the anchored corner, in scaled GUI pixels.",
					0, 400, 1,
					c -> c.hudMarginX, (c, v) -> c.hudMarginX = v),
			new Entry.IntRange("hudMarginY", "HUD margin Y",
					"Distance from the anchored corner, in scaled GUI pixels.",
					0, 400, 1,
					c -> c.hudMarginY, (c, v) -> c.hudMarginY = v),
			new Entry.Flag("guiKeybindEnabled", "Flip screen keybind",
					"Open the flip screen with a key. The screen is always reachable with /flip gui.",
					c -> c.guiKeybindEnabled, (c, v) -> c.guiKeybindEnabled = v),
			new Entry.Choice("guiZoom", "Flip screen zoom",
					"How far to shrink the flip screen relative to the game's GUI scale. Auto picks "
							+ "a factor that fits the layout, which is what most people want; at GUI "
							+ "scale 6 there are only about 330 scaled pixels of width to work with.",
					zoomOptions(),
					ConfigSchema::readZoom, ConfigSchema::writeZoom)));

	private static final Group CONNECTION = new Group("Connection", List.of(
			new Entry.Flag("pollingEnabled", "Poll the API",
					"Fetch market data from Hypixel. Turning this off freezes every number the mod "
							+ "shows.",
					c -> c.pollingEnabled, (c, v) -> c.pollingEnabled = v),
			new Entry.IntRange("bazaarPollSeconds", "Bazaar poll interval (seconds)",
					"How often to refetch the bazaar book. This is the largest ongoing download "
							+ "once auction scanning is off - the book is about 434KB, so 20 seconds "
							+ "is roughly 56GB a month. Raising it trades price freshness for "
							+ "bandwidth, and takes effect on the next reload.",
					10, 600, 5,
					c -> c.bazaarPollSeconds, (c, v) -> c.bazaarPollSeconds = v),
			new Entry.Text("apiKey", "API key",
					"Unused. Every endpoint this mod reads is public and unauthenticated; the field "
							+ "exists for profile-aware features that do not exist yet.",
					c -> c.apiKey, (c, v) -> c.apiKey = v)));

	private static final Group COLLECTOR = new Group("Collector sync", List.of(
			new Entry.Flag("tapeSyncEnabled", "Sync from the collector",
					"On startup, download whatever the collector taped while this client was closed "
							+ "and merge it into the local tape. Only new bytes are fetched, and "
							+ "nothing is overwritten - sales taped here and sales taped there are "
							+ "kept as one set.",
					c -> c.tapeSyncEnabled, (c, v) -> c.tapeSyncEnabled = v),
			new Entry.Text("tapeSyncUrl", "Collector URL",
					"Where the collector serves its tape, e.g. http://198.51.100.7:8080. The sales "
							+ "and bazaar directories are expected under it by the same names this "
							+ "client uses.",
					c -> c.tapeSyncUrl, (c, v) -> c.tapeSyncUrl = v),
			new Entry.Text("tapeSyncToken", "Collector token",
					"Shared secret sent with every sync request. It has to match the one the server "
							+ "checks. Blank sends none, which only works if the server asks for "
							+ "none.",
					c -> c.tapeSyncToken, (c, v) -> c.tapeSyncToken = v),
			new Entry.IntRange("tapeSyncIntervalMinutes", "Re-sync every (minutes)",
					"How often to sync again during a session. Zero means startup only, which is "
							+ "the useful setting: while this client is running it tapes the same "
							+ "endpoints the collector does, so a mid-session sync mostly downloads "
							+ "data it already holds.",
					0, 1440, 15,
					c -> c.tapeSyncIntervalMinutes, (c, v) -> c.tapeSyncIntervalMinutes = v)));

	private static final Group TRACKING = new Group("Tracking", List.of(
			new Entry.Flag("tradeCaptureEnabled", "Capture trade messages",
					"Record the Hypixel chat lines and menu contents your trades produce, to "
							+ "chat-capture.jsonl in the config folder. Nothing reads it at runtime; "
							+ "it exists so automatic trade tracking can be built against what "
							+ "Hypixel actually says. Leave it off unless you are collecting.",
					c -> c.tradeCaptureEnabled, (c, v) -> c.tradeCaptureEnabled = v),
			new Entry.Flag("autoTrackEnabled", "Track trades automatically",
					"Fill the ledger from the trades Hypixel reports, rather than typing them in "
							+ "with /flip take and /flip close. Buys open positions, sales close "
							+ "them, and a trade the mod never quoted is recorded but kept out of "
							+ "the capture rate. Open your bazaar orders menu now and then: a "
							+ "partial fill is announced nowhere else.",
					c -> c.autoTrackEnabled, (c, v) -> c.autoTrackEnabled = v),
			new Entry.Flag("trackUnquotedTrades", "Also track trades the mod never quoted",
					"With this off, tracking records only trades against plans you took, and the "
							+ "materials you buy to play the game with are ignored. On, every bazaar "
							+ "buy opens a position - which is what you want only if you flip by "
							+ "hand and want that measured, since those positions stay open until "
							+ "you sell the same item again.",
					c -> c.trackUnquotedTrades, (c, v) -> c.trackUnquotedTrades = v)));

	private static List<String> zoomOptions() {
		return List.of(ZOOM_AUTO, "0.5", "0.6", "0.7", "0.8", "0.9", "1.0");
	}

	private static String readZoom(FlipperConfig config) {
		// validated() folds anything at or below zero to exactly zero, which is the auto sentinel.
		if (config.guiZoom <= 0.0d) {
			return ZOOM_AUTO;
		}

		// validated() has already clamped to 0.5-1.0, so one decimal place always lands on an
		// offered step. A hand-edited 0.73 shows as 0.7 rather than as something unselectable.
		return String.format("%.1f", config.guiZoom);
	}

	private static void writeZoom(FlipperConfig config, String option) {
		config.guiZoom = ZOOM_AUTO.equals(option) ? 0.0d : Double.parseDouble(option);
	}
}
