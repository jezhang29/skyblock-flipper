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
package jeff.skyblockflipper.client.hud;

import jeff.skyblockflipper.client.CandidateFeed;
import jeff.skyblockflipper.client.MarketDataService;
import jeff.skyblockflipper.client.SkyblockFlipperClient;
import jeff.skyblockflipper.client.mixin.ContainerScreenLayout;
import jeff.skyblockflipper.core.config.OverlaySide;
import jeff.skyblockflipper.core.strategy.FlipCandidate;
import jeff.skyblockflipper.core.strategy.StrategyKind;
import jeff.skyblockflipper.core.text.Coins;
import jeff.skyblockflipper.core.track.AuctionMenu;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.network.chat.Component;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * The snipe panel drawn beside Hypixel's own auction-house menu - the {@link BazaarOverlay}'s
 * auction-side sibling, kept small on purpose.
 *
 * <p><b>What it drops from the bazaar panel, and why.</b> There is no type strip: the auction house
 * has one flip kind, {@link StrategyKind#AUCTION_VALUE}, not five. There is no worked-job list: a
 * snipe is a single bid on one ephemeral listing, not a multi-step buy/transform/sell the tracker
 * follows. And there is no green box on a slot: the auction house lists ephemeral BINs with no order
 * book, and a {@link FlipCandidate} carries no listing uuid to point at one - a wrong-slot click on a
 * BIN spends coins immediately, so a box here would be worse than the bazaar's, not better. What is
 * left is a passive read-only list.
 *
 * <p><b>What it keeps from the bazaar panel.</b> The attachment and geometry, copied because they are
 * proven: attach once per screen (weak ref), draw {@code afterBackground}, swallow only the clicks and
 * scrolls that land inside the panel's own rectangle, scale a fixed-width panel down to the room beside
 * the menu, and take that menu's real position from {@link ContainerScreenLayout} rather than assuming
 * a centred chest.
 *
 * <p><b>A click only ever copies a name.</b> Clicking a row copies the item's full display name to the
 * clipboard, to paste into the auction search sign; clicking its {@code [+]} caret expands the row to
 * show the buy price, the fair resale, the confidence and the one standing risk. Nothing is bought and
 * nothing is typed for the player.
 *
 * <p><b>It follows the search sign, unverified.</b> Like the bazaar's, the auction search is typed on a
 * sign - not a container, so it carries no title to match - and for {@link #FOLLOW_MILLIS} after an
 * auction menu the panel stays pinned to the edge on a sign. Two things about the sign could only be
 * settled in live play and are labelled rather than assumed: whether the search sign is really an
 * {@link AbstractSignEditScreen}, and whether the auction search is prefix-matched the way the bazaar's
 * is. Because the second is unknown, the name is copied <b>in full</b> (the bazaar offers a fitted
 * prefix because the bazaar matches prefixes) and the sign hint is worded so it cannot mislead.
 */
public final class AuctionOverlay {
	private static final int PANEL = 0xF01E1E2E;
	private static final int PANEL_EDGE = 0xFF585B70;
	private static final int HEADER_RULE = 0x40CDD6F4;
	private static final int ROW_STRIPE = 0x14CDD6F4;
	private static final int ROW_HOVER = 0x30CDD6F4;
	private static final int ROW_OPEN = 0x50CBA6F7;
	private static final int TEXT = 0xFFCDD6F4;
	private static final int TEXT_DIM = 0xFF7F849C;
	private static final int TEXT_PRICE = 0xFF89B4FA;
	private static final int TEXT_PROFIT = 0xFFA6E3A1;
	private static final int TEXT_COPIED = 0xFFF9E2AF;
	private static final int TEXT_HEADING = 0xFF89DCEB;

	/** The standing "may be gone" line, and the deep-discount warning: peach and red, the risk tones. */
	private static final int TEXT_RISK = 0xFFFAB387;
	private static final int TEXT_SUSPECT = 0xFFF38BA8;

	private static final int PAD = 4;

	/** Gap between the caret column and the numbers, and the click boundary the caret zone ends at. */
	private static final int GAP = 5;

	/** Space between two right-hand numbers on a row. */
	private static final int NUMBER_GAP = 5;

	/** The panel's fixed width in panel pixels, so the font size is the same on every screen. */
	private static final int PANEL_WIDTH = 170;

	/** Below this the text is not worth drawing, so the panel stays away rather than shrink illegibly. */
	private static final float MIN_SCALE = 0.34f;

	/** Where the panel's top sits, in real screen pixels, fixed rather than tied to the menu's height. */
	private static final int PANEL_TOP = 24;

	/** Gap between the panel and the menu, in real screen pixels. */
	private static final int MENU_GAP = 3;

	/** Share of the window the panel may take when it has no menu to sit beside (on a sign). */
	private static final int EDGE_WIDTH_SHARE = 3;

	/** How many snipes the list shows. The panel scrolls if the expanded rows push past the window. */
	private static final int TO_SHOW = 5;

	/**
	 * How long the panel keeps following after the auction menu closes, matching the bazaar's window.
	 * Ninety seconds covers a search typed slowly, and every auction screen refreshes it.
	 */
	private static final long FOLLOW_MILLIS = 90_000L;

	/** How long "copied" stays in the footer before the hint comes back. */
	private static final long COPIED_MILLIS = 2_000L;

	/** The empty answer, when the sweep has run and nothing clears. */
	private static final String EMPTY = "No snipes clear fees right now.";

	/** When an auction menu was last on screen, which {@link #FOLLOW_MILLIS} runs from. */
	private static long leftAuctionAt;

	/** Which candidate is expanded, by item id, or empty when none is. */
	private static String expandedCandidate = "";

	/**
	 * AFTER_INIT fires again on every window resize and a screen keeps whatever was registered on it,
	 * so attaching twice would lay the panel out twice a frame. Weak, so it never keeps a closed screen
	 * alive.
	 */
	private static WeakReference<Screen> attached = new WeakReference<>(null);

	private AuctionOverlay() {
	}

	public static void register() {
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (attached.get() == screen) {
				return;
			}

			attached = new WeakReference<>(screen);
			// After the background rather than the whole screen, so a tooltip draws over the panel.
			ScreenEvents.afterBackground(screen).register(
					(shown, graphics, mouseX, mouseY, tickProgress) -> render(shown, graphics, mouseX,
							mouseY));

			// Cancelling the event stops the click or scroll reaching Hypixel's menu; both handlers do
			// that only for input inside the panel's own rectangle, and pass everything else through.
			ScreenMouseEvents.allowMouseClick(screen).register(
					(shown, event) -> !Hit.click(event.x(), event.y()));
			ScreenMouseEvents.allowMouseScroll(screen).register(
					(shown, mouseX, mouseY, horizontal, vertical) -> !Hit.scroll(mouseX, mouseY,
							vertical));
		});
	}

	private static void render(Screen screen, GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		if (!SkyblockFlipperClient.config().auctionOverlayEnabled) {
			Hit.clear();
			return;
		}

		Font font = Minecraft.getInstance().font;
		List<FlipCandidate> ranked = rankedSnipes();

		if (screen instanceof ContainerScreenLayout layout
				&& screen instanceof AbstractContainerScreen<?> container) {
			String title = screen.getTitle().getString();

			if (AuctionMenu.isAuction(title)) {
				leftAuctionAt = System.currentTimeMillis();
				Board board = board(ranked, emptyNote(ranked));
				drawBesideMenu(screen, layout, graphics, board, font, mouseX, mouseY);
				return;
			}

			Hit.clear();
			return;
		}

		// Off a container menu: the only screen the panel belongs on is the search sign, and only for a
		// short window after the auction menu it was opened from. Everything else - chat, the pause
		// menu, settings - has its own clicks, and this panel eats every click that lands on it.
		boolean onASign = screen instanceof AbstractSignEditScreen;

		if (onASign && System.currentTimeMillis() - leftAuctionAt <= FOLLOW_MILLIS) {
			Board board = board(ranked, signNote());
			drawAtTheEdge(screen, graphics, board, font, mouseX, mouseY);
			return;
		}

		Hit.clear();
	}

	/**
	 * The top snipes, ranked at most once per auction sweep rather than every frame.
	 *
	 * <p>Cached against {@code ordinaryRevision}, which is what moves when a sweep republishes the
	 * underpriced listings - the same discipline the bazaar panel uses against the book revision. Empty
	 * until the first sweep lands, so the panel shows its "searching" note rather than a stale list.
	 */
	private static List<FlipCandidate> rankedSnipes() {
		long revision = MarketDataService.data().auctionScan().ordinaryRevision();

		if (revision != rankedRevision) {
			rankedRevision = revision;
			ranked = MarketDataService.data().hasScannedAuctions()
					? CandidateFeed.rank(StrategyKind.AUCTION_VALUE, TO_SHOW)
					: List.of();
		}

		return ranked;
	}

	private static List<FlipCandidate> ranked = List.of();
	private static long rankedRevision = -1L;

	/** Toggle a candidate's expansion, or collapse it if it is the one already open. */
	static void toggleExpand(String itemId) {
		if (itemId == null || itemId.isEmpty()) {
			return;
		}

		expandedCandidate = expandedCandidate.equals(itemId) ? "" : itemId;
	}

	/** Copy an item's full name to the clipboard, to paste into the auction search sign. */
	static void copyName(String name) {
		if (name == null || name.isEmpty()) {
			return;
		}

		Minecraft.getInstance().keyboardHandler.setClipboard(name);
		Hit.copied(name);
	}

	/** The note under the heading when the list is empty, saying why rather than showing nothing. */
	private static String emptyNote(List<FlipCandidate> ranked) {
		if (!ranked.isEmpty()) {
			return "";
		}

		if (!SkyblockFlipperClient.config().scanAuctions) {
			return "Auction search is off.";
		}

		return MarketDataService.data().hasScannedAuctions() ? EMPTY : "Searching the auction house...";
	}

	/**
	 * The line the panel shows in place of its own note while a search sign is open: the last name that
	 * was copied, flagged unconfirmed because whether the auction search prefix-matches is not known.
	 */
	private static String signNote() {
		String name = Hit.lastCopied();
		return name.isEmpty() ? "" : "Search: " + name + " (match unconfirmed)";
	}

	/** Beside Hypixel's menu, on the side the settings name (reusing the bazaar panel's side). */
	private static void drawBesideMenu(Screen screen, ContainerScreenLayout layout,
			GuiGraphicsExtractor graphics, Board board, Font font, int mouseX, int mouseY) {
		int menuLeft = layout.flipper$leftPos();
		int menuRight = menuLeft + layout.flipper$imageWidth();

		int roomLeft = menuLeft - MENU_GAP * 2;
		int roomRight = screen.width - menuRight - MENU_GAP * 2;

		int minimum = Math.round(PANEL_WIDTH * MIN_SCALE);
		OverlaySide side = SkyblockFlipperClient.config().overlaySide();
		boolean onLeft = side.drawOnLeft(roomLeft, roomRight, minimum);

		float scale = fit(onLeft ? roomLeft : roomRight);

		if (scale <= 0.0f) {
			Hit.clear();
			return;
		}

		draw(graphics, board, font, scale,
				Math.round((onLeft ? MENU_GAP : menuRight + MENU_GAP) / scale),
				Math.round(PANEL_TOP / scale), screen.height, mouseX, mouseY);
	}

	/** Against the left edge, for the sign screen that has no menu to sit beside. */
	private static void drawAtTheEdge(Screen screen, GuiGraphicsExtractor graphics, Board board,
			Font font, int mouseX, int mouseY) {
		float scale = fit(screen.width / EDGE_WIDTH_SHARE - MENU_GAP * 2);

		if (scale <= 0.0f) {
			Hit.clear();
			return;
		}

		draw(graphics, board, font, scale, Math.round(MENU_GAP / scale),
				Math.round(PANEL_TOP / scale), screen.height, mouseX, mouseY);
	}

	/** The scale that fits the fixed-width panel into {@code room} screen pixels, or 0 if none is worth it. */
	private static float fit(int room) {
		float scale = Math.min(1.0f, (float) room / PANEL_WIDTH);
		return scale < MIN_SCALE ? 0.0f : scale;
	}

	private static void draw(GuiGraphicsExtractor graphics, Board board, Font font, float scale,
			int x, int preferredY, int screenHeight, int mouseX, int mouseY) {
		// Everything from here is in panel pixels, so the window has to be measured in them too.
		int panelHeight = Math.round(screenHeight / scale);
		int shown = board.rowsFitting(panelHeight - 2 * PAD, font);

		// A board with rows and no room for any is not worth a panel; an empty board is a heading and
		// its note, still worth showing over an auction menu.
		if (shown <= 0 && !board.rows().isEmpty()) {
			Hit.clear();
			return;
		}

		int height = board.height(shown, font);
		int y = Math.clamp(preferredY, PAD, Math.max(PAD, panelHeight - height - PAD));

		Hit.laidOut(board, x, y, scale, shown, font);

		graphics.pose().pushMatrix();
		graphics.pose().scale(scale, scale);
		board.draw(graphics, font, x, y, shown, Hit.hoveredRow(mouseX, mouseY));
		graphics.pose().popMatrix();
	}

	/** What a click on a row does, so the hit test routes it without re-deriving the row's kind. */
	private enum Kind {
		/** A snipe one-liner: the caret expands it, the rest copies its name. */
		CANDIDATE,

		/** A number under an expanded snipe: buy, resale, confidence. Swallowed, no action. */
		DETAIL,

		/** A risk line under an expanded snipe. Swallowed, no action. */
		RISK
	}

	/**
	 * One laid-out row.
	 *
	 * @param caret    {@code [+]} / {@code [-]} on a candidate, empty otherwise
	 * @param left     the name on a candidate, the label on a detail, the whole sentence on a risk
	 * @param profit   the {@code +net} on a candidate, empty otherwise
	 * @param right    the discount on a candidate, the value on a detail, empty on a risk
	 * @param itemId   the id a candidate's caret toggles
	 * @param copyName the full name a candidate's body copies
	 */
	private record Row(Kind kind, String caret, String left, String profit, String right, String itemId,
			String copyName) {
		static Row candidate(FlipCandidate c) {
			boolean open = c.itemId().equals(expandedCandidate);
			return new Row(Kind.CANDIDATE, open ? "[-]" : "[+]", c.displayName(),
					"+" + Coins.format(c.totalNetProfit()), discountOf(c) + "%", c.itemId(),
					c.displayName());
		}

		static Row detail(String label, String value) {
			return new Row(Kind.DETAIL, "", label, "", value, "", "");
		}

		static Row risk(String sentence) {
			return new Row(Kind.RISK, "", sentence, "", "", "", "");
		}
	}

	/** How far under fair value this listing sits, as a whole-percent, reproducing the strategy's step text. */
	private static long discountOf(FlipCandidate c) {
		return c.unitSellPrice() <= 0.0d
				? 0L
				: Math.round((1.0d - c.unitBuyPrice() / c.unitSellPrice()) * 100.0d);
	}

	/** The list laid out as text, rebuilt only when its inputs change rather than every frame. */
	private static Board board(List<FlipCandidate> ranked, String note) {
		if (board == null || ranked != boardRanked || !note.equals(boardNote)
				|| !expandedCandidate.equals(boardExpanded)) {
			boardRanked = ranked;
			boardNote = note;
			boardExpanded = expandedCandidate;
			board = Board.of(ranked, note);
			Hit.reset(board);
		}

		return board;
	}

	private static Board board;
	private static List<FlipCandidate> boardRanked;
	private static String boardNote = "";
	private static String boardExpanded = "";

	/**
	 * The snipe list laid out as rows, measured before anything is drawn.
	 *
	 * <p>Separate from the drawing because the panel has to know how tall a row is before it can decide
	 * how many rows the window has room for, exactly as the bazaar board does.
	 */
	private record Board(List<Row> rows, String note) {
		Board {
			rows = List.copyOf(rows);
		}

		static Board of(List<FlipCandidate> ranked, String note) {
			List<Row> rows = new ArrayList<>();

			for (FlipCandidate candidate : ranked) {
				rows.add(Row.candidate(candidate));

				if (candidate.itemId().equals(expandedCandidate)) {
					rows.add(Row.detail("Buy", Coins.format(candidate.unitBuyPrice())));
					rows.add(Row.detail("Resale", Coins.format(candidate.unitSellPrice())));
					rows.add(Row.detail("Confidence",
							Math.round(candidate.confidence() * 100.0d) + "%"));
					rows.add(Row.risk("Not verified live, may be gone"));

					if (candidate.suspect()) {
						rows.add(Row.risk("Very deep discount - check for a hidden upgrade"));
					}
				}
			}

			return new Board(rows, note);
		}

		private int headerHeight(Font font) {
			return font.lineHeight + 5 + (note.isEmpty() ? 0 : font.lineHeight + 1);
		}

		private int rowHeight(Font font) {
			return font.lineHeight + 3;
		}

		int height(int shown, Font font) {
			return PAD * 2 + headerHeight(font) + shown * rowHeight(font) + font.lineHeight;
		}

		/**
		 * Rows that fit in {@code available} panel pixels. Room for the footer is always reserved, so a
		 * list that grows does not reflow the moment it hides its first row.
		 */
		int rowsFitting(int available, Font font) {
			int rowHeight = rowHeight(font);
			return Math.clamp((available - headerHeight(font) - rowHeight) / rowHeight, 0, rows.size());
		}

		/** Where the rows begin, relative to the panel's top. */
		int rowsTop(Font font) {
			return PAD + headerHeight(font);
		}

		void draw(GuiGraphicsExtractor graphics, Font font, int x, int y, int shown, int hovered) {
			int height = height(shown, font);
			int first = Hit.firstRow();
			int last = Math.min(rows.size(), first + shown);
			int right = x + PANEL_WIDTH;
			int rowHeight = rowHeight(font);
			int caretW = width(font, "[+] ");

			graphics.fill(x, y, right, y + height, PANEL);
			// A whole border rather than two edges: the panel sits on top of Hypixel's own menu art,
			// and an unclosed box reads as part of it.
			graphics.fill(x, y, right, y + 1, PANEL_EDGE);
			graphics.fill(x, y + height - 1, right, y + height, PANEL_EDGE);
			graphics.fill(x, y, x + 1, y + height, PANEL_EDGE);
			graphics.fill(right - 1, y, right, y + height, PANEL_EDGE);

			int cursor = y + PAD;

			graphics.text(font, Component.literal(heading(first, last))
					.withStyle(ChatFormatting.GOLD), x + PAD, cursor, TEXT);

			if (!note.isEmpty()) {
				graphics.text(font, Component.literal(clip(font, note, PANEL_WIDTH - 2 * PAD)), x + PAD,
						cursor + font.lineHeight + 1, TEXT_DIM);
			}

			graphics.fill(x + 1, cursor + headerHeight(font) - 3, right - 1,
					cursor + headerHeight(font) - 2, HEADER_RULE);
			cursor += headerHeight(font);

			for (int i = first; i < last; i++) {
				drawRow(graphics, font, rows.get(i), x, right, cursor, rowHeight, caretW, i, hovered);
				cursor += rowHeight;
			}

			graphics.text(font, Component.literal(Hit.footer(rows.size())), x + PAD, cursor,
					Hit.copiedRecently() ? TEXT_COPIED : TEXT_DIM);
		}

		private void drawRow(GuiGraphicsExtractor graphics, Font font, Row row, int x, int right,
				int cursor, int rowHeight, int caretW, int index, int hovered) {
			int bottom = cursor + rowHeight - 2;

			// Zebra striping under everything, so a stack of detail lines does not run into a wall.
			if ((index & 1) == 1) {
				graphics.fill(x + 1, cursor - 1, right - 1, bottom, ROW_STRIPE);
			}

			if (row.kind() == Kind.CANDIDATE && row.itemId().equals(expandedCandidate)) {
				graphics.fill(x + 1, cursor - 1, right - 1, bottom, ROW_OPEN);
			} else if (index == hovered) {
				graphics.fill(x + 1, cursor - 1, right - 1, bottom, ROW_HOVER);
			}

			if (row.kind() == Kind.RISK) {
				int colour = row.left().startsWith("Very deep") ? TEXT_SUSPECT : TEXT_RISK;
				graphics.text(font, Component.literal(clip(font, row.left(), right - PAD - (x + PAD))),
						x + PAD, cursor, colour);
				return;
			}

			// Candidate rows carry a caret and align their name after it; detail rows indent to the
			// same column so their labels sit under the name.
			int leftX = x + PAD + caretW;

			if (row.kind() == Kind.CANDIDATE) {
				graphics.text(font, Component.literal(row.caret()), x + PAD, cursor, TEXT_DIM);
			}

			// The money is the far-right column, the discount just left of it - both cut off the name
			// rather than being drawn over it, since the panel width is fixed.
			int rightEdge = right - PAD;

			if (!row.profit().isEmpty()) {
				int profitW = width(font, row.profit());
				int discW = width(font, row.right());
				int profitX = rightEdge - profitW;
				int discX = profitX - NUMBER_GAP - discW;

				graphics.text(font, Component.literal(row.right()), discX, cursor, TEXT_DIM);
				graphics.text(font, Component.literal(row.profit()), profitX, cursor, TEXT_PROFIT);

				String name = clip(font, row.left(), discX - GAP - leftX);
				graphics.text(font, Component.literal(name), leftX, cursor, TEXT);
				return;
			}

			// A detail row: a dim label on the left, its value on the right.
			int valueW = width(font, row.right());
			int valueX = rightEdge - valueW;
			graphics.text(font, Component.literal(row.right()), valueX, cursor, TEXT_PRICE);

			String label = clip(font, row.left(), valueX - GAP - leftX);
			graphics.text(font, Component.literal(label), leftX, cursor, TEXT_DIM);
		}

		/** The heading, plus the scroll range when the list is scrolled past the top or clipped. */
		private String heading(int first, int last) {
			if (rows.isEmpty()) {
				return "Auction snipes";
			}

			return rows.size() == last - first
					? "Auction snipes"
					: "Auction snipes  " + (first + 1) + "-" + last + " of " + rows.size();
		}
	}

	private static int width(Font font, String value) {
		return font.width(Component.literal(value));
	}

	private static String clip(Font font, String value, int maxWidth) {
		return maxWidth <= 0 ? "" : font.plainSubstrByWidth(value, maxWidth);
	}

	/**
	 * Where the panel was last drawn, in real screen pixels, and what a click there means.
	 *
	 * <p>Static because an immediate-mode panel has no widget to ask. The render pass records the
	 * rectangle it drew into and the mouse handlers hit-test against it, which is sound as long as only
	 * one panel is on screen at a time - it is, and it is cleared whenever a frame decides not to draw.
	 */
	private static final class Hit {
		/** Nothing has been drawn: every click and scroll passes straight through. */
		private static boolean live;

		private static int x;
		private static int y;
		private static int width;
		private static int height;
		private static float scale = 1.0f;

		/** The panel's top-left in panel pixels, for mapping a click onto the caret zone. */
		private static int panelX;

		private static int rowHeight = 1;

		/** The rows' top edge, in panel pixels. */
		private static int rowsTop;

		/** The caret zone's right edge, in panel pixels from the panel's left. */
		private static int caretRight;

		private static int visibleRows;
		private static int firstRow;
		private static int rowCount;

		private static Board drawn;

		private static String copiedName = "";
		private static long copiedAt;

		private Hit() {
		}

		static void clear() {
			live = false;
			drawn = null;
		}

		static void reset(Board board) {
			rowCount = board.rows().size();
		}

		static int firstRow() {
			return firstRow;
		}

		static void laidOut(Board board, int px, int py, float panelScale, int shown, Font font) {
			live = true;
			drawn = board;
			scale = panelScale;
			panelX = px;
			x = Math.round(px * panelScale);
			y = Math.round(py * panelScale);
			width = Math.round(PANEL_WIDTH * panelScale);
			height = Math.round(board.height(shown, font) * panelScale);

			rowHeight = board.rowHeight(font);
			rowsTop = py + board.rowsTop(font);
			caretRight = PAD + width(font, "[+] ");
			visibleRows = shown;
			rowCount = board.rows().size();

			// A list that shrank under a scrolled window would otherwise leave blank space below the
			// last row; clamp keeps the window on real rows.
			firstRow = Math.clamp(firstRow, 0, Math.max(0, rowCount - shown));
		}

		private static boolean inside(double mouseX, double mouseY) {
			return live && mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
		}

		/** The row under the cursor, or -1. Takes real screen pixels, like every mouse callback. */
		static int hoveredRow(double mouseX, double mouseY) {
			if (!inside(mouseX, mouseY)) {
				return -1;
			}

			int py = (int) (mouseY / scale);

			if (py < rowsTop) {
				return -1;
			}

			int row = firstRow + (py - rowsTop) / rowHeight;
			return row < firstRow + visibleRows && row < rowCount ? row : -1;
		}

		/** @return true when the panel took the click, which is what stops it reaching the menu */
		static boolean click(double mouseX, double mouseY) {
			if (!inside(mouseX, mouseY)) {
				return false;
			}

			int row = hoveredRow(mouseX, mouseY);

			if (row < 0 || drawn == null) {
				// The heading or the footer: swallowed anyway, because a click that lands on the panel
				// and moves an item in the menu behind it is the one failure this design cannot afford.
				return true;
			}

			Row clicked = drawn.rows().get(row);

			if (clicked.kind() == Kind.CANDIDATE) {
				// The caret column expands the row; the rest of it copies the name to search.
				int mx = (int) (mouseX / scale) - panelX;

				if (mx <= caretRight) {
					AuctionOverlay.toggleExpand(clicked.itemId());
				} else {
					AuctionOverlay.copyName(clicked.copyName());
				}
			}

			// A detail or risk row swallows the click and does nothing with it.
			return true;
		}

		/** @return true when the panel took the scroll */
		static boolean scroll(double mouseX, double mouseY, double amount) {
			if (!inside(mouseX, mouseY) || rowCount <= visibleRows) {
				return false;
			}

			// Down is negative, and down through a list means later rows.
			firstRow = Math.clamp(firstRow - (int) Math.signum(amount), 0,
					Math.max(0, rowCount - visibleRows));
			return true;
		}

		static void copied(String name) {
			copiedName = name;
			copiedAt = System.currentTimeMillis();
		}

		static String lastCopied() {
			return copiedName;
		}

		static boolean copiedRecently() {
			return !copiedName.isEmpty() && System.currentTimeMillis() - copiedAt < COPIED_MILLIS;
		}

		/** The footer: the copy confirmation, or the snipe count it replaces. */
		static String footer(int count) {
			if (copiedRecently()) {
				return "copied name";
			}

			return count == 0 ? "" : count == 1 ? "1 snipe" : count + " snipes";
		}
	}
}
