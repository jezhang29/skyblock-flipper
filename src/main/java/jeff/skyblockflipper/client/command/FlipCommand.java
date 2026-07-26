package jeff.skyblockflipper.client.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import jeff.skyblockflipper.client.MarketDataService;
import jeff.skyblockflipper.client.SkyblockFlipperClient;
import jeff.skyblockflipper.core.api.MarketData;
import jeff.skyblockflipper.core.config.FlipperConfig;
import jeff.skyblockflipper.core.model.BazaarSnapshot;
import jeff.skyblockflipper.core.model.MayorInfo;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import java.time.Duration;

/**
 * The {@code /flip} command tree. Client-side only: the command never reaches the server,
 * so it works on Hypixel without the server knowing it exists.
 *
 * <p>Scaffold for now. Subcommands for each strategy get grafted onto this tree as they land.
 */
public final class FlipCommand {
	private FlipCommand() {
	}

	public static void register() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
				dispatcher.register(build()));
	}

	private static LiteralArgumentBuilder<FabricClientCommandSource> build() {
		return ClientCommands.literal("flip")
				.executes(ctx -> {
					status(ctx.getSource());
					return 1;
				})
				.then(ClientCommands.literal("config")
						.executes(ctx -> {
							showConfig(ctx.getSource());
							return 1;
						}))
				.then(ClientCommands.literal("status")
						.executes(ctx -> {
							showStatus(ctx.getSource());
							return 1;
						}))
				.then(ClientCommands.literal("reload")
						.executes(ctx -> {
							if (SkyblockFlipperClient.reloadConfig()) {
								// Picks up a flipped pollingEnabled without a game restart.
								MarketDataService.restart();
								ctx.getSource().sendFeedback(
										prefixed(Component.literal("Config reloaded.")
												.withStyle(ChatFormatting.GREEN)));
								return 1;
							}

							ctx.getSource().sendError(
									Component.literal("Config reload failed - see the log.")
											.withStyle(ChatFormatting.RED));
							return 0;
						}));
	}

	private static void status(FabricClientCommandSource source) {
		source.sendFeedback(prefixed(Component.literal("collecting market data - no strategies wired up yet.")
				.withStyle(ChatFormatting.GRAY)));
		help(source, "/flip status", "market data and sales tape health");
		help(source, "/flip config", "show current settings");
		help(source, "/flip reload", "re-read config.json from disk");
	}

	private static void help(FabricClientCommandSource source, String command, String description) {
		source.sendFeedback(Component.literal("  " + command)
				.withStyle(ChatFormatting.YELLOW)
				.append(Component.literal(" - " + description).withStyle(ChatFormatting.GRAY)));
	}

	private static void showStatus(FabricClientCommandSource source) {
		MarketData data = MarketDataService.data();

		if (!MarketDataService.isRunning()) {
			source.sendFeedback(prefixed(Component.literal("Poller stopped (pollingEnabled=false).")
					.withStyle(ChatFormatting.RED)));
			return;
		}

		source.sendFeedback(prefixed(Component.literal("Poller running").withStyle(ChatFormatting.GREEN)));

		BazaarSnapshot bazaar = data.bazaar();
		line(source, "bazaar products", data.hasBazaar()
				? bazaar.products().size() + " (" + describeAge(data.bazaarAge()) + " ago)"
				: "waiting for first fetch");

		line(source, "sales recorded", data.salesRecorded() + " this session ("
				+ describeAge(data.salesAge()) + " ago)");

		MayorInfo mayor = data.mayor();
		if (mayor.isKnown()) {
			// Derpy quadruples every AH fee, so it is worth shouting about rather than burying.
			line(source, "mayor", mayor.name() + (mayor.isDerpy() ? " - AH FEES x4, avoid big flips" : ""));
		}

		line(source, "poll failures", String.valueOf(data.pollFailures()));

		if (!data.lastError().isEmpty()) {
			source.sendFeedback(Component.literal("  last error: " + data.lastError())
					.withStyle(ChatFormatting.RED));
		}
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

	private static void showConfig(FabricClientCommandSource source) {
		FlipperConfig config = SkyblockFlipperClient.config();

		source.sendFeedback(prefixed(Component.literal(SkyblockFlipperClient.configFile().toString())
				.withStyle(ChatFormatting.GRAY)));
		line(source, "bankroll", formatCoins(config.bankroll));
		line(source, "bazaar flipper level", String.valueOf(config.bazaarFlipperLevel));
		line(source, "min profit per flip", formatCoins(config.minProfitPerFlip));
		line(source, "min confidence", String.format("%.2f", config.minConfidence));
		line(source, "hud enabled", String.valueOf(config.hudEnabled));
		line(source, "polling enabled", String.valueOf(config.pollingEnabled));
	}

	private static void line(FabricClientCommandSource source, String key, String value) {
		source.sendFeedback(Component.literal("  " + key + ": ").withStyle(ChatFormatting.GRAY)
				.append(Component.literal(value).withStyle(ChatFormatting.WHITE)));
	}

	/** Renders coin amounts the way Skyblock players read them: 12.5M, 340k, 900. */
	private static String formatCoins(long coins) {
		if (coins >= 1_000_000_000L) {
			return String.format("%.2fB", coins / 1_000_000_000.0d);
		} else if (coins >= 1_000_000L) {
			return String.format("%.2fM", coins / 1_000_000.0d);
		} else if (coins >= 1_000L) {
			return String.format("%.1fk", coins / 1_000.0d);
		}

		return String.valueOf(coins);
	}

	private static Component prefixed(Component message) {
		return Component.literal("[Flipper] ").withStyle(ChatFormatting.GOLD).append(message);
	}
}
