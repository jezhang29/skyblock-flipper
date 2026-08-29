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

import jeff.skyblockflipper.core.strategy.NpcBasket;
import jeff.skyblockflipper.core.strategy.NpcWorklist;
import jeff.skyblockflipper.core.text.Coins;

import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

import java.util.List;

/**
 * Renders an NPC worklist into chat.
 *
 * <p>One renderer for both commands, because they are two views of one list: {@code /flip npc plan}
 * prints all of it and {@code /flip npc reprice} prints the part about orders that already exist.
 * Two renderers is how the mod previously managed to tell a player to place twenty-one orders and,
 * separately, that fourteen were already resting.
 */
public final class NpcRenderer {
	private NpcRenderer() {
	}

	/** Everything to do this trip: claims, cancels, reprices, then the new orders. */
	public static void renderWorklist(FabricClientCommandSource source,
			NpcWorklist.Worklist worklist) {
		render(source, worklist, worklist.pending(), true);
	}

	/**
	 * Only the orders already on the book.
	 *
	 * <p>What a player asks for on the way back with their slots full: the new orders in the same
	 * worklist are sized against slots that are only free once these clicks have happened, so
	 * printing them here would be a list that gets longer as you work it.
	 */
	public static void renderResting(FabricClientCommandSource source,
			NpcWorklist.Worklist worklist) {
		List<NpcWorklist.Task> resting = worklist.pending().stream()
				.filter(task -> task.kind() != NpcWorklist.Kind.PLACE)
				.toList();

		// Answered here rather than by the shared empty case, which would report the new orders this
		// command deliberately left out - "3 to place" is not an answer to "what do I do with what I
		// already have".
		if (resting.isEmpty()) {
			String waiting = worklist.waitingNote(System.currentTimeMillis());

			// "All of them are on top of the book" is exactly wrong where some have been outbid and
			// the round in hand is not asking about them yet. Say which it is, and when.
			if (!waiting.isEmpty()) {
				source.sendFeedback(Chat.prefixed(Component.literal("Nothing to reprice yet: "
						+ waiting + ".").withStyle(ChatFormatting.YELLOW)));
				source.sendFeedback(Component.literal("  Prices are frozen for a whole check-in "
								+ "interval so a number can be read, walked to the menu and typed.")
						.withStyle(ChatFormatting.DARK_GRAY));
				return;
			}

			source.sendFeedback(Chat.prefixed(Component.literal(worklist.holding() == 0
							? "No NPC buy orders resting. Run /flip npc plan for a basket to place."
							: "All " + worklist.holding() + " NPC orders are on top of the book with "
									+ "nothing to collect.")
					.withStyle(worklist.holding() == 0 ? ChatFormatting.GRAY : ChatFormatting.GREEN)));

			if (worklist.count(NpcWorklist.Kind.PLACE) > 0) {
				source.sendFeedback(Component.literal("  " + worklist.count(NpcWorklist.Kind.PLACE)
								+ " more would fit - /flip npc plan has them.")
						.withStyle(ChatFormatting.DARK_GRAY));
			}

			return;
		}

		render(source, worklist, resting, false);
	}

	private static void render(FabricClientCommandSource source, NpcWorklist.Worklist worklist,
			List<NpcWorklist.Task> tasks, boolean withTotals) {
		if (tasks.isEmpty()) {
			renderNothingToDo(source, worklist);
			return;
		}

		source.sendFeedback(Chat.prefixed(Component.literal(worklist.headline())
				.withStyle(ChatFormatting.WHITE)));

		int rank = 1;

		for (NpcWorklist.Task task : tasks) {
			source.sendFeedback(taskLine(rank++, task));
		}

		if (worklist.holding() > 0) {
			source.sendFeedback(Component.literal("  " + worklist.holding() + " other "
							+ (worklist.holding() == 1 ? "order is" : "orders are")
							+ " on top of the book with nothing to do.")
					.withStyle(ChatFormatting.DARK_GRAY));
		}

		if (withTotals) {
			renderTotals(source, worklist.basket());
		}

		renderRound(source, worklist);

		source.sendFeedback(Component.literal(
						"  Come back and run /flip npc reprice, or the orders stop filling.")
				.withStyle(ChatFormatting.DARK_GRAY));
	}

	/**
	 * When the numbers above stop being the numbers to type, and when the next list is due.
	 *
	 * <p>The list is a round rather than a running commentary on the book, and a frozen price with no
	 * stated expiry looks like a live one that has stopped updating. Nothing at all without a round -
	 * a caller off the clock has no due time to quote.
	 */
	private static void renderRound(FabricClientCommandSource source,
			NpcWorklist.Worklist worklist) {
		String note = worklist.roundNote(System.currentTimeMillis());

		if (!note.isEmpty()) {
			source.sendFeedback(Component.literal("  " + note).withStyle(ChatFormatting.GRAY));
		}
	}

	/**
	 * Why there is nothing to do, which is several different situations.
	 *
	 * <p>"No orders and no candidates" is the market being quiet, "every order fine" is the basket
	 * working, and "every slot full" is a limit the player can act on. Reporting all three as an
	 * empty list reads as a broken feature for two of them.
	 */
	private static void renderNothingToDo(FabricClientCommandSource source,
			NpcWorklist.Worklist worklist) {
		NpcBasket.Basket basket = worklist.basket();

		source.sendFeedback(Chat.prefixed(Component.literal(worklist.headline())
				.withStyle(worklist.holding() > 0 ? ChatFormatting.GREEN : ChatFormatting.GRAY)));

		renderRound(source, worklist);

		// Only when the market really is the answer. An empty basket the mod emptied itself has its
		// own explanation, and printing this one over it would blame the book for a wait.
		if (worklist.holding() == 0 && basket.isEmpty()
				&& basket.bound() == NpcBasket.Bound.CANDIDATES) {
			source.sendFeedback(Component.literal(
							"  An item needs a standing gap under the NPC price and a margin over the floor.")
					.withStyle(ChatFormatting.DARK_GRAY));
			return;
		}

		source.sendFeedback(Component.literal("  " + basket.boundExplanation())
				.withStyle(ChatFormatting.YELLOW));
	}

	private static void renderTotals(FabricClientCommandSource source, NpcBasket.Basket basket) {
		if (basket.isEmpty()) {
			source.sendFeedback(Component.literal("  " + basket.boundExplanation())
					.withStyle(ChatFormatting.YELLOW));
			return;
		}

		source.sendFeedback(Component.literal("  " + Coins.format(basket.capital()) + " in")
				.withStyle(ChatFormatting.GRAY)
				.append(Component.literal(" -> " + Coins.format(basket.profit()) + " over "
								+ String.format("%.0fh", basket.restingHours()))
						.withStyle(ChatFormatting.GREEN))
				.append(Component.literal(String.format("  (%s/hr)",
								Coins.format(basket.profitPerHour())))
						.withStyle(ChatFormatting.DARK_GRAY)));

		source.sendFeedback(Component.literal("  " + basket.boundExplanation())
				.withStyle(ChatFormatting.YELLOW));

		source.sendFeedback(Component.literal(String.format(
						"  %d of %d order slots free, %s of the day's NPC budget, %d inventory %s "
								+ "through /trades",
						basket.slotsFree(), basket.slotsAvailable(), Coins.format(basket.npcPayout()),
						basket.loads(), basket.loads() == 1L ? "load" : "loads"))
				.withStyle(ChatFormatting.DARK_GRAY));
	}

	private static Component taskLine(int rank, NpcWorklist.Task task) {
		MutableComponent text = Component.literal(" " + rank + ". ")
				.withStyle(ChatFormatting.DARK_GRAY)
				.append(Component.literal(task.verb() + " ").withStyle(colour(task.kind())))
				.append(Component.literal(task.displayName()).withStyle(ChatFormatting.AQUA))
				.append(Component.literal("  " + numbers(task)).withStyle(ChatFormatting.WHITE));

		if (task.profit() > 0.0d) {
			text.append(Component.literal("  " + Coins.format(task.profit()))
					.withStyle(ChatFormatting.GREEN));
		}

		return text.withStyle(style -> style
				.withHoverEvent(new HoverEvent.ShowText(taskDetail(task)))
				.withClickEvent(new ClickEvent.CopyToClipboard(task.displayName())));
	}

	/** The part of the row that gets typed into the game, written out and never abbreviated. */
	private static String numbers(NpcWorklist.Task task) {
		return switch (task.kind()) {
			case CLAIM -> task.units() + " units";
			case CANCEL -> task.units() + " units left";
			case REPRICE, PLACE -> String.format("%s @ %.1f", task.orderSplit(), task.price());
			case HOLD -> String.format("%d at %.1f", task.units(), task.price());
		};
	}

	private static ChatFormatting colour(NpcWorklist.Kind kind) {
		return switch (kind) {
			case CLAIM -> ChatFormatting.GREEN;
			case CANCEL -> ChatFormatting.RED;
			case REPRICE -> ChatFormatting.YELLOW;
			case PLACE -> ChatFormatting.GOLD;
			case HOLD -> ChatFormatting.DARK_GRAY;
		};
	}

	private static Component taskDetail(NpcWorklist.Task task) {
		MutableComponent text = Component.literal(task.displayName() + "\n")
				.withStyle(ChatFormatting.AQUA)
				.append(Component.literal(task.reason() + "\n\n").withStyle(ChatFormatting.GRAY));

		if (task.hasPrice()) {
			text.append(field("Post at", String.format("%.1f", task.price())));
		}

		text.append(field("Units", task.orderSplit().equals(String.valueOf(task.units()))
				? String.valueOf(task.units())
				: task.units() + " as " + task.orderSplit()));

		if (task.profit() > 0.0d) {
			text.append(field(task.kind() == NpcWorklist.Kind.CLAIM ? "Already made" : "Worth",
					Coins.format(task.profit())));
		}

		if (task.capital() > 0L) {
			text.append(field(task.kind() == NpcWorklist.Kind.CANCEL ? "Frees" : "Ties up",
					Coins.format(task.capital())));
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
