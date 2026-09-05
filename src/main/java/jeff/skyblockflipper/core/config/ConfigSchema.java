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

		/** One plain sentence on what it does; no jargon, no measurement dumps. */
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
		return List.of(MONEY, NPC, CRAFT, COMBINE, FUSION, RECOVERY, SCANNING, DISPLAY,
				CONNECTION, COLLECTOR, TRACKING);
	}

	/** Every entry, in group order. Useful for lookups and for the test that nothing is missing. */
	public static List<Entry> entries() {
		return groups().stream().flatMap(g -> g.entries().stream()).toList();
	}

	private static final Group MONEY = new Group("Money", List.of(
			new Entry.LongRange("bankroll", "Bankroll",
					"The most coins you will tie up in flips at once; every plan is sized to fit "
							+ "inside it.",
					0L, 1_000_000_000_000L, 1_000_000L,
					c -> c.bankroll, (c, v) -> c.bankroll = v),
			new Entry.IntRange("bazaarFlipperLevel", "Bazaar Flipper level",
					"Your Bazaar Flipper perk level (0 to 2), which sets your bazaar tax and order "
							+ "slots, so a wrong value makes every bazaar figure wrong.",
					0, Fees.MAX_BAZAAR_FLIPPER_LEVEL, 1,
					c -> c.bazaarFlipperLevel, (c, v) -> c.bazaarFlipperLevel = v),
			new Entry.Ratio("maxCapitalShare", "Most one flip may spend",
					"The largest share of your bankroll one flip may use, so the list is not topped by "
							+ "a single huge position.",
					0.01d, 1.0d, 0.05d,
					c -> c.maxCapitalShare, (c, v) -> c.maxCapitalShare = v),
			new Entry.LongRange("minProfitPerFlip", "Minimum profit per flip",
					"Hide any flip expected to make less than this in total profit, not as a rate per "
							+ "hour.",
					0L, 1_000_000_000L, 50_000L,
					c -> c.minProfitPerFlip, (c, v) -> c.minProfitPerFlip = v),
			new Entry.Ratio("minConfidence", "Hide shaky auction finds",
					"Hide auction snipes without enough recent same-item sales behind the price to "
							+ "trust; bazaar and NPC flips ignore it.",
					0.0d, 1.0d, 0.05d,
					c -> c.minConfidence, (c, v) -> c.minConfidence = v),
			new Entry.Ratio("maxAdverseDrift", "Skip items already falling",
					"Skip bazaar flips on items whose price has fallen more than this lately, since "
							+ "your order fills fastest while people dump; 0 turns it off.",
					0.0d, 1.0d, 0.01d,
					c -> c.maxAdverseDrift, (c, v) -> c.maxAdverseDrift = v),
			new Entry.IntRange("fillHorizonMinutes", "How long you will wait for a fill (minutes)",
					"How long you will leave a bazaar order resting, since plans only count what fills "
							+ "in that time.",
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
							+ "item; 500M is the current in-game limit.",
					1_000_000L, 100_000_000_000L, 10_000_000L,
					c -> c.npcDailyCapCoins, (c, v) -> c.npcDailyCapCoins = v),
			new Entry.Ratio("npcMinMarginRatio", "Minimum gap under the NPC price",
					"How far under the NPC price your buy order must sit to be worth a slot, and where "
							+ "you stop raising it; 15% earned the most in testing.",
					0.02d, 0.50d, 0.01d,
					c -> c.npcMinMarginRatio, (c, v) -> c.npcMinMarginRatio = v),
			new Entry.IntRange("npcCheckInMinutes", "Check in every (minutes)",
					"How often you will come back to move outbid orders to the top; plans are sized on "
							+ "it, so set it to what you will really do.",
					5, 480, 5,
					c -> c.npcCheckInMinutes, (c, v) -> c.npcCheckInMinutes = v),
			new Entry.Ratio("npcRestingHours", "Give up on an order after (hours)",
					"How long an NPC buy order may sit before you would rather cancel and take the "
							+ "coins back.",
					0.5d, 24.0d, 0.5d,
					c -> c.npcRestingHours, (c, v) -> c.npcRestingHours = v),
			new Entry.IntRange("npcMaxOrderSlots", "Order slots for NPC flips",
					"How many bazaar order slots NPC flips may fill, or 0 for all of them.",
					0, Fees.MAX_BAZAAR_ORDER_SLOTS, 1,
					c -> c.npcMaxOrderSlots, (c, v) -> c.npcMaxOrderSlots = v),
			new Entry.Choice("npcRankingKey", "What the basket should favour",
					"Which item wins a slot when the basket cannot fit everything: \"" + RANK_FEWER_TRIPS
							+ "\" carries less to the NPC, \"" + RANK_MORE_COINS + "\" earns more per "
							+ "slot for about three times the hauling.",
					List.of(RANK_FEWER_TRIPS, RANK_MORE_COINS),
					ConfigSchema::readRanking, ConfigSchema::writeRanking),
			new Entry.Flag("npcRepriceReminder", "Remind me to reprice",
					"Tell you in chat once a round when your resting NPC orders have been outbid; "
							+ "needs automatic tracking on to know which orders you hold.",
					c -> c.npcRepriceReminder, (c, v) -> c.npcRepriceReminder = v),
			new Entry.Flag("npcRepriceSound", "Play a sound with the reminder",
					"Play a note with the reprice reminder, since chat scrolls fast enough to lose a "
							+ "line before you read it.",
					c -> c.npcRepriceSound, (c, v) -> c.npcRepriceSound = v)));

	/**
	 * Crafting's own two settings, apart from Money for the same reason the NPC ones are: the slot
	 * budget means nothing to any other strategy, and it is a budget shared with the NPC basket
	 * rather than a coin limit.
	 */
	private static final Group CRAFT = new Group("Craft flipping", List.of(
			new Entry.Flag("craftFlipsEnabled", "Look for crafting profits",
					"Buy materials on the bazaar, craft, and sell the result back; it never checks "
							+ "whether you have unlocked the recipe, so read the unlock line first.",
					c -> c.craftFlipsEnabled, (c, v) -> c.craftFlipsEnabled = v),
			new Entry.IntRange("craftMaxOrderSlots", "Order slots one craft may use",
					"How many bazaar order slots one crafting job may use, since those slots are "
							+ "shared with the NPC basket.",
					1, Fees.MAX_BAZAAR_ORDER_SLOTS, 1,
					c -> c.craftMaxOrderSlots, (c, v) -> c.craftMaxOrderSlots = v)));

	/**
	 * Combining's one setting. It has no slot budget of its own because a combine rests at most two
	 * orders, so unlike a craft it cannot starve the NPC basket.
	 */
	private static final Group COMBINE = new Group("Combine flipping", List.of(
			new Entry.Flag("combineFlipsEnabled", "Look for book-combining profits",
					"Buy low-tier enchanted books, combine them up at the anvil, and sell the top "
							+ "tier; read /flip combine, as the return is per click and ranks low.",
					c -> c.combineFlipsEnabled, (c, v) -> c.combineFlipsEnabled = v)));

	/**
	 * Fusion's two settings. Like combine it needs no slot budget - a fusion rests only its base buys
	 * and one sell offer - but it has a perk the mod cannot read: the crocodile level that scales
	 * reptile-family output.
	 */
	private static final Group FUSION = new Group("Fusion flipping", List.of(
			new Entry.Flag("fusionFlipsEnabled", "Look for shard-fusion profits",
					"Buy cheap attribute shards, fuse them up at the Fusion Machine, and sell the "
							+ "output; read /flip fusion, as the return is per click and the haul heavy.",
					c -> c.fusionFlipsEnabled, (c, v) -> c.fusionFlipsEnabled = v),
			new Entry.IntRange("fusionCrocodileLevel", "Crocodile (Pure Reptile) level",
					"Your Pure Reptile perk level (0 to 10), which adds 2% reptile fusion output each; "
							+ "leave it at 0 unless you have the perk.",
					0, FlipperConfig.MAX_CROCODILE_LEVEL, 1,
					c -> c.fusionCrocodileLevel, (c, v) -> c.fusionCrocodileLevel = v)));

	private static final Group RECOVERY = new Group("Recovery values", List.of(
			new Entry.Flag("recoveryAlertsEnabled", "Enable recovery alerts",
					"Allow qualifying recovery finds to notify you; the Recovery tab remains available "
							+ "when this is off.",
					c -> c.recoveryAlertsEnabled, (c, v) -> c.recoveryAlertsEnabled = v),
			new Entry.Flag("recoveryChatNotifications", "Write recovery alerts in chat",
					"Write qualifying recovery finds into client chat without opening or buying the "
							+ "auction for you.",
					c -> c.recoveryChatNotifications, (c, v) -> c.recoveryChatNotifications = v),
			new Entry.Flag("recoveryToastNotifications", "Show recovery alert toasts",
					"Show qualifying recovery finds in a corner toast that cannot click or buy the "
							+ "auction for you.",
					c -> c.recoveryToastNotifications, (c, v) -> c.recoveryToastNotifications = v),
			new Entry.Flag("recoveryAlertSound", "Play a recovery alert sound",
					"Play one UI note for a qualifying recovery find, under the same stale and repeat "
							+ "checks as the visual notices.",
					c -> c.recoveryAlertSound, (c, v) -> c.recoveryAlertSound = v),
			new Entry.LongRange("recoveryMinProfit", "Minimum recovery profit",
					"Require this much conservative profit after buffer, fees and removal costs before "
							+ "showing or alerting a recovery find.",
					0L, 10_000_000_000L, 100_000L,
					c -> c.recoveryMinProfit, (c, v) -> c.recoveryMinProfit = v),
			new Entry.Ratio("recoveryMinMargin", "Minimum recovery margin",
					"Require this share of the purchase price as conservative profit before showing or "
							+ "alerting a recovery find.",
					0.0d, 5.0d, 0.05d,
					c -> c.recoveryMinMargin, (c, v) -> c.recoveryMinMargin = v),
			new Entry.Ratio("recoverySafetyBuffer", "Recovery safety buffer",
					"Haircut every uncertain resale value by this share before fees, while fixed "
							+ "removal costs stay undiscounted.",
					0.10d, 0.15d, 0.01d,
					c -> c.recoverySafetyBuffer, (c, v) -> c.recoverySafetyBuffer = v),
			new Entry.IntRange("recoveryMinAhSamples", "Minimum recovery sale samples",
					"Require this many realized clean-host or standalone-component sales before an "
							+ "auction exit receives value.",
					6, 100, 1,
					c -> c.recoveryMinAhSamples, (c, v) -> c.recoveryMinAhSamples = v),
			new Entry.Ratio("recoveryMinAhSalesPerDay", "Minimum recovery sales per day",
					"Require an auction exit to sell this many times per day in the realized-sales "
							+ "window before it receives value.",
					0.1d, 1_000.0d, 0.1d,
					c -> c.recoveryMinAhSalesPerDay, (c, v) -> c.recoveryMinAhSalesPerDay = v),
			new Entry.Ratio("recoveryMaxAhSellHours", "Maximum recovery resale hours",
					"Reject auction exits expected to take longer than this to resell from their "
							+ "observed realized-sale rate.",
					1.0d, 168.0d, 1.0d,
					c -> c.recoveryMaxAhSellHours, (c, v) -> c.recoveryMaxAhSellHours = v),
			new Entry.Ratio("recoveryMinBazaarSellsPerHour", "Minimum recovery Bazaar flow",
					"Require this many hourly instant sells into Bazaar bids before that visible depth "
							+ "can support a recovery exit.",
					0.1d, 1_000_000.0d, 1.0d,
					c -> c.recoveryMinBazaarSellsPerHour,
					(c, v) -> c.recoveryMinBazaarSellsPerHour = v),
			new Entry.IntRange("recoveryMaxAgeSeconds", "Maximum recovery alert age",
					"Suppress a notification when its shared auction snapshot is older than this, so "
							+ "a delayed client tick cannot replay an old listing.",
					30, 600, 30,
					c -> c.recoveryMaxAgeSeconds, (c, v) -> c.recoveryMaxAgeSeconds = v),
			new Entry.Flag("recoveryGemstoneAlerts", "Alert on gemstone recovery",
					"Allow alerts whose removable evidence includes a gemstone with verified current "
							+ "depth and removal cost.",
					c -> c.recoveryGemstoneAlerts, (c, v) -> c.recoveryGemstoneAlerts = v),
			new Entry.Flag("recoveryDrillAlerts", "Alert on drill-part recovery",
					"Allow drill-part recovery alerts only when every required mapping, sale and removal "
							+ "cost has evidence.",
					c -> c.recoveryDrillAlerts, (c, v) -> c.recoveryDrillAlerts = v),
			new Entry.Flag("recoveryRodAlerts", "Alert on fishing-part recovery",
					"Allow fishing-part recovery alerts only when every required mapping, sale and "
							+ "removal cost has evidence.",
					c -> c.recoveryRodAlerts, (c, v) -> c.recoveryRodAlerts = v),
			new Entry.Flag("recoveryLegacyAlerts", "Alert on legacy recovery",
					"Allow legacy salvage alerts only for outputs proven by an exact captured preview; "
							+ "unverified previews still receive zero value.",
					c -> c.recoveryLegacyAlerts, (c, v) -> c.recoveryLegacyAlerts = v)));

	private static final Group SCANNING = new Group("Scanning", List.of(
			new Entry.Flag("scanAuctions", "Search the auction house",
					"Search auctions for items listed under what they usually sell for; it downloads "
							+ "about 70MB a sweep, so turn it off on a metered connection.",
					c -> c.scanAuctions, (c, v) -> c.scanAuctions = v),
			new Entry.Ratio("snipeMinDiscount", "Minimum auction discount",
					"How far under the usual price an auction must be listed to be shown; higher means "
							+ "fewer but better finds and a faster search.",
					0.01d, 0.95d, 0.01d,
					c -> c.snipeMinDiscount, (c, v) -> c.snipeMinDiscount = v),
			new Entry.Ratio("exactMinDiscount", "Minimum discount on an exact match",
					"A smaller discount that only applies once an auction is matched to its exact "
							+ "item, with a confident price behind it; usually set below the minimum "
							+ "auction discount to catch closely-priced finds the wider one skips.",
					0.01d, 0.50d, 0.01d,
					c -> c.exactMinDiscount, (c, v) -> c.exactMinDiscount = v),
			new Entry.IntRange("valuationWindowDays", "Judge prices on the last (days)",
					"How many days of completed sales an item's usual price is worked out from.",
					1, 30, 1,
					c -> c.valuationWindowDays, (c, v) -> c.valuationWindowDays = v),
			new Entry.IntRange("tapeRetentionDays", "Keep auction sales for (days)",
					"How many days of recorded auction sales to keep on disk, at a few hundred "
							+ "megabytes a day.",
					1, 60, 1,
					c -> c.tapeRetentionDays, (c, v) -> c.tapeRetentionDays = v),
			new Entry.Flag("bazaarTapeEnabled", "Record bazaar prices",
					"Keep a history of bazaar prices on disk, without which the mod cannot tell a "
							+ "healthy spread from an item that is crashing.",
					c -> c.bazaarTapeEnabled, (c, v) -> c.bazaarTapeEnabled = v),
			new Entry.IntRange("bazaarTapeRetentionDays", "Keep bazaar prices for (days)",
					"How many days of bazaar price history to keep, at roughly 40MB a day.",
					1, 60, 1,
					c -> c.bazaarTapeRetentionDays, (c, v) -> c.bazaarTapeRetentionDays = v),
			new Entry.Flag("timedAuctionTapeEnabled", "Record ending-soon bid auctions",
					"Keep a history of timed (bid) auctions as they end, for the research into whether "
							+ "bidding is winnable; it needs the auction search on and is meant for a "
							+ "collector, not a player's client.",
					c -> c.timedAuctionTapeEnabled, (c, v) -> c.timedAuctionTapeEnabled = v),
			new Entry.IntRange("timedAuctionSampleWindowHours", "Record bid auctions ending within (hours)",
					"Only bid auctions ending within this many hours are recorded, which keeps the "
							+ "history small and focused on the auctions worth bidding on.",
					1, 24, 1,
					c -> c.timedAuctionSampleWindowHours, (c, v) -> c.timedAuctionSampleWindowHours = v),
			new Entry.Flag("auctionBidEnabled", "Advise bidding on timed auctions",
					"Advise bidding on timed (non-BIN) auctions ending soon that price below their "
							+ "buy-it-now value, with an exact 'bid up to X' ceiling. Off by default "
							+ "because whether that surplus is winnable is still being measured; it "
							+ "needs the auction search on.",
					c -> c.auctionBidEnabled, (c, v) -> c.auctionBidEnabled = v),
			new Entry.IntRange("bidWindowHours", "Consider bid auctions ending within (hours)",
					"Only timed auctions ending within this many hours are surfaced to bid on.",
					1, 48, 1,
					c -> c.bidWindowHours, (c, v) -> c.bidWindowHours = v),
			new Entry.IntRange("timedAuctionTapeRetentionDays", "Keep bid auctions for (days)",
					"How many days of recorded bid-auction history to keep on disk.",
					1, 60, 1,
					c -> c.timedAuctionTapeRetentionDays, (c, v) -> c.timedAuctionTapeRetentionDays = v),
			new Entry.IntRange("trendWindowHours", "Trend window (hours)",
					"How far back the rising and falling arrows look.",
					3, 72, 1,
					c -> c.trendWindowHours, (c, v) -> c.trendWindowHours = v)));

	private static final Group DISPLAY = new Group("Display", List.of(
			new Entry.Flag("hudEnabled", "Show the corner list",
					"Draw a short list of the best flips in the corner of the screen while you play.",
					c -> c.hudEnabled, (c, v) -> c.hudEnabled = v),
			new Entry.Flag("bazaarOverlayEnabled", "Show basket at the bazaar",
					"Draw your to-do list beside Hypixel's bazaar menu; click a row or number to copy "
							+ "it, and nothing is clicked or typed for you.",
					c -> c.bazaarOverlayEnabled, (c, v) -> c.bazaarOverlayEnabled = v),
			new Entry.Flag("auctionOverlayEnabled", "Show snipes at the auction house",
					"Draw the auction snipes worth buying beside Hypixel's auction menu; click a row to "
							+ "copy its name to search, and nothing is clicked or bought for you.",
					c -> c.auctionOverlayEnabled, (c, v) -> c.auctionOverlayEnabled = v),
			new Entry.Flag("bazaarHighlightEnabled", "Highlight the slot to click",
					"Put a green box behind the next slot to click while placing an order, and nothing "
							+ "where the mod cannot tell which slot that is.",
					c -> c.bazaarHighlightEnabled, (c, v) -> c.bazaarHighlightEnabled = v),
			new Entry.Choice("bazaarOverlaySide", "Which side the basket sits on",
					"Which side of Hypixel's menu the panel sits on; Automatic picks the roomier side "
							+ "but jumps as you move between screens.",
					List.of(OVERLAY_LEFT, OVERLAY_RIGHT, OVERLAY_AUTO),
					ConfigSchema::readOverlaySide, ConfigSchema::writeOverlaySide),
			new Entry.Choice("strategyFilter", "Show only",
					"Which kind of flip the list, corner list and flip screen open on; asking for one "
							+ "kind still shows it.",
					FlipperConfig.strategyFilterOptions(),
					c -> c.strategyFilter, (c, v) -> c.strategyFilter = v),
			new Entry.Choice("bazaarOverlayType", "Bazaar panel opens on",
					"Which flip type the panel beside the bazaar menu opens on; it remembers the last "
							+ "one you picked there.",
					FlipperConfig.bazaarOverlayTypeOptions(),
					c -> c.bazaarOverlayType, (c, v) -> c.bazaarOverlayType = v),
			new Entry.IntRange("hudLines", "Lines in the corner list",
					"How many flips the corner list shows; keep it short, the flip screen holds the "
							+ "whole list.",
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
					"Bind a key that opens the flip screen, which /flip gui always opens too.",
					c -> c.guiKeybindEnabled, (c, v) -> c.guiKeybindEnabled = v),
			new Entry.Choice("guiZoom", "Flip screen size",
					"How much to shrink the flip screen; Auto fits it to whatever GUI scale you play "
							+ "at.",
					zoomOptions(),
					ConfigSchema::readZoom, ConfigSchema::writeZoom)));

	private static final Group CONNECTION = new Group("Connection", List.of(
			new Entry.Flag("pollingEnabled", "Keep prices up to date",
					"Fetch market data from Hypixel, without which every number the mod shows stops "
							+ "moving.",
					c -> c.pollingEnabled, (c, v) -> c.pollingEnabled = v),
			new Entry.IntRange("bazaarPollSeconds", "Refresh bazaar prices every (seconds)",
					"How often bazaar prices are fetched, at about 434KB a time; applies after the "
							+ "next reload.",
					10, 600, 5,
					c -> c.bazaarPollSeconds, (c, v) -> c.bazaarPollSeconds = v)));

	private static final Group COLLECTOR = new Group("Collector sync", List.of(
			new Entry.Flag("tapeSyncEnabled", "Fetch history from your recorder",
					"On startup, download the price history a recorder on another machine kept while "
							+ "this game was closed, without overwriting your own.",
					c -> c.tapeSyncEnabled, (c, v) -> c.tapeSyncEnabled = v),
			new Entry.Text("tapeSyncUrl", "Recorder address",
					"Where that recorder serves its history, for example http://198.51.100.7:8080.",
					c -> c.tapeSyncUrl, (c, v) -> c.tapeSyncUrl = v),
			new Entry.Text("tapeSyncToken", "Recorder password",
					"The shared password sent with every request, which must match the one the "
							+ "recorder expects.",
					c -> c.tapeSyncToken, (c, v) -> c.tapeSyncToken = v),
			new Entry.IntRange("tapeSyncIntervalMinutes", "Fetch again every (minutes)",
					"How often to fetch again during a session; 0 means at startup only, which is "
							+ "usually right.",
					0, 1440, 15,
					c -> c.tapeSyncIntervalMinutes, (c, v) -> c.tapeSyncIntervalMinutes = v)));

	private static final Group TRACKING = new Group("Tracking", List.of(
			new Entry.Flag("tradeCaptureEnabled", "Record raw trade messages",
					"Save the raw chat and menu text your trades produce, so the mod can be fixed if "
							+ "Hypixel changes its wording; nothing else uses the file.",
					c -> c.tradeCaptureEnabled, (c, v) -> c.tradeCaptureEnabled = v),
			new Entry.Flag("autoTrackEnabled", "Record my trades for me",
					"Fill the ledger from the trades Hypixel announces; open your orders menu now and "
							+ "then, since a partial fill is announced nowhere else.",
					c -> c.autoTrackEnabled, (c, v) -> c.autoTrackEnabled = v),
			new Entry.Flag("trackUnquotedTrades", "Also record trades the mod never suggested",
					"Also record bazaar buys that match no plan, which you want only if you flip by "
							+ "hand and want that measured too.",
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
