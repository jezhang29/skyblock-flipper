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
 *
 * <p>Labels and help are written for a player who has never seen the code. No field names, no class
 * names, no file names: what the setting does in the game, and what turning it up or down costs.
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
		 * <p>The choices a player picks from are words, and the value stored in the file need not be
		 * one of them: {@code guiZoom} is a double whose zero means "auto", and the two enum-backed
		 * choices are stored as enum names nobody should have to read. Mapping happens in the getter
		 * and setter so the UI never has to know.
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

	/** The basket ranking that carries least to the NPC: {@link NpcRanking#LOAD}. */
	public static final String RANK_FEWER_TRIPS = "Fewer trips";

	/** The basket ranking that earns most per order slot: {@link NpcRanking#ORDER_SLOT}. */
	public static final String RANK_MORE_COINS = "More coins";

	/** The words offered for the basket panel's side, in the order they are shown. */
	private static final String OVERLAY_LEFT = "Left";

	private static final String OVERLAY_RIGHT = "Right";

	private static final String OVERLAY_AUTO = "Automatic";

	private ConfigSchema() {
	}

	public static List<Group> groups() {
		return List.of(MONEY, NPC, CRAFT, SCANNING, DISPLAY, CONNECTION, COLLECTOR, TRACKING);
	}

	/** Every entry, in group order. Useful for lookups and for the test that nothing is missing. */
	public static List<Entry> entries() {
		return groups().stream().flatMap(g -> g.entries().stream()).toList();
	}

	private static final Group MONEY = new Group("Money", List.of(
			new Entry.LongRange("bankroll", "Bankroll",
					"The most coins you are willing to have tied up in flips at once. Every plan is "
							+ "sized to fit inside it, and anything that needs more is hidden.",
					0L, 1_000_000_000_000L, 1_000_000L,
					c -> c.bankroll, (c, v) -> c.bankroll = v),
			new Entry.IntRange("bazaarFlipperLevel", "Bazaar Flipper level",
					"Your Bazaar Flipper perk level, 0 to 2. Each level takes a little off the bazaar "
							+ "sales tax and gives you 7 more order slots on top of the base 14. Set "
							+ "it wrong and every bazaar profit figure is quietly wrong.",
					0, Fees.MAX_BAZAAR_FLIPPER_LEVEL, 1,
					c -> c.bazaarFlipperLevel, (c, v) -> c.bazaarFlipperLevel = v),
			new Entry.Ratio("maxCapitalShare", "Most one flip may spend",
					"The largest share of your bankroll a single flip may use. Bigger positions always "
							+ "look better, so without this the top of the list would be one flip "
							+ "holding nearly everything you have. 0.25 leaves room for four at once.",
					0.01d, 1.0d, 0.05d,
					c -> c.maxCapitalShare, (c, v) -> c.maxCapitalShare = v),
			new Entry.LongRange("minProfitPerFlip", "Minimum profit per flip",
					"Hide anything expected to make less than this. It is always the total for the "
							+ "whole flip rather than a rate per hour, so a small number here still "
							+ "lets slow flips through.",
					0L, 1_000_000_000L, 50_000L,
					c -> c.minProfitPerFlip, (c, v) -> c.minProfitPerFlip = v),
			new Entry.Ratio("minConfidence", "Minimum confidence",
					"Hide auction finds the mod is less sure of than this. It grows more confident the "
							+ "more recent sales of the same item it has seen and the closer those "
							+ "prices are to each other. Bazaar and NPC flips ignore it.",
					0.0d, 1.0d, 0.05d,
					c -> c.minConfidence, (c, v) -> c.minConfidence = v),
			new Entry.Ratio("maxAdverseDrift", "Skip items already falling",
					"Skip bazaar flips on items whose price has fallen by more than this fraction "
							+ "lately. Your buy order fills fastest while people are dumping, which is "
							+ "how a good-looking spread turns into a loss. 0 turns the check off.",
					0.0d, 1.0d, 0.01d,
					c -> c.maxAdverseDrift, (c, v) -> c.maxAdverseDrift = v),
			new Entry.IntRange("fillHorizonMinutes", "How long you will wait for a fill (minutes)",
					"How long you are willing to leave a bazaar order resting. Plans only count what "
							+ "should fill inside this time, so a long setting ranks slow items higher "
							+ "and a short one keeps only what fills while you watch.",
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
			new Entry.LongRange("npcDailyCapCoins", "Daily NPC coin limit",
					"NPCs stop buying once they have paid you this many coins in a day, across every "
							+ "item. It counts what they hand you, not your profit, so expensive items "
							+ "use it up fast. 500M is the current in-game limit.",
					1_000_000L, 100_000_000_000L, 10_000_000L,
					c -> c.npcDailyCapCoins, (c, v) -> c.npcDailyCapCoins = v),
			new Entry.Ratio("npcMinMarginRatio", "Minimum gap under the NPC price",
					"How far under the NPC's price your buy order has to sit before the flip is worth "
							+ "an order slot. It is also the point you stop raising a price at. 0.15, "
							+ "meaning 15% under, earned the most in testing.",
					0.02d, 0.50d, 0.01d,
					c -> c.npcMinMarginRatio, (c, v) -> c.npcMinMarginRatio = v),
			new Entry.IntRange("npcCheckInMinutes", "Check in every (minutes)",
					"How often you intend to come back and move outbid orders back to the top of the "
							+ "book. Plans are sized on what fills between visits, and a reprice list "
							+ "keeps its prices this long. Set it to what you will really do.",
					5, 480, 5,
					c -> c.npcCheckInMinutes, (c, v) -> c.npcCheckInMinutes = v),
			new Entry.Ratio("npcRestingHours", "Give up on an order after (hours)",
					"How long an NPC buy order may sit before you would rather have the coins back. "
							+ "Nothing is at risk while it waits, because the NPC's price cannot move, "
							+ "so this is only about how long your coins stay tied up.",
					0.5d, 24.0d, 0.5d,
					c -> c.npcRestingHours, (c, v) -> c.npcRestingHours = v),
			new Entry.IntRange("npcMaxOrderSlots", "Order slots for NPC flips",
					"How many of your bazaar order slots NPC flips may fill, or 0 for all of them. "
							+ "Slots run out long before coins do, so lower this to leave room for "
							+ "other flipping.",
					0, Fees.MAX_BAZAAR_ORDER_SLOTS, 1,
					c -> c.npcMaxOrderSlots, (c, v) -> c.npcMaxOrderSlots = v),
			new Entry.Ratio("npcDriftPremium", "Pay to stay on top",
					"Post a little above the going price so your order holds the top of the book on "
							+ "its own, instead of you returning to raise it. 1.0 pays enough to hold "
							+ "it for a whole resting window and suits long gaps between visits; 0 "
							+ "posts at the plain price and expects you to come back and reprice.",
					0.0d, 2.0d, 0.05d,
					c -> c.npcDriftPremium, (c, v) -> c.npcDriftPremium = v),
			new Entry.Choice("npcRankingKey", "What the basket should favour",
					"Which item gets a slot when the basket cannot fit everything. \"" + RANK_FEWER_TRIPS
							+ "\" picks what earns most per inventory load, so you carry less to the "
							+ "NPC. \"" + RANK_MORE_COINS + "\" picks what earns most per order slot: "
							+ "about a third more coins for about three times the carrying.",
					List.of(RANK_FEWER_TRIPS, RANK_MORE_COINS),
					ConfigSchema::readRanking, ConfigSchema::writeRanking),
			new Entry.Flag("npcRepriceReminder", "Remind me to reprice",
					"Tell you in chat when your resting NPC buy orders have been outbid, once per "
							+ "round. An order only fills while it is the best offer, and a basket left "
							+ "alone all cycle makes about a fifth of one you keep working. Asking for "
							+ "the list yourself uses up that round's reminder. Needs automatic "
							+ "tracking on, since that is what knows which orders you have out.",
					c -> c.npcRepriceReminder, (c, v) -> c.npcRepriceReminder = v),
			new Entry.Flag("npcRepriceSound", "Play a sound with the reminder",
					"Play a note as well as printing the reminder, because Skyblock chat scrolls fast "
							+ "enough to lose a line before you read it. One note per round, and "
							+ "nothing at all with the reminder itself off.",
					c -> c.npcRepriceSound, (c, v) -> c.npcRepriceSound = v)));

	/**
	 * Crafting's own two settings, apart from Money for the same reason the NPC ones are: the slot
	 * budget means nothing to any other strategy, and it is a budget shared with the NPC basket
	 * rather than a coin limit.
	 */
	private static final Group CRAFT = new Group("Craft flipping", List.of(
			new Entry.Flag("craftFlipsEnabled", "Look for crafting profits",
					"Buy materials on the bazaar, craft, and sell the result back. The mod checks "
							+ "every recipe it knows against the live prices; it never checks whether "
							+ "you have unlocked the recipe, so read the unlock line before you buy.",
					c -> c.craftFlipsEnabled, (c, v) -> c.craftFlipsEnabled = v),
			new Entry.IntRange("craftMaxOrderSlots", "Order slots one craft may use",
					"How many of your bazaar order slots a single crafting job may take up. Materials "
							+ "are cheaper bought on your own buy orders, but each one sits in a slot "
							+ "the NPC basket also wants. Jobs over this limit are shown with the "
							+ "materials bought instantly instead, which uses one slot.",
					1, Fees.MAX_BAZAAR_ORDER_SLOTS, 1,
					c -> c.craftMaxOrderSlots, (c, v) -> c.craftMaxOrderSlots = v)));

	private static final Group SCANNING = new Group("Scanning", List.of(
			new Entry.Flag("scanAuctions", "Search the auction house",
					"Look through auctions for items listed under what they usually sell for. It "
							+ "downloads about 70MB each sweep, so turn it off on a metered "
							+ "connection - bazaar and NPC flipping do not need it.",
					c -> c.scanAuctions, (c, v) -> c.scanAuctions = v),
			new Entry.Ratio("snipeMinDiscount", "Minimum auction discount",
					"How far under the usual price an auction has to be listed before it is shown. "
							+ "Raising it means fewer but better finds, and a faster search.",
					0.01d, 0.95d, 0.01d,
					c -> c.snipeMinDiscount, (c, v) -> c.snipeMinDiscount = v),
			new Entry.IntRange("valuationWindowDays", "Judge prices on the last (days)",
					"How many days of completed sales an item's usual price is worked out from. Longer "
							+ "means more sales behind each estimate; shorter means last week's prices "
							+ "stop dragging on today's.",
					1, 30, 1,
					c -> c.valuationWindowDays, (c, v) -> c.valuationWindowDays = v),
			new Entry.IntRange("tapeRetentionDays", "Keep auction sales for (days)",
					"How many days of recorded auction sales to keep on disk. A day is a few hundred "
							+ "megabytes, so this is mostly a disk budget.",
					1, 60, 1,
					c -> c.tapeRetentionDays, (c, v) -> c.tapeRetentionDays = v),
			new Entry.Flag("bazaarTapeEnabled", "Record bazaar prices",
					"Keep a history of bazaar prices on disk. Without it the mod has no memory of "
							+ "prices and cannot tell a healthy spread from an item that is crashing.",
					c -> c.bazaarTapeEnabled, (c, v) -> c.bazaarTapeEnabled = v),
			new Entry.IntRange("bazaarTapeRetentionDays", "Keep bazaar prices for (days)",
					"How many days of bazaar price history to keep, at roughly 40MB a day. A mayor's "
							+ "term is about how long it takes prices to change character.",
					1, 60, 1,
					c -> c.bazaarTapeRetentionDays, (c, v) -> c.bazaarTapeRetentionDays = v),
			new Entry.IntRange("trendWindowHours", "Trend window (hours)",
					"How far back the rising and falling arrows look. They compare the last eighth of "
							+ "this against the rest, so 24 hours judges today against the last 3.",
					3, 72, 1,
					c -> c.trendWindowHours, (c, v) -> c.trendWindowHours = v)));

	private static final Group DISPLAY = new Group("Display", List.of(
			new Entry.Flag("hudEnabled", "Show the corner list",
					"Draw a short list of the best flips in the corner of the screen while you play.",
					c -> c.hudEnabled, (c, v) -> c.hudEnabled = v),
			new Entry.Flag("bazaarOverlayEnabled", "Show basket at the bazaar",
					"Draw your list of things to do beside Hypixel's bazaar menu, so the price and the "
							+ "amount are on screen where you type them instead of back in chat. "
							+ "Scroll it with the wheel, click a row to copy the item name, click a "
							+ "number to copy the number. Nothing is clicked or typed for you.",
					c -> c.bazaarOverlayEnabled, (c, v) -> c.bazaarOverlayEnabled = v),
			new Entry.Flag("bazaarHighlightEnabled", "Highlight the slot to click",
					"Put a green box behind the button or item the next job on your list needs, the "
							+ "whole way through placing an order. Where the mod cannot work out which "
							+ "slot that is, it draws nothing rather than a guess, and it still never "
							+ "clicks anything for you.",
					c -> c.bazaarHighlightEnabled, (c, v) -> c.bazaarHighlightEnabled = v),
			new Entry.Choice("bazaarOverlaySide", "Which side the basket sits on",
					"Which side of Hypixel's menu that panel sits on. Automatic takes whichever side "
							+ "has more room, which gives the widest panel but makes it jump sides as "
							+ "you move between bazaar screens. Left or Right keeps it in one place.",
					List.of(OVERLAY_LEFT, OVERLAY_RIGHT, OVERLAY_AUTO),
					ConfigSchema::readOverlaySide, ConfigSchema::writeOverlaySide),
			new Entry.Choice("strategyFilter", "Show only",
					"Which kind of flip the /flip list, the corner list and the flip screen open on. "
							+ "Asking for one kind by command or by tab still shows it.",
					FlipperConfig.strategyFilterOptions(),
					c -> c.strategyFilter, (c, v) -> c.strategyFilter = v),
			new Entry.IntRange("hudLines", "Lines in the corner list",
					"How many flips the corner list shows. Keep it short; the flip screen is where the "
							+ "whole list lives.",
					1, 10, 1,
					c -> c.hudLines, (c, v) -> c.hudLines = v),
			new Entry.Choice("hudAnchor", "Corner to use",
					"Which corner of the screen the list hangs from.",
					HudAnchor.names(),
					c -> c.anchor().name(), (c, v) -> c.hudAnchor = v),
			new Entry.IntRange("hudMarginX", "Distance from that corner, across",
					"How far in from the side of the screen the corner list sits.",
					0, 400, 1,
					c -> c.hudMarginX, (c, v) -> c.hudMarginX = v),
			new Entry.IntRange("hudMarginY", "Distance from that corner, down",
					"How far in from the top or bottom of the screen the corner list sits.",
					0, 400, 1,
					c -> c.hudMarginY, (c, v) -> c.hudMarginY = v),
			new Entry.Flag("guiKeybindEnabled", "Open the flip screen with a key",
					"Bind a key that opens the flip screen. It is always reachable by typing /flip gui "
							+ "as well.",
					c -> c.guiKeybindEnabled, (c, v) -> c.guiKeybindEnabled = v),
			new Entry.Choice("guiZoom", "Flip screen size",
					"How much to shrink the flip screen. Auto picks a size that fits whatever GUI "
							+ "scale you play at, which is what most people want; a number holds it "
							+ "there instead.",
					zoomOptions(),
					ConfigSchema::readZoom, ConfigSchema::writeZoom)));

	private static final Group CONNECTION = new Group("Connection", List.of(
			new Entry.Flag("pollingEnabled", "Keep prices up to date",
					"Fetch market data from Hypixel. With this off, every number the mod shows stops "
							+ "moving.",
					c -> c.pollingEnabled, (c, v) -> c.pollingEnabled = v),
			new Entry.IntRange("bazaarPollSeconds", "Refresh bazaar prices every (seconds)",
					"How often bazaar prices are fetched again. This is the mod's main ongoing "
							+ "download once auction searching is off - about 434KB a time, so 20 "
							+ "seconds is roughly 56GB a month. Applies after the next reload.",
					10, 600, 5,
					c -> c.bazaarPollSeconds, (c, v) -> c.bazaarPollSeconds = v),
			new Entry.Text("apiKey", "Hypixel API key",
					"Not used by anything. Every price the mod reads is public, so you can leave this "
							+ "blank.",
					c -> c.apiKey, (c, v) -> c.apiKey = v)));

	private static final Group COLLECTOR = new Group("Collector sync", List.of(
			new Entry.Flag("tapeSyncEnabled", "Fetch history from your recorder",
					"On startup, download the price history a recorder on another machine kept while "
							+ "this game was closed, and fold it into your own. Only the new part is "
							+ "fetched, and nothing you already have is overwritten.",
					c -> c.tapeSyncEnabled, (c, v) -> c.tapeSyncEnabled = v),
			new Entry.Text("tapeSyncUrl", "Recorder address",
					"Where that recorder serves its history, for example http://198.51.100.7:8080.",
					c -> c.tapeSyncUrl, (c, v) -> c.tapeSyncUrl = v),
			new Entry.Text("tapeSyncToken", "Recorder password",
					"The shared password sent with every request. It has to match the one the recorder "
							+ "expects; blank sends none, which only works if it asks for none.",
					c -> c.tapeSyncToken, (c, v) -> c.tapeSyncToken = v),
			new Entry.IntRange("tapeSyncIntervalMinutes", "Fetch again every (minutes)",
					"How often to fetch again during a session. 0 means at startup only, which is "
							+ "usually right: while you are playing, this game is recording the same "
							+ "prices itself.",
					0, 1440, 15,
					c -> c.tapeSyncIntervalMinutes, (c, v) -> c.tapeSyncIntervalMinutes = v)));

	private static final Group TRACKING = new Group("Tracking", List.of(
			new Entry.Flag("tradeCaptureEnabled", "Record raw trade messages",
					"Save the chat lines and menus your trades produce to a file, so the mod can be "
							+ "fixed if Hypixel changes its wording. Nothing uses the file while you "
							+ "play, so leave it off unless you are collecting for that.",
					c -> c.tradeCaptureEnabled, (c, v) -> c.tradeCaptureEnabled = v),
			new Entry.Flag("autoTrackEnabled", "Record my trades for me",
					"Fill the ledger from the trades Hypixel announces, instead of you typing each one "
							+ "in. A buy opens a flip and a sale closes it. Open your bazaar orders "
							+ "menu now and then: an order that fills part way is announced nowhere "
							+ "else.",
					c -> c.autoTrackEnabled, (c, v) -> c.autoTrackEnabled = v),
			new Entry.Flag("trackUnquotedTrades", "Also record trades the mod never suggested",
					"Off, only trades that match a plan you took are recorded, so the materials you "
							+ "buy to play with are ignored. On, every bazaar buy opens a flip, which "
							+ "you want only if you flip by hand and want that measured too.",
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

	private static String readRanking(FlipperConfig config) {
		return config.npcRanking() == NpcRanking.LOAD ? RANK_FEWER_TRIPS : RANK_MORE_COINS;
	}

	private static void writeRanking(FlipperConfig config, String option) {
		config.npcRankingKey = RANK_MORE_COINS.equals(option)
				? NpcRanking.ORDER_SLOT.name()
				: NpcRanking.LOAD.name();
	}

	private static String readOverlaySide(FlipperConfig config) {
		return switch (config.overlaySide()) {
			case LEFT -> OVERLAY_LEFT;
			case RIGHT -> OVERLAY_RIGHT;
			case AUTO -> OVERLAY_AUTO;
		};
	}

	private static void writeOverlaySide(FlipperConfig config, String option) {
		OverlaySide side = switch (option) {
			case OVERLAY_RIGHT -> OverlaySide.RIGHT;
			case OVERLAY_AUTO -> OverlaySide.AUTO;
			default -> OverlaySide.LEFT;
		};

		config.bazaarOverlaySide = side.name();
	}
}
