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
package jeff.skyblockflipper.client.command;

import jeff.skyblockflipper.core.ledger.LedgerEntry;
import jeff.skyblockflipper.core.ledger.LedgerStats;
import jeff.skyblockflipper.core.strategy.StrategyKind;
import jeff.skyblockflipper.core.text.Coins;

import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.OptionalDouble;

/** Renders open positions and the realized-versus-quoted numbers into chat. */
final class LedgerRenderer {
	/** Below this, quotes are being missed by enough that the ranking is misleading. */
	private static final double POOR_CAPTURE = 0.9d;

	private LedgerRenderer() {
	}

	static void renderOpen(FabricClientCommandSource source, List<LedgerEntry> open, long committed) {
		if (open.isEmpty()) {
			source.sendFeedback(Chat.prefixed(Component.literal("No open positions.")
					.withStyle(ChatFormatting.GRAY)));
			return;
		}

		source.sendFeedback(Chat.prefixed(Component.literal(
				open.size() + " open, " + Coins.format(committed) + " committed")
				.withStyle(ChatFormatting.WHITE)));

		for (LedgerEntry entry : open) {
			source.sendFeedback(Component.literal("  " + entry.id() + " ").withStyle(ChatFormatting.YELLOW)
					.append(Component.literal(entry.displayName()).withStyle(ChatFormatting.AQUA))
					.append(Component.literal(" " + progress(entry)).withStyle(soldColour(entry)))
					.append(Component.literal(" @ " + String.format("%.1f", entry.unitBuyPrice()))
							.withStyle(ChatFormatting.GRAY))
					.append(Component.literal("  " + money(entry)).withStyle(ChatFormatting.DARK_GRAY)));
		}

		source.sendFeedback(Component.literal("Close one with /flip close <id> <units sold> <sell price>")
				.withStyle(ChatFormatting.DARK_GRAY));
	}

	/**
	 * How much of a position has come back, which is the fact the old line left out entirely.
	 *
	 * <p>It printed the planned unit count and the quoted buy price and nothing else, so a position
	 * that had bought and sold 596 of its 899 units looked identical to one that had done nothing.
	 * That was read in live play as the tracker not working, when the tracker had in fact recorded
	 * every fill.
	 */
	private static String progress(LedgerEntry entry) {
		if (entry.unitsSold() == 0L) {
			return "x" + entry.units();
		}

		return entry.unitsSold() + "/" + entry.units() + " sold";
	}

	/** Realized against quoted once anything has sold, and the plain quote before that. */
	private static String money(LedgerEntry entry) {
		if (!entry.isQuoted()) {
			return entry.unitsSold() == 0L
					? "tracked, no quote"
					: "made " + Coins.format(entry.realizedTotal()) + ", no quote";
		}

		if (entry.unitsSold() == 0L) {
			return "quoted " + Coins.format(entry.quotedUnitNet() * entry.units());
		}

		return "made " + Coins.format(entry.realizedTotal())
				+ " of " + Coins.format(entry.quotedOnFilled()) + " quoted so far";
	}

	private static ChatFormatting soldColour(LedgerEntry entry) {
		if (entry.unitsSold() == 0L) {
			return ChatFormatting.GRAY;
		}

		return entry.unitsSold() >= entry.units() ? ChatFormatting.GREEN : ChatFormatting.WHITE;
	}

	static void renderStats(FabricClientCommandSource source, LedgerStats stats) {
		if (stats.closed() == 0 && stats.abandoned() == 0) {
			source.sendFeedback(Component.literal("  nothing closed yet - open positions above show "
					+ "what has filled so far").withStyle(ChatFormatting.DARK_GRAY));
			return;
		}

		source.sendFeedback(Component.literal("  " + stats.closed() + " closed, " + stats.abandoned()
				+ " abandoned").withStyle(ChatFormatting.GRAY));
		source.sendFeedback(Component.literal("  realized " + Coins.format(stats.realized())
				+ " against " + Coins.format(stats.quotedOnFilled()) + " quoted")
				.withStyle(ChatFormatting.WHITE));

		OptionalDouble capture = stats.captureRate();
		OptionalDouble fill = stats.fillRate();

		if (capture.isPresent()) {
			source.sendFeedback(Component.literal("  capture rate: "
					+ String.format("%.0f%%", capture.getAsDouble() * 100.0d)
					+ (stats.isMeaningful() ? "" : " (too few closes to trust yet)"))
					.withStyle(capture.getAsDouble() >= POOR_CAPTURE ? ChatFormatting.GREEN : ChatFormatting.YELLOW));
		}

		if (fill.isPresent()) {
			source.sendFeedback(Component.literal("  fill rate: "
					+ String.format("%.0f%%", fill.getAsDouble() * 100.0d) + " of planned units")
					.withStyle(ChatFormatting.GRAY));
		}
	}

	/**
	 * Warns when your own history says the quoted numbers overstate what you get. This is the one
	 * place the mod can tell you its own rankings are optimistic, so it is worth saying out loud
	 * next to the rankings rather than only inside {@code /flip ledger}.
	 */
	static void renderCaptureWarning(FabricClientCommandSource source, LedgerStats stats, StrategyKind kind) {
		OptionalDouble capture = stats.captureRate();

		if (!stats.isMeaningful() || capture.isEmpty() || capture.getAsDouble() >= POOR_CAPTURE) {
			return;
		}

		source.sendFeedback(Component.literal(String.format(
				"Your last %d closed %sflips realized %.0f%% of quoted. Discount these numbers accordingly.",
				stats.closed(),
				kind == null ? "" : kind.label().toLowerCase(java.util.Locale.ROOT) + " ",
				capture.getAsDouble() * 100.0d))
				.withStyle(ChatFormatting.YELLOW));
	}
}
