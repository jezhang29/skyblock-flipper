package jeff.skyblockflipper.client.command;

import jeff.skyblockflipper.core.strategy.NpcBasket;
import jeff.skyblockflipper.core.strategy.NpcPlan;
import jeff.skyblockflipper.core.strategy.NpcReprice;
import jeff.skyblockflipper.core.text.Coins;

import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

import java.util.List;

/** Renders an NPC basket and a reprice round into chat. */
public final class NpcRenderer {
	private NpcRenderer() {
	}

	/**
	 * The whole basket, one line per order to place.
	 *
	 * <p>Every line is printed rather than the top ten, which is what the ranked list does. A ranking
	 * is a menu to choose from; a basket is a list of things to do, and half of one spends the
	 * bankroll on half the plan.
	 */
	public static void renderBasket(FabricClientCommandSource source, NpcBasket.Basket basket) {
		if (basket.isEmpty()) {
			source.sendFeedback(Chat.prefixed(Component.literal(
							"Nothing on the book clears the NPC filters right now. That is a normal answer.")
					.withStyle(ChatFormatting.GRAY)));
			source.sendFeedback(Component.literal(
							"  An item needs a standing gap under the NPC price and a margin over the floor.")
					.withStyle(ChatFormatting.DARK_GRAY));
			return;
		}

		source.sendFeedback(Chat.prefixed(Component.literal(String.format(
				"NPC basket: %d items in %d of %d order slots",
				basket.lines().size(), basket.slotsUsed(), basket.slotsAvailable()))
				.withStyle(ChatFormatting.WHITE)));

		int rank = 1;

		for (NpcBasket.Line line : basket.lines()) {
			source.sendFeedback(basketLine(rank++, line));
		}

		source.sendFeedback(Component.literal("  " + Coins.format(basket.capital()) + " in")
				.withStyle(ChatFormatting.GRAY)
				.append(Component.literal(" -> " + Coins.format(basket.profit()) + " over "
								+ String.format("%.0fh", basket.restingHours()))
						.withStyle(ChatFormatting.GREEN))
				.append(Component.literal(String.format("  (%s/hr, %.0f%% on capital)",
								Coins.format(basket.profitPerHour()),
								basket.returnOnCapital() * 100.0d))
						.withStyle(ChatFormatting.DARK_GRAY)));

		source.sendFeedback(Component.literal("  " + basket.boundExplanation())
				.withStyle(ChatFormatting.YELLOW));

		source.sendFeedback(Component.literal(String.format(
						"  %s of the day's NPC budget, %d inventory %s through /trades",
						Coins.format(basket.npcPayout()), basket.loads(),
						basket.loads() == 1L ? "load" : "loads"))
				.withStyle(ChatFormatting.DARK_GRAY));

		source.sendFeedback(Component.literal(
						"  Come back and run /flip npc reprice, or the orders stop filling.")
				.withStyle(ChatFormatting.DARK_GRAY));
	}

	private static Component basketLine(int rank, NpcBasket.Line line) {
		NpcPlan plan = line.plan();

		MutableComponent text = Component.literal(" " + rank + ". ")
				.withStyle(ChatFormatting.DARK_GRAY)
				.append(Component.literal(plan.displayName()).withStyle(ChatFormatting.AQUA))
				.append(Component.literal(String.format("  %d @ %.1f", line.units(), plan.postPrice()))
						.withStyle(ChatFormatting.WHITE))
				.append(Component.literal("  " + Coins.format(line.profit()))
						.withStyle(ChatFormatting.GREEN));

		if (line.orders() > 1) {
			text.append(Component.literal("  as " + line.orderSplit())
					.withStyle(ChatFormatting.GRAY));
		}

		return text.withStyle(style -> style
				.withHoverEvent(new HoverEvent.ShowText(basketDetail(line)))
				.withClickEvent(new ClickEvent.CopyToClipboard(plan.displayName())));
	}

	private static Component basketDetail(NpcBasket.Line line) {
		NpcPlan plan = line.plan();

		MutableComponent text = Component.literal(plan.displayName() + "\n")
				.withStyle(ChatFormatting.AQUA);

		text.append(field("Post", String.format("%.1f as a buy order", plan.postPrice())));
		text.append(field("Units", line.orders() == 1
				? String.valueOf(line.units())
				: line.units() + " as " + line.orderSplit()));
		text.append(field("Orders", line.orders() + ", holding at most "
				+ plan.unitsPerOrder() + " units each"));
		text.append(field("Sell to NPC at", String.format("%.1f", plan.npcPrice())));
		text.append(field("Net/unit", String.format("%.1f (%.0f%% margin)", plan.unitNetProfit(),
				plan.marginRatio() * 100.0d)));
		text.append(field("Capital", Coins.format(line.capital())));
		text.append(field("Profit", Coins.format(line.profit())));

		if (plan.chaseCost() > 0.0d) {
			text.append(field("Chase cost", String.format("%.1f a unit, already taken out",
					plan.chaseCost())));
		}

		text.append(field("Fill", String.format("about %.0f units an hour %s", plan.fillPerHour(),
				plan.fillMeasured() ? "(measured)" : "(assumed - no tape history yet)")));
		text.append(field("Edge", plan.edgeMeasured()
				? String.format("held in %.0f%% of taped samples", plan.persistence() * 100.0d)
				: "never taped, so nothing has checked it stands"));
		text.append(field("Hauling", line.loads() + (line.loads() == 1L ? " load" : " loads")
				+ " at " + plan.unitsPerLoad() + " a load"));

		text.append(Component.literal("\nClick to copy the item name.")
				.withStyle(ChatFormatting.DARK_GRAY));

		return text;
	}

	/**
	 * A reprice round: which resting orders need a click, and which are fine.
	 *
	 * <p>The ones needing action are listed and the rest are counted. A player who has just walked
	 * back to the bazaar wants the short list of things to do, not confirmation that fourteen orders
	 * are still where they left them.
	 */
	public static void renderReprice(FabricClientCommandSource source, List<NpcReprice.Advice> advice) {
		List<NpcReprice.Advice> acting = advice.stream().filter(NpcReprice.Advice::needsAction).toList();
		int holding = advice.size() - acting.size();

		if (acting.isEmpty()) {
			source.sendFeedback(Chat.prefixed(Component.literal(holding == 0
							? "No NPC buy orders resting. Run /flip npc plan for a basket to place."
							: "All " + holding + " NPC orders are still on top of the book.")
					.withStyle(holding == 0 ? ChatFormatting.GRAY : ChatFormatting.GREEN)));
			return;
		}

		source.sendFeedback(Chat.prefixed(Component.literal(acting.size() + " of "
						+ advice.size() + " NPC orders need a click")
				.withStyle(ChatFormatting.WHITE)));

		for (NpcReprice.Advice entry : acting) {
			source.sendFeedback(repriceLine(entry));
		}

		if (holding > 0) {
			source.sendFeedback(Component.literal("  " + holding + " other "
							+ (holding == 1 ? "order is" : "orders are") + " still on top of the book.")
					.withStyle(ChatFormatting.DARK_GRAY));
		}
	}

	private static Component repriceLine(NpcReprice.Advice advice) {
		boolean cancel = advice.action() == NpcReprice.Action.CANCEL;

		MutableComponent text = Component.literal("  ")
				.append(Component.literal(cancel ? "cancel " : "reprice ")
						.withStyle(cancel ? ChatFormatting.RED : ChatFormatting.YELLOW))
				.append(Component.literal(advice.order().displayName())
						.withStyle(ChatFormatting.AQUA))
				.append(Component.literal(cancel
								? String.format("  %.1f is past the %.1f stop", advice.postPrice(),
										advice.chaseStop())
								: String.format("  %.1f -> %.1f", advice.order().unitPrice(),
										advice.postPrice()))
						.withStyle(ChatFormatting.WHITE))
				.append(Component.literal("  " + advice.order().remaining() + " units left")
						.withStyle(ChatFormatting.GRAY));

		return text.withStyle(style -> style
				.withHoverEvent(new HoverEvent.ShowText(repriceDetail(advice)))
				.withClickEvent(new ClickEvent.CopyToClipboard(advice.order().displayName())));
	}

	private static Component repriceDetail(NpcReprice.Advice advice) {
		MutableComponent text = Component.literal(advice.order().displayName() + "\n")
				.withStyle(ChatFormatting.AQUA)
				.append(Component.literal(advice.reason() + "\n\n").withStyle(ChatFormatting.GRAY));

		text.append(field("Your price", String.format("%.1f", advice.order().unitPrice())));
		text.append(field("Best bid", String.format("%.1f", advice.bestBid())));
		text.append(field("NPC pays", String.format("%.1f", advice.npcPrice())));
		text.append(field("Chase stop", String.format("%.1f", advice.chaseStop())));
		text.append(field("Units left", String.valueOf(advice.order().remaining())));

		if (advice.action() == NpcReprice.Action.REPRICE) {
			text.append(field("Reprice to", String.format("%.1f", advice.postPrice())));
			text.append(field("Costs", Coins.format(advice.extraCost()) + " more on the units left"));
			text.append(field("Still worth", Coins.format(advice.profitAtStake()) + " if it fills"));
		} else {
			text.append(field("Frees", Coins.format(advice.capitalAtStake()) + " and one order slot"));
		}

		text.append(Component.literal("\nClick to copy the item name.")
				.withStyle(ChatFormatting.DARK_GRAY));

		return text;
	}

	private static Component field(String label, String value) {
		return Component.literal(label + ": ").withStyle(ChatFormatting.GRAY)
				.append(Component.literal(value + "\n").withStyle(ChatFormatting.WHITE));
	}
}
