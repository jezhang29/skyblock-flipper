package jeff.skyblockflipper.client.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import jeff.skyblockflipper.client.SkyblockFlipperClient;
import jeff.skyblockflipper.core.config.FlipperConfig;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

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
				.then(ClientCommands.literal("reload")
						.executes(ctx -> {
							if (SkyblockFlipperClient.reloadConfig()) {
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
		source.sendFeedback(prefixed(Component.literal("scaffold ready - no strategies wired up yet.")
				.withStyle(ChatFormatting.GRAY)));
		source.sendFeedback(Component.literal("  /flip config")
				.withStyle(ChatFormatting.YELLOW)
				.append(Component.literal(" - show current settings").withStyle(ChatFormatting.GRAY)));
		source.sendFeedback(Component.literal("  /flip reload")
				.withStyle(ChatFormatting.YELLOW)
				.append(Component.literal(" - re-read config.json from disk").withStyle(ChatFormatting.GRAY)));
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
