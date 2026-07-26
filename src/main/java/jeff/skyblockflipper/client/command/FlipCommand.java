package jeff.skyblockflipper.client.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import jeff.skyblockflipper.client.CandidateFeed;
import jeff.skyblockflipper.client.MarketDataService;
import jeff.skyblockflipper.client.SkyblockFlipperClient;
import jeff.skyblockflipper.core.api.MarketData;
import jeff.skyblockflipper.core.config.FlipperConfig;
import jeff.skyblockflipper.core.model.BazaarSnapshot;
import jeff.skyblockflipper.core.model.MayorInfo;
import jeff.skyblockflipper.core.pricing.Fees;
import jeff.skyblockflipper.core.strategy.FlipCandidate;
import jeff.skyblockflipper.core.strategy.StrategyKind;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

import net.minecraft.ChatFormatting;
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
				.then(ClientCommands.literal("status")
						.executes(ctx -> {
							showStatus(ctx.getSource());
							return 1;
						}))
				.then(ClientCommands.literal("config")
						.executes(ctx -> {
							showConfig(ctx.getSource());
							return 1;
						}))
				.then(ClientCommands.literal("hud")
						.executes(ctx -> {
							toggleHud(ctx.getSource());
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

		if (data.mayor().isDerpy()) {
			source.sendFeedback(Component.literal("Derpy is mayor: auction fees are 4x. Bazaar is unaffected.")
					.withStyle(ChatFormatting.RED));
		}
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

	private static void showConfig(FabricClientCommandSource source) {
		FlipperConfig config = SkyblockFlipperClient.config();

		source.sendFeedback(Chat.prefixed(Component.literal(SkyblockFlipperClient.configFile().toString())
				.withStyle(ChatFormatting.GRAY)));
		line(source, "bankroll", Chat.coins(config.bankroll));
		line(source, "bazaar flipper level", String.valueOf(config.bazaarFlipperLevel));
		line(source, "min profit per flip", Chat.coins(config.minProfitPerFlip));
		line(source, "min confidence", String.format("%.2f", config.minConfidence));
		line(source, "hud", config.hudEnabled
				? config.hudLines + " lines, " + config.anchor() + " +" + config.hudMarginX + "," + config.hudMarginY
				: "off");
		line(source, "polling enabled", String.valueOf(config.pollingEnabled));
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
