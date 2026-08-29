package jeff.skyblockflipper.client;

import jeff.skyblockflipper.core.api.AuctionScanSnapshot;
import jeff.skyblockflipper.core.config.RecoverySettings;
import jeff.skyblockflipper.core.recovery.RecoveryAlertGate;
import jeff.skyblockflipper.core.recovery.RecoveryOpportunity;
import jeff.skyblockflipper.core.text.Coins;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.sounds.SoundEvents;

import java.time.Duration;
import java.time.Instant;

/** Opt-in client-tick delivery of current recovery opportunities. */
public final class RecoveryAlertService {
	private static final int MAX_ALERTS_PER_SNAPSHOT = 3;
	private static final RecoveryAlertGate GATE =
			new RecoveryAlertGate(512, Duration.ofMinutes(30));
	private static final SystemToast.SystemToastId TOAST_ID = new SystemToast.SystemToastId();
	private static long handledRevision = -1L;

	private RecoveryAlertService() {}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
	}

	public static void invalidate() {
		handledRevision = -1L;
	}

	private static void tick() {
		AuctionScanSnapshot snapshot = MarketDataService.data().auctionScan();
		if (snapshot.recoveryRevision() == handledRevision) {
			return;
		}
		handledRevision = snapshot.recoveryRevision();
		RecoverySettings settings = SkyblockFlipperClient.config().recoverySettings();
		Instant now = Instant.now();
		int announced = 0;
		for (RecoveryOpportunity opportunity : RecoveryFeed.current()) {
			if (announced >= MAX_ALERTS_PER_SNAPSHOT) {
				break;
			}
			if (GATE.claim(opportunity, snapshot, settings, now)) {
				announce(opportunity, settings);
				announced++;
			}
		}
		if (announced > 0 && settings.sound()) {
			Minecraft.getInstance().getSoundManager().play(
					SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING, 1.8f));
		}
	}

	private static void announce(RecoveryOpportunity opportunity, RecoverySettings settings) {
		Minecraft client = Minecraft.getInstance();
		if (settings.chatNotifications() && client.player != null) {
			MutableComponent message = Component.literal("[Flipper] ").withStyle(ChatFormatting.GOLD)
					.append(Component.literal("Recovery: " + opportunity.displayName() + "  "
							+ Coins.format(opportunity.expectedProfit()))
							.withStyle(style -> style.withColor(ChatFormatting.GREEN)
									.withClickEvent(new ClickEvent.CopyToClipboard(
											opportunity.auctionUuid()))))
					.append(Component.literal(" [copies UUID; no purchase]")
							.withStyle(ChatFormatting.DARK_GRAY));
			client.player.sendSystemMessage(message);
		}
		if (settings.toastNotifications()) {
			SystemToast.addOrUpdate(client.gui.toastManager(), TOAST_ID,
					Component.literal("Recovery value: " + opportunity.displayName())
							.withStyle(ChatFormatting.GOLD),
					Component.literal(Coins.format(opportunity.expectedProfit())
							+ " after buffer and fees"));
		}
	}
}
