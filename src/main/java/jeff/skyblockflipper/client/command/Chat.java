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

import jeff.skyblockflipper.core.text.Coins;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

/** Shared chat formatting helpers. */
final class Chat {
	private Chat() {
	}

	static Component prefixed(Component message) {
		return Component.literal("[Flipper] ").withStyle(ChatFormatting.GOLD).append(message);
	}

	/** Abbreviation lives in {@code core} so the HUD and chat never disagree about a number. */
	static String coins(long amount) {
		return Coins.format(amount);
	}
}
