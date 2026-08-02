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
import jeff.skyblockflipper.client.SkyblockFlipperClient;
import jeff.skyblockflipper.client.gui.FlipKeybinds;
import jeff.skyblockflipper.client.gui.Settings;
import jeff.skyblockflipper.client.track.CaptureService;
import jeff.skyblockflipper.core.api.MarketData;
import jeff.skyblockflipper.core.config.FlipperConfig;
import jeff.skyblockflipper.core.ledger.LedgerEntry;
import jeff.skyblockflipper.core.model.BazaarSnapshot;
import jeff.skyblockflipper.core.model.MayorInfo;
import jeff.skyblockflipper.core.pricing.Fees;
import jeff.skyblockflipper.core.strategy.FlipCandidate;
import jeff.skyblockflipper.core.strategy.StrategyKind;
import jeff.skyblockflipper.core.text.Coins;
import jeff.skyblockflipper.core.text.Guide;
import jeff.skyblockflipper.core.track.CaptureLog;
import jeff.skyblockflipper.core.valuation.TrendSnapshot;

import java.io.IOException;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.time.Duration;
import java.util.List;

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
					showTop(ctx.getSource(), null, "Top flips right now");
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
						}))
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
						}))
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
		MarketData data = MarketDataService.data();

		if (!data.hasBazaar()) {
			source.sendFeedback(Chat.prefixed(Component.literal(
					MarketDataService.isRunning()
							? "Waiting for the first market fetch, try again in a few seconds."
							: "Polling is disabled - run /flip config and set pollingEnabled.")
					.withStyle(ChatFormatting.YELLOW)));
			return;
		}

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
		line(source, "bazaar flipper level", String.valueOf(config.bazaarFlipperLevel));
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
