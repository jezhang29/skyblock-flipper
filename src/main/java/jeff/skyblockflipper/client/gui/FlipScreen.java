package jeff.skyblockflipper.client.gui;

import jeff.skyblockflipper.SkyblockFlipper;
import jeff.skyblockflipper.client.CandidateFeed;
import jeff.skyblockflipper.client.LedgerService;
import jeff.skyblockflipper.client.MarketDataService;
import jeff.skyblockflipper.client.SkyblockFlipperClient;
import jeff.skyblockflipper.core.api.MarketData;
import jeff.skyblockflipper.core.ledger.LedgerEntry;
import jeff.skyblockflipper.core.ledger.LedgerStats;
import jeff.skyblockflipper.core.strategy.FlipCandidate;
import jeff.skyblockflipper.core.strategy.StrategyKind;
import jeff.skyblockflipper.core.text.Coins;
import jeff.skyblockflipper.core.valuation.PriceTrend;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.util.List;

/**
 * The whole ranking on one screen, with room for the reasoning.
 *
 * <p>Chat can show ten candidates but only one explanation at a time, sorted one way, with the
 * steps hidden behind a hover. This exists for the other half of the job: comparing candidates
 * against each other, and reading why one is ranked where it is without losing the list.
 *
 * <p>{@code /flip} keeps working unchanged. This is an addition, not a replacement - chat is
 * scriptable, survives being mid-trade with a Hypixel menu open, and is where closing a position
 * still lives.
 *
 * <p><b>Rendering is 26.2's extract-render-state pipeline.</b> There is no {@code GuiGraphics} in
 * this version; screens fill a {@link GuiGraphicsExtractor} in
 * {@code extractRenderState}, which is also how {@code FlipHud} draws.
 */
public final class FlipScreen extends Screen {
	private static final int PANEL = 0xB0000000;
	private static final int PANEL_EDGE = 0xFF404040;
	private static final int TAB_ACTIVE = 0xFF3A3A3A;
	private static final int TAB_IDLE = 0x60000000;
	private static final int TEXT = 0xFFFFFFFF;
	private static final int TEXT_DIM = 0xFFAAAAAA;
	private static final int TEXT_WARN = 0xFFFFAA00;
	private static final int TEXT_GOOD = 0xFF55FF55;

	private static final int MARGIN = 8;
	private static final int TAB_HEIGHT = 16;
	private static final int FOOTER_HEIGHT = 22;

	/** Deep enough that scrolling has somewhere to go without ranking the entire book. */
	private static final int RANK_DEPTH = 60;

	private enum Tab {
		ALL("All", null),
		BAZAAR("Bazaar", StrategyKind.BAZAAR_SPREAD),
		NPC("NPC", StrategyKind.NPC_FLIP),
		SNIPE("Snipe", StrategyKind.AUCTION_VALUE),
		LEDGER("Ledger", null);

		private final String label;
		private final StrategyKind kind;

		Tab(String label, StrategyKind kind) {
			this.label = label;
			this.kind = kind;
		}
	}

	private final CandidateTable table = new CandidateTable();

	private Tab tab = Tab.ALL;
	private long renderedRevision = -1L;
	private String notice = "";

	private Button takeButton;
	private Button copyButton;

	public FlipScreen() {
		super(Component.literal("Skyblock Flipper"));
	}

	@Override
	protected void init() {
		int listWidth = (int) (width * 0.58d);
		int top = MARGIN + TAB_HEIGHT + 4;
		int listHeight = height - top - FOOTER_HEIGHT - MARGIN;

		table.setBounds(MARGIN, top, listWidth, listHeight);

		int buttonY = height - FOOTER_HEIGHT - MARGIN + 4;

		takeButton = addRenderableWidget(Button.builder(
						Component.literal("Take"), button -> takeSelected())
				.bounds(MARGIN, buttonY, 54, 16)
				.build());

		copyButton = addRenderableWidget(Button.builder(
						Component.literal("Copy name"), button -> copySelected())
				.bounds(MARGIN + 58, buttonY, 74, 16)
				.build());

		addRenderableWidget(Button.builder(Component.literal("Close"), button -> onClose())
				.bounds(width - MARGIN - 54, buttonY, 54, 16)
				.build());

		refresh(true);
	}

	/**
	 * Re-ranks only when the book has actually moved.
	 *
	 * <p>The same rule {@code CandidateFeed} enforces for the HUD, and for the same reason: this
	 * draws every frame, and ranking a couple of thousand order books to redraw an unchanged list
	 * is pure waste. Candidates cannot change while the revision has not.
	 */
	@Override
	public void tick() {
		refresh(false);
	}

	private void refresh(boolean force) {
		MarketData data = MarketDataService.data();
		long revision = data.bazaarRevision();

		if (!force && revision == renderedRevision) {
			return;
		}

		renderedRevision = revision;

		if (tab != Tab.LEDGER) {
			table.setCandidates(CandidateFeed.rank(tab.kind, RANK_DEPTH), data.trends());
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
			float partialTick) {
		extractTransparentBackground(graphics);
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);

		renderTabs(graphics, mouseX, mouseY);

		int top = MARGIN + TAB_HEIGHT + 4;
		int listWidth = (int) (width * 0.58d);
		int listHeight = height - top - FOOTER_HEIGHT - MARGIN;
		int detailX = MARGIN + listWidth + 6;
		int detailWidth = width - detailX - MARGIN;

		panel(graphics, MARGIN, top, listWidth, listHeight);
		panel(graphics, detailX, top, detailWidth, listHeight);

		if (tab == Tab.LEDGER) {
			renderLedger(graphics, MARGIN + 6, top + 6, listWidth - 12);
			renderLedgerStats(graphics, detailX + 6, top + 6, detailWidth - 12);
		} else {
			table.render(graphics, font, mouseX, mouseY);
			renderDetail(graphics, detailX + 6, top + 6, detailWidth - 12);
		}

		renderFooter(graphics);
	}

	private void renderTabs(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		int tabX = MARGIN;

		for (Tab candidate : Tab.values()) {
			int tabWidth = font.width(Component.literal(candidate.label)) + 14;
			boolean hovered = mouseX >= tabX && mouseX < tabX + tabWidth
					&& mouseY >= MARGIN && mouseY < MARGIN + TAB_HEIGHT;

			graphics.fill(tabX, MARGIN, tabX + tabWidth, MARGIN + TAB_HEIGHT,
					candidate == tab ? TAB_ACTIVE : TAB_IDLE);

			graphics.text(font, Component.literal(candidate.label),
					tabX + 7, MARGIN + (TAB_HEIGHT - font.lineHeight) / 2 + 1,
					candidate == tab || hovered ? TEXT : TEXT_DIM);

			tabX += tabWidth + 2;
		}
	}

	/**
	 * The reasoning behind the selected candidate.
	 *
	 * <p>Renders the {@code steps} and {@code risks} the strategy already produced, which is the
	 * same text {@code /flip}'s hover tooltip shows. Two views of one flip must never be able to
	 * disagree, so neither of them writes its own explanation.
	 */
	private void renderDetail(GuiGraphicsExtractor graphics, int x, int y, int listWidth) {
		FlipCandidate candidate = table.selection();

		if (candidate == null) {
			graphics.textWithWordWrap(font, Component.literal(table.isEmpty()
							? "No candidates clear the fee stack right now. That is a normal answer."
							: "Select a row to see the plan and the risks.")
					.withStyle(ChatFormatting.DARK_GRAY), x, y, listWidth, TEXT_DIM);
			return;
		}

		int cursor = y;

		graphics.text(font, Component.literal(candidate.displayName()), x, cursor, 0xFF55FFFF);
		cursor += font.lineHeight + 1;

		graphics.text(font, Component.literal(
						candidate.kind().label() + " - paid for " + candidate.kind().edge()),
				x, cursor, TEXT_DIM);
		cursor += font.lineHeight + 5;

		cursor = field(graphics, x, cursor, "Buy", String.format("%.1f", candidate.unitBuyPrice()));
		cursor = field(graphics, x, cursor, "Sell", String.format("%.1f", candidate.unitSellPrice()));
		cursor = field(graphics, x, cursor, "Net/unit",
				String.format("%.1f after fees", candidate.unitNetProfit()));
		cursor = field(graphics, x, cursor, "Units", String.valueOf(candidate.units()));
		cursor = field(graphics, x, cursor, "Capital", Coins.format(candidate.capitalRequired()));
		cursor = field(graphics, x, cursor, "Total", Coins.format(candidate.totalNetProfit()));
		cursor = field(graphics, x, cursor, "Per hour", Coins.format(candidate.profitPerHour()));
		cursor = field(graphics, x, cursor, "Confidence",
				String.format("%.2f", candidate.confidence()));

		PriceTrend trend = MarketDataService.data().trends().trendFor(candidate.itemId()).orElse(null);

		if (trend != null) {
			cursor = field(graphics, x, cursor, "Trend",
					String.format("%+.1f%% over %dh", trend.drift() * 100.0d,
							MarketDataService.data().trends().window().toHours()));
		}

		cursor += 4;
		cursor = section(graphics, x, cursor, listWidth, "Steps", candidate.steps(), TEXT);
		cursor += 2;
		section(graphics, x, cursor, listWidth, "Risks", candidate.risks(), TEXT_WARN);
	}

	private int section(GuiGraphicsExtractor graphics, int x, int y, int wrapWidth, String heading,
			List<String> lines, int colour) {
		if (lines.isEmpty()) {
			return y;
		}

		graphics.text(font, Component.literal(heading).withStyle(ChatFormatting.GOLD), x, y, TEXT);
		int cursor = y + font.lineHeight + 2;

		for (String line : lines) {
			Component text = Component.literal("- " + line);
			graphics.textWithWordWrap(font, text, x, cursor, wrapWidth, colour);
			// Ask the font how tall it actually wrapped to; guessing from the string width puts
			// every subsequent entry in the wrong place as soon as one of them wraps.
			cursor += font.wordWrapHeight(text, wrapWidth) + 2;
		}

		return cursor;
	}

	private int field(GuiGraphicsExtractor graphics, int x, int y, String key, String value) {
		graphics.text(font, Component.literal(key), x, y, TEXT_DIM);
		graphics.text(font, Component.literal(value), x + 66, y, TEXT);
		return y + font.lineHeight + 1;
	}

	private void renderLedger(GuiGraphicsExtractor graphics, int x, int y, int panelWidth) {
		List<LedgerEntry> open = LedgerService.ledger().openEntries();

		graphics.text(font, Component.literal("Open positions").withStyle(ChatFormatting.GOLD),
				x, y, TEXT);
		int cursor = y + font.lineHeight + 4;

		if (open.isEmpty()) {
			graphics.text(font, Component.literal("Nothing open. Take a flip to start tracking."),
					x, cursor, TEXT_DIM);
			return;
		}

		for (LedgerEntry entry : open) {
			graphics.text(font, Component.literal(entry.id()), x, cursor, TEXT_WARN);
			graphics.text(font, Component.literal(entry.displayName()), x + 60, cursor, TEXT);
			graphics.text(font, Component.literal(Coins.format(entry.capital())),
					x + panelWidth - 50, cursor, TEXT_DIM);
			cursor += font.lineHeight + 2;
		}

		cursor += 4;
		graphics.text(font, Component.literal(
						"Committed: " + Coins.format(LedgerService.ledger().committedCapital())),
				x, cursor, TEXT_DIM);
	}

	private void renderLedgerStats(GuiGraphicsExtractor graphics, int x, int y, int panelWidth) {
		LedgerStats stats = LedgerService.ledger().stats(null);

		graphics.text(font, Component.literal("Performance").withStyle(ChatFormatting.GOLD),
				x, y, TEXT);
		int cursor = y + font.lineHeight + 4;

		if (!stats.isMeaningful()) {
			graphics.textWithWordWrap(font, Component.literal(
							"Not enough closed flips yet. Capture rate needs at least "
									+ LedgerStats.MIN_MEANINGFUL_SAMPLES + " to mean anything."),
					x, cursor, panelWidth, TEXT_DIM);
			return;
		}

		cursor = field(graphics, x, cursor, "Capture",
				stats.captureRate().isPresent()
						? String.format("%.0f%%", stats.captureRate().getAsDouble() * 100.0d)
						: "n/a");
		field(graphics, x, cursor, "Fill",
				stats.fillRate().isPresent()
						? String.format("%.0f%%", stats.fillRate().getAsDouble() * 100.0d)
						: "n/a");
	}

	private void renderFooter(GuiGraphicsExtractor graphics) {
		int y = height - MARGIN - font.lineHeight;

		String hint = notice.isEmpty()
				? "Click a column to sort, a row to select. " + FlipKeybinds.boundKeyName()
						+ " or Esc to close. Closing a position is /flip close."
				: notice;

		graphics.text(font, Component.literal(hint),
				MARGIN + 140, y, notice.isEmpty() ? TEXT_DIM : TEXT_GOOD);
	}

	private void panel(GuiGraphicsExtractor graphics, int x, int y, int panelWidth, int panelHeight) {
		graphics.fill(x, y, x + panelWidth, y + panelHeight, PANEL);
		graphics.outline(x, y, panelWidth, panelHeight, PANEL_EDGE);
	}

	private void takeSelected() {
		FlipCandidate candidate = table.selection();

		if (candidate == null) {
			notice = "Select a row first.";
			return;
		}

		try {
			// Exactly the path /flip take uses, so both routes write one ledger with one format.
			LedgerEntry entry = LedgerService.ledger().open(candidate, System.currentTimeMillis());
			notice = "Took " + entry.displayName() + " as " + entry.id()
					+ " - close it with /flip close " + entry.id() + " <units> <price>";
		} catch (IOException e) {
			SkyblockFlipper.LOGGER.error("Ledger write failed", e);
			notice = "Could not write the ledger - see the log.";
		}
	}

	private void copySelected() {
		FlipCandidate candidate = table.selection();

		if (candidate == null) {
			notice = "Select a row first.";
			return;
		}

		minecraft.keyboardHandler.setClipboard(candidate.displayName());
		notice = "Copied \"" + candidate.displayName() + "\" - paste it into the bazaar search.";
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
		if (super.mouseClicked(event, doubled)) {
			return true;
		}

		if (tabClicked(event.x(), event.y())) {
			return true;
		}

		return tab != Tab.LEDGER && table.mouseClicked(event.x(), event.y());
	}

	private boolean tabClicked(double mouseX, double mouseY) {
		if (mouseY < MARGIN || mouseY >= MARGIN + TAB_HEIGHT) {
			return false;
		}

		int tabX = MARGIN;

		for (Tab candidate : Tab.values()) {
			int tabWidth = font.width(Component.literal(candidate.label)) + 14;

			if (mouseX >= tabX && mouseX < tabX + tabWidth) {
				tab = candidate;
				notice = "";
				// Forced: the book has not moved, but the question being asked of it has.
				refresh(true);
				return true;
			}

			tabX += tabWidth + 2;
		}

		return false;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (tab != Tab.LEDGER && table.mouseScrolled(scrollY)) {
			return true;
		}

		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		// Key mappings do not tick while a screen is up, so the open key has to be recognised
		// here for it to work as a toggle. Read from the live binding, so rebinding still closes.
		if (FlipKeybinds.isOpenKey(event.key())) {
			onClose();
			return true;
		}

		if (tab != Tab.LEDGER) {
			if (event.key() == GLFW.GLFW_KEY_DOWN) {
				table.moveSelection(1);
				return true;
			}

			if (event.key() == GLFW.GLFW_KEY_UP) {
				table.moveSelection(-1);
				return true;
			}
		}

		return super.keyPressed(event);
	}

	/** The world keeps running behind this; it is a reference panel, not a menu. */
	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
