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
import jeff.skyblockflipper.core.strategy.NpcBasket;
import jeff.skyblockflipper.core.strategy.NpcPlan;
import jeff.skyblockflipper.core.strategy.NpcWorklist;
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
	private static final int TEXT_BAD = 0xFFFF5555;
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
		BASKET("Basket", null),
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
			return this != BASKET && this != LEDGER && this != GUIDE;
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

	/**
	 * The Basket tab's own copy buttons, in the slots Take and Copy occupy elsewhere.
	 *
	 * <p>A basket line cannot be taken into the ledger the way a candidate can - a plan the order
	 * slots trimmed is not the plan that was quoted - so the slot the Take button occupies is free
	 * here, and leaving a hole in the footer to reuse one button would look like a missing control.
	 *
	 * <p>Three of them because a bazaar order is three things typed on three signs: the name into
	 * the search, the price into the price box, the size into the amount box. Copying the name and
	 * then retyping 84999.9 by hand is where the digit gets dropped.
	 */
	private final TextButton basketCopyButton = new TextButton("Copy name", this::copySelectedName);
	private final TextButton basketPriceButton = new TextButton("Copy price", this::copySelectedPrice);
	private final TextButton basketUnitsButton = new TextButton("Copy size", this::copySelectedUnits);

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

	/**
	 * The worklist as it was last assembled, rebuilt on the same revision rule as the table.
	 *
	 * <p>Never assembled in a render pass: planning one walks every product on the book, and the
	 * screen draws sixty times a second. Null until the Basket tab has been opened once, because a
	 * player working the auction house should not pay for an allocation they are not looking at.
	 */
	private NpcWorklist.Worklist worklist;

	/** Basket tab: the selected row, as an index into {@link NpcWorklist.Worklist#tasks()}. */
	private int selectedLine = -1;
	private int basketRowsTop;
	private int basketRowHeight = 1;
	private int basketRowCount;

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
		int basketCopyWidth = basketCopyButton.preferredWidth(font);
		int basketPriceWidth = basketPriceButton.preferredWidth(font);
		basketCopyButton.setBounds(MARGIN, buttonY, basketCopyWidth, BUTTON_HEIGHT);
		basketPriceButton.setBounds(MARGIN + basketCopyWidth + 4, buttonY, basketPriceWidth,
				BUTTON_HEIGHT);
		basketUnitsButton.setBounds(MARGIN + basketCopyWidth + basketPriceWidth + 8, buttonY,
				basketUnitsButton.preferredWidth(font), BUTTON_HEIGHT);
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
		} else if (tab == Tab.BASKET) {
			worklist = CandidateFeed.worklist();

			// The line that was selected is not necessarily the same trade in the new allocation,
			// and a row index that outlives its list is how a panel ends up describing something
			// that is no longer on screen.
			if (selectedLine >= worklist.tasks().size()) {
				selectedLine = -1;
			}
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
			case BASKET -> {
				panel(graphics, MARGIN, top, listWidth(), panelHeight);
				panel(graphics, detailX(), top, detailWidth(), panelHeight);
				renderBasket(graphics, MARGIN, top, listWidth(), panelHeight);
				renderBasketTotals(graphics, detailX(), top, detailWidth(), panelHeight);
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

	/**
	 * Everything to do at the bazaar, in the order to do it.
	 *
	 * <p>Not a ranking, and the difference is the whole point of the panel: the orders already on
	 * the book come first, and each new line is sized against what the rows above it took, so
	 * following all of them spends the bankroll and the order slots exactly once. The candidate tabs
	 * cannot show this, because a ranked list has no idea what the row above it committed.
	 */
	private void renderBasket(GuiGraphicsExtractor graphics, int x, int y, int panelWidth,
			int panelHeight) {
		int startY = y + PANEL_PAD - sideScroll.offset();
		int cursor = startY;

		basketRowHeight = font.lineHeight + 2;
		basketRowCount = worklist == null ? 0 : worklist.tasks().size();

		graphics.enableScissor(x, y, x + panelWidth, y + panelHeight);

		graphics.text(font, Component.literal("Do these").withStyle(ChatFormatting.GOLD),
				x + PANEL_PAD, cursor, TEXT);
		cursor += font.lineHeight + 4;

		if (basketRowCount == 0) {
			Component message = Component.literal(
					"Nothing on the book clears the NPC filters right now. That is a normal answer: "
							+ "an item needs a gap under the NPC price that has stood up on the tape, "
							+ "and a margin left over after chasing the book.");
			graphics.textWithWordWrap(font, message, x + PANEL_PAD, cursor,
					panelWidth - 2 * PANEL_PAD, TEXT_DIM);
			cursor += font.wordWrapHeight(message, panelWidth - 2 * PANEL_PAD);
		} else {
			int priceWidth = font.width(Component.literal("000000.0")) + 8;
			// Wide enough for the order split - "3 x 256 + 112" - rather than for the total alone.
			// A total on its own reads as one order, and an order the bazaar will not accept is
			// the one mistake this panel can make that costs a trip to the menu to discover.
			int unitsWidth = font.width(Component.literal("00 x 00000 + 00000")) + 8;
			int nameWidth = panelWidth - 2 * PANEL_PAD - priceWidth - unitsWidth;

			Component priceHeading = Component.literal("at");
			graphics.text(font, priceHeading,
					x + PANEL_PAD + nameWidth + priceWidth - 8 - font.width(priceHeading),
					cursor, TEXT_DIM);

			Component unitsHeading = Component.literal("units");
			graphics.text(font, unitsHeading,
					x + panelWidth - PANEL_PAD - font.width(unitsHeading), cursor, TEXT_DIM);
			cursor += font.lineHeight + 2;

			basketRowsTop = cursor;

			for (int i = 0; i < basketRowCount; i++) {
				NpcWorklist.Task task = worklist.tasks().get(i);

				if (i == selectedLine) {
					graphics.fill(x + 1, cursor - 1, x + panelWidth - 1,
							cursor + basketRowHeight - 1, ROW_SELECTED);
				}

				// The verb is in the name column rather than a column of its own: at this width a
				// fourth column costs more than it explains, and "cancel Clipped Wings" reads.
				graphics.text(font, Component.literal(Labels.fit(font,
								task.verb() + " " + task.displayName(), nameWidth)),
						x + PANEL_PAD, cursor, taskColour(task.kind()));

				if (task.hasPrice()) {
					Component price = Component.literal(String.format("%.1f", task.price()));
					graphics.text(font, price,
							x + PANEL_PAD + nameWidth + priceWidth - 8 - font.width(price), cursor,
							TEXT_NOTE);
				}

				Component units = Component.literal(task.orderSplit());
				graphics.text(font, units,
						x + panelWidth - PANEL_PAD - font.width(units), cursor, TEXT_DIM);
				cursor += basketRowHeight;
			}
		}

		graphics.disableScissor();

		sideScroll.measured(cursor - startY + PANEL_PAD, panelHeight - PANEL_PAD);
		sideScroll.renderBar(graphics, x, y, panelWidth, panelHeight);
	}

	/** One colour per kind of click, so the shape of a trip is readable before it is read. */
	private static int taskColour(NpcWorklist.Kind kind) {
		return switch (kind) {
			case CLAIM -> TEXT_GOOD;
			case CANCEL -> TEXT_BAD;
			case REPRICE -> TEXT_WARN;
			case PLACE -> TEXT;
			case HOLD -> TEXT_DIM;
		};
	}

	/**
	 * What the basket costs and makes, and then whichever row is selected.
	 *
	 * <p>The totals come first because they are the answer to the question the tab exists for: what
	 * one cycle of this is worth, and which shared resource is the reason it is not worth more.
	 */
	private void renderBasketTotals(GuiGraphicsExtractor graphics, int x, int y, int panelWidth,
			int panelHeight) {
		int contentWidth = panelWidth - 2 * PANEL_PAD;
		int startY = y + PANEL_PAD - detailScroll.offset();
		int cursor = startY;
		int textX = x + PANEL_PAD;

		graphics.enableScissor(x, y, x + panelWidth, y + panelHeight);

		graphics.text(font, Component.literal("This cycle").withStyle(ChatFormatting.GOLD),
				textX, cursor, TEXT);
		cursor += font.lineHeight + 4;

		NpcBasket.Basket basket = worklist == null ? null : worklist.basket();

		if (basket == null) {
			Component message = Component.literal("Nothing to allocate.");
			graphics.textWithWordWrap(font, message, textX, cursor, contentWidth, TEXT_DIM);
			cursor += font.wordWrapHeight(message, contentWidth);
		} else {
			// Free slots rather than used ones, because that is the number a player acts on: it is
			// what the next basket has to work with, and it already has the resting orders out of it.
			cursor = field(graphics, textX, cursor, contentWidth, "Slots",
					basket.held().orders() + " resting, " + basket.slotsUsed() + " to place, "
							+ basket.slotsAvailable() + " in all");
			cursor = field(graphics, textX, cursor, contentWidth, "Capital",
					String.format("%s of %s (%.0f%%)", Coins.format(basket.capital()),
							Coins.format(basket.bankrollFree()), basket.capitalShare() * 100.0d));
			cursor = field(graphics, textX, cursor, contentWidth, "Profit",
					String.format("%s over %.0fh", Coins.format(basket.profit()),
							basket.restingHours()));
			cursor = field(graphics, textX, cursor, contentWidth, "Per hour",
					Coins.format(basket.profitPerHour()));
			cursor = field(graphics, textX, cursor, contentWidth, "ROC",
					String.format("%.0f%%", basket.returnOnCapital() * 100.0d));
			cursor = field(graphics, textX, cursor, contentWidth, "NPC coins",
					Coins.format(basket.npcPayout()) + " of the day's budget");
			cursor = field(graphics, textX, cursor, contentWidth, "Hauling",
					basket.loads() + (basket.loads() == 1L ? " load" : " loads") + " via /trades");

			cursor += 4;
			Component bound = Component.literal(basket.boundExplanation());
			graphics.textWithWordWrap(font, bound, textX, cursor, contentWidth, TEXT_WARN);
			cursor += font.wordWrapHeight(bound, contentWidth) + 6;

			cursor = renderBasketLine(graphics, textX, cursor, contentWidth);
		}

		graphics.disableScissor();

		detailScroll.measured(cursor - startY + PANEL_PAD, panelHeight - PANEL_PAD);
		detailScroll.renderBar(graphics, x, y, panelWidth, panelHeight);
	}

	/**
	 * What the selected click is and why.
	 *
	 * <p>Two shapes, because the rows come from two places. A new order carries a whole plan - fill
	 * rate, measured edge, confidence - and an order already on the book carries none of that and
	 * carries instead the one fact a plan cannot have: the price it actually got.
	 *
	 * @return the y the content finished at
	 */
	private int renderBasketLine(GuiGraphicsExtractor graphics, int x, int y, int contentWidth) {
		if (selectedLine < 0 || selectedLine >= worklist.tasks().size()) {
			Component message = Component.literal("Select a row for what to type into the bazaar.");
			graphics.textWithWordWrap(font, message, x, y, contentWidth, TEXT_DIM);
			return y + font.wordWrapHeight(message, contentWidth);
		}

		NpcWorklist.Task task = worklist.tasks().get(selectedLine);

		Component name = Component.literal(task.verb() + " " + task.displayName());
		graphics.textWithWordWrap(font, name, x, y, contentWidth, 0xFF55FFFF);
		int cursor = y + font.wordWrapHeight(name, contentWidth) + 3;

		Component reason = Component.literal(task.reason());
		graphics.textWithWordWrap(font, reason, x, cursor, contentWidth, TEXT_DIM);
		cursor += font.wordWrapHeight(reason, contentWidth) + 4;

		if (task.hasPrice()) {
			cursor = field(graphics, x, cursor, contentWidth, "Price",
					String.format("%.1f", task.price()));
		}

		cursor = field(graphics, x, cursor, contentWidth, "Units",
				task.orderSplit().equals(String.valueOf(task.units()))
						? String.valueOf(task.units())
						: task.units() + " as " + task.orderSplit());

		if (task.profit() > 0.0d) {
			cursor = field(graphics, x, cursor, contentWidth,
					task.kind() == NpcWorklist.Kind.CLAIM ? "Already made" : "Worth",
					Coins.format(task.profit()));
		}

		if (task.capital() > 0L) {
			cursor = field(graphics, x, cursor, contentWidth,
					task.kind() == NpcWorklist.Kind.CANCEL ? "Frees" : "Ties up",
					Coins.format(task.capital()));
		}

		return task.kind() == NpcWorklist.Kind.PLACE
				? renderPlanDetail(graphics, x, cursor, contentWidth, task.itemId())
				: cursor;
	}

	/** The plan behind a new order, which only exists for a row the basket produced. */
	private int renderPlanDetail(GuiGraphicsExtractor graphics, int x, int y, int contentWidth,
			String itemId) {
		NpcPlan plan = worklist.basket().lines().stream()
				.map(NpcBasket.Line::plan)
				.filter(candidate -> candidate.itemId().equals(itemId))
				.findFirst()
				.orElse(null);

		if (plan == null) {
			return y;
		}

		int cursor = field(graphics, x, y, contentWidth, "NPC pays",
				String.format("%.1f", plan.npcPrice()));
		cursor = field(graphics, x, cursor, contentWidth, "Net/unit",
				String.format("%.1f (%.0f%% margin)", plan.unitNetProfit(),
						plan.marginRatio() * 100.0d));
		cursor = field(graphics, x, cursor, contentWidth, "Order limit",
				plan.unitsPerOrder() + " units");

		if (plan.chaseCost() > 0.0d) {
			cursor = field(graphics, x, cursor, contentWidth, "Chase",
					String.format("%.1f a unit, already out of the margin", plan.chaseCost()));
		}

		cursor = field(graphics, x, cursor, contentWidth, "Fill",
				String.format("%.0f units an hour %s", plan.fillPerHour(),
						plan.fillMeasured() ? "(measured)" : "(assumed)"));
		cursor = field(graphics, x, cursor, contentWidth, "Edge", plan.edgeMeasured()
				? String.format("held in %.0f%% of samples", plan.persistence() * 100.0d)
				: "never taped");

		return field(graphics, x, cursor, contentWidth, "Confidence",
				String.format("%.2f", plan.confidence()));
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
		} else if (tab == Tab.BASKET) {
			basketCopyButton.render(graphics, font, mouseX, mouseY, selectedLine >= 0);
			basketPriceButton.render(graphics, font, mouseX, mouseY, selectedHasPrice());
			basketUnitsButton.render(graphics, font, mouseX, mouseY, selectedLine >= 0);
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
				switch (tab) {
					case LEDGER -> "Abandon keeps a position in the numbers, Forget deletes it.";
					case BASKET -> "Work the list top down: claims, cancels, reprices, then places.";
					default -> "Click a column to sort, a row to select.";
				},
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

	/** The selected task, or null when the selection points at nothing. */
	private NpcWorklist.Task selectedTask() {
		return worklist == null || selectedLine < 0 || selectedLine >= worklist.tasks().size()
				? null
				: worklist.tasks().get(selectedLine);
	}

	private boolean selectedHasPrice() {
		NpcWorklist.Task task = selectedTask();
		return task != null && task.hasPrice();
	}

	private void copySelectedName() {
		NpcWorklist.Task task = selectedTask();

		if (task == null) {
			notice = "Select a row first.";
			return;
		}

		minecraft.keyboardHandler.setClipboard(task.displayName());
		notice = "Copied \"" + task.displayName() + "\" - paste it into the bazaar search.";
	}

	/**
	 * The price, written out in full, because it is typed into a box character by character.
	 *
	 * <p>The one number here that cannot be approximated: 84999.9 retyped as 85000 is an order at a
	 * different price, and on the wrong side of a book that is already at 85000.
	 */
	private void copySelectedPrice() {
		NpcWorklist.Task task = selectedTask();

		if (task == null || !task.hasPrice()) {
			notice = "That row has no price to type - it is a button in the orders menu.";
			return;
		}

		String price = String.format("%.1f", task.price());
		minecraft.keyboardHandler.setClipboard(price);
		notice = "Copied " + price + " - paste it into the price box.";
	}

	/**
	 * The units of one order, never the line total.
	 *
	 * <p>A line of 500 unstackable units is three orders, and typing 500 into the amount box is the
	 * mistake {@code orderSplit} exists to prevent, so what goes on the clipboard is what one order
	 * takes.
	 */
	private void copySelectedUnits() {
		NpcWorklist.Task task = selectedTask();

		if (task == null) {
			notice = "Select a row first.";
			return;
		}

		String units = String.valueOf(unitsPerOrder(task));
		minecraft.keyboardHandler.setClipboard(units);
		notice = units.equals(String.valueOf(task.units()))
				? "Copied " + units + " - paste it into the amount box."
				: "Copied " + units + " - one order of " + task.orderSplit() + ".";
	}

	/** Units one order of this task holds, which is the whole line unless the bazaar splits it. */
	private long unitsPerOrder(NpcWorklist.Task task) {
		return worklist.basket().lines().stream()
				.filter(line -> line.plan().itemId().equals(task.itemId()))
				.mapToLong(line -> Math.min(task.units(), line.plan().unitsPerOrder()))
				.findFirst()
				.orElse(task.units());
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

		if (tab == Tab.BASKET && (basketCopyButton.clicked(mouseX, mouseY)
				|| basketPriceButton.clicked(mouseX, mouseY)
				|| basketUnitsButton.clicked(mouseX, mouseY))) {
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

		if (tab == Tab.BASKET) {
			return basketRowClicked(mouseX, mouseY);
		}

		return tab.showsCandidates() && table.mouseClicked(mouseX, mouseY);
	}

	/** @return true when the click landed in the basket panel, on a row or not */
	private boolean basketRowClicked(double mouseX, double mouseY) {
		int top = contentTop();

		if (mouseX < MARGIN || mouseX >= MARGIN + listWidth()
				|| mouseY < top || mouseY >= top + contentHeight()) {
			return false;
		}

		// Tested before the division, which truncates towards zero: a click one row above the first
		// one divides to 0 as well, and the column headings sit in exactly that band.
		int row = mouseY < basketRowsTop ? -1 : (int) ((mouseY - basketRowsTop) / basketRowHeight);

		if (row >= 0 && row < basketRowCount) {
			selectedLine = row;
			// The right-hand panel is now describing a different item from top to bottom.
			detailScroll.reset();
			notice = "";
		}

		return true;
	}

	/** @return true when the click landed in the open-positions panel, selected row or not */
	private boolean positionClicked(double mouseX, double mouseY) {
		int top = contentTop();

		if (mouseX < MARGIN || mouseX >= MARGIN + listWidth()
				|| mouseY < top || mouseY >= top + contentHeight()) {
			return false;
		}

		int row = mouseY < positionRowsTop ? -1
				: (int) ((mouseY - positionRowsTop) / positionRowHeight);

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

		if (tab == Tab.BASKET) {
			// Two scrolling panels rather than one, because the right-hand side carries a whole
			// item's plan under the totals and does not fit either.
			if ((vx >= detailX() ? detailScroll : sideScroll).scroll(scrollY)) {
				return true;
			}
		} else if (!tab.showsCandidates()) {
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
