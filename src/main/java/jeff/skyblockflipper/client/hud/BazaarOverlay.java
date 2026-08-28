package jeff.skyblockflipper.client.hud;

import jeff.skyblockflipper.client.CandidateFeed;
import jeff.skyblockflipper.client.MarketDataService;
import jeff.skyblockflipper.client.NpcCheckInService;
import jeff.skyblockflipper.client.SkyblockFlipperClient;
import jeff.skyblockflipper.client.mixin.ContainerScreenLayout;
import jeff.skyblockflipper.client.track.MenuReader;
import jeff.skyblockflipper.client.track.TrackerService;
import jeff.skyblockflipper.core.config.OverlaySide;
import jeff.skyblockflipper.core.model.Stacking;
import jeff.skyblockflipper.core.strategy.BazaarAction;
import jeff.skyblockflipper.core.strategy.BazaarStep;
import jeff.skyblockflipper.core.strategy.FlipCandidate;
import jeff.skyblockflipper.core.strategy.NpcWorklist;
import jeff.skyblockflipper.core.strategy.StrategyKind;
import jeff.skyblockflipper.core.strategy.WorkedJob;
import jeff.skyblockflipper.core.text.Coins;
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
 * The flip panel drawn beside Hypixel's own bazaar menu, one flip type at a time.
 *
 * <p>A thin type strip along the top names every bazaar flip type - {@link StrategyKind#bazaarKinds()}
 * by their own labels - and clicking one switches what the body shows. The pick is remembered in
 * {@code bazaarOverlayType}, so the panel comes back on the type it was left on. A type Codex adds
 * later needs no edit here: it appears in the strip the moment it is marked {@link StrategyKind#atBazaar()}.
 *
 * <p><b>NPC is the basket.</b> The whole trip {@link CandidateFeed#worklist()} works out - claims,
 * cancels, reprices, places, and the between-rounds note - exactly as before. It has no <i>To
 * start</i> list, because a basket line is not a per-item job to follow.
 *
 * <p><b>Every other type is two lists.</b> <i>Working now</i> is the committed jobs of that type,
 * expanded to their buy / transform / sell steps with progress badges. <i>To start</i> is the ranked
 * candidates as one-liners; clicking one expands its steps inline, and from there the flip is worked.
 *
 * <p><b>No click is sent to the game.</b> The panel's clicks copy a step's name, price or size to the
 * clipboard, switch the active type, expand a candidate, or commit a flip to the worked list - all
 * mod state, never an inventory packet. A click on Hypixel's menu reaches Hypixel's menu untouched,
 * because the handlers only swallow input that lands inside the panel's own rectangle.
 *
 * <p><b>It refreshes itself.</b> Each list comes from {@link CandidateFeed}, rebuilt when the book
 * moves - every poll, about every twenty seconds - and again whenever the order tracker is fed, so a
 * placed order takes its line off the panel without waiting for a poll. The lists are the ones the
 * flip screen shows, so the two cannot disagree.
 *
 * <p><b>Where it draws.</b> On the side {@code bazaarOverlaySide} names, scaled down to fit what is
 * there. The menu's real position comes from {@link ContainerScreenLayout} rather than from assuming
 * every Hypixel menu is a centred 176-wide chest.
 *
 * <p><b>It follows the sign.</b> An amount or a price is typed on a sign, which is not a container
 * menu and carries no title worth matching, and is the exact moment the numbers are needed. So for a
 * few seconds after the bazaar menu it came from, the panel stays on screen against the left edge -
 * on a sign and nowhere else.
 */
public final class BazaarOverlay {
	private static final int PANEL = 0xF01E1E2E;
	private static final int PANEL_EDGE = 0xFF585B70;
	private static final int HEADER_RULE = 0x40CDD6F4;
	private static final int GROUP_RULE = 0x22CDD6F4;
	private static final int ROW_STRIPE = 0x14CDD6F4;
	private static final int TEXT = 0xFFCDD6F4;
	private static final int TEXT_DIM = 0xFF7F849C;
	private static final int TEXT_PRICE = 0xFF89B4FA;
	private static final int TEXT_UNITS = 0xFFA6E3A1;
	private static final int TEXT_COPIED = 0xFFF9E2AF;

	/** The between-rounds line: peach, the reprice tone, because that is what it is about. */
	private static final int TEXT_WAITING = 0xFFFAB387;
	private static final int ROW_OPEN = 0x50CBA6F7;

	/** A section title: which job the rows under it belong to. Sky, like the panel's own heading. */
	private static final int TEXT_HEADING = 0xFF89DCEB;

	/**
	 * The group a heading belongs to, which is no group at all - it exists so the rule that
	 * separates one kind of work from the next also lands above every section title.
	 */
	private static final int HEADING_GROUP = -1;
	private static final int ROW_HOVER = 0x30CDD6F4;

	/** The active type's chip in the strip. */
	private static final int CHIP_ACTIVE = 0x5089DCEB;
	private static final int CHIP_IDLE = 0x1ECDD6F4;
	private static final int CHIP_HOVER = 0x33CDD6F4;

	/** How many candidates the <i>To start</i> list ranks. The panel scrolls if the type has more. */
	private static final int TO_START = 5;

	/** Padding inside a type chip, either side of its label. */
	private static final int CHIP_PAD_X = 3;

	/** Gap between two chips, across and down. */
	private static final int CHIP_GAP = 2;

	/** Gap under the last chip row, before the heading. */
	private static final int SELECTOR_BOTTOM_GAP = 3;

	/** The empty answer for a per-item type. Short enough to fit one fixed-width line. */
	private static final String EMPTY_TYPE = "No flips clear fees right now.";

	/** What a click on a panel row does, so the hit test routes it without re-deriving the row's kind. */
	private enum Action {
		/** Copy the row's name, price or size, depending where on the row the click landed. */
		COPY,

		/** Expand or collapse a <i>To start</i> candidate's steps. */
		EXPAND,

		/** Commit an expanded candidate to the worked list. */
		WORK,

		/** Stop working a committed job, leaving any resting orders alone. */
		STOP,

		/** A section title or a note: swallow the click and do nothing with it. */
		NONE
	}

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
			case CLAIM -> 0xFFF9E2AF;
			case CANCEL -> 0xFFF38BA8;
			case REPRICE -> 0xFFFAB387;
			case PLACE -> 0xFFA6E3A1;
			case HOLD -> TEXT_DIM;
		};
	}

	/**
	 * The same idea for a worked flip: one colour per stage, so the buying, the transformation and
	 * the sale read as three blocks rather than as one wall. Shared by craft, combine, fusion and
	 * spread, because a player working two of them at once should not have to learn two colour schemes.
	 */
	private static int colourOf(WorkedJob.Stage stage) {
		return switch (stage) {
			case BUY_ORDER -> 0xFFA6E3A1;
			case INSTANT_BUY -> 0xFF89B4FA;
			case TRANSFORM -> 0xFFCBA6F7;
			case SELL_OFFER -> 0xFFF9E2AF;
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

	/**
	 * The panel's fixed width in panel pixels. It never grows with content, so the scale - and the
	 * apparent font size - stays the same on every type. Content wider than this is truncated rather
	 * than allowed to push the panel wider and the font smaller.
	 */
	private static final int PANEL_WIDTH = 170;

	/** Below this the text stops being worth drawing, so the panel stays away instead. */
	private static final float MIN_SCALE = 0.34f;

	/**
	 * Where the panel's top sits, in real screen pixels from the top of the screen.
	 *
	 * <p>Fixed rather than tied to the menu's own top, which moves with the menu's row count - a
	 * six-row search page centres lower than a three-row page, and the panel used to jump with it.
	 */
	private static final int PANEL_TOP = 24;

	/** Gap between the panel and the menu, in real screen pixels. */
	private static final int MENU_GAP = 3;

	/**
	 * How long the panel keeps following after the bazaar menu closes.
	 *
	 * <p>Ninety seconds covers a whole order typed slowly, and the window is refreshed by every screen
	 * in the bazaar's own chain - the product page, the amount page, the sign - so it only runs down
	 * once the player has actually left.
	 */
	private static final long FOLLOW_MILLIS = 90_000L;

	/**
	 * Pixels of text one line of a sign holds, which is {@code SignBlockEntity.getMaxTextLineWidth}.
	 *
	 * <p>The sign screen drops any keystroke or paste that would take a line past this, so a long item
	 * name cannot be pasted into the search at all. The bazaar searches on a prefix, so the panel
	 * offers the longest prefix that fits and the search still lands on the item.
	 */
	private static final int SIGN_LINE_WIDTH = 90;

	/** How long "copied" stays under the list before the hint comes back. */
	private static final long COPIED_MILLIS = 2_000L;

	/** Share of the window the panel may take when there is no menu to sit beside. */
	private static final int EDGE_WIDTH_SHARE = 3;

	/** When a bazaar menu was last on screen, which is what {@link #FOLLOW_MILLIS} runs from. */
	private static long leftBazaarAt;

	/** Which <i>To start</i> candidate is expanded, by item id, or empty when none is. */
	private static String expandedCandidate = "";

	/**
	 * AFTER_INIT fires again on every window resize and a screen keeps whatever was registered on it,
	 * so attaching twice would lay the panel out twice a frame. Held weakly, because a strong reference
	 * here would keep a closed screen alive.
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
			// panel instead of under it.
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

		StrategyKind type = SkyblockFlipperClient.config().bazaarOverlayType();
		boolean npc = type == StrategyKind.NPC_FLIP;

		// The NPC worklist is fetched either way: it drives the green box on the NPC type, and its
		// bazaar-flow read is what tells the container branch it is looking at a bazaar menu at all.
		NpcWorklist.Worklist worklist = CandidateFeed.worklist();
		List<WorkedJob> jobs = npc ? List.of() : jobsOf(type);
		List<FlipCandidate> ranked = npc ? List.of() : rankedFor(type);
		String note = npc
				? note(worklist)
				: (jobs.isEmpty() && ranked.isEmpty() ? EMPTY_TYPE : "");

		String title = screen.getTitle().getString();
		Font font = Minecraft.getInstance().font;

		// Half the bazaar does not name itself: a product page is titled "Item Upgrades ➜ ..." and the
		// amount page "How many do you want?", so those are recognised by the buttons on them. The
		// layout accessor is checked rather than cast outright: a panel that quietly does not appear
		// beats a ClassCastException on every menu the player opens.
		List<Guide> guides = guidesFor(type, jobs, ranked, worklist);

		if (screen instanceof ContainerScreenLayout layout
				&& screen instanceof AbstractContainerScreen<?> container) {
			// The box follows the active type: the first of its actions this menu can serve. Guidance
			// reads the menu once either way, so the container is known to be a bazaar one even when no
			// action matches.
			boolean flow = Guidance.update(container, guides);
			String stepNote = Guidance.stepNote();
			Board board = board(type, jobs, ranked, worklist, title,
					stepNote.isEmpty() ? note : stepNote, font);

			// The panel shows over a bazaar menu even when the active type has nothing to do, so the
			// type strip is always reachable to switch away from an empty one.
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

		// On a sign, the panel says the one thing the screen is asking for rather than the whole list:
		// the number or the name to type into it, for whichever type's step opened it.
		Board board = board(type, jobs, ranked, worklist, title,
				Guidance.typing() ? Guidance.typeNote() : note, font);

		// Typing a price or an amount happens on a sign, which is not a container menu and carries no
		// title worth matching. So the panel follows the bazaar menu it was opened from for a short
		// window. A screen that is not a sign gets nothing at any time: chat, the pause menu and the
		// settings screen all have their own clicks, and this panel eats every click that lands on it.
		if (onASign && System.currentTimeMillis() - leftBazaarAt <= FOLLOW_MILLIS) {
			drawAtTheEdge(screen, graphics, board, font, mouseX, mouseY);
			return;
		}

		Hit.clear();
	}

	/**
	 * The worked jobs of one type, cached so the identity is stable between frames.
	 *
	 * <p>{@link CandidateFeed#jobs()} returns a list of every worked flip; filtering it to one type
	 * produces a fresh list each call, and the board cache compares its inputs by identity - so without
	 * memoising the filtered list the board would rebuild every frame.
	 */
	private static List<WorkedJob> jobsOf(StrategyKind type) {
		List<WorkedJob> all = CandidateFeed.jobs();

		if (all != filteredSource || type != filteredKind) {
			filteredSource = all;
			filteredKind = type;
			List<WorkedJob> mine = new ArrayList<>();

			for (WorkedJob job : all) {
				if (job.kind() == type) {
					mine.add(job);
				}
			}

			filteredJobs = List.copyOf(mine);
		}

		return filteredJobs;
	}

	private static List<WorkedJob> filteredJobs = List.of();
	private static List<WorkedJob> filteredSource;
	private static StrategyKind filteredKind;

	/**
	 * The <i>To start</i> candidates for one type, ranked at most once per book revision.
	 *
	 * <p>Only the active type is ranked, which is what keeps the strip cheap: ranking ~2000 order books
	 * for every type each frame is the waste {@link CandidateFeed} exists to avoid, so the answer is
	 * cached against the same revision the rest of the mod ranks on.
	 */
	private static List<FlipCandidate> rankedFor(StrategyKind type) {
		long revision = MarketDataService.data().bazaarRevision();

		if (type != rankedKind || revision != rankedRevision) {
			rankedKind = type;
			rankedRevision = revision;
			ranked = MarketDataService.data().hasBazaar()
					? CandidateFeed.rank(type, TO_START)
					: List.of();
		}

		return ranked;
	}

	private static List<FlipCandidate> ranked = List.of();
	private static StrategyKind rankedKind;
	private static long rankedRevision = -1L;

	/** One action the green box may point at, and what its order rests at where that tells two apart. */
	private record Guide(BazaarAction action, double restingPrice) {
	}

	/**
	 * The active type's bazaar actions, in the order the box should try them, cached against the same
	 * inputs the board is.
	 *
	 * <p>The NPC basket's pending tasks, or - for a per-item type - the committed jobs' steps followed
	 * by the expanded candidate's. Cached so its identity is stable between frames, which is what lets
	 * {@link Guidance} keep its own read of the menu throttled.
	 */
	private static List<Guide> guidesFor(StrategyKind type, List<WorkedJob> jobs,
			List<FlipCandidate> ranked, NpcWorklist.Worklist worklist) {
		if (type != guidesType || jobs != guidesJobs || ranked != guidesRanked
				|| worklist != guidesWorklist || !expandedCandidate.equals(guidesExpanded)) {
			guidesType = type;
			guidesJobs = jobs;
			guidesRanked = ranked;
			guidesWorklist = worklist;
			guidesExpanded = expandedCandidate;
			guides = buildGuides(type, jobs, ranked, worklist);
		}

		return guides;
	}

	private static List<Guide> buildGuides(StrategyKind type, List<WorkedJob> jobs,
			List<FlipCandidate> ranked, NpcWorklist.Worklist worklist) {
		List<Guide> list = new ArrayList<>();

		if (type == StrategyKind.NPC_FLIP) {
			for (NpcWorklist.Task task : worklist.pending()) {
				BazaarAction action = BazaarAction.of(task);

				if (action != null) {
					list.add(new Guide(action, worklist.restingPriceFor(task)));
				}
			}

			return List.copyOf(list);
		}

		// Committed jobs first, then the expanded candidate - the same order the panel lists them, so
		// the box works down the visible list rather than jumping about.
		for (WorkedJob job : jobs) {
			addStepGuides(job.steps(), list);
		}

		if (!expandedCandidate.isEmpty()) {
			for (FlipCandidate candidate : ranked) {
				if (candidate.itemId().equals(expandedCandidate)
						&& !CandidateFeed.working(candidate.itemId())) {
					WorkedJob preview = CandidateFeed.preview(type, candidate.itemId(),
							candidate.displayName());

					if (preview != null) {
						addStepGuides(preview.steps(), list);
					}

					break;
				}
			}
		}

		return List.copyOf(list);
	}

	private static void addStepGuides(List<WorkedJob.Step> steps, List<Guide> list) {
		for (WorkedJob.Step step : steps) {
			BazaarAction action = BazaarAction.of(step);

			if (action != null) {
				list.add(new Guide(action, 0.0d));
			}
		}
	}

	private static List<Guide> guides = List.of();
	private static StrategyKind guidesType;
	private static List<WorkedJob> guidesJobs;
	private static List<FlipCandidate> guidesRanked;
	private static NpcWorklist.Worklist guidesWorklist;
	private static String guidesExpanded = "";

	/** Switch the panel to another type, remembering the pick. Called from a chip click. */
	static void switchType(StrategyKind kind) {
		if (kind == null || kind.name().equals(SkyblockFlipperClient.config().bazaarOverlayType)) {
			return;
		}

		SkyblockFlipperClient.config().bazaarOverlayType = kind.name();
		SkyblockFlipperClient.saveConfig();
		// A candidate id from the type we are leaving means nothing under the new one.
		expandedCandidate = "";
	}

	/** Expand a candidate's steps, or collapse it if it is the one already open. Called from a click. */
	static void toggleExpand(String itemId) {
		if (itemId == null || itemId.isEmpty()) {
			return;
		}

		expandedCandidate = expandedCandidate.equals(itemId) ? "" : itemId;
	}

	/**
	 * Commit an expanded candidate to the worked list, the same path {@code FlipScreen}'s Work button
	 * takes. The flip moves to <i>Working now</i>, so the expanded <i>To start</i> row collapses.
	 */
	static void workCandidate(String itemId, String displayName) {
		StrategyKind kind = SkyblockFlipperClient.config().bazaarOverlayType();

		// work() records the FlipIntent that keeps the NPC side off this order, and returns false only
		// for a kind with no bazaar steps - which the candidate list never offers, since NPC has none.
		if (CandidateFeed.work(kind, itemId, displayName)) {
			expandedCandidate = "";
		}
	}

	/** Stop working a committed job. Any orders already on the book are left alone. */
	static void stopJob(String itemId) {
		CandidateFeed.stopWork(itemId);
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

	/** Against the left edge, for the sign screens that have no menu to sit beside. */
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

	/**
	 * The scale that fits the fixed-width panel into {@code room} screen pixels, or 0 if none is worth
	 * it. It reads {@link #PANEL_WIDTH}, never the board's content, so the font is the same size on
	 * every type.
	 */
	private static float fit(int room) {
		float scale = Math.min(1.0f, (float) room / PANEL_WIDTH);
		return scale < MIN_SCALE ? 0.0f : scale;
	}

	private static void draw(GuiGraphicsExtractor graphics, Board board, Font font, float scale,
			int x, int preferredY, int screenHeight, int mouseX, int mouseY) {
		// Everything from here is in panel pixels, so the window has to be measured in them too.
		int panelHeight = Math.round(screenHeight / scale);
		int shown = board.rowsFitting(panelHeight - 2 * PAD);

		// A board with rows and no room for any of them is not worth a panel. A board with no rows at
		// all is a bare type strip and its note, which is still worth showing at the bazaar.
		if (shown <= 0 && !board.rows().isEmpty()) {
			Hit.clear();
			return;
		}

		int height = board.height(shown, font);
		int y = Math.clamp(preferredY, PAD, Math.max(PAD, panelHeight - height - PAD));

		Hit.laidOut(board, x, y, scale, shown, font);

		// The list is on screen, so the player has been told. Without this the panel was the one way of
		// working the basket that never restarted the reminder.
		if (shown > 0) {
			NpcCheckInService.acknowledge();
		}

		graphics.pose().pushMatrix();
		graphics.pose().scale(scale, scale);
		board.draw(graphics, font, x, y, shown, Hit.hoveredRow(mouseX, mouseY),
				Hit.hoveredChip(mouseX, mouseY));
		graphics.pose().popMatrix();
	}

	private static Board board;
	private static StrategyKind boardType;
	private static List<WorkedJob> boardJobs = List.of();
	private static List<FlipCandidate> boardRanked = List.of();
	private static long boardOrders = -1L;
	private static NpcWorklist.Worklist boardWorklist;
	private static String boardTitle = "";
	private static String boardNote = "";
	private static String boardExpanded = "";

	/**
	 * The laid-out board for this type and this screen, rebuilt only when one of its inputs changes.
	 *
	 * <p>This runs every frame a bazaar menu is open. Measuring the rows sixty times a second to arrive
	 * at the same widths is the same waste {@link CandidateFeed} exists to avoid for the ranked list,
	 * and the lists only change once a poll.
	 */
	private static Board board(StrategyKind type, List<WorkedJob> jobs, List<FlipCandidate> ranked,
			NpcWorklist.Worklist worklist, String title, String note, Font font) {
		long orders = TrackerService.orderRevision();

		if (board == null || type != boardType || jobs != boardJobs || ranked != boardRanked
				|| orders != boardOrders || worklist != boardWorklist || !title.equals(boardTitle)
				|| !note.equals(boardNote) || !expandedCandidate.equals(boardExpanded)) {
			boardType = type;
			boardJobs = jobs;
			boardRanked = ranked;
			boardOrders = orders;
			boardWorklist = worklist;
			boardTitle = title;
			boardNote = note;
			boardExpanded = expandedCandidate;
			board = Board.of(type, jobs, ranked, TrackerService.orders(), worklist, title, note,
					expandedCandidate, font);
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
	 * <p>It counts down, so it cannot be cached with the board the way the rows are - and it cannot be
	 * rebuilt sixty times a second either. A second is finer than a number quoted in minutes needs.
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
	 * One type's lists laid out as text, measured before anything is drawn.
	 *
	 * <p>Separate from the drawing because the panel has to know how wide it wants to be before it can
	 * work out how far to scale down, and how tall a row is before it can decide how many rows the
	 * window has room for.
	 *
	 * @param activeType    which type this board is for, which the chip strip highlights and the body
	 *                      dispatches on
	 * @param chips         the type strip, one chip per bazaar kind, with panel-relative rectangles
	 * @param selectorHeight the height the strip occupies above the heading
	 * @param openProduct   the row the open product page is for, empty on every other screen
	 * @param note          the note under the heading, empty when there is nothing to say
	 * @param basketFirstRow the first row the green box counts from, for the NPC type
	 */
	private record Board(StrategyKind activeType, List<Chip> chips, int selectorHeight, int chipHeight,
			List<Row> rows, String openProduct, String note, String label, boolean guided, int holding,
			int width, int rowHeight, int headerHeight, int basketFirstRow) {
		Board {
			chips = List.copyOf(chips);
			rows = List.copyOf(rows);
		}

		/**
		 * One line of the body: what to search for, what to type, how the units divide into orders, and
		 * what a click on it does.
		 *
		 * @param price     the post price written out in full and never abbreviated - it is typed into a
		 *                  box character by character. Empty on a claim, a cancel or a title
		 * @param units     the right-hand value: an order size on a step, a done count or a profit on a
		 *                  heading
		 * @param heading   drawn as a title - no accent bar, starting at the panel edge
		 * @param action    what a click does; {@link Action#COPY} copies name/price/size, others switch
		 *                  or expand
		 * @param actionId  the item id an {@link Action#EXPAND} row toggles
		 */
		private record Row(int colour, int group, String verb, String name, String price,
				String units, boolean heading, Action action, String actionId, String actionName) {
			/** A step or a task: an accent bar, a copyable name and numbers. */
			static Row line(int colour, int group, String verb, String name, String price,
					String units) {
				return new Row(colour, group, verb, name, price, units, false, Action.COPY, "", "");
			}

			/** A plain section title. */
			static Row section(String title) {
				return new Row(TEXT_HEADING, HEADING_GROUP, "", title, "", "", true, Action.NONE, "",
						"");
			}

			/** A worked job's title, with how far along it is; clicking it stops the job. */
			static Row jobHeading(String verb, String name, String progress, String itemId) {
				return new Row(TEXT_HEADING, HEADING_GROUP, verb, name, "", progress, true, Action.STOP,
						itemId, name);
			}

			/** A job's between-steps note, dim and unclickable. */
			static Row jobNote(String note) {
				return new Row(TEXT_WAITING, HEADING_GROUP, "", note, "", "", true, Action.NONE, "", "");
			}

			/** A <i>To start</i> candidate one-liner: a caret, its name and its total net profit. */
			static Row candidate(String itemId, String name, String profit, boolean open) {
				return new Row(TEXT_DIM, HEADING_GROUP, open ? "[-]" : "[+]", name, "", profit, true,
						Action.EXPAND, itemId, name);
			}

			/**
			 * The Work control under an expanded candidate. The label rides on the verb, not the name,
			 * so it is drawn in its own green rather than the plain white every row name uses.
			 */
			static Row work(String itemId, String name) {
				return new Row(0xFFA6E3A1, HEADING_GROUP, "Work this flip", "", "", "", true,
						Action.WORK, itemId, name);
			}
		}

		/** One type chip in the strip, positioned relative to the panel's top-left corner. */
		private record Chip(StrategyKind kind, int x, int y, int width) {
		}

		/**
		 * The type strip, then the body for the active type.
		 *
		 * @param orders every order the tracker holds, for the progress badges. Empty when auto-tracking
		 *               is off, which draws no badges rather than wrong ones
		 */
		static Board of(StrategyKind type, List<WorkedJob> jobs, List<FlipCandidate> ranked,
				List<TrackedOrder> orders, NpcWorklist.Worklist worklist, String title, String note,
				String expanded, Font font) {
			List<Row> rows = new ArrayList<>();
			List<String> names = new ArrayList<>();
			String label;
			boolean guided;
			int basketFirstRow;

			if (type == StrategyKind.NPC_FLIP) {
				for (NpcWorklist.Task task : worklist.pending()) {
					rows.add(Row.line(colourOf(task.kind()), task.kind().ordinal(), task.verb(),
							task.displayName(),
							task.hasPrice() ? String.format("%.1f", task.price()) : "",
							task.orderSplit()));
					names.add(task.displayName());
				}

				label = "Do these";
				guided = true;
				basketFirstRow = 0;
			} else {
				perTypeRows(type, jobs, ranked, orders, expanded, rows, names);
				label = type.label();
				guided = false;
				basketFirstRow = rows.size();
			}

			// Fixed, never grown to content: a wider board would scale down to fit the room beside the
			// menu and shrink the font with it, which is exactly the per-type font jitter this avoids.
			int width = PANEL_WIDTH;
			int chipHeight = font.lineHeight + 4;

			List<Chip> chips = layoutChips(type, width, chipHeight, font);
			int selectorHeight = chips.get(chips.size() - 1).y() + chipHeight + SELECTOR_BOTTOM_GAP;

			return new Board(type, chips, selectorHeight, chipHeight, rows,
					BazaarMenu.productPageFor(title, names), note, label, guided,
					type == StrategyKind.NPC_FLIP ? worklist.holding() : 0, width,
					font.lineHeight * 2 + LINE_GAP + 2,
					font.lineHeight + 5 + (note.isEmpty() ? 0 : font.lineHeight + LINE_GAP),
					basketFirstRow);
		}

		/** <i>Working now</i>, then <i>To start</i>, for the craft / combine / fusion / spread types. */
		private static void perTypeRows(StrategyKind type, List<WorkedJob> jobs,
				List<FlipCandidate> ranked, List<TrackedOrder> orders, String expanded,
				List<Row> rows, List<String> names) {
			if (!jobs.isEmpty()) {
				rows.add(Row.section("Working now"));

				for (WorkedJob job : jobs) {
					rows.add(Row.jobHeading(headingVerb(job.kind()), job.displayName(),
							progressOf(job, orders), job.itemId()));
					names.add(job.displayName());
					addSteps(job.steps(), job, orders, rows, names);

					if (!job.note().isEmpty()) {
						rows.add(Row.jobNote(job.note()));
					}
				}
			}

			for (FlipCandidate candidate : ranked) {
				// A candidate already being worked is in the Working now list above; showing it here as
				// something "to start" would be the same flip listed twice.
				if (CandidateFeed.working(candidate.itemId())) {
					continue;
				}

				boolean open = candidate.itemId().equals(expanded);
				rows.add(Row.candidate(candidate.itemId(), candidate.displayName(),
						"+" + Coins.format(candidate.totalNetProfit()), open));
				names.add(candidate.displayName());

				if (open) {
					WorkedJob preview = CandidateFeed.preview(type, candidate.itemId(),
							candidate.displayName());

					if (preview != null) {
						addSteps(preview.steps(), preview, orders, rows, names);
					}

					// The commit control, below the steps it would set going. Shown even for a plan that
					// stopped clearing (a stalled preview has no steps), so the player is never stuck with
					// an expanded row they cannot act on.
					rows.add(Row.work(candidate.itemId(), candidate.displayName()));
				}
			}
		}

		/** A job's steps as rows, with the tracker's progress badge on the verb. */
		private static void addSteps(List<WorkedJob.Step> steps, WorkedJob job,
				List<TrackedOrder> orders, List<Row> rows, List<String> names) {
			for (WorkedJob.Step step : steps) {
				// The badge rides on the verb rather than in a column of its own: the verb is already
				// the coloured half of the line, and a fourth column at this width costs more than it
				// says.
				rows.add(Row.line(colourOf(step.stage()), step.stage().ordinal(),
						job.progressOf(step, orders).badge() + " " + step.label(), step.displayName(),
						step.stage().priced() ? String.format("%.1f", step.price()) : "",
						step.orderSplit()));
				names.add(step.displayName());
			}
		}

		/** The type chips wrapped to as many rows as the panel width needs. */
		private static List<Chip> layoutChips(StrategyKind active, int width, int chipHeight,
				Font font) {
			List<Chip> chips = new ArrayList<>();
			int x = PAD;
			int y = 0;

			for (StrategyKind kind : StrategyKind.bazaarKinds()) {
				int chipWidth = text(font, kind.label()) + CHIP_PAD_X * 2;

				if (x != PAD && x + chipWidth > width - PAD) {
					x = PAD;
					y += chipHeight + CHIP_GAP;
				}

				chips.add(new Chip(kind, x, y, chipWidth));
				x += chipWidth + CHIP_GAP;
			}

			return chips;
		}

		/** The word that names a job's strategy in a section heading. */
		private static String headingVerb(StrategyKind kind) {
			return switch (kind) {
				case CRAFT -> "Craft";
				case COMBINE -> "Combine";
				case FUSION -> "Fusion";
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
		 * <p>Room for the footer is always reserved, even when nothing ends up hidden. The alternative
		 * is a layout that fits one extra row until the list grows, and then reflows everything by a line
		 * the moment it does.
		 */
		int rowsFitting(int available) {
			return Math.clamp((available - selectorHeight - headerHeight - rowHeight) / rowHeight, 0,
					rows.size());
		}

		int height(int shown, Font font) {
			return PAD * 2 + selectorHeight + headerHeight + shown * rowHeight + font.lineHeight;
		}

		/** Where the numbers line of row {@code i} starts, relative to the panel's top. */
		int numbersOffset(int i, Font font) {
			return PAD + selectorHeight + headerHeight + i * rowHeight + font.lineHeight + LINE_GAP;
		}

		void draw(GuiGraphicsExtractor graphics, Font font, int x, int y, int shown, int hovered,
				StrategyKind hoveredChip) {
			int height = height(shown, font);
			int first = Hit.firstRow();
			int last = Math.min(rows.size(), first + shown);
			int right = x + width;
			int textX = x + PAD + ACCENT + ACCENT_GAP;

			graphics.fill(x, y, right, y + height, PANEL);
			// A whole border rather than two edges: the panel sits on top of Hypixel's own menu art, and
			// an unclosed box reads as part of it.
			graphics.fill(x, y, right, y + 1, PANEL_EDGE);
			graphics.fill(x, y + height - 1, right, y + height, PANEL_EDGE);
			graphics.fill(x, y, x + 1, y + height, PANEL_EDGE);
			graphics.fill(right - 1, y, right, y + height, PANEL_EDGE);

			int cursor = y + PAD;

			drawSelector(graphics, font, x, cursor, hoveredChip);
			cursor += selectorHeight;

			graphics.text(font, Component.literal(heading(first, last))
					.withStyle(ChatFormatting.GOLD), x + PAD, cursor, TEXT);

			// Under the heading and above the rule, so it reads as part of what this list is rather than
			// as another row to click.
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

				// Zebra striping under everything else. Two-line rows with no background of their own ran
				// together into a wall of text.
				if ((i & 1) == 1) {
					graphics.fill(x + 1, cursor - 1, right - 1, bottom, ROW_STRIPE);
				}

				// The row for the product page actually open, and - on the NPC type - the row the green
				// box is serving, so a long list does not have to be read through to find the one in
				// front of you.
				if ((guided && i >= basketFirstRow && i - basketFirstRow == Guidance.row())
						|| (!row.heading() && !openProduct.isEmpty()
						&& row.name().equalsIgnoreCase(openProduct))) {
					graphics.fill(x + 1, cursor - 1, right - 1, bottom, ROW_OPEN);
				} else if (i == hovered) {
					graphics.fill(x + 1, cursor - 1, right - 1, bottom, ROW_HOVER);
				}

				// Where one kind of work ends and the next begins.
				if (i > first && rows.get(i - 1).group() != row.group()) {
					graphics.fill(x + 1, cursor - 1, right - 1, cursor, GROUP_RULE);
				}

				// A title has no accent bar and starts at the panel edge, so it reads as a title rather
				// than as one more thing to click.
				int rowTextX = row.heading() ? x + PAD : textX;

				if (!row.heading()) {
					graphics.fill(x + PAD, cursor, x + PAD + ACCENT, bottom, row.colour());
				}

				graphics.text(font, Component.literal(row.verb()), rowTextX, cursor, row.colour());

				// The panel width is fixed, so a long name is cut to what is left of the line rather than
				// drawn past the border. The search copies the full name, so the cut is display-only.
				int nameX = rowTextX + text(font, row.verb() + " ");
				String name = font.plainSubstrByWidth(row.name(), right - PAD - nameX);
				graphics.text(font, Component.literal(name), nameX, cursor, TEXT);

				drawNumbers(graphics, font, row, rowTextX, right, cursor + font.lineHeight + LINE_GAP);

				cursor += rowHeight;
			}

			graphics.text(font, Component.literal(Hit.footer(this)), x + PAD, cursor,
					Hit.copiedRecently() ? TEXT_COPIED : TEXT_DIM);
		}

		/** The type strip along the top, the active one lit. */
		private void drawSelector(GuiGraphicsExtractor graphics, Font font, int x, int top,
				StrategyKind hoveredChip) {
			for (Chip chip : chips) {
				int cx = x + chip.x();
				int cy = top + chip.y();
				boolean active = chip.kind() == activeType;
				int background = active ? CHIP_ACTIVE
						: chip.kind() == hoveredChip ? CHIP_HOVER : CHIP_IDLE;

				graphics.fill(cx, cy, cx + chip.width(), cy + chipHeight, background);
				graphics.text(font, Component.literal(chip.kind().label()), cx + CHIP_PAD_X,
						cy + (chipHeight - font.lineHeight) / 2, active ? TEXT : TEXT_DIM);
			}
		}

		/**
		 * The price on the left of its line and the size on the right, each with a one-character mark.
		 *
		 * <p>Both numbers used to be pushed against the right edge, where {@code 306.5 26737} read as one
		 * number twice - and the two are typed into different boxes. The marks are drawn rather than made
		 * part of the string, because clicking either half copies the string.
		 */
		private void drawNumbers(GuiGraphicsExtractor graphics, Font font, Row row, int textX,
				int right, int y) {
			// A title's second line is a done count or a profit, which is not a size to type into a box -
			// so no size mark, and dimmed.
			if (row.heading()) {
				if (!row.units().isEmpty()) {
					graphics.text(font, Component.literal(row.units()),
							right - PAD - text(font, row.units()), y, TEXT_DIM);
				}

				return;
			}

			// The panel width is fixed, so the size is cut to whatever the price leaves it rather than
			// drawn over the top of it. A wide split ("2 x 71680 + 42118") loses its tail, not the price.
			int markW = text(font, UNITS_MARK);
			int priceEnd = row.price().isEmpty()
					? textX
					: textX + text(font, PRICE_MARK) + text(font, row.price()) + NUMBER_GAP;
			String units = font.plainSubstrByWidth(row.units(),
					Math.max(0, right - PAD - markW - priceEnd));
			int unitsX = right - PAD - markW - text(font, units);

			graphics.text(font, Component.literal(units), unitsX, y, TEXT_UNITS);
			graphics.text(font, Component.literal(UNITS_MARK), unitsX + text(font, units), y, TEXT_DIM);

			if (row.price().isEmpty()) {
				return;
			}

			graphics.text(font, Component.literal(PRICE_MARK), textX, y, TEXT_DIM);
			graphics.text(font, Component.literal(row.price()), textX + text(font, PRICE_MARK), y,
					TEXT_PRICE);
		}

		/** The heading is the type's name, plus the scroll range when the list is scrolled past the top. */
		private String heading(int first, int last) {
			if (rows.isEmpty()) {
				// The note under this says what is waiting. On the NPC type an empty list is the
				// between-rounds case; elsewhere it is the empty answer.
				return activeType == StrategyKind.NPC_FLIP ? "Nothing to do yet" : label;
			}

			return rows.size() == last - first
					? label
					: label + "  " + (first + 1) + "-" + last + " of " + rows.size();
		}

		/**
		 * What the footer says when nothing has just been copied: the resting-order count, or nothing.
		 *
		 * <p>No instructions. The count is the one fact the rows do not carry - the difference between
		 * "waiting on the round" and "you have no orders out" - so it is all the footer says.
		 */
		String hint() {
			return holding > 0
					? holding + (holding == 1 ? " order resting" : " orders resting")
					: "";
		}
	}

	/**
	 * The green box behind the slot the basket needs clicked next.
	 *
	 * <p>The panel says what to do and this says where. {@link BazaarStep} works the slot out from the
	 * menu that is open, and everything it knows was measured off a live bazaar - see {@code
	 * BazaarSlots}. A screen it cannot read, a row it cannot find, two rows it cannot choose between:
	 * all of them draw nothing. A box behind the wrong slot would be clicked.
	 *
	 * <p><b>It follows the active type.</b> {@link #update} is handed that type's actions - the NPC
	 * basket's tasks, or a worked job's and the expanded candidate's steps - and points at the first one
	 * the open menu can serve, top down. Off-bazaar steps (a transform) and unmeasured ones (an instant
	 * buy) carry no action, so nothing is drawn for them.
	 */
	private static final class Guidance {
		/** Green, at the strength of vanilla's own slot hover but coloured. */
		private static final int BOX = 0x9932CD32;

		/**
		 * A brighter edge around it.
		 *
		 * <p>A search page fills its empty slots with green glass, so a green box on its own is one green
		 * square among forty. The outline is what makes it findable there.
		 */
		private static final int BOX_EDGE = 0xFF7CFC00;

		/** One vanilla slot, and the pixel of padding either side that vanilla's hover box uses. */
		private static final int SLOT = 16;
		private static final int MARGIN = 1;

		/**
		 * How often the open menu is re-read.
		 *
		 * <p>Reading fifty-four slots to decide one box is not worth doing sixty times a second, and a
		 * menu that has just changed is worth pointing at within a frame or two of settling.
		 */
		private static final long REREAD_MILLIS = 250L;

		private static WeakReference<Screen> screen = new WeakReference<>(null);
		private static List<Guide> served = List.of();
		private static long readAt;

		private static BazaarStep.Step step;

		/** Which row of the panel the box is serving, or -1. */
		private static int row = -1;

		/**
		 * What the sign that is about to open wants typed into it, and what that number is.
		 *
		 * <p>Kept after the menu closes, because the sign replaces the menu that named it: by the time
		 * the box is on screen there is nothing left to ask what it is for.
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
		 * asks for, and the note is drawn in a panel about seventy pixels wide, so the words are spent on
		 * the one case where the wrong button does something else.
		 */
		static String stepNote() {
			if (step == null || !SkyblockFlipperClient.config().bazaarHighlightEnabled) {
				return "";
			}

			// Only the right-click case earns a line: the box already points at the slot, so naming a
			// plain left-click step ("search", "place") is noise. Right-click is the one where the wrong
			// button does something else.
			return step.click() == BazaarStep.Click.RIGHT
					? "right-click: " + step.label()
					: "";
		}

		/** The line the panel shows in place of its own note while a sign is open. */
		static String typeNote() {
			return "type " + typeLabel + ": " + typeValue;
		}

		/**
		 * The menu closed. {@code sign} says whether what replaced it is a sign, which is the only screen
		 * the typing note - or the panel itself - belongs on once the menu has gone.
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
				// recorded and so what the step is expressed in. The player's own inventory shares those
				// numbers and is never what a bazaar step means.
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
		 * @param list the active type's actions, in the order to try them; the first this menu can serve
		 *             is the one the box points at
		 * @return whether this menu belongs to the bazaar at all, which is what decides if the panel is
		 *         drawn over it
		 */
		static boolean update(AbstractContainerScreen<?> container, List<Guide> list) {
			long now = System.currentTimeMillis();

			if (screen.get() == container && list == served && now - readAt < REREAD_MILLIS) {
				return bazaarFlow;
			}

			screen = new WeakReference<>(container);
			served = list;
			readAt = now;
			step = null;
			row = -1;
			onASign = false;

			CapturedMenu menu = MenuReader.describe(container, now);
			bazaarFlow = BazaarSlots.isBazaarFlow(menu);

			for (int i = 0; i < list.size(); i++) {
				Guide guide = list.get(i);
				Optional<BazaarStep.Step> found =
						BazaarStep.next(guide.action(), guide.restingPrice(), menu);

				if (found.isPresent()) {
					step = found.get();
					row = i;

					// Remembered now, while the menu that named it is still open. The sign this click
					// opens replaces the menu and says nothing about what it wants.
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
		 * <p>An item name is often too long to type or paste into the search sign at all. The bazaar
		 * searches on a prefix, so the shortened form finds the same item.
		 */
		private static String fitting(String value) {
			return Minecraft.getInstance().font.plainSubstrByWidth(value, SIGN_LINE_WIDTH);
		}

	}

	/**
	 * Where the panel was last drawn, in real screen pixels, and what a click there means.
	 *
	 * <p>Static because an immediate-mode panel has no widget to ask. The render pass records the
	 * rectangle it drew into and the mouse handlers hit-test against it, which is sound as long as only
	 * one panel is on screen at a time - it is drawn by one screen at a time, and cleared whenever a
	 * frame decides not to draw it.
	 */
	private static final class Hit {
		/** Nothing has been drawn: every click and scroll passes straight through. */
		private static boolean live;

		private static int x;
		private static int y;
		private static int width;
		private static int height;
		private static float scale = 1.0f;

		/** The panel's top-left in panel pixels, for mapping a click onto a chip. */
		private static int panelX;
		private static int panelY;

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
		 * <p>Held here rather than read off {@link BazaarOverlay#board}, which stays populated while a
		 * different type is on screen. Reading that one would take the right row index into the wrong
		 * list.
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
		 * <p>The lists are rebuilt on every poll whether or not they changed. Resetting here would return
		 * a player who scrolled to row fifteen back to row one within twenty seconds. {@link #laidOut}
		 * clamps it, so a list that got shorter cannot leave the window past the end.
		 */
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
			panelY = py;
			x = Math.round(px * panelScale);
			y = Math.round(py * panelScale);
			width = Math.round(board.width() * panelScale);
			height = Math.round(board.height(shown, font) * panelScale);

			rowHeight = board.rowHeight();
			rowsTop = py + PAD + board.selectorHeight() + board.headerHeight();
			numbersTop = py + board.numbersOffset(0, font);
			lineHeight = font.lineHeight;
			visibleRows = shown;
			rowCount = board.rows().size();

			// A different list, not a rebuild of the same one: switching type starts at the top rather
			// than at row fifteen of something else.
			if (!scrolling.equals(board.label())) {
				scrolling = board.label();
				firstRow = 0;
			}

			// A list that shrank under a scrolled window - orders were placed, or the book moved - would
			// otherwise leave the panel showing blank space below the last row.
			firstRow = Math.clamp(firstRow, 0, Math.max(0, rowCount - shown));
		}

		private static boolean inside(double mouseX, double mouseY) {
			return live && mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
		}

		/** The type chip under the cursor, or null. */
		static StrategyKind hoveredChip(double mouseX, double mouseY) {
			return chipAt(mouseX, mouseY);
		}

		private static StrategyKind chipAt(double mouseX, double mouseY) {
			if (!inside(mouseX, mouseY) || drawn == null) {
				return null;
			}

			int mx = (int) (mouseX / scale) - panelX;
			int my = (int) (mouseY / scale) - (panelY + PAD);

			for (Board.Chip chip : drawn.chips()) {
				if (mx >= chip.x() && mx < chip.x() + chip.width()
						&& my >= chip.y() && my < chip.y() + drawn.chipHeight()) {
					return chip.kind();
				}
			}

			return null;
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

		/**
		 * @return true when the panel took the click, which is what stops it reaching the menu
		 */
		static boolean click(double mouseX, double mouseY) {
			if (!inside(mouseX, mouseY)) {
				return false;
			}

			StrategyKind chip = chipAt(mouseX, mouseY);

			if (chip != null) {
				BazaarOverlay.switchType(chip);
				return true;
			}

			int row = hoveredRow(mouseX, mouseY);

			if (row < 0 || drawn == null) {
				// Inside the panel but not on a row or chip: the heading or the footer. Swallowed anyway,
				// because a click that lands on the panel and moves an item in the menu behind it is the
				// one failure this whole design cannot afford.
				return true;
			}

			Board.Row clicked = drawn.rows().get(row);

			switch (clicked.action()) {
				case COPY -> copy(clicked, mouseX, mouseY);
				case EXPAND -> BazaarOverlay.toggleExpand(clicked.actionId());
				case WORK -> BazaarOverlay.workCandidate(clicked.actionId(), clicked.actionName());
				case STOP -> BazaarOverlay.stopJob(clicked.actionId());
				case NONE -> {
					// A title or a note: swallow and do nothing.
				}
			}

			return true;
		}

		/**
		 * Which of the three things on a row was clicked.
		 *
		 * <p>The name line copies the item name, which is what goes into the bazaar's search sign. The
		 * numbers line is split at the gap between them: the left half copies the price and the right
		 * half copies the size. Clicking a row with no price - a claim, a cancel - always copies the
		 * name.
		 */
		private static void copy(Board.Row row, double mouseX, double mouseY) {
			int py = (int) (mouseY / scale);
			int rowTop = numbersTop + (hoveredRowIndex(py) * rowHeight);
			boolean onNumbers = py >= rowTop && py < rowTop + lineHeight;

			if (!onNumbers || row.price().isEmpty()) {
				// Shortened to what the search sign will take.
				put(Guidance.fitting(row.name()), "name");
				return;
			}

			Font font = Minecraft.getInstance().font;
			int unitsLeft = x + width - Math.round((PAD + Board.text(font, UNITS_MARK)
					+ Board.text(font, row.units())) * scale);

			if (mouseX >= unitsLeft) {
				// The units of one order rather than the whole line: it is what goes in the amount box,
				// and neither the line total nor the split text itself is a number the box takes.
				long first = Stacking.firstOrder(row.units());

				put(first > 0L ? String.valueOf(first) : row.units(), "size");
			} else {
				put(row.price(), "price");
			}
		}

		private static int hoveredRowIndex(int py) {
			return Math.max(0, (py - rowsTop) / rowHeight);
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
