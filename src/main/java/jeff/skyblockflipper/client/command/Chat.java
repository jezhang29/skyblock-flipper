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
