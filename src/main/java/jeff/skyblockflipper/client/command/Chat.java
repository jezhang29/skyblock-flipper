package jeff.skyblockflipper.client.command;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

/** Shared chat formatting helpers. */
final class Chat {
	private Chat() {
	}

	static Component prefixed(Component message) {
		return Component.literal("[Flipper] ").withStyle(ChatFormatting.GOLD).append(message);
	}

	/** Renders coin amounts the way Skyblock players read them: 12.5M, 340k, 900. */
	static String coins(long amount) {
		if (amount >= 1_000_000_000L) {
			return String.format("%.2fB", amount / 1_000_000_000.0d);
		} else if (amount >= 1_000_000L) {
			return String.format("%.2fM", amount / 1_000_000.0d);
		} else if (amount >= 1_000L) {
			return String.format("%.1fk", amount / 1_000.0d);
		}

		return String.valueOf(amount);
	}
}
