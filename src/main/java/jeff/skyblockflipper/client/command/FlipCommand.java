package jeff.skyblockflipper.client.command;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import jeff.skyblockflipper.SkyblockFlipper;
import jeff.skyblockflipper.client.CandidateFeed;
import jeff.skyblockflipper.client.LedgerService;
import jeff.skyblockflipper.client.MarketDataService;
import jeff.skyblockflipper.client.TapeSyncService;
import jeff.skyblockflipper.client.SkyblockFlipperClient;
import jeff.skyblockflipper.client.gui.FlipKeybinds;
import jeff.skyblockflipper.client.gui.Settings;
import jeff.skyblockflipper.client.track.CaptureService;
import jeff.skyblockflipper.client.track.TrackerService;
import jeff.skyblockflipper.core.api.MarketData;
import jeff.skyblockflipper.core.config.FlipperConfig;
import jeff.skyblockflipper.core.ledger.LedgerEntry;
import jeff.skyblockflipper.core.model.BazaarSnapshot;
import jeff.skyblockflipper.core.model.MayorInfo;
import jeff.skyblockflipper.core.pricing.Fees;
import jeff.skyblockflipper.core.strategy.FlipCandidate;
import jeff.skyblockflipper.core.strategy.NpcBasket;
import jeff.skyblockflipper.core.strategy.NpcReprice;
import jeff.skyblockflipper.core.strategy.StrategyKind;
import jeff.skyblockflipper.core.text.Coins;
import jeff.skyblockflipper.core.text.Guide;
import jeff.skyblockflipper.core.track.CaptureLog;
import jeff.skyblockflipper.core.track.TradeEvent;
import jeff.skyblockflipper.core.track.TrackedOrder;
import jeff.skyblockflipper.core.track.TradeTracker;
import jeff.skyblockflipper.core.valuation.TrendSnapshot;

import java.io.IOException;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * The {@code /flip} command tree. Client-side only: the command never reaches the server, so it
 * works on Hypixel without the server knowing it exists.
 */
public final class FlipCommand {
	private static final int DEFAULT_LIMIT = 10;

	private FlipCommand() {
	}

	public static void register() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
				dispatcher.register(build()));
	}

	private static LiteralArgumentBuilder<FabricClientCommandSource> build() {
		return ClientCommands.literal("flip")
				.executes(ctx -> {
					// Honours the configured filter, unlike the named subcommands: typing /flip
					// bazaar is a statement of intent, typing /flip is not.
					StrategyKind only = SkyblockFlipperClient.config().filteredKind();
					showTop(ctx.getSource(), only, only == null
							? "Top flips right now"
							: "Top " + only.label().toLowerCase(Locale.ROOT) + " flips right now");
					return 1;
				})
				.then(ClientCommands.literal("bazaar")
						.executes(ctx -> {
							showTop(ctx.getSource(), StrategyKind.BAZAAR_SPREAD, "Best bazaar spreads");
							return 1;
						}))
				.then(ClientCommands.literal("npc")
						.executes(ctx -> {
							showTop(ctx.getSource(), StrategyKind.NPC_FLIP, "Bazaar prices below NPC buy price");
							return 1;
						})
						// The ranked list answers "which one item is worth the most"; the basket
						// answers "what do I do with all my order slots", which is the question
						// this strategy is actually about.
						.then(ClientCommands.literal("plan")
								.executes(ctx -> {
									showBasket(ctx.getSource());
									return 1;
								}))
						.then(ClientCommands.literal("reprice")
								.executes(ctx -> {
									showReprice(ctx.getSource());
									return 1;
								})))
				.then(ClientCommands.literal("snipe")
						.executes(ctx -> {
							showSnipes(ctx.getSource());
							return 1;
						}))
				.then(ClientCommands.literal("guide")
						.executes(ctx -> {
							showGuide(ctx.getSource(), null);
							return 1;
						})
						.then(ClientCommands.argument("topic", StringArgumentType.word())
								.executes(ctx -> showGuide(ctx.getSource(),
										StringArgumentType.getString(ctx, "topic")))))
				.then(ClientCommands.literal("status")
						.executes(ctx -> {
							showStatus(ctx.getSource());
							return 1;
						}))
				.then(ClientCommands.literal("config")
						.executes(ctx -> {
							showConfig(ctx.getSource());
							return 1;
						})
						.then(ClientCommands.literal("edit")
								.executes(ctx -> editConfig(ctx.getSource()))))
				.then(ClientCommands.literal("take")
						.then(ClientCommands.argument("rank", IntegerArgumentType.integer(1))
								.executes(ctx -> take(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "rank")))))
				.then(ClientCommands.literal("close")
						.then(ClientCommands.argument("id", StringArgumentType.word())
								.then(ClientCommands.argument("units", LongArgumentType.longArg(0))
										.then(ClientCommands.argument("price", DoubleArgumentType.doubleArg(0))
												.executes(ctx -> close(
														ctx.getSource(),
														StringArgumentType.getString(ctx, "id"),
														LongArgumentType.getLong(ctx, "units"),
														DoubleArgumentType.getDouble(ctx, "price")))))))
				.then(ClientCommands.literal("abandon")
						.then(ClientCommands.argument("id", StringArgumentType.word())
								.executes(ctx -> abandon(ctx.getSource(), StringArgumentType.getString(ctx, "id")))))
				.then(ClientCommands.literal("ledger")
						.executes(ctx -> {
							showLedger(ctx.getSource());
							return 1;
						})
						.then(ClientCommands.literal("forget")
								.then(ClientCommands.argument("id", StringArgumentType.word())
										.executes(ctx -> forget(ctx.getSource(),
												StringArgumentType.getString(ctx, "id")))))
						// Deleting history cannot be undone, so the first call only counts what it
						// would delete and the word "confirm" is what actually does it.
						.then(ClientCommands.literal("clear")
								.executes(ctx -> offerClear(ctx.getSource(), false))
								.then(ClientCommands.literal("confirm")
										.executes(ctx -> clear(ctx.getSource(), false)))
								.then(ClientCommands.literal("unquoted")
										.executes(ctx -> offerClear(ctx.getSource(), true))
										.then(ClientCommands.literal("confirm")
												.executes(ctx -> clear(ctx.getSource(), true))))))
				.then(ClientCommands.literal("hud")
						.executes(ctx -> {
							toggleHud(ctx.getSource());
							return 1;
						}))
				.then(ClientCommands.literal("capture")
						.executes(ctx -> {
							toggleCapture(ctx.getSource());
							return 1;
						}))
				.then(ClientCommands.literal("track")
						.executes(ctx -> {
							toggleAutoTrack(ctx.getSource());
							return 1;
						}))
				.then(ClientCommands.literal("sync")
						.executes(ctx -> {
							syncTape(ctx.getSource());
							return 1;
						}))
				.then(ClientCommands.literal("gui")
						.executes(ctx -> {
							// Deferred rather than opened inline: the chat screen is still closing
							// when a command executes, and replacing it mid-teardown leaves the
							// new screen without a size.
							Minecraft.getInstance().execute(FlipKeybinds::open);
							return 1;
						}))
				.then(ClientCommands.literal("reload")
						.executes(ctx -> {
							if (SkyblockFlipperClient.reloadConfig()) {
								// Picks up a flipped pollingEnabled without a game restart.
								MarketDataService.restart();
								// A new bankroll changes the ranking without the book moving.
								CandidateFeed.invalidate();
								ctx.getSource().sendFeedback(Chat.prefixed(
										Component.literal("Config reloaded.").withStyle(ChatFormatting.GREEN)));
								return 1;
							}

							ctx.getSource().sendError(Component.literal("Config reload failed - see the log.")
									.withStyle(ChatFormatting.RED));
							return 0;
						}));
	}

	/** @param kind null for the merged ranking across every strategy */
	private static void showTop(FabricClientCommandSource source, StrategyKind kind, String heading) {
		if (!marketReady(source)) {
			return;
		}

		MarketData data = MarketDataService.data();

		// Ranked on demand rather than read from the HUD's cache: someone who just typed the
		// command is asking about the book as it is now.
		List<FlipCandidate> candidates = CandidateFeed.rank(kind, DEFAULT_LIMIT);

		CandidateRenderer.renderList(source, candidates, heading);
		LedgerRenderer.renderCaptureWarning(source, LedgerService.ledger().stats(kind), kind);

		if (data.mayor().isDerpy()) {
			source.sendFeedback(Component.literal("Derpy is mayor: auction fees are 4x. Bazaar is unaffected.")
					.withStyle(ChatFormatting.RED));
		}
	}

	/**
	 * The NPC basket: every order slot and the bankroll allocated once, rather than a ranked list
	 * where each row is sized against the whole bankroll on its own.
	 */
	private static void showBasket(FabricClientCommandSource source) {
		if (!marketReady(source)) {
			return;
		}

		NpcRenderer.renderBasket(source, NpcBasket.plan(CandidateFeed.context()));
	}

	/**
	 * Which resting buy orders have been outbid, and which have been outbid past the point of being
	 * worth a slot.
	 *
	 * <p>Reads the orders as the orders menu last described them, which is the only place their real
	 * posted price exists - a plan's quoted cost includes chasing it had not paid yet. That makes
	 * this depend on automatic tracking being on and on the menu having been opened at least once,
	 * and both are worth saying out loud rather than reporting an empty list.
	 */
	private static void showReprice(FabricClientCommandSource source) {
		if (!marketReady(source)) {
			return;
		}

		if (!TrackerService.enabled()) {
			source.sendFeedback(Chat.prefixed(Component.literal(
					"Nothing has read your orders menu: automatic tracking is off. Run /flip track, "
							+ "then open Bazaar -> Manage Orders once.").withStyle(ChatFormatting.YELLOW)));
			return;
		}

		TradeTracker tracker = TrackerService.tracker();
		List<NpcReprice.Order> orders = new ArrayList<>();

		for (TrackedOrder order : tracker.resting()) {
			// Sell offers are the other leg of a spread flip and have nothing to do with an NPC.
			// A price of zero means the order was seen announced in chat but never in a menu, and
			// chat never names the price.
			if (order.side() != TradeEvent.Side.BUY || order.unitPrice() <= 0.0d
					|| order.remaining() <= 0L) {
				continue;
			}

			// Enchantment-book orders carry no item data at all, so the name index is the only route
			// from what the menu said to an id the book can be looked up by.
			String itemId = order.itemId().isEmpty()
					? tracker.names().idFor(order.displayName())
					: order.itemId();

			if (!itemId.isEmpty()) {
				orders.add(new NpcReprice.Order(itemId, order.displayName(), order.unitPrice(),
						order.remaining()));
			}
		}

		if (orders.isEmpty()) {
			source.sendFeedback(Chat.prefixed(Component.literal(
					"No resting buy orders are known yet. Open Bazaar -> Manage Orders and run this "
							+ "again.").withStyle(ChatFormatting.YELLOW)));
			return;
		}

		NpcRenderer.renderReprice(source, NpcReprice.review(orders, CandidateFeed.context()));
	}

	/** Whether there is a book to answer with, with the reason there is not if there is not. */
	private static boolean marketReady(FabricClientCommandSource source) {
		if (MarketDataService.data().hasBazaar()) {
			return true;
		}

		source.sendFeedback(Chat.prefixed(Component.literal(
				MarketDataService.isRunning()
						? "Waiting for the first market fetch, try again in a few seconds."
						: "Polling is disabled - run /flip config and set pollingEnabled.")
				.withStyle(ChatFormatting.YELLOW)));
		return false;
	}

	/**
	 * Auction flips get their own explanation of why the list is empty, because there are several
	 * quite different reasons and "nothing found" reads like a broken feature for all of them.
	 */
	private static void showSnipes(FabricClientCommandSource source) {
		FlipperConfig config = SkyblockFlipperClient.config();
		MarketData data = MarketDataService.data();

		if (!config.scanAuctions) {
			source.sendFeedback(Chat.prefixed(Component.literal(
					"Auction scanning is off - set scanAuctions in the config to enable it.")
					.withStyle(ChatFormatting.YELLOW)));
			return;
		}

		if (data.values().isEmpty()) {
			source.sendFeedback(Chat.prefixed(Component.literal(
					"No valuations yet. Item values are learned from realized sales, so this needs "
							+ "the game to have been running a while.")
					.withStyle(ChatFormatting.YELLOW)));
			return;
		}

		if (!data.hasScannedAuctions()) {
			source.sendFeedback(Chat.prefixed(Component.literal(
					"First auction sweep has not finished yet.").withStyle(ChatFormatting.YELLOW)));
			return;
		}

		showTop(source, StrategyKind.AUCTION_VALUE, "Listings below fair value");
	}

	/** Records the flip on the line the player is looking at, at the numbers they saw. */
	private static int take(FabricClientCommandSource source, int rank) {
		List<FlipCandidate> shown = CandidateRenderer.lastShown();

		if (shown.isEmpty()) {
			source.sendError(Component.literal("Nothing to take - run /flip first.")
					.withStyle(ChatFormatting.RED));
			return 0;
		}

		if (rank > shown.size()) {
			source.sendError(Component.literal("That list only had " + shown.size() + " entries.")
					.withStyle(ChatFormatting.RED));
			return 0;
		}

		FlipCandidate candidate = shown.get(rank - 1);

		try {
			LedgerEntry entry = LedgerService.ledger().open(candidate, System.currentTimeMillis());

			source.sendFeedback(Chat.prefixed(Component.literal("Took " + entry.displayName() + " as ")
					.withStyle(ChatFormatting.WHITE)
					.append(Component.literal(entry.id()).withStyle(ChatFormatting.YELLOW))));
			source.sendFeedback(Component.literal("  quoted " + Coins.format(candidate.totalNetProfit())
					+ " on " + candidate.units() + " units. Close it with /flip close " + entry.id()
					+ " <units sold> <sell price>").withStyle(ChatFormatting.DARK_GRAY));
			return 1;
		} catch (IOException e) {
			return ledgerWriteFailed(source, e);
		}
	}

	private static int close(FabricClientCommandSource source, String id, long unitsSold, double unitSellPrice) {
		try {
			// Fees come from live config so the realized side is computed on the same basis the
			// quote used; otherwise the capture rate would measure the fee model, not the market.
			return LedgerService.ledger()
					.close(id, unitsSold, unitSellPrice, CandidateFeed.context().fees())
					.map(entry -> {
						double realized = entry.realizedTotal();
						double quoted = entry.quotedOnFilled();

						source.sendFeedback(Chat.prefixed(Component.literal("Closed " + entry.displayName())
								.withStyle(ChatFormatting.WHITE)));
						source.sendFeedback(Component.literal("  realized " + Coins.format(realized)
								+ " against " + Coins.format(quoted) + " quoted on " + entry.unitsSold()
								+ "/" + entry.units() + " units")
								.withStyle(realized >= quoted ? ChatFormatting.GREEN : ChatFormatting.YELLOW));
						return 1;
					})
					.orElseGet(() -> {
						source.sendError(Component.literal("No open position with id " + id + ".")
								.withStyle(ChatFormatting.RED));
						return 0;
					});
		} catch (IOException e) {
			return ledgerWriteFailed(source, e);
		}
	}

	private static int abandon(FabricClientCommandSource source, String id) {
		try {
			return LedgerService.ledger().abandon(id)
					.map(entry -> {
						source.sendFeedback(Chat.prefixed(Component.literal(
								"Abandoned " + entry.displayName() + " - counted against the fill rate.")
								.withStyle(ChatFormatting.GRAY)));
						return 1;
					})
					.orElseGet(() -> {
						source.sendError(Component.literal("No open position with id " + id + ".")
								.withStyle(ChatFormatting.RED));
						return 0;
					});
		} catch (IOException e) {
			return ledgerWriteFailed(source, e);
		}
	}

	/**
	 * Deletes one entry outright.
	 *
	 * <p>Separate from {@code abandon}, which is about a plan that did not work out and keeps its
	 * units in the fill rate. This is for an entry that should never have been recorded - the stack
	 * of materials you bought to play the game with, not to flip.
	 */
	private static int forget(FabricClientCommandSource source, String id) {
		try {
			return LedgerService.ledger().forget(id)
					.map(entry -> {
						source.sendFeedback(Chat.prefixed(Component.literal(
								"Forgot " + entry.displayName() + " - it is out of the ledger and out "
										+ "of every rate.").withStyle(ChatFormatting.GRAY)));
						return 1;
					})
					.orElseGet(() -> {
						source.sendError(Component.literal("No ledger entry with id " + id + ".")
								.withStyle(ChatFormatting.RED));
						return 0;
					});
		} catch (IOException e) {
			return ledgerWriteFailed(source, e);
		}
	}

	/** Says what a clear would delete and how to ask for it, without deleting anything. */
	private static int offerClear(FabricClientCommandSource source, boolean unquotedOnly) {
		long count = LedgerService.ledger().count(filter(unquotedOnly));

		if (count == 0L) {
			source.sendFeedback(Chat.prefixed(Component.literal(unquotedOnly
					? "No untracked-trade entries to clear."
					: "The ledger is already empty.").withStyle(ChatFormatting.GRAY)));
			return 1;
		}

		String command = unquotedOnly ? "/flip ledger clear unquoted confirm" : "/flip ledger clear confirm";

		source.sendFeedback(Chat.prefixed(Component.literal(unquotedOnly
				? count + " entries came from trades the mod never quoted."
				: count + " entries in the ledger, quoted flips included.")
				.withStyle(ChatFormatting.YELLOW)));
		source.sendFeedback(Component.literal("  This cannot be undone. Run " + command + " to delete them.")
				.withStyle(ChatFormatting.DARK_GRAY));
		return 1;
	}

	private static int clear(FabricClientCommandSource source, boolean unquotedOnly) {
		try {
			int removed = LedgerService.ledger().forgetAll(filter(unquotedOnly));

			source.sendFeedback(Chat.prefixed(Component.literal("Deleted " + removed + " ledger "
					+ (removed == 1 ? "entry." : "entries.")).withStyle(ChatFormatting.GREEN)));
			return 1;
		} catch (IOException e) {
			return ledgerWriteFailed(source, e);
		}
	}

	/**
	 * Which entries a clear touches.
	 *
	 * <p>The unquoted filter is the useful one: it deletes what automatic tracking recorded off your
	 * ordinary buying and selling, and keeps every flip you took from the mod's own list.
	 */
	private static Predicate<LedgerEntry> filter(boolean unquotedOnly) {
		return unquotedOnly ? entry -> !entry.isQuoted() : entry -> true;
	}

	private static void showLedger(FabricClientCommandSource source) {
		LedgerRenderer.renderOpen(source,
				LedgerService.ledger().openEntries(),
				LedgerService.ledger().committedCapital());
		LedgerRenderer.renderStats(source, LedgerService.ledger().stats(null));
	}

	private static int ledgerWriteFailed(FabricClientCommandSource source, IOException e) {
		SkyblockFlipper.LOGGER.error("Ledger write failed", e);
		source.sendError(Component.literal("Could not write the ledger - see the log.")
				.withStyle(ChatFormatting.RED));
		return 0;
	}

	private static void toggleHud(FabricClientCommandSource source) {
		FlipperConfig config = SkyblockFlipperClient.config();
		config.hudEnabled = !config.hudEnabled;

		if (!SkyblockFlipperClient.saveConfig()) {
			source.sendError(Component.literal("HUD toggled for this session, but the config could not be saved.")
					.withStyle(ChatFormatting.RED));
			return;
		}

		source.sendFeedback(Chat.prefixed(Component.literal("HUD " + (config.hudEnabled ? "on" : "off"))
				.withStyle(config.hudEnabled ? ChatFormatting.GREEN : ChatFormatting.GRAY)));
	}

	/**
	 * Turns the trade-message capture on or off and says where the file is.
	 *
	 * <p>Reports the record count on the way out, because the failure mode of a capture session is
	 * finding out afterwards that nothing was written and having to play it again.
	 */
	/**
	 * Pulls the collector's tape now, rather than waiting for the next launch.
	 *
	 * <p>Run on a thread of its own and reported back when it lands. A first sync moves hundreds of
	 * megabytes and the merge rescans the local tape; doing that on the client thread would stop the
	 * game for as long as it took, which is exactly the freeze a player would report as a crash.
	 */
	private static void syncTape(FabricClientCommandSource source) {
		FlipperConfig config = SkyblockFlipperClient.config();
		TapeSyncService sync = MarketDataService.sync();

		// Read from the config, not from the service: the service object exists whenever polling
		// does, whether or not sync is configured, so a null check alone would send a sync at an
		// empty URL and get an unreported failure back.
		if (!config.tapeSyncEnabled || config.tapeSyncUrl.isEmpty()) {
			source.sendError(Component.literal(
					"Collector sync is off. Set tapeSyncEnabled and tapeSyncUrl, then /flip reload.")
					.withStyle(ChatFormatting.RED));
			return;
		}

		if (sync == null) {
			source.sendError(Component.literal("Polling is off, so there is no tape to sync into.")
					.withStyle(ChatFormatting.RED));
			return;
		}

		source.sendFeedback(Chat.prefixed(Component.literal("Syncing from the collector...")
				.withStyle(ChatFormatting.GRAY)));

		Thread worker = new Thread(() -> {
			String outcome = sync.runNow();
			Minecraft.getInstance().execute(() -> source.sendFeedback(Chat.prefixed(
					Component.literal(outcome).withStyle(ChatFormatting.GREEN))));
		}, "skyblock-flipper-manual-sync");

		worker.setDaemon(true);
		worker.start();
	}

	private static void toggleCapture(FabricClientCommandSource source) {
		FlipperConfig config = SkyblockFlipperClient.config();
		config.tradeCaptureEnabled = !config.tradeCaptureEnabled;

		if (!SkyblockFlipperClient.saveConfig()) {
			source.sendError(Component.literal(
					"Capture toggled for this session, but the config could not be saved.")
					.withStyle(ChatFormatting.RED));
			return;
		}

		CaptureLog log = CaptureService.log();

		if (config.tradeCaptureEnabled) {
			source.sendFeedback(Chat.prefixed(Component.literal(
					"Capturing trade messages to " + CaptureService.file().getFileName()
							+ ". Buy, sell, cancel an order, and open your bazaar orders and your "
							+ "auctions so the menus get recorded too.")
					.withStyle(ChatFormatting.GREEN)));
			return;
		}

		source.sendFeedback(Chat.prefixed(Component.literal(
				"Capture off. " + log.records() + " records this session, "
						+ (log.bytes() / 1024) + "KB in " + CaptureService.file())
				.withStyle(ChatFormatting.GRAY)));

		if (log.isFull()) {
			source.sendError(Component.literal("The capture file hit its size cap and stopped "
					+ "recording before you turned it off.").withStyle(ChatFormatting.RED));
		}
	}

	/**
	 * Turns automatic ledger filling on or off.
	 *
	 * <p>Says what it will and will not see, because the two limits are not guessable: a partial
	 * fill is announced in no chat line at all, and a sale of stock bought before tracking started
	 * settles against no position and is dropped.
	 */
	private static void toggleAutoTrack(FabricClientCommandSource source) {
		FlipperConfig config = SkyblockFlipperClient.config();
		config.autoTrackEnabled = !config.autoTrackEnabled;

		if (!SkyblockFlipperClient.saveConfig()) {
			source.sendError(Component.literal(
					"Tracking toggled for this session, but the config could not be saved.")
					.withStyle(ChatFormatting.RED));
			return;
		}

		if (!config.autoTrackEnabled) {
			source.sendFeedback(Chat.prefixed(Component.literal(
					"Automatic tracking off. Positions already open stay in the ledger.")
					.withStyle(ChatFormatting.GRAY)));
			return;
		}

		source.sendFeedback(Chat.prefixed(Component.literal(
				"Tracking your trades into the ledger. Open your bazaar orders menu now and then - "
						+ "a partial fill is announced nowhere else. Sales of stock you had before "
						+ "this was on settle against nothing and are skipped.")
				.withStyle(ChatFormatting.GREEN)));
	}

	private static void showStatus(FabricClientCommandSource source) {
		MarketData data = MarketDataService.data();

		if (!MarketDataService.isRunning()) {
			source.sendFeedback(Chat.prefixed(Component.literal("Poller stopped (pollingEnabled=false).")
					.withStyle(ChatFormatting.RED)));
			return;
		}

		source.sendFeedback(Chat.prefixed(Component.literal("Poller running").withStyle(ChatFormatting.GREEN)));

		BazaarSnapshot bazaar = data.bazaar();
		line(source, "bazaar products", data.hasBazaar()
				? bazaar.products().size() + " (" + describeAge(data.bazaarAge()) + " ago)"
				: "waiting for first fetch");

		line(source, "item catalog", data.catalog().isEmpty()
				? "waiting for first fetch"
				: data.catalog().items().size() + " items");

		line(source, "sales recorded", data.salesRecorded() + " this session ("
				+ describeAge(data.salesAge()) + " ago)");

		// The rollup is what outlives retention, so it is worth saying out loud that it is filling.
		line(source, "sales rollup", data.salesRollupDays() == 0
				? "no completed days summarised yet"
				: data.salesRollupEntries() + " configurations over " + data.salesRollupDays()
						+ " day(s), kept past retention");

		line(source, "valuations", data.values().isEmpty()
				? "none yet (learned from realized sales)"
				: data.values().pricedConfigurations() + " item configurations from "
						+ data.values().salesConsidered() + " sales");

		TrendSnapshot trends = data.trends();
		String history = "off (bazaarTapeEnabled=false)";

		if (SkyblockFlipperClient.config().bazaarTapeEnabled) {
			history = trends.isEmpty()
					? "warming up (sampled every 5m)"
					: trends.samples() + " samples over " + trends.size() + " products, "
							+ trends.window().toHours() + "h window"
							+ (trends.productsWithDailyHistory() > 0
									? ", " + trends.productsWithDailyHistory() + " with daily rollup"
									: "")
							// Fills need an hour of uninterrupted sampling before they mean
							// anything, so a player who has just started the client would otherwise
							// have no way to tell whether the ranking is measured or assumed.
							+ (trends.productsWithMeasuredFills() > 0
									? ", " + trends.productsWithMeasuredFills() + " with measured fills"
									: ", fills still assumed (needs ~1h of uptime)");
		}

		line(source, "price history", history);

		String sweep = "disabled";

		if (SkyblockFlipperClient.config().scanAuctions) {
			sweep = data.hasScannedAuctions()
					? data.scanSummary() + " (" + describeAge(data.auctionsAge()) + " ago)"
					: "waiting for first sweep";
		}

		line(source, "auction sweep", sweep);

		MayorInfo mayor = data.mayor();
		if (mayor.isKnown()) {
			line(source, "mayor", mayor.name() + (mayor.isDerpy() ? " - AH FEES x4, avoid big flips" : ""));
		}

		line(source, "bazaar tax", String.format("%.3f%%",
				new Fees(SkyblockFlipperClient.config().bazaarFlipperLevel, mayor.isDerpy()).bazaarTaxRate() * 100.0d));
		// Resting orders are the half of tracking that has no other display: the ledger shows
		// positions, and an order that has filled but not been collected is not one yet.
		if (SkyblockFlipperClient.config().autoTrackEnabled) {
			TradeTracker tracker = TrackerService.tracker();
			int waiting = tracker.awaitingClaim().size();

			line(source, "trade tracking", tracker.resting().size() + " resting order(s), "
					+ tracker.settlements().size() + " trade(s) seen this session"
					+ (waiting > 0 ? ", " + waiting + " with coins to collect" : ""));
		}

		// The sync runs on its own thread five seconds after login and says nothing in chat, so
		// without this line the only report that it happened at all is the log file.
		TapeSyncService sync = MarketDataService.sync();

		if (sync != null && SkyblockFlipperClient.config().tapeSyncEnabled) {
			line(source, "collector sync", sync.lastRun() == null
					? "not run yet"
					: sync.lastOutcome() + " (" + describeAge(
							Duration.between(sync.lastRun(), Instant.now())) + " ago)");
		}

		line(source, "poll failures", String.valueOf(data.pollFailures()));

		if (!data.lastError().isEmpty()) {
			source.sendFeedback(Component.literal("  last error: " + data.lastError())
					.withStyle(ChatFormatting.RED));
		}
	}

	/**
	 * Opens the settings screen, or explains why there is not one.
	 *
	 * <p>Deferred through {@code Minecraft.execute} for the same reason {@code /flip gui} is: the
	 * chat screen is still tearing down while a command executes, and a screen set during that
	 * never gets a size.
	 */
	private static int editConfig(FabricClientCommandSource source) {
		if (!Settings.available()) {
			source.sendError(Component.literal(
					"The settings screen needs Cloth Config API. Without it, edit "
							+ SkyblockFlipperClient.configFile() + " and run /flip reload.")
					.withStyle(ChatFormatting.RED));
			return 0;
		}

		// No parent: this was opened from chat, so there is nothing to go back to.
		Minecraft.getInstance().execute(() -> Settings.open(null));
		return 1;
	}

	private static void showConfig(FabricClientCommandSource source) {
		FlipperConfig config = SkyblockFlipperClient.config();

		source.sendFeedback(Chat.prefixed(Component.literal(SkyblockFlipperClient.configFile().toString())
				.withStyle(ChatFormatting.GRAY)));
		line(source, "bankroll", Chat.coins(config.bankroll));
		line(source, "bazaar flipper level", config.bazaarFlipperLevel + " ("
				+ new Fees(config.bazaarFlipperLevel, false).bazaarOrderSlots() + " order slots)");
		line(source, "npc daily cap", Chat.coins(CandidateFeed.npcCapRemaining(config)) + " left of "
				+ Chat.coins(config.npcDailyCapCoins));
		line(source, "npc orders", (config.npcMaxOrderSlots > 0
						? config.npcMaxOrderSlots + " of "
						: "all ")
				+ new Fees(config.bazaarFlipperLevel, false).bazaarOrderSlots() + " slots, "
				+ String.format("%.0f%%", config.npcMinMarginRatio * 100.0d) + " min margin, "
				+ String.format("%.2gh", config.npcRestingHours) + " resting, checked every "
				+ config.npcCheckInMinutes + "m");
		line(source, "min profit per flip", Chat.coins(config.minProfitPerFlip));
		line(source, "min confidence", String.format("%.2f", config.minConfidence));
		line(source, "max adverse drift", config.maxAdverseDrift <= 0.0d
				? "off"
				: String.format("%.1f%%", config.maxAdverseDrift * 100.0d));
		line(source, "bazaar tape", config.bazaarTapeEnabled
				? config.bazaarTapeRetentionDays + "d retained, " + config.trendWindowHours + "h trend window"
				: "off");
		line(source, "hud", config.hudEnabled
				? config.hudLines + " lines, " + config.anchor() + " +" + config.hudMarginX + "," + config.hudMarginY
				: "off");
		line(source, "gui zoom", config.guiZoom <= 0.0d
				? "auto"
				: String.format("%.2f", config.guiZoom));
		line(source, "polling enabled", String.valueOf(config.pollingEnabled));

		// The file path above is only actionable if you know editing it is the only way, and with
		// Cloth installed it is not.
		source.sendFeedback(Component.literal(Settings.available()
						? "  /flip config edit opens all of these as a screen"
						: "  edit the file, then /flip reload")
				.withStyle(ChatFormatting.DARK_GRAY));
	}

	/**
	 * The vocabulary, in chat.
	 *
	 * <p>Whole thing at once is a wall of text in a five-line chat box, so a bare {@code /flip
	 * guide} lists the sections and a topic prints one. The screen's Guide tab shows the same text
	 * from the same source with room to read it.
	 *
	 * @param topic section heading, matched on its first word; null lists what is available
	 */
	private static int showGuide(FabricClientCommandSource source, String topic) {
		if (topic == null) {
			source.sendFeedback(Chat.prefixed(Component.literal("Guide").withStyle(ChatFormatting.WHITE)));

			for (Guide.Section section : Guide.sections()) {
				source.sendFeedback(Component.literal("  /flip guide " + section.keyword())
						.withStyle(ChatFormatting.YELLOW)
						.append(Component.literal(" - " + section.heading())
								.withStyle(ChatFormatting.GRAY)));
			}

			source.sendFeedback(Component.literal("  or open the flip screen and pick the Guide tab")
					.withStyle(ChatFormatting.DARK_GRAY));
			return 1;
		}

		for (Guide.Section section : Guide.sections()) {
			if (section.keyword().equalsIgnoreCase(topic)) {
				source.sendFeedback(Chat.prefixed(Component.literal(section.heading())
						.withStyle(ChatFormatting.WHITE)));

				for (Guide.Term term : section.terms()) {
					source.sendFeedback(Component.literal("  " + term.name())
							.withStyle(ChatFormatting.YELLOW)
							.append(Component.literal(" - " + term.meaning())
									.withStyle(ChatFormatting.GRAY)));
				}

				return 1;
			}
		}

		source.sendError(Component.literal("No guide section called " + topic + " - run /flip guide.")
				.withStyle(ChatFormatting.RED));
		return 0;
	}

	private static void line(FabricClientCommandSource source, String key, String value) {
		source.sendFeedback(Component.literal("  " + key + ": ").withStyle(ChatFormatting.GRAY)
				.append(Component.literal(value).withStyle(ChatFormatting.WHITE)));
	}

	private static String describeAge(Duration age) {
		long seconds = age.toSeconds();

		if (seconds <= 0L) {
			return "just now";
		} else if (seconds < 60L) {
			return seconds + "s";
		}

		return age.toMinutes() + "m";
	}
}
