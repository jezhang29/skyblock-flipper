package jeff.skyblockflipper.client.hud;

import jeff.skyblockflipper.SkyblockFlipper;
import jeff.skyblockflipper.client.CandidateFeed;
import jeff.skyblockflipper.client.MarketDataService;
import jeff.skyblockflipper.client.SkyblockFlipperClient;
import jeff.skyblockflipper.core.config.FlipperConfig;
import jeff.skyblockflipper.core.config.HudAnchor;
import jeff.skyblockflipper.core.strategy.FlipCandidate;
import jeff.skyblockflipper.core.text.Coins;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;

import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.List;

/**
 * A small always-on panel listing the best few candidates.
 *
 * <p>The point of the HUD is glanceability while you are doing something else, so it carries only
 * the name and the ranking number. Anything you would have to stop and read - the fee breakdown,
 * the steps, the risks - belongs in {@code /flip}, where there is room to show it honestly.
 *
 * <p>Registered before the chat element so it draws under chat rather than over the top of it, and
 * so it inherits the vanilla hidden-HUD condition: F1 hides this too.
 */
public final class FlipHud implements HudElement {
	/** Wide enough to read against terrain, dim enough not to fight with the game. */
	private static final int BACKDROP_COLOR = 0x90000000;
	private static final int TEXT_COLOR = 0xFFFFFFFF;

	private static final int PADDING = 3;
	private static final int LINE_SPACING = 2;

	/** Long item names would otherwise stretch the panel across half the screen. */
	private static final int MAX_NAME_CHARS = 22;

	private FlipHud() {
	}

	public static void register() {
		HudElementRegistry.attachElementBefore(
				VanillaHudElements.CHAT, SkyblockFlipper.id("flip_candidates"), new FlipHud());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		FlipperConfig config = SkyblockFlipperClient.config();

		if (!config.hudEnabled) {
			return;
		}

		List<Component> lines = lines(config);

		if (lines.isEmpty()) {
			return;
		}

		Font font = Minecraft.getInstance().font;
		int lineHeight = font.lineHeight + LINE_SPACING;
		int contentWidth = 0;

		for (Component line : lines) {
			contentWidth = Math.max(contentWidth, font.width(line));
		}

		int panelWidth = contentWidth + PADDING * 2;
		int panelHeight = lines.size() * lineHeight - LINE_SPACING + PADDING * 2;

		HudAnchor anchor = config.anchor();
		int left = anchor.isRight()
				? graphics.guiWidth() - config.hudMarginX - panelWidth
				: config.hudMarginX;
		int top = anchor.isBottom()
				? graphics.guiHeight() - config.hudMarginY - panelHeight
				: config.hudMarginY;

		graphics.fill(left, top, left + panelWidth, top + panelHeight, BACKDROP_COLOR);

		int y = top + PADDING;

		for (Component line : lines) {
			graphics.text(font, line, left + PADDING, y, TEXT_COLOR);
			y += lineHeight;
		}
	}

	private static List<Component> lines(FlipperConfig config) {
		List<FlipCandidate> candidates = CandidateFeed.top();

		if (candidates.isEmpty()) {
			// Silence would be indistinguishable from a broken HUD, so say why there is nothing
			// to show - but only while there is a reason to expect something later.
			return MarketDataService.isRunning()
					? List.of(Component.literal("Flipper: waiting for market data")
							.withStyle(ChatFormatting.DARK_GRAY))
					: List.of();
		}

		List<Component> lines = new ArrayList<>();
		lines.add(Component.literal("Flips").withStyle(ChatFormatting.GOLD));

		int rank = 1;

		for (FlipCandidate candidate : candidates.subList(0, Math.min(config.hudLines, candidates.size()))) {
			lines.add(summary(rank++, candidate));
		}

		return lines;
	}

	private static MutableComponent summary(int rank, FlipCandidate candidate) {
		return Component.literal(rank + ". ").withStyle(ChatFormatting.DARK_GRAY)
				.append(Component.literal(shorten(candidate.displayName())).withStyle(ChatFormatting.AQUA))
				.append(Component.literal(" " + Coins.format(candidate.profitPerHour()) + "/hr")
						.withStyle(ChatFormatting.GREEN));
	}

	private static String shorten(String name) {
		return name.length() <= MAX_NAME_CHARS ? name : name.substring(0, MAX_NAME_CHARS - 1) + "…";
	}
}
