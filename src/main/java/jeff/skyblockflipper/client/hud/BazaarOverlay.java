package jeff.skyblockflipper.client.hud;

import jeff.skyblockflipper.client.CandidateFeed;
import jeff.skyblockflipper.client.SkyblockFlipperClient;
import jeff.skyblockflipper.client.gui.FlipScreen;
import jeff.skyblockflipper.client.mixin.ContainerScreenLayout;
import jeff.skyblockflipper.core.config.OverlaySide;
import jeff.skyblockflipper.core.strategy.NpcWorklist;
import jeff.skyblockflipper.core.track.BazaarMenu;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * The NPC worklist, drawn beside Hypixel's own bazaar menu.
 *
 * <p>{@code /flip npc plan} prints the list into chat, and chat is the wrong place to keep it: by
 * the time you have opened the bazaar, searched the item and reached the price box, the numbers have
 * scrolled away, and you cannot read chat and a menu at the same time. This puts the same list on
 * screen while the menu that needs it is open, so the price and the order size are in front of you
 * at the moment you type them.
 *
 * <p><b>It shows the whole trip, not only the new orders.</b> {@link NpcWorklist} puts the claims,
 * the cancels and the reprices ahead of the orders to place, so the panel is a list of clicks in the
 * order to make them rather than a shopping list that ignores what is already on the book.
 *
 * <p><b>No click is sent to the game.</b> Nothing is filled in, nothing is placed, nothing is
 * cancelled. The panel's own clicks copy text to the clipboard and scroll the list, and they are
 * only ever consumed inside the panel's own rectangle - a click on Hypixel's menu reaches Hypixel's
 * menu untouched.
 *
 * <p><b>It refreshes itself.</b> The list comes from {@link CandidateFeed#worklist()}, which is
 * rebuilt when the book moves - every poll, about every twenty seconds - and again whenever the
 * order tracker is fed, so placing an order takes its line off the panel without waiting for a poll.
 * It shares that list with the Basket tab, so the two cannot quote different prices for one line.
 *
 * <p><b>Where it draws.</b> On the side {@code bazaarOverlaySide} names, scaled down to fit what is
 * there. At GUI scale 6 - which is what this mod is used at - a 1080p window is about 330 scaled
 * pixels wide against a menu 176 wide, leaving roughly 77 either side, so shrinking is the normal
 * case rather than the fallback. The menu's real position comes from {@link ContainerScreenLayout}
 * rather than from assuming every Hypixel menu is a centred 176-wide chest.
 *
 * <p><b>It follows the sign.</b> An amount or a price is typed on a sign, which is not a container
 * menu, carries no title worth matching, and is the exact moment the numbers are needed. So for a
 * few seconds after the bazaar menu it came from, the panel stays on screen against the left edge.
 * That window will also catch a chat box or a pause menu opened straight after leaving the bazaar,
 * which is a small price for covering the one screen the plan is actually typed into.
 */
public final class BazaarOverlay {
	private static final int PANEL = 0xD0000000;
	private static final int PANEL_EDGE = 0xFF404040;
	private static final int TEXT = 0xFFFFFFFF;
	private static final int TEXT_DIM = 0xFFAAAAAA;
	private static final int TEXT_PRICE = 0xFF9FD4FF;
	private static final int TEXT_UNITS = 0xFF55FF55;
	private static final int TEXT_COPIED = 0xFFFFD700;
	private static final int ROW_OPEN = 0x50FFD700;
	private static final int ROW_HOVER = 0x30FFFFFF;

	private static final int PAD = 4;

	/** Gap between the name line and the numbers under it. */
	private static final int LINE_GAP = 1;

	/** Gap between the price and the units on the numbers line, and the click boundary between them. */
	private static final int NUMBER_GAP = 5;

	/** Width the panel would like before it starts shrinking to fit the space beside the menu. */
	private static final int TARGET_WIDTH = 150;

	/** Below this the text stops being worth drawing, so the panel stays away instead. */
	private static final float MIN_SCALE = 0.34f;

	/** Gap between the panel and the menu, in real screen pixels. */
	private static final int MENU_GAP = 3;

	/**
	 * How long the panel keeps following after the bazaar menu closes.
	 *
	 * <p>Long enough to cover a sign opening in the place of the menu it came from, short enough
	 * that a sign opened for something else is not decorated with a shopping list.
	 */
	private static final long FOLLOW_MILLIS = 8_000L;

	/** How long "copied" stays under the list before the hint comes back. */
	private static final long COPIED_MILLIS = 2_000L;

	/** Share of the window the panel may take when there is no menu to sit beside. */
	private static final int EDGE_WIDTH_SHARE = 3;

	/** When a bazaar menu was last on screen, which is what {@link #FOLLOW_MILLIS} runs from. */
	private static long leftBazaarAt;

	/**
	 * AFTER_INIT fires again on every window resize and a screen keeps whatever was registered on
	 * it, so attaching twice would lay the panel out twice a frame. Held weakly, because a strong
	 * reference here would keep a closed screen alive.
	 */
	private static WeakReference<Screen> attached = new WeakReference<>(null);

	private BazaarOverlay() {
	}

	public static void register() {
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (attached.get() == screen) {
				return;
			}

			attached = new WeakReference<>(screen);
			// After the background rather than after the whole screen, so a tooltip draws over the
			// panel instead of under it. A tooltip is what the player asked for by hovering; the
			// panel is not, and at GUI scale 6 there is little enough room beside the menu that a
			// wide tooltip reaches into it often.
			ScreenEvents.afterBackground(screen).register(
					(shown, graphics, mouseX, mouseY, tickProgress) -> render(shown, graphics,
							mouseX, mouseY));

			// Cancelling the event is what stops the click or the scroll reaching Hypixel's menu, and
			// both handlers only do that for input inside the panel's own rectangle. Everywhere else
			// they return true and the game never knows they ran.
			ScreenMouseEvents.allowMouseClick(screen).register(
					(shown, event) -> !Hit.click(event.x(), event.y()));
			ScreenMouseEvents.allowMouseScroll(screen).register(
					(shown, mouseX, mouseY, horizontal, vertical) -> !Hit.scroll(mouseX, mouseY,
							vertical));
		});
	}

	private static void render(Screen screen, GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		if (!SkyblockFlipperClient.config().bazaarOverlayEnabled) {
			Hit.clear();
			return;
		}

		NpcWorklist.Worklist worklist = CandidateFeed.worklist();

		if (worklist.pending().isEmpty()) {
			Hit.clear();
			return;
		}

		String title = screen.getTitle().getString();
		Font font = Minecraft.getInstance().font;
		Board board = board(worklist, title, font);

		// The bazaar's own menus by title, plus the product page of anything in the list, which is
		// only recognisable as the name of a line - see BazaarMenu for why, and where those titles
		// were measured. The layout accessor is checked rather than cast outright: a panel that
		// quietly does not appear beats a ClassCastException on every menu the player opens.
		if (screen instanceof ContainerScreenLayout layout
				&& (BazaarMenu.isBazaar(title) || !board.openProduct().isEmpty())) {
			leftBazaarAt = System.currentTimeMillis();
			drawBesideMenu(screen, layout, graphics, board, font, mouseX, mouseY);
			return;
		}

		// Typing a price or an amount happens on a sign, which is not a container menu and carries
		// no title worth matching - and it is the moment the numbers are actually needed. So the
		// panel follows for a few seconds after the bazaar menu it was opened from. A sign opened
		// on your island a minute later is outside the window and gets nothing.
		if (!(screen instanceof AbstractContainerScreen<?>) && !(screen instanceof FlipScreen)
				&& System.currentTimeMillis() - leftBazaarAt <= FOLLOW_MILLIS) {
			drawAtTheEdge(screen, graphics, board, font, mouseX, mouseY);
			return;
		}

		Hit.clear();
	}

	/** Beside Hypixel's menu, on the side the settings name. */
	private static void drawBesideMenu(Screen screen, ContainerScreenLayout layout,
			GuiGraphicsExtractor graphics, Board board, Font font, int mouseX, int mouseY) {
		int menuLeft = layout.flipper$leftPos();
		int menuRight = menuLeft + layout.flipper$imageWidth();

		int roomLeft = menuLeft - MENU_GAP * 2;
		int roomRight = screen.width - menuRight - MENU_GAP * 2;

		// The width one row of text needs to be worth drawing at all, which is what a fixed side is
		// allowed to be overruled on.
		int minimum = Math.round(board.width() * MIN_SCALE);
		OverlaySide side = SkyblockFlipperClient.config().overlaySide();
		boolean onLeft = side.drawOnLeft(roomLeft, roomRight, minimum);

		float scale = fit(onLeft ? roomLeft : roomRight, board);

		if (scale <= 0.0f) {
			Hit.clear();
			return;
		}

		draw(graphics, board, font, scale,
				Math.round((onLeft ? MENU_GAP : menuRight + MENU_GAP) / scale),
				Math.round(layout.flipper$topPos() / scale), screen.height, mouseX, mouseY);
	}

	/** Against the left edge, for the sign screens that have no menu to sit beside. */
	private static void drawAtTheEdge(Screen screen, GuiGraphicsExtractor graphics, Board board,
			Font font, int mouseX, int mouseY) {
		float scale = fit(screen.width / EDGE_WIDTH_SHARE - MENU_GAP * 2, board);

		if (scale <= 0.0f) {
			Hit.clear();
			return;
		}

		draw(graphics, board, font, scale, Math.round(MENU_GAP / scale), PAD, screen.height,
				mouseX, mouseY);
	}

	/** The scale that fits the board into {@code room} screen pixels, or 0 if none is worth it. */
	private static float fit(int room, Board board) {
		float scale = Math.min(1.0f, (float) room / board.width());
		return scale < MIN_SCALE ? 0.0f : scale;
	}

	private static void draw(GuiGraphicsExtractor graphics, Board board, Font font, float scale,
			int x, int preferredY, int screenHeight, int mouseX, int mouseY) {
		// Everything from here is in panel pixels, so the window has to be measured in them too.
		int panelHeight = Math.round(screenHeight / scale);
		int shown = board.rowsFitting(panelHeight - 2 * PAD);

		if (shown <= 0) {
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

	private static Board board;
	private static NpcWorklist.Worklist boardWorklist;
	private static String boardTitle = "";

	/**
	 * The laid-out board for this worklist and this screen, rebuilt only when one of them changes.
	 *
	 * <p>This runs every frame a bazaar menu is open. Measuring twenty rows of text sixty times a
	 * second to arrive at the same widths is the same waste {@code CandidateFeed} exists to avoid
	 * for the ranked list, and the list only changes once a poll.
	 */
	private static Board board(NpcWorklist.Worklist worklist, String title, Font font) {
		if (board == null || worklist != boardWorklist || !title.equals(boardTitle)) {
			boardWorklist = worklist;
			boardTitle = title;
			board = Board.of(worklist, title, font);
			Hit.reset(board);
		}

		return board;
	}

	/**
	 * One worklist laid out as text, measured before anything is drawn.
	 *
	 * <p>Separate from the drawing because the panel has to know how wide it wants to be before it
	 * can work out how far to scale down, and how tall a row is before it can decide how many rows
	 * the window has room for.
	 *
	 * @param openProduct the row the open product page is for, empty on every other screen
	 */
	private record Board(List<Row> rows, String openProduct, int holding, int width, int rowHeight,
			int headerHeight) {
		/**
		 * One line of work: what to search for, what to type, and how the units divide into orders.
		 *
		 * @param price the post price written out in full and never abbreviated - it is typed into a
		 *              box character by character, and 84999.9 shortened to 85k is a different order.
		 *              Empty on a claim or a cancel, which are a button rather than a number
		 */
		private record Row(NpcWorklist.Task task, String name, String price, String units) {
		}

		static Board of(NpcWorklist.Worklist worklist, String title, Font font) {
			List<Row> rows = new ArrayList<>();
			List<String> names = new ArrayList<>();
			int width = TARGET_WIDTH;

			for (NpcWorklist.Task task : worklist.pending()) {
				Row row = new Row(task, task.verb() + " " + task.displayName(),
						task.hasPrice() ? String.format("%.1f", task.price()) : "",
						task.orderSplit());

				rows.add(row);
				names.add(task.displayName());
				width = Math.max(width, PAD * 2 + Math.max(text(font, row.name()),
						text(font, row.price()) + NUMBER_GAP + text(font, row.units())));
			}

			return new Board(rows, BazaarMenu.productPageFor(title, names), worklist.holding(),
					width, font.lineHeight * 2 + LINE_GAP + 2, font.lineHeight + 3);
		}

		private static int text(Font font, String value) {
			return font.width(Component.literal(value));
		}

		/**
		 * Rows that fit in {@code available} panel pixels.
		 *
		 * <p>Room for the footer is always reserved, even when nothing ends up hidden. The
		 * alternative is a layout that fits one extra row until the list grows, and then reflows
		 * everything by a line the moment it does.
		 */
		int rowsFitting(int available) {
			return Math.clamp((available - headerHeight - rowHeight) / rowHeight, 0, rows.size());
		}

		int height(int shown, Font font) {
			return PAD * 2 + headerHeight + shown * rowHeight + font.lineHeight;
		}

		/** Where the numbers line of row {@code i} starts, relative to the panel's top. */
		int numbersOffset(int i, Font font) {
			return PAD + headerHeight + i * rowHeight + font.lineHeight + LINE_GAP;
		}

		void draw(GuiGraphicsExtractor graphics, Font font, int x, int y, int shown, int hovered) {
			int height = height(shown, font);
			int first = Hit.firstRow();
			int last = Math.min(rows.size(), first + shown);

			graphics.fill(x, y, x + width, y + height, PANEL);
			graphics.fill(x, y, x + width, y + 1, PANEL_EDGE);
			graphics.fill(x, y + height - 1, x + width, y + height, PANEL_EDGE);

			int cursor = y + PAD;

			graphics.text(font, Component.literal(heading(first, last))
					.withStyle(ChatFormatting.GOLD), x + PAD, cursor, TEXT);
			cursor += headerHeight;

			for (int i = first; i < last; i++) {
				Row row = rows.get(i);

				// The row for the product page actually open, so a list of twenty does not have to
				// be read through to find the one in front of you.
				if (!openProduct.isEmpty()
						&& row.task().displayName().equalsIgnoreCase(openProduct)) {
					graphics.fill(x + 1, cursor - 1, x + width - 1, cursor + rowHeight - 2, ROW_OPEN);
				} else if (i == hovered) {
					graphics.fill(x + 1, cursor - 1, x + width - 1, cursor + rowHeight - 2, ROW_HOVER);
				}

				graphics.text(font, Component.literal(row.name()), x + PAD, cursor, TEXT);

				int numbersY = cursor + font.lineHeight + LINE_GAP;
				int unitsX = x + width - PAD - text(font, row.units());

				graphics.text(font, Component.literal(row.units()), unitsX, numbersY, TEXT_UNITS);

				if (!row.price().isEmpty()) {
					graphics.text(font, Component.literal(row.price()),
							unitsX - NUMBER_GAP - text(font, row.price()), numbersY, TEXT_PRICE);
				}

				cursor += rowHeight;
			}

			graphics.text(font, Component.literal(Hit.footer(this)), x + PAD, cursor,
					Hit.copiedRecently() ? TEXT_COPIED : TEXT_DIM);
		}

		/** The heading says how far down a scrolled list you are, and nothing when it all fits. */
		private String heading(int first, int last) {
			return rows.size() == last - first
					? "Do these (" + rows.size() + ")"
					: "Do these (" + (first + 1) + "-" + last + " of " + rows.size() + ")";
		}

		String hint() {
			return holding > 0 ? "click to copy - " + holding + " ok" : "click name/number to copy";
		}
	}

	/**
	 * Where the panel was last drawn, in real screen pixels, and what a click there means.
	 *
	 * <p>Static because an immediate-mode panel has no widget to ask. The render pass records the
	 * rectangle it drew into and the mouse handlers hit-test against it, which is sound as long as
	 * only one panel is on screen at a time - it is drawn by one screen at a time, and cleared
	 * whenever a frame decides not to draw it.
	 */
	private static final class Hit {
		/** Nothing has been drawn: every click and scroll passes straight through. */
		private static boolean live;

		private static int x;
		private static int y;
		private static int width;
		private static int height;
		private static float scale = 1.0f;

		/** Panel-space offsets, scaled on the way in from the mouse. */
		private static int rowHeight = 1;
		private static int rowsTop;
		private static int numbersTop;
		private static int lineHeight;
		private static int visibleRows;

		/** The row at the top of the visible window, which is what scrolling moves. */
		private static int firstRow;

		private static int rowCount;
		private static String copied = "";
		private static long copiedAt;

		private Hit() {
		}

		static void clear() {
			live = false;
		}

		/**
		 * A rebuilt list keeps the scroll position rather than jumping back to the top.
		 *
		 * <p>The worklist is rebuilt on every poll whether or not it changed, about three times a
		 * minute. Resetting here would mean a player who scrolled to row fifteen to read it is
		 * returned to row one within twenty seconds, which makes the panel unusable for exactly the
		 * long lists scrolling was added for. {@link #laidOut} clamps it, so a list that got shorter
		 * cannot leave the window past the end.
		 */
		static void reset(Board board) {
			rowCount = board.rows().size();
		}

		static int firstRow() {
			return firstRow;
		}

		static void laidOut(Board board, int panelX, int panelY, float panelScale, int shown,
				Font font) {
			live = true;
			scale = panelScale;
			x = Math.round(panelX * panelScale);
			y = Math.round(panelY * panelScale);
			width = Math.round(board.width() * panelScale);
			height = Math.round(board.height(shown, font) * panelScale);

			rowHeight = board.rowHeight();
			rowsTop = panelY + PAD + board.headerHeight();
			numbersTop = panelY + board.numbersOffset(0, font);
			lineHeight = font.lineHeight;
			visibleRows = shown;
			rowCount = board.rows().size();

			// A list that shrank under a scrolled window - orders were placed, or the book moved -
			// would otherwise leave the panel showing blank space below the last row.
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

			int panelY = (int) (mouseY / scale);

			if (panelY < rowsTop) {
				return -1;
			}

			int row = firstRow + (panelY - rowsTop) / rowHeight;
			return row < firstRow + visibleRows && row < rowCount ? row : -1;
		}

		/**
		 * @return true when the panel took the click, which is what stops it reaching the menu
		 */
		static boolean click(double mouseX, double mouseY) {
			if (!inside(mouseX, mouseY)) {
				return false;
			}

			int row = hoveredRow(mouseX, mouseY);

			if (row < 0 || board == null) {
				// Inside the panel but not on a row: the heading or the footer. Swallowed anyway,
				// because a click that lands on the panel and moves an item in the menu behind it
				// is the one failure this whole design cannot afford.
				return true;
			}

			copy(board.rows().get(row), mouseX, mouseY);
			return true;
		}

		/**
		 * Which of the three things on a row was clicked.
		 *
		 * <p>The name line copies the item name, which is what goes into the bazaar's search sign.
		 * The numbers line is split at the gap between them: the left half copies the price and the
		 * right half copies the size, which are the two other things typed on a sign. Clicking a row
		 * with no price - a claim, a cancel - always copies the name, because there is no number to
		 * type for either.
		 */
		private static void copy(Board.Row row, double mouseX, double mouseY) {
			int panelY = (int) (mouseY / scale);
			int rowTop = numbersTop + (hoveredRowIndex(panelY) * rowHeight);
			boolean onNumbers = panelY >= rowTop && panelY < rowTop + lineHeight;

			if (!onNumbers || row.price().isEmpty()) {
				put(row.task().displayName(), "name");
				return;
			}

			Font font = Minecraft.getInstance().font;
			int unitsLeft = x + width - Math.round((PAD + Board.text(font, row.units())) * scale);

			if (mouseX >= unitsLeft) {
				// The units of one order rather than the whole line: it is what goes in the amount
				// box, and a split line's total would be rejected by the bazaar.
				put(row.units(), "size");
			} else {
				put(row.price(), "price");
			}
		}

		private static int hoveredRowIndex(int panelY) {
			return Math.max(0, (panelY - rowsTop) / rowHeight);
		}

		private static void put(String value, String what) {
			Minecraft.getInstance().keyboardHandler.setClipboard(value);
			copied = "copied " + what;
			copiedAt = System.currentTimeMillis();
		}

		/**
		 * @return true when the panel took the scroll
		 */
		static boolean scroll(double mouseX, double mouseY, double amount) {
			if (!inside(mouseX, mouseY) || rowCount <= visibleRows) {
				return false;
			}

			// Down is negative, and down through a list means later rows.
			firstRow = Math.clamp(firstRow - (int) Math.signum(amount), 0,
					Math.max(0, rowCount - visibleRows));
			return true;
		}

		static boolean copiedRecently() {
			return System.currentTimeMillis() - copiedAt < COPIED_MILLIS;
		}

		/** What the last line of the panel says: the copy confirmation, or the hint it replaces. */
		static String footer(Board board) {
			return copiedRecently() ? copied : board.hint();
		}
	}
}
