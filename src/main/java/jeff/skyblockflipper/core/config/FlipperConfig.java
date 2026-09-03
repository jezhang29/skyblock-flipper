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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import jeff.skyblockflipper.core.pricing.Fees;
import jeff.skyblockflipper.core.strategy.CraftContext;
import jeff.skyblockflipper.core.strategy.StrategyKind;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
	/** Coins available to deploy. Candidates needing more capital than this are hidden. */
	public long bankroll = 10_000_000L;

	/**
	 * Bazaar Flipper perk level (0-2). Each level cuts the 1.25% bazaar sales tax by
	 * 0.125%, to a floor of 1%, and raises the bazaar order limit by 7 from a base of 14.
	 * Wrong value here silently biases every bazaar margin, so it is worth setting accurately.
	 *
	 * <p>The perk has two levels in the Community Shop. This used to accept 0-6, which the tax
	 * math survived only because the 1% floor caught levels 3 and up; the order limit derived in
	 * {@link Fees#bazaarOrderSlots()} has no such floor and would have reported 56 slots where
	 * the game allows 28.
	 */
	public int bazaarFlipperLevel = 0;

	/**
	 * The largest share of {@link #bankroll} any single plan may ask for (0-1).
	 *
	 * <p>Profit per hour rises with position size, so the ranking on its own will always prefer the
	 * biggest plan the coins allow. Measured on 2026-08-04 with a 250M bankroll and no cap: a
	 * Recombobulator 3000 plan asked for 249,212,105 coins - 99.7% of everything, 25 units, on a
	 * book that filled none of them. A quarter is four concurrent positions, which is enough for one
	 * of them to be wrong without the session being over.
	 */
	public double maxCapitalShare = 0.25d;

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

	/** Master switch for unsolicited recovery alerts. Analysis and the Recovery tab remain read-only. */
	public boolean recoveryAlertsEnabled = false;

	/** Write qualifying recovery alerts to client chat. Off by default. */
	public boolean recoveryChatNotifications = false;

	/** Show qualifying recovery alerts as in-game system toasts. Off by default. */
	public boolean recoveryToastNotifications = false;

	/** Play one UI note with a qualifying recovery alert. Off by default. */
	public boolean recoveryAlertSound = false;

	/** Minimum conservative recovery profit for a row or alert. */
	public long recoveryMinProfit = 500_000L;

	/** Minimum conservative recovery margin after the safety buffer. */
	public double recoveryMinMargin = 0.15d;

	/** Haircut applied to uncertain recovery resale values before fees. */
	public double recoverySafetyBuffer = 0.15d;

	/** Minimum realized standalone/clean-host AH samples. */
	public int recoveryMinAhSamples = 6;

	/** Minimum realized AH sale rate per day. */
	public double recoveryMinAhSalesPerDay = 1.0d;

	/** Maximum estimated hours to resell an AH leg. */
	public double recoveryMaxAhSellHours = 48.0d;

	/** Minimum weekly-flow-derived hourly dumps into Bazaar bids. */
	public double recoveryMinBazaarSellsPerHour = 1.0d;

	/** Maximum age of a shared auction snapshot before an alert is suppressed. */
	public int recoveryMaxAgeSeconds = 120;

	/** Recovery alert family gates. Analysis remains visible even when an alert family is off. */
	public boolean recoveryGemstoneAlerts = true;
	public boolean recoveryDrillAlerts = true;
	public boolean recoveryRodAlerts = true;
	public boolean recoveryLegacyAlerts = false;

	/**
	 * How far under fair value a listing has to be listed before it is worth looking at (0-1).
	 * Also the prune that keeps a sweep affordable: almost every listing fails it before its
	 * item data is ever parsed.
	 */
	public double snipeMinDiscount = 0.15d;

	/**
	 * The exact gate's own, smaller discount (0-1), applied only after a listing is matched to its
	 * full decoded signature and only when that estimate is well-backed. {@link #snipeMinDiscount}
	 * has to be wide because name-and-rarity mixes every configuration together; an exact-signature
	 * match with high confidence and enough samples is priced against the same configuration, so it
	 * does not need that width. Set this equal to {@code snipeMinDiscount} to hold the exact gate at
	 * the coarse margin.
	 *
	 * <p>Default 0.12, not lower, and the reason is measured. The model's own median carries about
	 * 10% error (median absolute log error 0.106, {@code ValuationWindowBacktestTest}), so a discount
	 * has to clear that error to be a real bargain rather than pricing noise. On a 36,106-sale
	 * holdout ({@code ExactMarginBacktestTest}) a 5% exact margin flagged 48% more listings but they
	 * lost to fees 57% of the time; a 12% margin matched the 15% baseline's ~26% false-positive rate.
	 */
	public double exactMinDiscount = 0.12d;

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
	 * Pull the collector's tape from the server on startup and merge it into the local one.
	 *
	 * <p>The collector records the hours this client is closed for, and {@code auctions_ended}
	 * will not answer for them twice. Off by default because it needs a server to point at.
	 */
	public boolean tapeSyncEnabled = false;

	/**
	 * Where the collector serves its tape, e.g. {@code http://198.51.100.7:8080}. The two tape
	 * directories are expected under it by the names this client uses for its own.
	 */
	public String tapeSyncUrl = "";

	/**
	 * Shared secret sent as a header with every sync request.
	 *
	 * <p>The tape is public Hypixel data and not worth hiding, but an open directory of gigabyte
	 * files is worth not advertising to whatever finds the port. Blank sends no header.
	 */
	public String tapeSyncToken = "";

	/**
	 * How often to sync again while the game runs. Zero means only at startup.
	 *
	 * <p>Zero is the default because a running client tapes the same endpoints the server does, so
	 * a mid-session sync usually downloads an hour of data to discover it already holds all of it.
	 * Raise it if this client's polls are being lost to rate limits or a flaky connection, which is
	 * the one case where the server saw something the client did not while both were up.
	 */
	public int tapeSyncIntervalMinutes = 0;

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

	/**
	 * Gross coins an NPC will pay you across all items before it stops buying, per day.
	 *
	 * <p>This counts what the NPC hands over, not profit. An item bought at 4.1 and sold to the NPC
	 * at 8.0 spends 8.0 of this budget per unit and returns 3.9, so the cap is worth
	 * {@code 500M * 3.9/8} = 243.75M of profit on that item and far less on an expensive one.
	 *
	 * <p>It binds across a day, not inside one cycle: a basket sized to the order slots an account
	 * has turns over roughly 86M per eight-hour cycle, so the cap allows about two cycles. What
	 * actually limits a single plan is {@link #npcMaxOrderSlots} and the bankroll.
	 *
	 * <p>500M per day, from the Skyblock wiki. Nothing in the API carries it, so it is a setting
	 * rather than a constant, and it will need editing if Hypixel changes the number.
	 */
	public long npcDailyCapCoins = 500_000_000L;

	/**
	 * Smallest gap between the bazaar buy order and the NPC price worth an order slot, as a fraction
	 * of the NPC price.
	 *
	 * <p>Both a filter and a chase stop: a product below this is never planned, and a resting order
	 * is never repriced above {@code npcPrice * (1 - this)}. One threshold with one meaning, because
	 * two would eventually disagree about whether a trade that has drifted is still on.
	 *
	 * <p>0.15 is the peak of a sweep measured over three days of tape on 2026-08-09
	 * ({@code docs/npc-flipping.md}). Lower admits products whose margin the chase eats; higher
	 * empties the basket faster than it raises the profit per slot.
	 */
	public double npcMinMarginRatio = 0.15d;

	/**
	 * How often you come back to reprice resting NPC buy orders, in minutes.
	 *
	 * <p>The horizon a plan's fill is measured over, and the interval the chase cost is charged
	 * over: coming back twice as often fills more but pays to outbid more often. 30 minutes is what
	 * the measured plan assumed.
	 *
	 * <p><b>It is also the length of a reprice round</b>
	 * ({@link jeff.skyblockflipper.core.strategy.NpcRound}), which is what stops the advice chasing
	 * a contested book move by move: a round freezes its prices for this long and no new one opens
	 * until it has run out. A round freezes the interval along with the prices, so editing this
	 * changes the next round rather than moving the end of the one in hand.
	 */
	public int npcCheckInMinutes = 30;

	/**
	 * How long NPC buy orders are left resting before the coins would rather be somewhere else, in
	 * hours. One cycle.
	 *
	 * <p>Unlike a bazaar spread flip there is no price risk in waiting - the exit price cannot move,
	 * so an order either fills at your price or is cancelled. This is a statement about capital, not
	 * about risk: it is how long the basket is allowed to tie coins up before its profit is judged.
	 *
	 * <p>Replaced {@code npcSessionHours}, which meant how long the player would keep walking to an
	 * NPC. There is no walking - {@code /trades} with a booster cookie reaches a shop from anywhere,
	 * confirmed in play on 2026-08-09 - so what is being sized is the order, not the trip.
	 */
	public double npcRestingHours = 8.0d;

	/**
	 * How many bazaar order slots the NPC basket may occupy, or 0 for all of them.
	 *
	 * <p>Order slots are the binding resource on this trade, not coins and not the daily cap, so
	 * this is the setting that decides how big the basket gets. Zero means
	 * {@code Fees.bazaarOrderSlots()}, which is what {@link #bazaarFlipperLevel} allows; a smaller
	 * number leaves room for spread flipping or for a coop member. A larger one is not obeyed - the
	 * account's real limit still wins.
	 */
	public int npcMaxOrderSlots = 0;

	/**
	 * Whether craft flips are offered at all.
	 *
	 * <p>On, because the strategy refuses rather than guesses everywhere its pricing is unsure, and
	 * a strategy nobody sees is a strategy nobody checks. Off is for the player who wants the ranked
	 * list to be about the NPC basket and nothing else.
	 */
	public boolean craftFlipsEnabled = true;

	/**
	 * How many bazaar order slots one craft plan may occupy.
	 *
	 * <p>Slots are shared with the NPC basket, which is the daily driver, and measured on the live
	 * book of 2026-08-18 the best eight craft plans together wanted 19 of the 21 slots a Bazaar
	 * Flipper 1 account has. A plan over this budget is re-quoted with its materials instant-bought,
	 * which rests nothing but the sell offer, rather than dropped.
	 */
	public int craftMaxOrderSlots = CraftContext.DEFAULT_MAX_ORDER_SLOTS;

	/**
	 * Whether enchanted-book combine flips are offered at all.
	 *
	 * <p>On, for the same reason craft is: the strategy refuses rather than guesses. It ranks low on
	 * profit per hour on purpose - its return is per anvil click, not per hour - so it never crowds
	 * the list; {@code /flip combine} is where it is meant to be read. Off is for the player who does
	 * not want the anvil work.
	 */
	public boolean combineFlipsEnabled = true;

	/**
	 * Whether attribute-shard fusion flips are offered at all.
	 *
	 * <p>On, for the same reason combine is: the strategy refuses rather than guesses. A fusion's
	 * per-click return dwarfs a combine's, but the input-to-output haul is heavy, so {@code /flip
	 * fusion} is where it is meant to be read. Off is for the player who does not want the fusing.
	 */
	public boolean fusionFlipsEnabled = true;

	/**
	 * The player's Pure Reptile (crocodile) perk level, 0 to 10.
	 *
	 * <p>Each level adds 2% to reptile-family fusion output, so a level-10 crocodile turns a two-shard
	 * reptile fusion into 2.4 outputs a click. The mod cannot read the perk from the game, so it comes
	 * in here. It defaults to 0 - no bonus - because a profit-flattering multiplier ships as an
	 * off-by-default setting, never a baked-in default: set too high, every reptile fusion is quietly
	 * over-valued. See {@code docs/fusion-flipping.md}.
	 */
	public int fusionCrocodileLevel = 0;

	/**
	 * What the basket ranks candidates on when it has to choose between them.
	 *
	 * <p>A greedy allocator should rank on profit per unit of whatever it runs out of, and this trade
	 * runs out of two different things depending on whose time is being spent.
	 *
	 * <p><b>{@code LOAD} ranks on profit per inventory load</b>, which is the shipped behaviour and
	 * what minimises hauling: an item's units have to be carried to the NPC 35 inventory slots at a
	 * time, and that carrying is most of the clicking this trade costs.
	 *
	 * <p><b>{@code ORDER_SLOT} ranks on profit per bazaar order slot</b>, which is the resource that
	 * actually runs out - every sweep in {@code docs/npc-flipping.md} came back {@code SLOTS}, with
	 * hauling at 34 of 864 loads. Measured on the live book 2026-08-14 at the user's settings it is
	 * worth 65.5M a cycle against 47.3M, and it costs 363 inventory loads against 114.
	 *
	 * <p>So this is not a right answer and a wrong one; it is which of your two budgets is scarcer,
	 * coins or clicks. {@code LOAD} stays the default because the clicking is what a player runs out
	 * of first.
	 */
	public String npcRankingKey = NpcRanking.LOAD.name();

	/**
	 * Whether the reprice reminder also plays a note.
	 *
	 * <p>Separate from {@link #npcRepriceReminder} because they fail differently: a chat line that
	 * scrolls past unread is the reminder not working, and a sound in a game somebody is listening
	 * to something else over is the reminder being rude. Under the reminder's own rate limit either
	 * way, so it is one note per reprice round at most.
	 */
	public boolean npcRepriceSound = true;

	/**
	 * Whether to say in chat when resting NPC buy orders have been outbid.
	 *
	 * <p>The measured gap between working a basket and forgetting one is 59.7M against 11.5M per
	 * eight-hour cycle, and nothing else in the mod is worth interrupting a player over.
	 *
	 * <p><b>Once per reprice round</b>, which is what it means for the notice and the list it opens
	 * to be the same batch of work - see {@link jeff.skyblockflipper.core.strategy.NpcRound}. It
	 * spoke on the book having moved before that, which on a contested product is true again seconds
	 * later, so it asked for a click that was already stale. Asking for the list yourself, or having
	 * the basket panel on screen at the bazaar, spends that round's notice.
	 *
	 * <p>Needs {@link #autoTrackEnabled}, which is the only thing that knows what you have resting.
	 */
	public boolean npcRepriceReminder = true;

	/**
	 * Which strategy the unqualified views show: {@code ALL}, or one {@code StrategyKind} name.
	 *
	 * <p>{@code /flip}, the HUD and the flip screen's opening tab all answered "every strategy at
	 * once" before this existed, which is the wrong answer for a player working one market. The
	 * per-strategy commands and tabs ignore it: asking for {@code /flip npc} is a clearer statement
	 * of intent than any setting.
	 */
	public String strategyFilter = FILTER_ALL;

	/** The value of {@link #strategyFilter} that means no filtering at all. */
	public static final String FILTER_ALL = "ALL";

	/** The highest crocodile (Pure Reptile) perk level the game grants, which caps {@link #fusionCrocodileLevel}. */
	public static final int MAX_CROCODILE_LEVEL = 10;

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

	/**
	 * Draw the NPC basket beside Hypixel's bazaar menu.
	 *
	 * <p>On by default, unlike the other opt-in features, because it writes nothing and sends
	 * nothing: it draws the same numbers {@code /flip npc plan} prints, on the screen where they get
	 * typed in. Chat is where the plan goes to be forgotten - it has scrolled away by the time the
	 * price box is open, and you cannot read it and a menu at once.
	 */
	public boolean bazaarOverlayEnabled = true;

	/**
	 * Which side of Hypixel's menu the bazaar panel sits on: LEFT, RIGHT or AUTO.
	 *
	 * <p>Fixed by default. AUTO takes whichever side has more room, which is the widest panel and
	 * the wrong answer in play: Hypixel's menus are not all the same width, so the panel moves from
	 * one edge to the other as you cross the bazaar's own screens and has to be found again each
	 * time.
	 */
	public String bazaarOverlaySide = OverlaySide.LEFT.name();

	/**
	 * Which flip type the bazaar panel opens on, as a {@link StrategyKind} name.
	 *
	 * <p>The panel shows one bazaar flip type at a time and this remembers the last one picked. NPC by
	 * default, which is what the panel always showed. Independent of {@link #strategyFilter}, which
	 * also carries {@code ALL} and the auction snipe - neither of which is a type the in-bazaar panel
	 * can draw a trip for, so this is its own setting rather than a reuse of that one.
	 */
	public String bazaarOverlayType = StrategyKind.NPC_FLIP.name();

	/**
	 * Put a green box behind the slot the top row of the basket is asking you to click.
	 *
	 * <p>Same rule as the panel it belongs to: nothing is clicked, nothing is typed, nothing is sent.
	 * The box is drawn behind Hypixel's own item so the item is still readable, and it appears only
	 * where the slot was worked out from the menu in front of you - see {@code BazaarSlots}, which
	 * shows nothing rather than guess.
	 */
	public boolean bazaarHighlightEnabled = true;

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

	/**
	 * Fill the ledger from the trades Hypixel reports, instead of typing them in.
	 *
	 * <p>Off by default because it writes to your ledger: a wrong reading is worse than an empty
	 * ledger, since the capture rate is the one number that is supposed to contradict the mod. Reads
	 * the same chat lines and menus {@link #tradeCaptureEnabled} records, and needs neither that
	 * flag nor the file.
	 */
	public boolean autoTrackEnabled = false;

	/**
	 * Whether tracking also records buys the mod never suggested.
	 *
	 * <p>Off by default, because most bazaar buying is not flipping. Playing normally means buying
	 * materials and selling drops, and with this on every one of those opens a position that no
	 * later sale ever closes: measured on a real ledger on 2026-08-09, 55 of 60 entries were
	 * unquoted positions still sitting open. Those units are in the fill rate, so the one number
	 * that is supposed to judge the strategies ends up judging your shopping.
	 *
	 * <p>Turn it on if you flip by hand without taking the mod's plans and want that measured
	 * anyway. Sales still only ever settle against a position that exists, either way.
	 */
	public boolean trackUnquotedTrades = false;

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

	/** Resolved once per frame by the bazaar panel, for the same reason {@link #anchor()} is. */
	public OverlaySide overlaySide() {
		return OverlaySide.parse(bazaarOverlaySide);
	}

	/**
	 * {@link #bazaarOverlayType} as a strategy, defaulting to the NPC basket.
	 *
	 * <p>Only ever a bazaar kind: a hand-edited name that is not one - or the auction snipe, which is
	 * not at the bazaar - falls back to {@code NPC_FLIP} rather than leaving the panel with a type it
	 * cannot draw.
	 */
	public StrategyKind bazaarOverlayType() {
		String name = bazaarOverlayType == null ? "" : bazaarOverlayType.trim();

		for (StrategyKind kind : StrategyKind.bazaarKinds()) {
			if (kind.name().equalsIgnoreCase(name)) {
				return kind;
			}
		}

		return StrategyKind.NPC_FLIP;
	}

	/** Resolved at plan time, so a hand-edited name costs a default rather than a null. */
	public NpcRanking npcRanking() {
		return NpcRanking.parse(npcRankingKey);
	}

	/** Resolved once per frame by the HUD, so parsing stays out of the render path's way. */
	public HudAnchor anchor() {
		return HudAnchor.parse(hudAnchor);
	}

	/**
	 * {@link #strategyFilter} as a strategy, or null for every strategy.
	 *
	 * <p>Null rather than an Optional because that is what the ranking call already takes to mean
	 * "no restriction", and wrapping it here would only be unwrapped there.
	 */
	public StrategyKind filteredKind() {
		for (StrategyKind kind : StrategyKind.values()) {
			if (kind.name().equalsIgnoreCase(strategyFilter)) {
				return kind;
			}
		}

		return null;
	}

	/** What the filter may be set to: no restriction, or any strategy the engine actually runs. */
	public static List<String> strategyFilterOptions() {
		List<String> options = new ArrayList<>();
		options.add(FILTER_ALL);

		for (StrategyKind kind : StrategyKind.values()) {
			options.add(kind.name());
		}

		return List.copyOf(options);
	}

	/** What the bazaar panel may open on: every bazaar flip type, by {@link StrategyKind} name. */
	public static List<String> bazaarOverlayTypeOptions() {
		return StrategyKind.bazaarKinds().stream().map(StrategyKind::name).toList();
	}

	/** What the background sweep should do, read fresh so a reload takes effect on the next one. */
	public ScanSettings scanSettings() {
		return new ScanSettings(scanAuctions, valuationWindowDays, snipeMinDiscount, exactMinDiscount,
				bankroll, bazaarTapeEnabled, bazaarTapeRetentionDays, trendWindowHours,
				bazaarPollSeconds, bazaarFlipperLevel, recoverySettings());
	}

	public RecoverySettings recoverySettings() {
		return new RecoverySettings(recoveryAlertsEnabled, recoveryChatNotifications,
				recoveryToastNotifications, recoveryAlertSound, recoveryMinProfit,
				recoveryMinMargin, recoverySafetyBuffer, recoveryMinAhSamples,
				recoveryMinAhSalesPerDay, recoveryMaxAhSellHours,
				recoveryMinBazaarSellsPerHour, recoveryMaxAgeSeconds,
				recoveryGemstoneAlerts, recoveryDrillAlerts, recoveryRodAlerts,
				recoveryLegacyAlerts);
	}

	/** Clamps hand-edited values into ranges the rest of the mod can rely on. */
	public FlipperConfig validated() {
		bankroll = Math.max(0L, bankroll);
		bazaarFlipperLevel = Math.clamp(bazaarFlipperLevel, 0, Fees.MAX_BAZAAR_FLIPPER_LEVEL);
		// The crocodile perk caps at 10; a value above it would over-value every reptile fusion.
		fusionCrocodileLevel = Math.clamp(fusionCrocodileLevel, 0, MAX_CROCODILE_LEVEL);
		minProfitPerFlip = Math.max(0L, minProfitPerFlip);
		// A zero share would size every plan at one unit and rank nothing; above one it is not a
		// share of anything.
		maxCapitalShare = Math.clamp(maxCapitalShare, 0.01d, 1.0d);
		minConfidence = Math.clamp(minConfidence, 0.0d, 1.0d);
		recoveryMinProfit = Math.max(0L, recoveryMinProfit);
		recoveryMinMargin = Math.clamp(recoveryMinMargin, 0.0d, 5.0d);
		recoverySafetyBuffer = Math.clamp(recoverySafetyBuffer, 0.10d, 0.15d);
		recoveryMinAhSamples = Math.clamp(recoveryMinAhSamples, 6, 100);
		recoveryMinAhSalesPerDay = Math.clamp(recoveryMinAhSalesPerDay, 0.1d, 1_000.0d);
		recoveryMaxAhSellHours = Math.clamp(recoveryMaxAhSellHours, 1.0d, 168.0d);
		recoveryMinBazaarSellsPerHour = Math.clamp(
				recoveryMinBazaarSellsPerHour, 0.1d, 1_000_000.0d);
		recoveryMaxAgeSeconds = Math.clamp(recoveryMaxAgeSeconds, 30, 600);
		// Zero would make every NPC plan empty rather than uncapped, which is not what someone
		// clearing the field means; the upper bound is loose because the real value is unverified.
		npcDailyCapCoins = Math.clamp(npcDailyCapCoins, 1_000_000L, 100_000_000_000L);
		// Below 2% the chase cost alone can exceed the margin on a book that moves at all; above
		// 50% almost nothing on the tape qualifies and the basket sits empty.
		npcMinMarginRatio = Math.clamp(npcMinMarginRatio, 0.02d, 0.50d);
		// Under five minutes is faster than the bazaar tape samples, so the fill it would be
		// measured against is not observable.
		npcCheckInMinutes = Math.clamp(npcCheckInMinutes, 5, 480);
		npcRestingHours = Math.clamp(npcRestingHours, 0.5d, 24.0d);
		npcRankingKey = npcRanking().name();
		// Zero means "all of them", so it stays; the ceiling is the most any Bazaar Flipper level
		// could give. What the account actually has still wins at plan time.
		npcMaxOrderSlots = Math.clamp(npcMaxOrderSlots, 0, Fees.MAX_BAZAAR_ORDER_SLOTS);
		// A craft plan always rests the one sell offer it exits on, so zero would mean "no craft
		// flips" while reading as a tightened budget. Turning them off is what the flag is for.
		craftMaxOrderSlots = Math.clamp(craftMaxOrderSlots, 1, Fees.MAX_BAZAAR_ORDER_SLOTS);
		hudLines = Math.clamp(hudLines, 1, 10);
		// A zero or negative discount would call every listing at fair value a bargain and hand
		// the sweep tens of thousands of blobs to decode.
		snipeMinDiscount = Math.clamp(snipeMinDiscount, 0.01d, 0.95d);
		// The exact gate can safely sit below the coarse margin, but never at zero (every listing
		// looks cheap) and never above 0.50 (nothing on the tape qualifies).
		exactMinDiscount = Math.clamp(exactMinDiscount, 0.01d, 0.50d);
		valuationWindowDays = Math.clamp(valuationWindowDays, 1, 30);
		tapeRetentionDays = Math.clamp(tapeRetentionDays, 1, 60);
		bazaarTapeRetentionDays = Math.clamp(bazaarTapeRetentionDays, 1, 60);
		// A key present in the file but null parses to null rather than to the default, and every
		// reader of these treats them as strings.
		tapeSyncUrl = tapeSyncUrl == null ? "" : tapeSyncUrl.trim();
		tapeSyncToken = tapeSyncToken == null ? "" : tapeSyncToken.trim();
		// Zero means startup only. A day is the loosest upper bound that is still a schedule.
		tapeSyncIntervalMinutes = Math.clamp(tapeSyncIntervalMinutes, 0, 1440);
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
		bazaarOverlaySide = overlaySide().name();
		// An unknown or non-bazaar name would leave the panel with a type it cannot draw a trip for;
		// bazaarOverlayType() folds it back to the NPC basket.
		bazaarOverlayType = bazaarOverlayType().name();
		// An unknown name would silently mean "every strategy", which looks like the filter
		// being ignored rather than being misspelled.
		StrategyKind kind = filteredKind();
		strategyFilter = kind == null ? FILTER_ALL : kind.name();
		return this;
	}
}
