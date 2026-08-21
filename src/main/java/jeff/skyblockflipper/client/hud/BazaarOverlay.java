package jeff.skyblockflipper.client.hud;

import jeff.skyblockflipper.client.CandidateFeed;
import jeff.skyblockflipper.client.NpcCheckInService;
import jeff.skyblockflipper.client.SkyblockFlipperClient;
import jeff.skyblockflipper.client.mixin.ContainerScreenLayout;
import jeff.skyblockflipper.client.track.MenuReader;
import jeff.skyblockflipper.client.track.TrackerService;
import jeff.skyblockflipper.core.config.OverlaySide;
import jeff.skyblockflipper.core.model.Stacking;
import jeff.skyblockflipper.core.strategy.BazaarStep;
import jeff.skyblockflipper.core.strategy.NpcWorklist;
import jeff.skyblockflipper.core.strategy.StrategyKind;
import jeff.skyblockflipper.core.strategy.WorkedJob;
import jeff.skyblockflipper.core.track.BazaarMenu;
import jeff.skyblockflipper.core.track.BazaarSlots;
import jeff.skyblockflipper.core.track.CapturedMenu;
import jeff.skyblockflipper.core.track.TrackedOrder;

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
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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
 * few seconds after the bazaar menu it came from, the panel stays on screen against the left edge -
 * <b>on a sign and nowhere else</b>. It used to draw on any screen at all inside that window, which
 * put it over chat, the pause menu and the mod's own settings screen, where its clicks are swallowed
 * and copy an item name instead of pressing the button underneath.
 */
public final class BazaarOverlay {
	private static final int PANEL = 0xE0080A0D;
	private static final int PANEL_EDGE = 0xFF3A4048;
	private static final int HEADER_RULE = 0x40FFFFFF;
	private static final int GROUP_RULE = 0x22FFFFFF;
	private static final int ROW_STRIPE = 0x14FFFFFF;
	private static final int TEXT = 0xFFF0F2F5;
	private static final int TEXT_DIM = 0xFF8B939C;
	private static final int TEXT_PRICE = 0xFF7FB8FF;
	private static final int TEXT_UNITS = 0xFFBFD8B0;
	private static final int TEXT_COPIED = 0xFFFFD700;

	/** The between-rounds line: the colour of a reprice, dimmed, because that is what it is about. */
	private static final int TEXT_WAITING = 0xFFC98A4B;
	private static final int ROW_OPEN = 0x50FFD700;

	/** A section title: which job the rows under it belong to. Gold, like the panel's own heading. */
	private static final int TEXT_HEADING = 0xFFFFD24A;

	/**
	 * The group a heading belongs to, which is no group at all - it exists so the rule that
	 * separates one kind of work from the next also lands above every section title.
	 */
	private static final int HEADING_GROUP = -1;
	private static final int ROW_HOVER = 0x30FFFFFF;

	/**
	 * One colour per {@link NpcWorklist.Kind}, which is what makes a twenty-row list scannable.
	 *
	 * <p>Every row used to be the same white, so the only thing separating "these coins are already
	 * yours" from "type this price into a box" was reading the first word of each line. The list is
	 * already sorted by kind, so colouring the verb and the stripe beside it turns it into blocks a
	 * player can find their place in without reading.
	 */
	private static int colourOf(NpcWorklist.Kind kind) {
		return switch (kind) {
			case CLAIM -> 0xFFFFD24A;
			case CANCEL -> 0xFFFF6B6B;
			case REPRICE -> 0xFFFFA65C;
			case PLACE -> 0xFF6FD98A;
			case HOLD -> TEXT_DIM;
		};
	}

	/**
	 * The same idea for a worked flip: one colour per stage, so the buying, the transformation and
	 * the sale read as three blocks rather than as one wall. Shared by craft, combine and spread,
	 * because a player working two of them at once should not have to learn two colour schemes.
	 */
	private static int colourOf(WorkedJob.Stage stage) {
		return switch (stage) {
			case BUY_ORDER -> 0xFF6FD98A;
			case INSTANT_BUY -> 0xFF7FB8FF;
			case TRANSFORM -> 0xFFD59BFF;
			case SELL_OFFER -> 0xFFFFD24A;
		};
	}

	private static final int PAD = 4;

	/** Width of the coloured bar down the left of each row, in panel pixels. */
	private static final int ACCENT = 2;

	/** Space between that bar and the text. */
	private static final int ACCENT_GAP = 3;

	/** Gap between the name line and the numbers under it. */
	private static final int LINE_GAP = 1;

	/** Gap between the price and the units on the numbers line, and the click boundary between them. */
	private static final int NUMBER_GAP = 5;

	/** Drawn before the price and after the units, so two bare numbers cannot read as one. */
	private static final String PRICE_MARK = "@";
	private static final String UNITS_MARK = "x";

	/** Width the panel would like before it starts shrinking to fit the space beside the menu. */
	private static final int TARGET_WIDTH = 150;

	/** Below this the text stops being worth drawing, so the panel stays away instead. */
	private static final float MIN_SCALE = 0.34f;

	/** Gap between the panel and the menu, in real screen pixels. */
	private static final int MENU_GAP = 3;

	/**
	 * How long the panel keeps following after the bazaar menu closes.
	 *
	 * <p>Was eight seconds, which is how long it takes to read a sign and start typing: the number
	 * vanished from under the player mid-order, and again whenever a keypress put another screen in
	 * front of the sign for a moment. Ninety seconds covers a whole order typed slowly, and the
	 * window is refreshed by every screen in the bazaar's own chain - the product page, the amount
	 * page, the sign - so it only runs down once the player has actually left.
	 */
	private static final long FOLLOW_MILLIS = 90_000L;

	/**
	 * Pixels of text one line of a sign holds, which is {@code SignBlockEntity.getMaxTextLineWidth}.
	 *
	 * <p>The sign screen drops any keystroke or paste that would take a line past this, so a long
	 * item name cannot be pasted into the search at all - measured live on "Transmission Tuner". The
	 * bazaar searches on a prefix, so the panel offers the longest prefix that fits and the search
	 * still lands on the item.
	 */
	private static final int SIGN_LINE_WIDTH = 90;

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

		// Every flip the player said they are working, then the basket under them. The panel used to
		// draw exactly one of the three, so picking a second flip - or picking a bazaar row merely to
		// read it - took the first one off the screen while its orders were still resting. One list
		// with a section per job is what the player actually has open.
		List<WorkedJob> jobs = CandidateFeed.jobs();
		NpcWorklist.Worklist worklist = CandidateFeed.worklist();
		String note = note(worklist);

		// Drawn for the note alone when there are no rows, which is the between-rounds case: the book
		// has walked past orders this round is not asking about, and a panel that simply vanishes then
		// is indistinguishable from a panel that has stopped working.
		if (jobs.isEmpty() && worklist.pending().isEmpty() && note.isEmpty()) {
			Hit.clear();
			return;
		}

		String title = screen.getTitle().getString();
		Font font = Minecraft.getInstance().font;

		// Half the bazaar does not name itself. Browsing and the orders are titled unmistakably; a
		// product page is titled "Item Upgrades ➜ Transmission Tuner" and the amount page "How many
		// do you want?", so those are recognised by the buttons on them - which is what the panel
		// failed to do, leaving the two screens an order is actually placed on with nothing on them.
		// The layout accessor is checked rather than cast outright: a panel that quietly does not
		// appear beats a ClassCastException on every menu the player opens.
		if (screen instanceof ContainerScreenLayout layout
				&& screen instanceof AbstractContainerScreen<?> container) {
			boolean flow = Guidance.update(container, worklist);

			// What the box under the cursor is for, in place of the countdown, because the box is the
			// one thing on screen the player is about to act on. It is how a claim-before-reprice is
			// said at all: the row above says "reprice", and the click the menu actually wants first
			// is the claim on the same row.
			String stepNote = Guidance.stepNote();
			Board board = board(jobs, worklist, title, stepNote.isEmpty() ? note : stepNote, font);

			if (flow || BazaarMenu.isBazaar(title) || !board.openProduct().isEmpty()) {
				leftBazaarAt = System.currentTimeMillis();

				// Before the panel, because the panel scales the pose and this draws in real screen
				// pixels, on Hypixel's menu rather than beside it.
				Guidance.draw(graphics, container, layout);

				drawBesideMenu(screen, layout, graphics, board, font, mouseX, mouseY);
				return;
			}

			Guidance.clear();
			Hit.clear();
			return;
		}

		boolean onASign = screen instanceof AbstractSignEditScreen;

		Guidance.leftTheMenu(onASign);

		// On a sign, the panel says the one thing the screen is asking for rather than the whole
		// list: the number or the name to type into it.
		Board board = board(jobs, worklist, title,
				Guidance.typing() ? Guidance.typeNote() : note, font);

		// Typing a price or an amount happens on a sign, which is not a container menu and carries
		// no title worth matching - and it is the moment the numbers are actually needed. So the
		// panel follows the bazaar menu it was opened from. A sign opened on your island long
		// afterwards is outside the window and gets nothing, and a screen that is not a sign gets
		// nothing at any time: chat, the pause menu and the settings screen all have their own
		// clicks, and this panel eats every click that lands on it.
		if (onASign && System.currentTimeMillis() - leftBazaarAt <= FOLLOW_MILLIS) {
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

		// A board with rows and no room for any of them is not worth a panel. A board with no rows
		// at all is the between-rounds note, which is the whole reason it is on screen.
		if (shown <= 0 && !board.rows().isEmpty()) {
			Hit.clear();
			return;
		}

		int height = board.height(shown, font);
		int y = Math.clamp(preferredY, PAD, Math.max(PAD, panelHeight - height - PAD));

		Hit.laidOut(board, x, y, scale, shown, font);

		// The list is on screen, so the player has been told. Without this the panel was the one way
		// of working the basket that never restarted the reminder, and the chime arrived while they
		// were placing the orders it was about to ask for - fault 1 in the round ADR.
		if (shown > 0) {
			NpcCheckInService.acknowledge();
		}

		graphics.pose().pushMatrix();
		graphics.pose().scale(scale, scale);
		board.draw(graphics, font, x, y, shown, Hit.hoveredRow(mouseX, mouseY));
		graphics.pose().popMatrix();
	}

	private static Board board;
	private static List<WorkedJob> boardJobs = List.of();
	private static long boardOrders = -1L;
	private static NpcWorklist.Worklist boardWorklist;
	private static String boardTitle = "";
	private static String boardNote = "";

	/**
	 * The laid-out board for this worklist and this screen, rebuilt only when one of them changes.
	 *
	 * <p>This runs every frame a bazaar menu is open. Measuring twenty rows of text sixty times a
	 * second to arrive at the same widths is the same waste {@code CandidateFeed} exists to avoid
	 * for the ranked list, and the list only changes once a poll.
	 */
	private static Board board(List<WorkedJob> jobs, NpcWorklist.Worklist worklist, String title,
			String note, Font font) {
		long orders = TrackerService.orderRevision();

		if (board == null || jobs != boardJobs || orders != boardOrders || worklist != boardWorklist
				|| !title.equals(boardTitle) || !note.equals(boardNote)) {
			boardJobs = jobs;
			boardOrders = orders;
			boardWorklist = worklist;
			boardTitle = title;
			boardNote = note;
			board = Board.of(jobs, TrackerService.orders(), worklist, title, note, font);
			Hit.reset(board);
		}

		return board;
	}

	/** How often the countdown in the note is recomputed. It is quoted in minutes. */
	private static final long NOTE_MILLIS = 1_000L;

	private static String note = "";
	private static long notedAt;

	/**
	 * The between-rounds line, refreshed on a timer rather than every frame.
	 *
	 * <p>It counts down, so it cannot be cached with the board the way the rows are - and it cannot
	 * be rebuilt sixty times a second either, for the reason on {@link #board}. A second is finer
	 * than a number quoted in minutes needs.
	 *
	 * <p>The wording comes from {@code core} with the rest of the worklist, so the panel, the Basket
	 * tab and {@code /flip npc reprice} cannot describe the same round differently.
	 */
	private static String note(NpcWorklist.Worklist worklist) {
		long now = System.currentTimeMillis();

		if (worklist != boardWorklist || now - notedAt >= NOTE_MILLIS) {
			notedAt = now;
			note = worklist.waitingNote(now);
		}

		return note;
	}

	/**
	 * One worklist laid out as text, measured before anything is drawn.
	 *
	 * <p>Separate from the drawing because the panel has to know how wide it wants to be before it
	 * can work out how far to scale down, and how tall a row is before it can decide how many rows
	 * the window has room for.
	 *
	 * @param openProduct the row the open product page is for, empty on every other screen
	 * @param note        the between-rounds line under the heading, empty when nothing is waiting
	 */
	private record Board(List<Row> rows, String openProduct, String note, String label,
			boolean guided, int holding, int width, int rowHeight, int headerHeight,
			int basketFirstRow) {
		/**
		 * One line of work: what to search for, what to type, and how the units divide into orders.
		 *
		 * @param price the post price written out in full and never abbreviated - it is typed into a
		 *              box character by character, and 84999.9 shortened to 85k is a different order.
		 *              Empty on a claim or a cancel, which are a button rather than a number
		 */
		private record Row(int colour, int group, String verb, String name, String price,
				String units, boolean heading) {
			Row(int colour, int group, String verb, String name, String price, String units) {
				this(colour, group, verb, name, price, units, false);
			}

			/**
			 * A section title: which job the rows under it belong to, and how much of it is done.
			 *
			 * <p>Laid out as an ordinary row rather than as a shorter one, because the panel's whole
			 * scroll and hit-test arithmetic is one row height - a heading of its own height would
			 * make every row index a search instead of a division.
			 */
			static Row heading(String verb, String name, String progress) {
				return new Row(TEXT_HEADING, HEADING_GROUP, verb, name, "", progress, true);
			}
		}

		/**
		 * Every worked flip, then the basket, as one scrollable list.
		 *
		 * <p><b>Jobs first.</b> They are what the player explicitly chose to work and what they have
		 * coins committed to; the basket is the standing list and sits under them. The basket keeps
		 * the panel's own heading when there are no jobs, so a session that only ever works the NPC
		 * side sees exactly the panel it always did.
		 *
		 * <p><b>A section heading is an ordinary row.</b> One row height everywhere is what lets the
		 * scroll offset and the hit test stay a division rather than a search, and the heading's
		 * second line is not wasted - it carries the done count.
		 *
		 * @param orders every order the tracker holds, for the progress badges. Empty when
		 *               auto-tracking is off, which draws no badges rather than wrong ones
		 */
		static Board of(List<WorkedJob> jobs, List<TrackedOrder> orders,
				NpcWorklist.Worklist worklist, String title, String note, Font font) {
			List<Row> rows = new ArrayList<>();
			List<String> names = new ArrayList<>();
			int width = Math.max(TARGET_WIDTH, PAD * 2 + text(font, note));
			int indent = PAD + ACCENT + ACCENT_GAP;

			for (WorkedJob job : jobs) {
				rows.add(Row.heading(headingVerb(job.kind()), job.displayName(),
						progressOf(job, orders)));
				names.add(job.displayName());

				for (WorkedJob.Step step : job.steps()) {
					// The badge rides on the verb rather than in a column of its own: the verb is
					// already the coloured half of the line, and a fourth column at this width costs
					// more than it says.
					rows.add(new Row(colourOf(step.stage()), step.stage().ordinal(),
							job.progressOf(step, orders).badge() + " " + step.label(),
							step.displayName(),
							step.stage().priced() ? String.format("%.1f", step.price()) : "",
							step.orderSplit()));
					names.add(step.displayName());
				}

				// Said on the section rather than in the panel's note, which belongs to the basket.
				if (!job.note().isEmpty()) {
					rows.add(new Row(TEXT_WAITING, HEADING_GROUP, "", job.note(), "", "", true));
				}
			}

			int basketFirstRow = rows.size();

			// A heading of its own only when something is above it. On its own it keeps the panel's
			// heading, which is where the scroll position and the resting count are already said.
			if (!jobs.isEmpty() && !worklist.pending().isEmpty()) {
				rows.add(Row.heading("NPC", "basket", ""));
				basketFirstRow = rows.size();
			}

			for (NpcWorklist.Task task : worklist.pending()) {
				rows.add(new Row(colourOf(task.kind()), task.kind().ordinal(), task.verb(),
						task.displayName(),
						task.hasPrice() ? String.format("%.1f", task.price()) : "",
						task.orderSplit()));
				names.add(task.displayName());
			}

			for (Row row : rows) {
				int nameLine = text(font, row.verb() + " " + row.name());
				int numberLine = text(font, PRICE_MARK) + text(font, row.price()) + NUMBER_GAP
						+ text(font, row.units()) + text(font, UNITS_MARK);

				width = Math.max(width, indent + PAD + Math.max(nameLine, numberLine));
			}

			String label = jobs.isEmpty() ? "Do these" : "Work";

			width = Math.max(width, PAD * 2 + text(font, label + " (" + rows.size() + ")"));

			return new Board(rows, BazaarMenu.productPageFor(title, names), note, label, true,
					worklist.holding(), width, font.lineHeight * 2 + LINE_GAP + 2,
					font.lineHeight + 5 + (note.isEmpty() ? 0 : font.lineHeight + LINE_GAP),
					basketFirstRow);
		}

		/** The word that names a job's strategy in a section heading. */
		private static String headingVerb(StrategyKind kind) {
			return switch (kind) {
				case CRAFT -> "Craft";
				case COMBINE -> "Combine";
				case BAZAAR_SPREAD -> "Spread";
				default -> "Flip";
			};
		}

		/** {@code 1/3 done}, or nothing when the tracker cannot see any of this job's steps. */
		private static String progressOf(WorkedJob job, List<TrackedOrder> orders) {
			int trackable = job.trackableCount();

			return orders.isEmpty() || trackable == 0
					? ""
					: job.doneCount(orders) + "/" + trackable + " done";
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
			int right = x + width;
			int textX = x + PAD + ACCENT + ACCENT_GAP;

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

			// Under the heading and above the rule, so it reads as part of what this list is rather
			// than as another row to click.
			if (!note.isEmpty()) {
				graphics.text(font, Component.literal(note), x + PAD,
						cursor + font.lineHeight + LINE_GAP, TEXT_WAITING);
			}

			graphics.fill(x + 1, cursor + headerHeight - 3, right - 1, cursor + headerHeight - 2,
					HEADER_RULE);
			cursor += headerHeight;

			for (int i = first; i < last; i++) {
				Row row = rows.get(i);
				int bottom = cursor + rowHeight - 2;

				// Zebra striping under everything else. Two-line rows with no background of their own
				// ran together into a wall of text, which is what made a twenty-row list unreadable.
				if ((i & 1) == 1) {
					graphics.fill(x + 1, cursor - 1, right - 1, bottom, ROW_STRIPE);
				}

				// The row for the product page actually open, and the row the green box in the menu
				// is serving, so a list of twenty does not have to be read through to find the one
				// in front of you. Guidance counts within the basket, which no longer starts at the
				// top of the panel - the worked jobs are above it.
				if ((guided && i >= basketFirstRow && i - basketFirstRow == Guidance.row())
						|| (!row.heading() && !openProduct.isEmpty()
						&& row.name().equalsIgnoreCase(openProduct))) {
					graphics.fill(x + 1, cursor - 1, right - 1, bottom, ROW_OPEN);
				} else if (i == hovered) {
					graphics.fill(x + 1, cursor - 1, right - 1, bottom, ROW_HOVER);
				}

				// Where one kind of work ends and the next begins. The list is sorted claims, cancels,
				// reprices, places, so this is a group boundary and not a per-row decoration.
				if (i > first && rows.get(i - 1).group() != row.group()) {
					graphics.fill(x + 1, cursor - 1, right - 1, cursor, GROUP_RULE);
				}

				// A section title has no accent bar and starts at the panel edge, so it reads as a
				// title rather than as one more thing to click.
				int rowTextX = row.heading() ? x + PAD : textX;

				if (!row.heading()) {
					graphics.fill(x + PAD, cursor, x + PAD + ACCENT, bottom, row.colour());
				}

				graphics.text(font, Component.literal(row.verb()), rowTextX, cursor, row.colour());
				graphics.text(font, Component.literal(row.name()),
						rowTextX + text(font, row.verb() + " "), cursor, TEXT);

				drawNumbers(graphics, font, row, rowTextX, right,
						cursor + font.lineHeight + LINE_GAP);

				cursor += rowHeight;
			}

			graphics.text(font, Component.literal(Hit.footer(this)), x + PAD, cursor,
					Hit.copiedRecently() ? TEXT_COPIED : TEXT_DIM);
		}

		/**
		 * The price on the left of its line and the size on the right, each with a one-character mark.
		 *
		 * <p>Both numbers used to be pushed against the right edge, where {@code 306.5 26737} read as
		 * one number twice - and the two are typed into different boxes. The marks are drawn rather
		 * than made part of the string, because clicking either half copies the string.
		 */
		private void drawNumbers(GuiGraphicsExtractor graphics, Font font, Row row, int textX,
				int right, int y) {
			// A section title's second line is the done count, which is not a size to type into a
			// box - so no size mark, and dimmed.
			if (row.heading()) {
				if (!row.units().isEmpty()) {
					graphics.text(font, Component.literal(row.units()),
							right - PAD - text(font, row.units()), y, TEXT_DIM);
				}

				return;
			}

			int unitsX = right - PAD - text(font, UNITS_MARK) - text(font, row.units());

			graphics.text(font, Component.literal(row.units()), unitsX, y, TEXT_UNITS);
			graphics.text(font, Component.literal(UNITS_MARK), unitsX + text(font, row.units()), y,
					TEXT_DIM);

			if (row.price().isEmpty()) {
				return;
			}

			graphics.text(font, Component.literal(PRICE_MARK), textX, y, TEXT_DIM);
			graphics.text(font, Component.literal(row.price()), textX + text(font, PRICE_MARK), y,
					TEXT_PRICE);
		}

		/** The heading says how far down a scrolled list you are, and nothing when it all fits. */
		private String heading(int first, int last) {
			if (rows.isEmpty()) {
				// The note under this says what is waiting and when. "Do these (0)" would be an
				// instruction to do nothing, which is not what the panel is on screen for.
				return label.equals("Do these") ? "Nothing to do yet" : label;
			}

			return rows.size() == last - first
					? label + " (" + rows.size() + ")"
					: label + " (" + (first + 1) + "-" + last + " of " + rows.size() + ")";
		}

		/**
		 * What the footer says when nothing has just been copied.
		 *
		 * <p>"1 ok" was not a sentence anybody could act on. The holds are the orders that need no
		 * click, so saying what they are is the difference between a number and an explanation of why
		 * they are not in the list above.
		 */
		String hint() {
			if (rows.isEmpty()) {
				// Nothing to click, so nothing about clicking. The count is still worth saying: it
				// is the difference between "waiting on the round" and "you have no orders".
				return holding + (holding == 1 ? " order resting" : " orders resting");
			}

			return holding > 0
					? "click to copy - " + holding + " resting fine"
					: "click a name or a number to copy";
		}
	}

	/**
	 * The green box behind the slot the basket needs clicked next.
	 *
	 * <p>The panel says what to do and this says where. {@link BazaarStep} works the slot out from
	 * the menu that is open, and everything it knows was measured off a live bazaar - see
	 * {@code BazaarSlots}. A screen it cannot read, a row it cannot find, two rows it cannot choose
	 * between: all of them draw nothing. A box behind the wrong slot would be clicked.
	 *
	 * <p><b>Which row it serves.</b> The first pending row this screen can serve, top down, which is
	 * the order the panel already lists them in. So the box follows the list rather than jumping
	 * about: on the orders menu it lands on the first claim, and on a search page on the first item
	 * being placed.
	 *
	 * <p><b>It draws before the items.</b> {@code afterBackground} runs after Hypixel's menu art and
	 * before the stacks in it, so the box is a background and the item stays readable on top.
	 */
	private static final class Guidance {
		/** Green, at the strength of vanilla's own slot hover but coloured. */
		private static final int BOX = 0x9932CD32;

		/**
		 * A brighter edge around it.
		 *
		 * <p>A search page fills its empty slots with green glass, so a green box on its own is one
		 * green square among forty. The outline is what makes it findable there.
		 */
		private static final int BOX_EDGE = 0xFF7CFC00;

		/** One vanilla slot, and the pixel of padding either side that vanilla's hover box uses. */
		private static final int SLOT = 16;
		private static final int MARGIN = 1;

		/**
		 * How often the open menu is re-read.
		 *
		 * <p>Reading fifty-four slots to decide one box is not worth doing sixty times a second, and
		 * a menu that has just changed is worth pointing at within a frame or two of settling. The
		 * cache is dropped outright when the screen or the worklist changes, so this is only about
		 * the menu's contents moving under a screen that stayed open - an order filling, a page
		 * turning.
		 */
		private static final long REREAD_MILLIS = 250L;

		private static WeakReference<Screen> screen = new WeakReference<>(null);
		private static NpcWorklist.Worklist worklist;
		private static long readAt;

		private static BazaarStep.Step step;

		/** Which row of the panel the box is serving, or -1. */
		private static int row = -1;

		/**
		 * What the sign that is about to open wants typed into it, and what that number is.
		 *
		 * <p>Kept after the menu closes, because the sign replaces the menu that named it: by the time
		 * the box is on screen there is nothing left to ask what it is for. Cleared only by a later
		 * step that opens a different sign, or by the follow window running out.
		 */
		private static String typeValue = "";
		private static String typeLabel = "";

		/** Whether a sign is what is in front of the player now. */
		private static boolean onASign;

		private Guidance() {
		}

		static int row() {
			return row;
		}

		static boolean typing() {
			return onASign && !typeValue.isEmpty();
		}

		/**
		 * What the highlighted slot is for, or empty where nothing is highlighted.
		 *
		 * <p>A right click is named and a left one is not. Left is what every other step in the bazaar
		 * asks for, and the note is drawn in a panel about seventy pixels wide, so the words are spent
		 * on the one case where the wrong button does something else.
		 */
		static String stepNote() {
			if (step == null || !SkyblockFlipperClient.config().bazaarHighlightEnabled) {
				return "";
			}

			return step.click() == BazaarStep.Click.RIGHT
					? "right-click: " + step.label()
					: step.label();
		}

		/** The line the panel shows in place of its own note while a sign is open. */
		static String typeNote() {
			return "type " + typeLabel + ": " + typeValue;
		}

		/**
		 * The menu closed. {@code sign} says whether what replaced it is a sign, which is the only
		 * screen the typing note - or the panel itself - belongs on once the menu has gone.
		 */
		static void leftTheMenu(boolean sign) {
			onASign = sign;
			screen = new WeakReference<>(null);
		}

		static void clear() {
			step = null;
			row = -1;
			screen = new WeakReference<>(null);
		}

		static void draw(GuiGraphicsExtractor graphics, AbstractContainerScreen<?> container,
				ContainerScreenLayout layout) {
			if (step == null || !SkyblockFlipperClient.config().bazaarHighlightEnabled) {
				return;
			}

			for (Slot slot : container.getMenu().slots) {
				// Slot.index is the index within the slot's own container, which is what MenuReader
				// recorded and so what the step is expressed in. The player's own inventory shares
				// those numbers and is never what a bazaar step means.
				if (slot.index != step.slot() || slot.container instanceof Inventory) {
					continue;
				}

				int x = layout.flipper$leftPos() + slot.x;
				int y = layout.flipper$topPos() + slot.y;

				int left = x - MARGIN;
				int top = y - MARGIN;
				int right = x + SLOT + MARGIN;
				int bottom = y + SLOT + MARGIN;

				graphics.fill(left, top, right, bottom, BOX);
				graphics.fill(left, top, right, top + 1, BOX_EDGE);
				graphics.fill(left, bottom - 1, right, bottom, BOX_EDGE);
				graphics.fill(left, top, left + 1, bottom, BOX_EDGE);
				graphics.fill(right - 1, top, right, bottom, BOX_EDGE);
				return;
			}
		}

		/**
		 * Re-reads the menu and re-picks the row, at most every {@link #REREAD_MILLIS}.
		 *
		 * @return whether this menu belongs to the bazaar at all, which is what decides if the panel
		 *         is drawn over it. Read from the menu's contents, so it holds for the two screens
		 *         whose titles say nothing about the bazaar
		 */
		static boolean update(AbstractContainerScreen<?> container, NpcWorklist.Worklist list) {
			long now = System.currentTimeMillis();

			if (screen.get() == container && list == worklist && now - readAt < REREAD_MILLIS) {
				return bazaarFlow;
			}

			screen = new WeakReference<>(container);
			worklist = list;
			readAt = now;
			step = null;
			row = -1;
			onASign = false;

			CapturedMenu menu = MenuReader.describe(container, now);
			bazaarFlow = BazaarSlots.isBazaarFlow(menu);

			List<NpcWorklist.Task> tasks = list.pending();

			for (int i = 0; i < tasks.size(); i++) {
				NpcWorklist.Task task = tasks.get(i);
				Optional<BazaarStep.Step> found =
						BazaarStep.next(task, list.restingPriceFor(task), menu);

				if (found.isPresent()) {
					step = found.get();
					row = i;

					// Remembered now, while the menu that named it is still open. The sign this
					// click opens replaces the menu and says nothing about what it wants. A click
					// that opens no sign clears it, so a screen opened later cannot be handed the
					// number the step before last wanted.
					typeValue = step.opensASign() ? fitting(step.type()) : "";
					typeLabel = step.label();

					return bazaarFlow;
				}
			}

			typeValue = "";
			return bazaarFlow;
		}

		/** Whether the menu last read is part of the bazaar. */
		private static boolean bazaarFlow;

		/**
		 * The longest prefix of {@code value} that a sign will accept.
		 *
		 * <p>An item name is often too long to type or paste into the search sign at all, which is
		 * where "Transmission Tuner" gets stuck at "Transmission Tun". The bazaar searches on a
		 * prefix, so the shortened form finds the same item, and offering the full name offers
		 * something the screen will not take.
		 */
		private static String fitting(String value) {
			return Minecraft.getInstance().font.plainSubstrByWidth(value, SIGN_LINE_WIDTH);
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

		/**
		 * The board the last frame actually drew, which is the only one a click can be about.
		 *
		 * <p>Held here rather than read off {@link BazaarOverlay#board}, which is the basket's board
		 * and stays populated while a craft job is on screen. Reading that one copied the basket's
		 * name and price for whatever row of the craft list was clicked - the right row index into
		 * the wrong list, so it produced a plausible name and a plausible price for a different
		 * item, which is worse than copying nothing.
		 */
		private static Board drawn;

		/** Which list the scroll position belongs to. Survives {@link #clear()}, unlike {@link #drawn}. */
		private static String scrolling = "";

		private static String copied = "";
		private static long copiedAt;

		private Hit() {
		}

		static void clear() {
			live = false;
			drawn = null;
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
			drawn = board;
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

			// A different list, not a rebuild of the same one: switching between the basket and a
			// craft job starts at the top rather than at row fifteen of something else. Checked
			// here rather than where a board is built, because coming back to a basket that never
			// changed while the craft panel was up rebuilds nothing.
			if (!scrolling.equals(board.label())) {
				scrolling = board.label();
				firstRow = 0;
			}

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

			if (row < 0 || drawn == null) {
				// Inside the panel but not on a row: the heading or the footer. Swallowed anyway,
				// because a click that lands on the panel and moves an item in the menu behind it
				// is the one failure this whole design cannot afford.
				return true;
			}

			copy(drawn.rows().get(row), mouseX, mouseY);
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
				// Shortened to what the search sign will take: the full name of a long item cannot
				// be pasted into it at all, and the bazaar searches on a prefix anyway.
				put(Guidance.fitting(row.name()), "name");
				return;
			}

			Font font = Minecraft.getInstance().font;
			int unitsLeft = x + width - Math.round((PAD + Board.text(font, UNITS_MARK)
					+ Board.text(font, row.units())) * scale);

			if (mouseX >= unitsLeft) {
				// The units of one order rather than the whole line: it is what goes in the amount
				// box, and neither the line total nor the split text itself is a number the box
				// takes - "2 x 256 + 30" pasted whole is not an amount at all.
				long first = Stacking.firstOrder(row.units());

				put(first > 0L ? String.valueOf(first) : row.units(), "size");
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
