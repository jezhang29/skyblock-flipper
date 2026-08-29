package jeff.skyblockflipper.client.command;

import jeff.skyblockflipper.core.recovery.RecoveryComponentQuote;
import jeff.skyblockflipper.core.recovery.RecoveryOpportunity;
import jeff.skyblockflipper.core.text.Coins;

import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.List;

/** Chat rendering for the advisory-only recovery feed. */
public final class RecoveryRenderer {
	private RecoveryRenderer() {}

	public static void renderList(FabricClientCommandSource source,
			List<RecoveryOpportunity> opportunities) {
		if (opportunities.isEmpty()) {
			source.sendFeedback(Chat.prefixed(Component.literal(
					"No evidence-backed recovery opportunities are in the latest auction sweep.")
					.withStyle(ChatFormatting.GRAY)));
			return;
		}
		source.sendFeedback(Chat.prefixed(Component.literal("Recovery values (read-only)")
				.withStyle(ChatFormatting.WHITE)));
		int rank = 1;
		for (RecoveryOpportunity opportunity : opportunities) {
			MutableComponent line = Component.literal(" " + rank++ + ". ")
					.withStyle(ChatFormatting.DARK_GRAY)
					.append(Component.literal(opportunity.displayName()).withStyle(ChatFormatting.AQUA))
					.append(Component.literal("  " + Coins.format(opportunity.expectedProfit()))
							.withStyle(ChatFormatting.GREEN))
					.append(Component.literal("  " + String.format("%.1f%%", opportunity.margin() * 100.0d))
							.withStyle(ChatFormatting.GRAY));
			source.sendFeedback(line.withStyle(style -> style.withClickEvent(
					new ClickEvent.CopyToClipboard(opportunity.auctionUuid()))));
		}
		source.sendFeedback(Component.literal(
				"Use /flip recovery <auction UUID> for evidence. Clicking copies the UUID; nothing is bought.")
				.withStyle(ChatFormatting.DARK_GRAY));
	}

	public static void renderDetail(FabricClientCommandSource source,
			RecoveryOpportunity opportunity) {
		source.sendFeedback(Chat.prefixed(Component.literal(opportunity.displayName())
				.withStyle(ChatFormatting.AQUA)));
		source.sendFeedback(field("Auction", opportunity.auctionUuid()));
		source.sendFeedback(field("Purchase", Coins.format(opportunity.purchasePrice())));
		source.sendFeedback(field("Floor", Coins.format(opportunity.conservativeFloor())));
		source.sendFeedback(field("Profit", Coins.format(opportunity.expectedProfit()) + " ("
				+ String.format("%.1f%%", opportunity.margin() * 100.0d) + ")"));
		renderLeg(source, opportunity.cleanHostQuote());
		for (RecoveryComponentQuote component : opportunity.componentQuotes()) {
			renderLeg(source, component);
		}
		if (!opportunity.warnings().isEmpty()) {
			source.sendFeedback(Component.literal("  Warnings: " + opportunity.warnings())
					.withStyle(ChatFormatting.YELLOW));
		}
		source.sendFeedback(Component.literal(
				"  Advisory only. Verify the active listing yourself; no click or purchase was sent.")
				.withStyle(ChatFormatting.DARK_GRAY));
	}

	private static void renderLeg(FabricClientCommandSource source, RecoveryComponentQuote quote) {
		String evidence = quote.exitVenue() + ", gross " + Coins.format(quote.grossQuickSale())
				+ ", buffer " + Coins.format(quote.bufferedGross())
				+ ", fee " + Coins.format(quote.fee())
				+ ", removal " + Coins.format(quote.removalCost())
				+ ", net " + Coins.format(quote.netContribution());
		source.sendFeedback(Component.literal("  " + quote.displayName() + ": " + evidence)
				.withStyle(quote.credited() ? ChatFormatting.GRAY : ChatFormatting.YELLOW));
	}

	private static Component field(String name, String value) {
		return Component.literal("  " + name + ": ").withStyle(ChatFormatting.GRAY)
				.append(Component.literal(value).withStyle(ChatFormatting.WHITE));
	}
}
