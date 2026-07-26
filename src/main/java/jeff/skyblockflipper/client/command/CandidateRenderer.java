package jeff.skyblockflipper.client.command;

import jeff.skyblockflipper.core.strategy.FlipCandidate;

import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

import java.util.List;

/** Renders ranked candidates into chat. */
public final class CandidateRenderer {
	private CandidateRenderer() {
	}

	public static void renderList(FabricClientCommandSource source, List<FlipCandidate> candidates, String heading) {
		if (candidates.isEmpty()) {
			source.sendFeedback(Chat.prefixed(Component.literal(
					"No candidates clear the fee stack right now. That is a normal answer.")
					.withStyle(ChatFormatting.GRAY)));
			return;
		}

		source.sendFeedback(Chat.prefixed(Component.literal(heading).withStyle(ChatFormatting.WHITE)));

		int rank = 1;

		for (FlipCandidate candidate : candidates) {
			source.sendFeedback(summaryLine(rank++, candidate));
		}

		source.sendFeedback(Component.literal("Hover a line for the steps and risks.")
				.withStyle(ChatFormatting.DARK_GRAY));
	}

	/**
	 * One line per candidate. The full plan lives in the hover tooltip rather than in chat, so a
	 * ten-candidate list stays readable.
	 */
	private static Component summaryLine(int rank, FlipCandidate candidate) {
		MutableComponent line = Component.literal(" " + rank + ". ").withStyle(ChatFormatting.DARK_GRAY)
				.append(Component.literal(candidate.displayName()).withStyle(ChatFormatting.AQUA))
				.append(Component.literal("  " + Chat.coins(Math.round(candidate.profitPerHour())) + "/hr")
						.withStyle(ChatFormatting.GREEN))
				.append(Component.literal("  " + Chat.coins(candidate.capitalRequired()) + " in")
						.withStyle(ChatFormatting.GRAY));

		return line.withStyle(style -> style
				.withHoverEvent(new HoverEvent.ShowText(detail(candidate)))
				// Puts the item name on the clipboard so it can be pasted into the bazaar search.
				.withClickEvent(new ClickEvent.CopyToClipboard(candidate.displayName())));
	}

	private static Component detail(FlipCandidate candidate) {
		MutableComponent text = Component.literal(candidate.displayName() + "\n")
				.withStyle(ChatFormatting.AQUA)
				.append(Component.literal(candidate.kind().label() + " - paid for " + candidate.kind().edge() + "\n\n")
						.withStyle(ChatFormatting.DARK_GRAY));

		text.append(field("Buy", String.format("%.1f", candidate.unitBuyPrice())));
		text.append(field("Sell", String.format("%.1f", candidate.unitSellPrice())));
		text.append(field("Net/unit", String.format("%.1f after fees", candidate.unitNetProfit())));
		text.append(field("Units", String.valueOf(candidate.units())));
		text.append(field("Total", Chat.coins(Math.round(candidate.totalNetProfit()))));
		text.append(field("Return", String.format("%.1f%% on capital", candidate.returnOnCapital() * 100.0d)));
		text.append(field("Confidence", String.format("%.0f%%", candidate.confidence() * 100.0d)));

		text.append(Component.literal("\nSteps\n").withStyle(ChatFormatting.WHITE));

		int step = 1;

		for (String instruction : candidate.steps()) {
			text.append(Component.literal("  " + step++ + ". " + instruction + "\n")
					.withStyle(ChatFormatting.GRAY));
		}

		if (!candidate.risks().isEmpty()) {
			text.append(Component.literal("\nRisks\n").withStyle(ChatFormatting.RED));

			for (String risk : candidate.risks()) {
				text.append(Component.literal("  - " + risk + "\n").withStyle(ChatFormatting.GRAY));
			}
		}

		text.append(Component.literal("\nClick to copy the item name.").withStyle(ChatFormatting.DARK_GRAY));

		return text;
	}

	private static Component field(String label, String value) {
		return Component.literal(label + ": ").withStyle(ChatFormatting.GRAY)
				.append(Component.literal(value + "\n").withStyle(ChatFormatting.WHITE));
	}
}
