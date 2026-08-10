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
import jeff.skyblockflipper.core.text.Guide;
import jeff.skyblockflipper.core.text.Waits;
import jeff.skyblockflipper.core.valuation.PriceTrend;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.util.ArrayList;
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
 *
 * <p><b>Everything is drawn inside a scaled coordinate space.</b> At GUI scale 6 - a normal choice,
 * and the one this was reported against - a 1080p window is only about 330 scaled pixels wide,
 * which is not enough room for a five-column table beside a panel of prose. So the screen picks a
 * zoom factor, lays itself out in the larger virtual space that produces, and scales the drawing
 * down to fit. The alternative was asking the player to change their GUI scale for one screen,
 * which is not a fix. Mouse coordinates are divided by the same factor on the way in, and the
 * footer buttons are drawn by {@link TextButton} rather than being vanilla widgets, because a
 * widget renders and hit-tests outside this transform and would not line up with anything.
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
	private static final int TEXT_NOTE = 0xFF9FD4FF;
	private static final int ROW_SELECTED = 0x40FFD700;

	private static final int MARGIN = 8;
	private static final int TAB_HEIGHT = 16;
	private static final int BUTTON_HEIGHT = 16;
	private static final int PANEL_PAD = 6;

	/** Layout space the screen wants before it starts shrinking itself to get it. */
	private static final int TARGET_WIDTH = 480;
	private static final int TARGET_HEIGHT = 280;

	/** Past this the text stops being readable, so the layout has to cope instead. */
	private static final float MIN_ZOOM = 0.5f;

	/** Deep enough that scrolling has somewhere to go without ranking the entire book. */
	private static final int RANK_DEPTH = 60;

	private enum Tab {
		ALL("All", null),
		BAZAAR("Bazaar", StrategyKind.BAZAAR_SPREAD),
		NPC("NPC", StrategyKind.NPC_FLIP),
		SNIPE("Snipe", StrategyKind.AUCTION_VALUE),
		LEDGER("Ledger", null),
		GUIDE("Guide", null);

		private final String label;
		private final StrategyKind kind;

		Tab(String label, StrategyKind kind) {
			this.label = label;
			this.kind = kind;
		}

		boolean showsCandidates() {
			return this != LEDGER && this != GUIDE;
		}

		/**
		 * The tab the configured strategy filter names, or {@link #ALL} when it names none.
		 *
		 * <p>The screen used to open on All unconditionally, which meant a player working one market
		 * clicked past two lists they had already said they did not want.
		 */
		static Tab forFilter(StrategyKind filter) {
			if (filter == null) {
				return ALL;
			}

			for (Tab candidate : values()) {
				if (candidate.kind == filter) {
					return candidate;
				}
			}

			return ALL;
		}
	}

	private final CandidateTable table = new CandidateTable();
	private final Scroller detailScroll = new Scroller();
	private final Scroller sideScroll = new Scroller();

	private final TextButton takeButton = new TextButton("Take", this::takeSelected);
	private final TextButton copyButton = new TextButton("Copy name", this::copySelected);
	private final TextButton closeButton = new TextButton("Close", this::onClose);

	/** Shares the Take button's slot: the tab showing one never shows the other. */
	private final TextButton abandonButton = new TextButton("Abandon", this::abandonSelected);

	/** Beside Abandon, and only on the Ledger tab. */
	private final TextButton forgetButton = new TextButton("Forget", this::forgetSelected);

	/** Only laid out and drawn when Cloth Config is installed - see {@link Settings}. */
	private final TextButton settingsButton = new TextButton("Settings", this::openSettings);

	private Tab tab = Tab.forFilter(SkyblockFlipperClient.config().filteredKind());
	private long renderedRevision = -1L;
	private String notice = "";

	/** Whatever the detail panel was last drawn for, so its scroll can reset when that changes. */
	private FlipCandidate detailShown;

	/** Ledger tab: the selected open position. */
	private String selectedPosition = "";

	/**
	 * The destructive action waiting on a second click, as {@code <button>:<position id>}.
	 *
	 * <p>One field rather than one per button, so arming either one disarms the other: two live
	 * confirmations would let a second click land on a button the player did not arm.
	 */
	private String pendingAction = "";

	/** Where the open positions were last drawn, so a click can be turned back into one of them. */
	private final List<String> positionRows = new ArrayList<>();
	private int positionRowsTop;
	private int positionRowHeight = 1;

	private float zoom = 1.0f;
	private int viewWidth;
	private int viewHeight;

	public FlipScreen() {
		super(Component.literal("Skyblock Flipper"));
	}

	@Override
	protected void init() {
		zoom = resolveZoom();
		viewWidth = Math.round(width / zoom);
		viewHeight = Math.round(height / zoom);

		table.setBounds(MARGIN, contentTop(), listWidth(), contentHeight());

		int buttonY = viewHeight - MARGIN - BUTTON_HEIGHT;
		int takeWidth = takeButton.preferredWidth(font);
		int copyWidth = copyButton.preferredWidth(font);

		takeButton.setBounds(MARGIN, buttonY, takeWidth, BUTTON_HEIGHT);
		copyButton.setBounds(MARGIN + takeWidth + 4, buttonY, copyWidth, BUTTON_HEIGHT);
		int abandonWidth = abandonButton.preferredWidth(font);
		abandonButton.setBounds(MARGIN, buttonY, abandonWidth, BUTTON_HEIGHT);
		forgetButton.setBounds(MARGIN + abandonWidth + 4, buttonY,
				forgetButton.preferredWidth(font), BUTTON_HEIGHT);

		int closeWidth = closeButton.preferredWidth(font);
		closeButton.setBounds(viewWidth - MARGIN - closeWidth, buttonY, closeWidth, BUTTON_HEIGHT);

		if (Settings.available()) {
			int settingsWidth = settingsButton.preferredWidth(font);
			settingsButton.setBounds(viewWidth - MARGIN - closeWidth - 4 - settingsWidth, buttonY,
					settingsWidth, BUTTON_HEIGHT);
		}

		refresh(true);
	}

	/**
	 * How much to shrink the layout by so it has room to breathe.
	 *
	 * <p>A configured value wins outright; otherwise this is whatever it takes to get
	 * {@link #TARGET_WIDTH} by {@link #TARGET_HEIGHT} of layout space out of the window, and never
	 * an enlargement - at GUI scale 2 there is already plenty of room and blowing the screen up to
	 * fill it would just make a small table enormous.
	 */
	private float resolveZoom() {
		double configured = SkyblockFlipperClient.config().guiZoom;

		if (configured > 0.0d) {
			return (float) configured;
		}

		float needed = Math.min(width / (float) TARGET_WIDTH, height / (float) TARGET_HEIGHT);
		return Math.clamp(needed, MIN_ZOOM, 1.0f);
	}

	private int contentTop() {
		return MARGIN + TAB_HEIGHT + 4;
	}

	private int contentHeight() {
		return viewHeight - contentTop() - footerHeight() - MARGIN;
	}

	/**
	 * Two rows: a line of status text, then the buttons.
	 *
	 * <p>They used to share one row, which meant the hint ran underneath the Close button and a
	 * notice as long as "Took Enchanted Melon as a4f1 - close it with /flip close ..." had nowhere
	 * to go at all. Measured from the font rather than fixed, because the font is the thing that
	 * decides how tall a line of text is.
	 */
	private int footerHeight() {
		return BUTTON_HEIGHT + font.lineHeight + 8;
	}

	private int listWidth() {
		return (int) (viewWidth * 0.55d);
	}

	private int detailX() {
		return MARGIN + listWidth() + 6;
	}

	private int detailWidth() {
		return viewWidth - detailX() - MARGIN;
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

		if (tab.showsCandidates()) {
			table.setCandidates(CandidateFeed.rank(tab.kind, RANK_DEPTH), data.trends());
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
			float partialTick) {
		extractTransparentBackground(graphics);
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);

		int vx = virtual(mouseX);
		int vy = virtual(mouseY);

		graphics.pose().pushMatrix();
		graphics.pose().scale(zoom, zoom);

		renderTabs(graphics, vx, vy);

		int top = contentTop();
		int panelHeight = contentHeight();

		switch (tab) {
			case GUIDE -> {
				panel(graphics, MARGIN, top, viewWidth - 2 * MARGIN, panelHeight);
				renderGuide(graphics, MARGIN, top, viewWidth - 2 * MARGIN, panelHeight);
			}
			case LEDGER -> {
				panel(graphics, MARGIN, top, listWidth(), panelHeight);
				panel(graphics, detailX(), top, detailWidth(), panelHeight);
				renderLedger(graphics, MARGIN, top, listWidth(), panelHeight);
				renderLedgerStats(graphics, detailX() + PANEL_PAD, top + PANEL_PAD,
						detailWidth() - 2 * PANEL_PAD);
			}
			default -> {
				panel(graphics, MARGIN, top, listWidth(), panelHeight);
				panel(graphics, detailX(), top, detailWidth(), panelHeight);
				table.render(graphics, font, vx, vy);
				renderDetail(graphics, detailX(), top, detailWidth(), panelHeight);
			}
		}

		renderFooter(graphics, vx, vy);

		// Last, so it sits over the detail panel it will usually overlap.
		if (tab.showsCandidates()) {
			table.renderHoverName(graphics, font, vx, vy, viewWidth, viewHeight);
		}

		graphics.pose().popMatrix();
	}

	private int virtual(int screenCoordinate) {
		return Math.round(screenCoordinate / zoom);
	}

	private void renderTabs(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		int tabX = MARGIN;

		for (Tab candidate : Tab.values()) {
			int tabWidth = tabWidth(candidate);
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

	private int tabWidth(Tab candidate) {
		return font.width(Component.literal(candidate.label)) + 14;
	}

	/**
	 * The reasoning behind the selected candidate, scrollable because it does not fit.
	 *
	 * <p>Renders the {@code steps}, {@code risks} and {@code notes} the strategy already produced,
	 * which is the same text {@code /flip}'s hover tooltip shows. Two views of one flip must never
	 * be able to disagree, so neither of them writes its own explanation. That also means the panel
	 * cannot assume a length: a bazaar candidate with three risks and an NPC one that has to
	 * explain which of two routes it picked are not the same height, and the panel used to simply
	 * draw the overflow across the footer.
	 */
	private void renderDetail(GuiGraphicsExtractor graphics, int x, int y, int panelWidth,
			int panelHeight) {
		FlipCandidate candidate = table.selection();

		if (candidate != detailShown) {
			detailShown = candidate;
			detailScroll.reset();
		}

		int contentWidth = panelWidth - 2 * PANEL_PAD;
		int startY = y + PANEL_PAD - detailScroll.offset();

		graphics.enableScissor(x, y, x + panelWidth, y + panelHeight);
		int endY = drawDetail(graphics, candidate, x + PANEL_PAD, startY, contentWidth);
		graphics.disableScissor();

		detailScroll.measured(endY - startY + PANEL_PAD, panelHeight - PANEL_PAD);
		detailScroll.renderBar(graphics, x, y, panelWidth, panelHeight);
	}

	/** @return the y the content finished at, so the caller can work out how far it overflowed */
	private int drawDetail(GuiGraphicsExtractor graphics, FlipCandidate candidate, int x, int y,
			int wrapWidth) {
		if (candidate == null) {
			Component message = Component.literal(table.isEmpty()
					? "No candidates clear the fee stack right now. That is a normal answer."
					: "Select a row to see the plan and the risks.");

			graphics.textWithWordWrap(font, message, x, y, wrapWidth, TEXT_DIM);
			return y + font.wordWrapHeight(message, wrapWidth);
		}

		int cursor = y;

		Component name = Component.literal(candidate.displayName());
		graphics.textWithWordWrap(font, name, x, cursor, wrapWidth, 0xFF55FFFF);
		cursor += font.wordWrapHeight(name, wrapWidth) + 1;

		graphics.text(font, Component.literal(
						candidate.kind().label() + " - paid for " + candidate.kind().edge()),
				x, cursor, TEXT_DIM);
		cursor += font.lineHeight + 5;

		cursor = field(graphics, x, cursor, wrapWidth, "Buy", String.format("%.1f", candidate.unitBuyPrice()));
		cursor = field(graphics, x, cursor, wrapWidth, "Sell", String.format("%.1f", candidate.unitSellPrice()));
		cursor = field(graphics, x, cursor, wrapWidth, "Net/unit",
				String.format("%.1f after fees", candidate.unitNetProfit()));
		cursor = field(graphics, x, cursor, wrapWidth, "Units", String.valueOf(candidate.units()));
		cursor = field(graphics, x, cursor, wrapWidth, "Capital", Coins.format(candidate.capitalRequired()));
		cursor = field(graphics, x, cursor, wrapWidth, "Total", Coins.format(candidate.totalNetProfit()));
		cursor = field(graphics, x, cursor, wrapWidth, "Per hour", Coins.format(candidate.profitPerHour()));
		cursor = field(graphics, x, cursor, wrapWidth, "ROC",
				String.format("%.0f%%", candidate.returnOnCapital() * 100.0d));
		cursor = field(graphics, x, cursor, wrapWidth, "Confidence",
				String.format("%.2f", candidate.confidence()));

		// Stated as fields rather than left in the prose notes: whether an order fills is the first
		// question about a resting plan, and it was three paragraphs down.
		if (candidate.fill() != null) {
			cursor = field(graphics, x, cursor, wrapWidth, "Fill",
					candidate.timeToTurnOver().map(Waits::format).orElse("never at this size")
							+ (candidate.fillMeasured() ? " (measured)" : " (assumed)"));

			if (candidate.fill().outbidsPerHour() > 0.0d) {
				cursor = field(graphics, x, cursor, wrapWidth, "Outbid",
						String.format("%.1f times an hour", candidate.fill().outbidsPerHour()));
			}
		}

		PriceTrend trend = MarketDataService.data().trends().trendFor(candidate.itemId()).orElse(null);

		if (trend != null) {
			cursor = field(graphics, x, cursor, wrapWidth, "Trend",
					String.format("%+.1f%% over %dh", trend.drift() * 100.0d,
							MarketDataService.data().trends().window().toHours()));
		}

		cursor += 4;
		cursor = section(graphics, x, cursor, wrapWidth, "Notes", candidate.notes(), TEXT_NOTE);
		cursor += 2;
		cursor = section(graphics, x, cursor, wrapWidth, "Steps", candidate.steps(), TEXT);
		cursor += 2;
		return section(graphics, x, cursor, wrapWidth, "Risks", candidate.risks(), TEXT_WARN);
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

	/**
	 * A label and a value on one line, with the value wrapped rather than run off the panel.
	 *
	 * <p>The label column is measured from the widest label rather than fixed at 66 pixels, which
	 * was a number picked at one GUI scale and wrong at every other.
	 */
	private int field(GuiGraphicsExtractor graphics, int x, int y, int wrapWidth, String key,
			String value) {
		int labelWidth = Math.min(font.width(Component.literal("Net/unit")) + 8, wrapWidth / 2);
		Component text = Component.literal(value);

		graphics.text(font, Component.literal(key), x, y, TEXT_DIM);
		graphics.textWithWordWrap(font, text, x + labelWidth, y, wrapWidth - labelWidth, TEXT);

		return y + Math.max(font.lineHeight, font.wordWrapHeight(text, wrapWidth - labelWidth)) + 1;
	}

	/** Everything the mod means by the words it uses, from the same source {@code /flip guide} reads. */
	private void renderGuide(GuiGraphicsExtractor graphics, int x, int y, int panelWidth,
			int panelHeight) {
		int contentWidth = panelWidth - 2 * PANEL_PAD;
		int startY = y + PANEL_PAD - sideScroll.offset();
		int cursor = startY;

		graphics.enableScissor(x, y, x + panelWidth, y + panelHeight);

		for (Guide.Section section : Guide.sections()) {
			graphics.text(font, Component.literal(section.heading())
					.withStyle(ChatFormatting.GOLD), x + PANEL_PAD, cursor, TEXT);
			cursor += font.lineHeight + 3;

			for (Guide.Term term : section.terms()) {
				// Term on its own line, meaning indented under it. Putting both on one wrapped
				// block would read better on a wide panel and hang off the term on a narrow one,
				// and this panel is whatever width the window left it.
				graphics.text(font, Component.literal(term.name()), x + PANEL_PAD, cursor, TEXT);
				cursor += font.lineHeight + 1;

				Component meaning = Component.literal(term.meaning());
				graphics.textWithWordWrap(font, meaning, x + PANEL_PAD + 8, cursor,
						contentWidth - 8, TEXT_DIM);
				cursor += font.wordWrapHeight(meaning, contentWidth - 8) + 4;
			}

			cursor += 5;
		}

		graphics.disableScissor();

		sideScroll.measured(cursor - startY + PANEL_PAD, panelHeight - PANEL_PAD);
		sideScroll.renderBar(graphics, x, y, panelWidth, panelHeight);
	}

	/**
	 * Open positions, selectable so that one taken by mistake can be dropped.
	 *
	 * <p>The rows are laid out here and nowhere else, so the geometry is recorded as it is drawn
	 * rather than recomputed for {@link #positionClicked}. The panel scrolls, and a second copy of
	 * "where the third row is" would only have to agree with this one.
	 */
	private void renderLedger(GuiGraphicsExtractor graphics, int x, int y, int panelWidth,
			int panelHeight) {
		List<LedgerEntry> open = LedgerService.ledger().openEntries();
		int startY = y + PANEL_PAD - sideScroll.offset();
		int cursor = startY;

		positionRows.clear();
		positionRowHeight = font.lineHeight + 2;

		graphics.enableScissor(x, y, x + panelWidth, y + panelHeight);

		graphics.text(font, Component.literal("Open positions").withStyle(ChatFormatting.GOLD),
				x + PANEL_PAD, cursor, TEXT);
		cursor += font.lineHeight + 4;

		if (open.isEmpty()) {
			graphics.text(font, Component.literal("Nothing open. Take a flip to start tracking."),
					x + PANEL_PAD, cursor, TEXT_DIM);
			cursor += font.lineHeight;
		} else {
			int idWidth = font.width(Component.literal("0000")) + 8;
			int capitalWidth = font.width(Component.literal("000.00M")) + 8;
			// How much of the position has come back. Without it an open row says only what was
			// planned, which reads as nothing having happened even while fills are being booked.
			int filledWidth = font.width(Component.literal("0000/0000")) + 8;
			int nameWidth = panelWidth - 2 * PANEL_PAD - idWidth - capitalWidth - filledWidth;

			// Named, because "596/899" beside a coin figure is not self-explanatory.
			Component soldHeading = Component.literal("sold");
			graphics.text(font, soldHeading,
					x + PANEL_PAD + idWidth + nameWidth + filledWidth - 8 - font.width(soldHeading),
					cursor, TEXT_DIM);

			Component capitalHeading = Component.literal("in");
			graphics.text(font, capitalHeading,
					x + panelWidth - PANEL_PAD - font.width(capitalHeading), cursor, TEXT_DIM);
			cursor += font.lineHeight + 2;

			positionRowsTop = cursor;

			for (LedgerEntry entry : open) {
				positionRows.add(entry.id());

				if (entry.id().equals(selectedPosition)) {
					graphics.fill(x + 1, cursor - 1, x + panelWidth - 1,
							cursor + positionRowHeight - 1, ROW_SELECTED);
				}

				graphics.text(font, Component.literal(entry.id()), x + PANEL_PAD, cursor, TEXT_WARN);

				graphics.text(font, Component.literal(Labels.fit(font, entry.displayName(), nameWidth)),
						x + PANEL_PAD + idWidth, cursor, TEXT);

				Component filled = Component.literal(entry.unitsSold() + "/" + entry.units());
				graphics.text(font, filled,
						x + PANEL_PAD + idWidth + nameWidth + filledWidth - 8 - font.width(filled),
						cursor, entry.unitsSold() == 0L ? TEXT_DIM : TEXT_GOOD);

				Component capital = Component.literal(Coins.format(entry.capital()));
				graphics.text(font, capital,
						x + panelWidth - PANEL_PAD - font.width(capital), cursor, TEXT_DIM);
				cursor += positionRowHeight;
			}

			cursor += 4;
			graphics.text(font, Component.literal(
							"Committed: " + Coins.format(LedgerService.ledger().committedCapital())),
					x + PANEL_PAD, cursor, TEXT_DIM);
			cursor += font.lineHeight;
		}

		graphics.disableScissor();

		sideScroll.measured(cursor - startY + PANEL_PAD, panelHeight - PANEL_PAD);
		sideScroll.renderBar(graphics, x, y, panelWidth, panelHeight);
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

		cursor = field(graphics, x, cursor, panelWidth, "Capture",
				stats.captureRate().isPresent()
						? String.format("%.0f%%", stats.captureRate().getAsDouble() * 100.0d)
						: "n/a");
		field(graphics, x, cursor, panelWidth, "Fill",
				stats.fillRate().isPresent()
						? String.format("%.0f%%", stats.fillRate().getAsDouble() * 100.0d)
						: "n/a");
	}

	/**
	 * Buttons, then whatever hint fits above them.
	 *
	 * <p>The hint row is one line tall by construction - a notice that silently grew to two would
	 * push the buttons off the bottom of the screen - so something has to give when the text is
	 * wider than the screen. It used to be given to a scissor, which cut the sentence mid-word and
	 * left "The Guide tab explains every c" on screen. A hint is a list of clauses, so the ones that
	 * do not fit are dropped whole; a notice is one sentence with an id in it, so it is cut with an
	 * ellipsis that at least says it was cut.
	 */
	private void renderFooter(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		boolean hasSelection = tab.showsCandidates() && table.selection() != null;

		if (tab.showsCandidates()) {
			takeButton.render(graphics, font, mouseX, mouseY, hasSelection);
			copyButton.render(graphics, font, mouseX, mouseY, hasSelection);
		} else if (tab == Tab.LEDGER) {
			abandonButton.render(graphics, font, mouseX, mouseY, !selectedPosition.isEmpty());
			forgetButton.render(graphics, font, mouseX, mouseY, !selectedPosition.isEmpty());
		}

		closeButton.render(graphics, font, mouseX, mouseY, true);

		if (Settings.available()) {
			settingsButton.render(graphics, font, mouseX, mouseY, true);
		}

		int y = viewHeight - MARGIN - BUTTON_HEIGHT - 4 - font.lineHeight;
		int available = viewWidth - 2 * MARGIN;

		String hint = notice.isEmpty()
				? Labels.join(font, hintClauses(), available)
				: Labels.fit(font, notice, available);

		graphics.text(font, Component.literal(hint), MARGIN, y,
				notice.isEmpty() ? TEXT_DIM : TEXT_GOOD);
	}

	/**
	 * The idle hint, most useful clause first.
	 *
	 * <p>Ordered by what a player cannot work out for themselves. Which mouse button does what is
	 * guessable; which key closes a screen that is not a menu is not, and neither is the fact that
	 * every column heading has a definition a tab away.
	 */
	private List<String> hintClauses() {
		return List.of(
				FlipKeybinds.boundKeyName() + " or Esc closes.",
				tab == Tab.LEDGER
						? "Abandon keeps a position in the numbers, Forget deletes it."
						: "Click a column to sort, a row to select.",
				"Guide tab defines every column.");
	}

	private void panel(GuiGraphicsExtractor graphics, int x, int y, int panelWidth, int panelHeight) {
		graphics.fill(x, y, x + panelWidth, y + panelHeight, PANEL);
		graphics.outline(x, y, panelWidth, panelHeight, PANEL_EDGE);
	}

	/**
	 * Hands over to the settings screen, which comes back here when it is done.
	 *
	 * <p>Passing {@code this} rather than reopening on close means Cancel returns to the same list,
	 * scrolled and sorted as it was. Vanilla re-runs {@link #init()} on the way back, so a changed
	 * {@code guiZoom} is applied without the player having to reopen anything.
	 */
	private void openSettings() {
		Settings.open(this);
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

	/**
	 * Drops a position that was never going to fill, on a second click. Its units stay in the fill
	 * rate, because an order you gave up on is evidence about how much of a plan is reachable.
	 */
	private void abandonSelected() {
		if (!armed("Abandon")) {
			return;
		}

		try {
			notice = LedgerService.ledger().abandon(selectedPosition)
					.map(entry -> "Abandoned " + entry.displayName()
							+ " - its units count against the fill rate.")
					.orElse("That position is no longer open.");
		} catch (IOException e) {
			SkyblockFlipper.LOGGER.error("Ledger write failed", e);
			notice = "Could not write the ledger - see the log.";
		}

		selectedPosition = "";
		pendingAction = "";
	}

	/**
	 * Deletes a position outright, on a second click.
	 *
	 * <p>Abandon is for a plan that did not work out and keeps its units in the fill rate. This is
	 * for an entry that should not be in the ledger at all, which is what automatic tracking writes
	 * when you buy something to use rather than to flip. Nothing it touched is counted afterwards.
	 */
	private void forgetSelected() {
		if (!armed("Forget")) {
			return;
		}

		try {
			notice = LedgerService.ledger().forget(selectedPosition)
					.map(entry -> "Forgot " + entry.displayName() + " - out of the ledger entirely.")
					.orElse("That position is already gone.");
		} catch (IOException e) {
			SkyblockFlipper.LOGGER.error("Ledger write failed", e);
			notice = "Could not write the ledger - see the log.";
		}

		selectedPosition = "";
		pendingAction = "";
	}

	/**
	 * Whether this click is the confirming one for {@code button}, arming it if it is not.
	 *
	 * <p>Confirmed rather than immediate because these are the only actions on this screen that
	 * cannot be undone and that change a number the mod is judged by. The confirmation is the button
	 * itself rather than a dialog, which would have to live outside the zoomed coordinate space
	 * everything else is drawn in.
	 */
	private boolean armed(String button) {
		if (selectedPosition.isEmpty()) {
			notice = "Select an open position first.";
			return false;
		}

		String wanted = button + ":" + selectedPosition;

		if (!wanted.equals(pendingAction)) {
			pendingAction = wanted;
			notice = button + " " + nameOf(selectedPosition) + "? Press " + button + " again to confirm.";
			return false;
		}

		return true;
	}

	private String nameOf(String positionId) {
		return LedgerService.ledger().get(positionId)
				.map(LedgerEntry::displayName)
				.orElse(positionId);
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

		double mouseX = event.x() / zoom;
		double mouseY = event.y() / zoom;

		if (closeButton.clicked(mouseX, mouseY)) {
			return true;
		}

		if (Settings.available() && settingsButton.clicked(mouseX, mouseY)) {
			return true;
		}

		if (tab.showsCandidates()
				&& (takeButton.clicked(mouseX, mouseY) || copyButton.clicked(mouseX, mouseY))) {
			return true;
		}

		if (tab == Tab.LEDGER
				&& (abandonButton.clicked(mouseX, mouseY) || forgetButton.clicked(mouseX, mouseY))) {
			return true;
		}

		if (tabClicked(mouseX, mouseY)) {
			return true;
		}

		if (tab == Tab.LEDGER) {
			return positionClicked(mouseX, mouseY);
		}

		return tab.showsCandidates() && table.mouseClicked(mouseX, mouseY);
	}

	/** @return true when the click landed in the open-positions panel, selected row or not */
	private boolean positionClicked(double mouseX, double mouseY) {
		int top = contentTop();

		if (mouseX < MARGIN || mouseX >= MARGIN + listWidth()
				|| mouseY < top || mouseY >= top + contentHeight()) {
			return false;
		}

		int row = (int) ((mouseY - positionRowsTop) / positionRowHeight);

		if (row >= 0 && row < positionRows.size()) {
			selectedPosition = positionRows.get(row);
			// A confirmation follows the thing it was asked about, so moving off it withdraws it.
			pendingAction = "";
			notice = "";
		}

		return true;
	}

	private boolean tabClicked(double mouseX, double mouseY) {
		if (mouseY < MARGIN || mouseY >= MARGIN + TAB_HEIGHT) {
			return false;
		}

		int tabX = MARGIN;

		for (Tab candidate : Tab.values()) {
			int tabWidth = tabWidth(candidate);

			if (mouseX >= tabX && mouseX < tabX + tabWidth) {
				tab = candidate;
				notice = "";
				// The confirmation was for a position on a panel that is no longer on screen.
				pendingAction = "";
				sideScroll.reset();
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
		double vx = mouseX / zoom;

		if (!tab.showsCandidates()) {
			if (sideScroll.scroll(scrollY)) {
				return true;
			}
		} else if (vx >= detailX()) {
			if (detailScroll.scroll(scrollY)) {
				return true;
			}
		} else if (table.mouseScrolled(scrollY)) {
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

		if (tab.showsCandidates()) {
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
