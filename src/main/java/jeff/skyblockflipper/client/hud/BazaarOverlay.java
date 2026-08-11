package jeff.skyblockflipper.client.hud;

import jeff.skyblockflipper.client.CandidateFeed;
import jeff.skyblockflipper.client.SkyblockFlipperClient;
import jeff.skyblockflipper.client.gui.FlipScreen;
import jeff.skyblockflipper.client.mixin.ContainerScreenLayout;
import jeff.skyblockflipper.core.strategy.NpcBasket;
import jeff.skyblockflipper.core.track.BazaarMenu;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;

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
 * The NPC basket, drawn beside Hypixel's own bazaar menu.
 *
 * <p>{@code /flip npc plan} prints the basket into chat, and chat is the wrong place to keep it: by
 * the time you have opened the bazaar, searched the item and reached the price box, the numbers have
 * scrolled away, and you cannot read chat and a menu at the same time. This puts the same allocation
 * on screen while the menu that needs it is open, so the price and the order size are in front of
 * you at the moment you type them.
 *
 * <p><b>It is a display and nothing else.</b> No click is sent, no field is filled, no order is
 * placed. It shows the numbers {@code /flip npc plan} already prints, in a place they can be read
 * from.
 *
 * <p><b>It refreshes itself.</b> The basket comes from {@link CandidateFeed#basket()}, which
 * reallocates when the book revision moves - every poll, about every twenty seconds - so the panel
 * follows the market without being asked for a new plan. It shares that allocation with the Basket
 * tab, so the two cannot quote different prices for the same line.
 *
 * <p><b>Where it draws.</b> Beside the menu, on whichever side has more room, scaled down to fit
 * what is there. At GUI scale 6 - which is what this mod is used at - a 1080p window is about 330
 * scaled pixels wide against a menu 176 wide, leaving roughly 77 either side, so shrinking is the
 * normal case rather than the fallback. The menu's real position comes from
 * {@link ContainerScreenLayout} rather than from assuming every Hypixel menu is a centred 176-wide
 * chest.
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
	private static final int ROW_OPEN = 0x50FFD700;

	private static final int PAD = 4;

	/** Gap between the name line and the numbers under it. */
	private static final int LINE_GAP = 1;

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
					(shown, graphics, mouseX, mouseY, tickProgress) -> render(shown, graphics));
		});
	}

	private static void render(Screen screen, GuiGraphicsExtractor graphics) {
		if (!SkyblockFlipperClient.config().bazaarOverlayEnabled) {
			return;
		}

		NpcBasket.Basket basket = CandidateFeed.basket();

		if (basket.isEmpty()) {
			return;
		}

		String title = screen.getTitle().getString();
		Font font = Minecraft.getInstance().font;
		Board board = board(basket, title, font);

		// The bazaar's own menus by title, plus the product page of anything in the basket, which is
		// only recognisable as the name of a line - see BazaarMenu for why, and where those titles
		// were measured. The layout accessor is checked rather than cast outright: a panel that
		// quietly does not appear beats a ClassCastException on every menu the player opens.
		if (screen instanceof ContainerScreenLayout layout
				&& (BazaarMenu.isBazaar(title) || !board.openProduct().isEmpty())) {
			leftBazaarAt = System.currentTimeMillis();
			drawBesideMenu(screen, layout, graphics, board, font);
			return;
		}

		// Typing a price or an amount happens on a sign, which is not a container menu and carries
		// no title worth matching - and it is the moment the numbers are actually needed. So the
		// panel follows for a few seconds after the bazaar menu it was opened from. A sign opened
		// on your island a minute later is outside the window and gets nothing.
		if (!(screen instanceof AbstractContainerScreen<?>) && !(screen instanceof FlipScreen)
				&& System.currentTimeMillis() - leftBazaarAt <= FOLLOW_MILLIS) {
			drawAtTheEdge(screen, graphics, board, font);
		}
	}

	/** Beside Hypixel's menu, on whichever side of it has more room. */
	private static void drawBesideMenu(Screen screen, ContainerScreenLayout layout,
			GuiGraphicsExtractor graphics, Board board, Font font) {
		int menuLeft = layout.flipper$leftPos();
		int menuRight = menuLeft + layout.flipper$imageWidth();

		int roomLeft = menuLeft - MENU_GAP * 2;
		int roomRight = screen.width - menuRight - MENU_GAP * 2;
		boolean onLeft = roomLeft >= roomRight;

		float scale = fit(Math.max(roomLeft, roomRight), board);

		if (scale <= 0.0f) {
			return;
		}

		draw(graphics, board, font, scale,
				Math.round((onLeft ? MENU_GAP : menuRight + MENU_GAP) / scale),
				Math.round(layout.flipper$topPos() / scale), screen.height);
	}

	/** Against the left edge, for the sign screens that have no menu to sit beside. */
	private static void drawAtTheEdge(Screen screen, GuiGraphicsExtractor graphics, Board board,
			Font font) {
		float scale = fit(screen.width / EDGE_WIDTH_SHARE - MENU_GAP * 2, board);

		if (scale <= 0.0f) {
			return;
		}

		draw(graphics, board, font, scale, Math.round(MENU_GAP / scale), PAD, screen.height);
	}

	/** The scale that fits the board into {@code room} screen pixels, or 0 if none is worth it. */
	private static float fit(int room, Board board) {
		float scale = Math.min(1.0f, (float) room / board.width());
		return scale < MIN_SCALE ? 0.0f : scale;
	}

	private static void draw(GuiGraphicsExtractor graphics, Board board, Font font, float scale,
			int x, int preferredY, int screenHeight) {
		// Everything from here is in panel pixels, so the window has to be measured in them too.
		int panelHeight = Math.round(screenHeight / scale);
		int shown = board.rowsFitting(panelHeight - 2 * PAD);

		if (shown <= 0) {
			return;
		}

		int height = board.height(shown, font);
		int y = Math.clamp(preferredY, PAD, Math.max(PAD, panelHeight - height - PAD));

		graphics.pose().pushMatrix();
		graphics.pose().scale(scale, scale);
		board.draw(graphics, font, x, y, shown);
		graphics.pose().popMatrix();
	}

	private static Board board;
	private static NpcBasket.Basket boardBasket;
	private static String boardTitle = "";

	/**
	 * The laid-out board for this basket and this screen, rebuilt only when one of them changes.
	 *
	 * <p>This runs every frame a bazaar menu is open. Measuring twenty rows of text sixty times a
	 * second to arrive at the same widths is the same waste {@code CandidateFeed} exists to avoid
	 * for the ranked list, and the basket only changes once a poll.
	 */
	private static Board board(NpcBasket.Basket basket, String title, Font font) {
		if (board == null || basket != boardBasket || !title.equals(boardTitle)) {
			boardBasket = basket;
			boardTitle = title;
			board = Board.of(basket, title, font);
		}

		return board;
	}

	/**
	 * One basket laid out as text, measured before anything is drawn.
	 *
	 * <p>Separate from the drawing because the panel has to know how wide it wants to be before it
	 * can work out how far to scale down, and how tall a row is before it can decide how many rows
	 * the window has room for.
	 *
	 * @param openProduct the row the open product page is for, empty on every other screen
	 */
	private record Board(List<Row> rows, String openProduct, int width, int rowHeight,
			int headerHeight) {
		/** One basket line: what to search for, what to post, and how the units divide into orders. */
		private record Row(String name, String post, String units) {
		}

		static Board of(NpcBasket.Basket basket, String title, Font font) {
			List<Row> rows = new ArrayList<>();
			List<String> names = new ArrayList<>();
			int width = TARGET_WIDTH;

			for (NpcBasket.Line line : basket.lines()) {
				// The post price is written out in full and never abbreviated: it is typed into a
				// box character by character, and 84999.9 shortened to 85k is a different order.
				Row row = new Row(line.plan().displayName(),
						String.format("%.1f", line.plan().postPrice()),
						line.orderSplit());

				rows.add(row);
				names.add(row.name());
				width = Math.max(width, PAD * 2 + Math.max(text(font, row.name()),
						text(font, row.post()) + 5 + text(font, row.units())));
			}

			return new Board(rows, BazaarMenu.productPageFor(title, names), width,
					font.lineHeight * 2 + LINE_GAP + 2, font.lineHeight + 3);
		}

		private static int text(Font font, String value) {
			return font.width(Component.literal(value));
		}

		/**
		 * Rows that fit in {@code available} panel pixels.
		 *
		 * <p>Room for the "+N more" footer is always reserved, even when nothing ends up hidden. The
		 * alternative is a layout that fits one extra row until the basket grows, and then reflows
		 * everything by a line the moment it does.
		 */
		int rowsFitting(int available) {
			return Math.clamp((available - headerHeight - rowHeight) / rowHeight, 0, rows.size());
		}

		int height(int shown, Font font) {
			return PAD * 2 + headerHeight + shown * rowHeight
					+ (shown < rows.size() ? font.lineHeight : 0);
		}

		void draw(GuiGraphicsExtractor graphics, Font font, int x, int y, int shown) {
			int hidden = rows.size() - shown;
			int height = height(shown, font);

			graphics.fill(x, y, x + width, y + height, PANEL);
			graphics.fill(x, y, x + width, y + 1, PANEL_EDGE);
			graphics.fill(x, y + height - 1, x + width, y + height, PANEL_EDGE);

			int cursor = y + PAD;

			graphics.text(font, Component.literal("Buy these").withStyle(ChatFormatting.GOLD),
					x + PAD, cursor, TEXT);
			cursor += headerHeight;

			for (int i = 0; i < shown; i++) {
				Row row = rows.get(i);

				// The row for the product page actually open, so a basket of twenty does not have
				// to be read through to find the one in front of you.
				if (!openProduct.isEmpty() && row.name().equalsIgnoreCase(openProduct)) {
					graphics.fill(x + 1, cursor - 1, x + width - 1, cursor + rowHeight - 2, ROW_OPEN);
				}

				graphics.text(font, Component.literal(row.name()), x + PAD, cursor, TEXT);

				int numbersY = cursor + font.lineHeight + LINE_GAP;
				int unitsX = x + width - PAD - text(font, row.units());

				graphics.text(font, Component.literal(row.units()), unitsX, numbersY, TEXT_UNITS);
				graphics.text(font, Component.literal(row.post()),
						unitsX - 5 - text(font, row.post()), numbersY, TEXT_PRICE);

				cursor += rowHeight;
			}

			if (hidden > 0) {
				graphics.text(font, Component.literal("+" + hidden + " more in /flip npc plan"),
						x + PAD, cursor, TEXT_DIM);
			}
		}
	}
}
