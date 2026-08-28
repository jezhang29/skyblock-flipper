package jeff.skyblockflipper.client.gui;

import jeff.skyblockflipper.core.strategy.FlipCandidate;
import jeff.skyblockflipper.core.text.Coins;
import jeff.skyblockflipper.core.text.Waits;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The scrollable, sortable list of candidates.
 *
 * <p>Not an {@code AbstractWidget}: it owns a scroll offset, a selection and clickable column
 * headers, and expressing that through the widget contract would be more plumbing than the screen
 * driving it directly. The screen forwards clicks and scrolls; this decides what they mean.
 *
 * <p>Sorting happens here and nowhere else. The list handed in is already ranked by profit per
 * hour - {@link FlipCandidate}'s natural order - and re-sorting is a view concern; the ranking
 * itself still belongs to {@code CandidateFeed}.
 *
 * <p><b>Columns are measured, not placed at fixed fractions.</b> The numeric columns are as wide as
 * their widest actual value plus their header, right-aligned against their own edge, and the item
 * name gets whatever is left over and is cut to fit it. Fractional positions were what let
 * "Enchanted Golden Carrot" run underneath its own profit figure: a fraction that works at one GUI
 * scale, in one language, for one set of numbers does not work for the next, and the failure mode
 * is two numbers drawn on top of each other with no indication which is which.
 *
 * <p><b>The name is paid first, and columns that no longer fit are dropped.</b> Leftovers turned out
 * to be nothing: five columns left the name about 45 pixels, which cut most bazaar
 * items to "Enchante..." - and since 187 of 5549 Skyblock names are a strict prefix of another, a
 * truncated name is not a weak identifier but a wrong one. So the name reserves room for
 * {@link #NAME_YARDSTICK} first and the numeric columns fill what is left, rightmost dropping out
 * first. Nothing is lost by dropping one: the detail panel states every figure in full for whatever
 * row is selected, and this table exists to compare rows, which needs the ranking column and a name
 * you can read.
 */
final class CandidateTable {
	static final int ROW_HEIGHT = 12;
	private static final int HEADER_HEIGHT = 14;

	private static final int ROW_ALTERNATE = 0x18FFFFFF;
	private static final int ROW_SELECTED = 0x40FFD700;
	private static final int ROW_HOVERED = 0x20FFFFFF;
	private static final int HEADER_RULE = 0xFF555555;
	private static final int TEXT = 0xFFFFFFFF;
	private static final int TEXT_DIM = 0xFFAAAAAA;
	private static final int TEXT_NAME = 0xFF55FFFF;
	private static final int TEXT_PROFIT = 0xFF55FF55;

	/** Gap between one column's text and the next column's, and either edge of the panel. */
	private static final int GAP = 6;

	/** Where the rank number ends and the name may start. */
	private static final int RANK_WIDTH = 16;

	/**
	 * Room the name column is given before any numeric column is placed, in pixels.
	 *
	 * <p>Sized from a real name rather than a round number, and from a typical one rather than the
	 * longest: this is a floor the layout must clear, not the width the name ends up with, and
	 * setting it at the longest name in the game would cost a whole column to serve the tail.
	 * Capped against the panel in {@link #layout} so a narrow window starves the numbers rather than
	 * the numbers starving it.
	 */
	private static final String NAME_YARDSTICK = "Enchanted Cocoa Bean";

	/** Padding around the hovered-name tooltip, and how far it sits from the cursor. */
	private static final int TOOLTIP_PAD = 3;
	private static final int TOOLTIP_BACKGROUND = 0xF0100010;
	private static final int TOOLTIP_EDGE = 0xFF5A5A7A;

	/** What a column click sorts by. Every one of these is already on {@link FlipCandidate}. */
	enum Column {
		NAME("Item", Comparator.comparing(FlipCandidate::displayName)),
		PROFIT("Profit/hr", Comparator.comparingDouble(FlipCandidate::profitPerHour)),
		CAPITAL("Capital", Comparator.comparingLong(FlipCandidate::capitalRequired)),

		/**
		 * How long the plan takes to turn over, and the column the screen was missing.
		 *
		 * <p>A plan quoting 6.78M an hour that needs eleven hours to fill and one that clears in
		 * twenty minutes ranked identically and looked identical. Sorted with the unknowns last,
		 * because "no estimate" is not "fast".
		 */
		FILL("Fill", Comparator.comparingDouble(
				c -> c.timeToTurnOver().map(Duration::toSeconds).orElse(Long.MAX_VALUE)));

		private final String label;
		private final Comparator<FlipCandidate> ascending;

		Column(String label, Comparator<FlipCandidate> ascending) {
			this.label = label;
			this.ascending = ascending;
		}

		String label() {
			return label;
		}
	}

	private List<FlipCandidate> candidates = List.of();

	private Column sortColumn = Column.PROFIT;
	private boolean descending = true;
	private int scroll;
	private int selected = -1;

	private int x;
	private int y;
	private int width;
	private int height;

	/** Right edge of each numeric column's text, measured from the data. */
	private final Map<Column, Integer> rightEdge = new EnumMap<>(Column.class);

	/** Which columns the panel turned out to be wide enough for. Never empty of NAME or PROFIT. */
	private final Set<Column> shown = EnumSet.noneOf(Column.class);
	private boolean layoutStale = true;

	/** The row under the cursor as of the last frame, so the overlay pass knows what to explain. */
	private int hoveredRow = -1;

	void setBounds(int x, int y, int width, int height) {
		this.x = x;
		this.y = y;
		this.width = width;
		this.height = height;
		this.layoutStale = true;
	}

	/**
	 * Replaces the contents, keeping the selected item selected if it is still present.
	 *
	 * <p>Matching on the candidate's identity rather than its index matters: the book moves under
	 * a screen that is open, and re-ranking would otherwise silently slide the selection onto a
	 * different flip than the one being read.
	 */
	void setCandidates(List<FlipCandidate> candidates) {
		FlipCandidate previous = selection();

		this.candidates = new ArrayList<>(candidates);
		this.layoutStale = true;
		applySort();

		selected = -1;

		if (previous != null) {
			for (int i = 0; i < this.candidates.size(); i++) {
				FlipCandidate candidate = this.candidates.get(i);

				if (candidate.itemId().equals(previous.itemId())
						&& candidate.kind() == previous.kind()) {
					selected = i;
					break;
				}
			}
		}

		clampScroll();
	}

	FlipCandidate selection() {
		return selected >= 0 && selected < candidates.size() ? candidates.get(selected) : null;
	}

	boolean isEmpty() {
		return candidates.isEmpty();
	}

	/** Rank as shown, 1-based, so it lines up with what {@code /flip take} would mean. */
	int selectedRank() {
		return selected + 1;
	}

	void render(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY) {
		layout(font);
		renderHeader(graphics, font, mouseX, mouseY);

		int rows = visibleRows();
		int top = y + HEADER_HEIGHT;

		hoveredRow = -1;

		// Clipped so a partially visible final row cannot bleed past the panel.
		graphics.enableScissor(x, top, x + width, y + height);

		for (int i = 0; i < rows && scroll + i < candidates.size(); i++) {
			int index = scroll + i;
			int rowY = top + i * ROW_HEIGHT;
			boolean hovered = mouseX >= x && mouseX < x + width
					&& mouseY >= rowY && mouseY < rowY + ROW_HEIGHT;

			if (hovered) {
				hoveredRow = index;
			}

			if (index == selected) {
				graphics.fill(x, rowY, x + width, rowY + ROW_HEIGHT, ROW_SELECTED);
			} else if (hovered) {
				graphics.fill(x, rowY, x + width, rowY + ROW_HEIGHT, ROW_HOVERED);
			} else if (index % 2 == 1) {
				graphics.fill(x, rowY, x + width, rowY + ROW_HEIGHT, ROW_ALTERNATE);
			}

			renderRow(graphics, font, candidates.get(index), index, rowY);
		}

		graphics.disableScissor();
		renderScrollbar(graphics);
	}

	private void renderRow(GuiGraphicsExtractor graphics, Font font, FlipCandidate candidate,
			int index, int rowY) {
		int textY = rowY + (ROW_HEIGHT - font.lineHeight) / 2 + 1;

		graphics.text(font, Component.literal(String.valueOf(index + 1))
				.withStyle(ChatFormatting.DARK_GRAY), x + 3, textY, TEXT_DIM);

		graphics.text(font, Component.literal(fitName(font, candidate.displayName())),
				x + RANK_WIDTH, textY, TEXT_NAME);

		rightAligned(graphics, font, Column.PROFIT, candidate, textY, TEXT_PROFIT);
		rightAligned(graphics, font, Column.CAPITAL, candidate, textY, TEXT);
		rightAligned(graphics, font, Column.FILL, candidate, textY,
				candidate.fillMeasured() ? TEXT : TEXT_DIM);

	}

	private void rightAligned(GuiGraphicsExtractor graphics, Font font, Column column,
			FlipCandidate candidate, int textY, int colour) {
		if (!shown.contains(column)) {
			return;
		}

		Component component = Component.literal(cell(column, candidate));
		graphics.text(font, component, rightEdge(column) - font.width(component), textY, colour);
	}

	private void renderHeader(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY) {
		int textY = y + (HEADER_HEIGHT - font.lineHeight) / 2;
		boolean overHeader = mouseY >= y && mouseY < y + HEADER_HEIGHT;

		graphics.text(font, Component.literal("#").withStyle(ChatFormatting.DARK_GRAY),
				x + 3, textY, TEXT_DIM);

		for (Column column : Column.values()) {
			if (!shown.contains(column)) {
				continue;
			}

			Component label = Component.literal(headerLabel(column));
			int left = columnLeft(column);
			boolean hovered = overHeader && mouseX >= left && mouseX < columnRight(column);
			int colour = column == sortColumn || hovered ? TEXT : TEXT_DIM;

			// Headers sit over their own cells: the name reads from the left like the names below
			// it, the numbers from the right like the numbers below them.
			int at = column == Column.NAME ? left : rightEdge(column) - font.width(label);
			graphics.text(font, label, at, textY, colour);
		}

		graphics.fill(x, y + HEADER_HEIGHT - 1, x + width, y + HEADER_HEIGHT, HEADER_RULE);
	}

	private String headerLabel(Column column) {
		return column.label() + (column == sortColumn ? (descending ? " v" : " ^") : "");
	}

	private void renderScrollbar(GuiGraphicsExtractor graphics) {
		int rows = visibleRows();

		if (candidates.size() <= rows) {
			return;
		}

		int trackTop = y + HEADER_HEIGHT;
		int trackHeight = height - HEADER_HEIGHT;
		int thumbHeight = Math.max(8, trackHeight * rows / candidates.size());
		int travel = trackHeight - thumbHeight;
		int thumbTop = trackTop + travel * scroll / Math.max(1, candidates.size() - rows);

		graphics.fill(x + width - 2, trackTop, x + width, trackTop + trackHeight, 0x30FFFFFF);
		graphics.fill(x + width - 2, thumbTop, x + width, thumbTop + thumbHeight, 0xA0FFFFFF);
	}

	/**
	 * @return true when the click landed on the table and was handled
	 */
	boolean mouseClicked(double mouseX, double mouseY) {
		if (mouseX < x || mouseX >= x + width || mouseY < y || mouseY >= y + height) {
			return false;
		}

		if (mouseY < y + HEADER_HEIGHT) {
			return headerClicked(mouseX);
		}

		int row = (int) ((mouseY - y - HEADER_HEIGHT) / ROW_HEIGHT) + scroll;

		if (row >= 0 && row < candidates.size()) {
			selected = row;
		}

		return true;
	}

	private boolean headerClicked(double mouseX) {
		for (Column column : Column.values()) {
			if (!shown.contains(column)) {
				continue;
			}

			if (mouseX >= columnLeft(column) && mouseX < columnRight(column)) {
				// Clicking the active column reverses it; a new column starts descending, which
				// is what "best first" means for every column except the name.
				if (column == sortColumn) {
					descending = !descending;
				} else {
					sortColumn = column;
					descending = column != Column.NAME;
				}

				applySort();
				// The arrow moves with the sort column, which changes how wide the headers are.
				layoutStale = true;
				scroll = 0;
				return true;
			}
		}

		return true;
	}

	boolean mouseScrolled(double amount) {
		int rows = visibleRows();

		if (candidates.size() <= rows) {
			return false;
		}

		scroll -= (int) Math.signum(amount);
		clampScroll();
		return true;
	}

	/** Keyboard row movement, so the list is usable without a mouse. */
	void moveSelection(int delta) {
		if (candidates.isEmpty()) {
			return;
		}

		selected = Math.clamp(selected + delta, 0, candidates.size() - 1);

		int rows = visibleRows();

		if (selected < scroll) {
			scroll = selected;
		} else if (selected >= scroll + rows) {
			scroll = selected - rows + 1;
		}

		clampScroll();
	}

	private void applySort() {
		Comparator<FlipCandidate> comparator = sortColumn.ascending;
		candidates.sort(descending ? comparator.reversed() : comparator);
	}

	private void clampScroll() {
		scroll = Math.clamp(scroll, 0, Math.max(0, candidates.size() - visibleRows()));
	}

	private int visibleRows() {
		return Math.max(1, (height - HEADER_HEIGHT) / ROW_HEIGHT);
	}

	/**
	 * Measures the numeric columns and packs them against the right edge.
	 *
	 * <p>Recomputed only when the data, the sort arrow or the panel size changes, not per frame -
	 * {@link #width} walks the whole candidate list, which is not a per-frame cost worth paying.
	 */
	private void layout(Font font) {
		if (!layoutStale) {
			return;
		}

		layoutStale = false;
		rightEdge.clear();
		shown.clear();
		shown.add(Column.NAME);

		int nameLeft = x + RANK_WIDTH;
		int right = x + width - GAP;

		// Half the panel at most: on a narrow window a name that insisted on its full yardstick would
		// push even the profit column out, and a ranking with no ranking figure is not worth reading.
		int reserved = Math.min(font.width(Component.literal(NAME_YARDSTICK)),
				(width - RANK_WIDTH) / 2);
		int budget = right - nameLeft - reserved;
		int spent = 0;

		// Chosen before they are placed, and in the order they matter rather than the order they
		// happen to fit. Stopping at the first column that misses keeps the visible set a prefix of
		// this list: "the two most useful columns" is explainable, "all but Capital" is a puzzle.
		// Fill sits second: whether an order clears at all decides more than how hard the coins
		// work while it does not.
		for (Column column : List.of(Column.PROFIT, Column.FILL, Column.CAPITAL)) {
			int widest = width(font, column);

			// Profit is what the list is ranked by, so it is placed whether it fits or not.
			if (column != Column.PROFIT && spent + widest + GAP > budget) {
				break;
			}

			shown.add(column);
			spent += widest + GAP;
		}

		// Right to left, because it is the right-hand edge each column is aligned to.
		for (Column column : List.of(Column.CAPITAL, Column.FILL, Column.PROFIT)) {
			if (shown.contains(column)) {
				rightEdge.put(column, right);
				right -= width(font, column) + GAP;
			}
		}

		rightEdge.put(Column.NAME, Math.max(right, nameLeft));

		// A sort arrow on a column that is no longer drawn cannot be clicked off again, which would
		// leave the list stuck in an order with nothing on screen explaining it.
		if (!shown.contains(sortColumn)) {
			sortColumn = Column.PROFIT;
			descending = true;
			applySort();
		}
	}

	/**
	 * How wide a column has to be to hold its header and every value in it.
	 *
	 * <p>Every candidate, not only the visible ones, so that scrolling does not make the columns
	 * twitch as longer values come into view. The header is measured with a sort arrow whether it
	 * has one or not, so that sorting by a column does not widen it.
	 */
	private int width(Font font, Column column) {
		int widest = font.width(Component.literal(column.label() + " v"));

		for (FlipCandidate candidate : candidates) {
			widest = Math.max(widest, font.width(Component.literal(cell(column, candidate))));
		}

		return widest;
	}

	private int rightEdge(Column column) {
		return rightEdge.getOrDefault(column, x + width);
	}

	/** Left edge of a column's clickable header, which starts where the last shown column ended. */
	private int columnLeft(Column column) {
		if (column == Column.NAME) {
			return x + RANK_WIDTH;
		}

		Column previous = Column.NAME;

		for (Column other : Column.values()) {
			if (other == column) {
				break;
			}

			if (shown.contains(other)) {
				previous = other;
			}
		}

		return rightEdge(previous) + GAP;
	}

	private int columnRight(Column column) {
		return column == Column.NAME ? rightEdge(Column.NAME) : rightEdge(column);
	}

	private static String cell(Column column, FlipCandidate candidate) {
		return switch (column) {
			case NAME -> candidate.displayName();
			case PROFIT -> Coins.format(candidate.profitPerHour());
			case CAPITAL -> Coins.format(candidate.capitalRequired());
			// A tilde marks an estimate from an assumed share of flow rather than from recorded
			// displacement, so a guess never reads as a measurement.
			case FILL -> candidate.timeToTurnOver()
					.map(d -> (candidate.fillMeasured() ? "" : "~") + Waits.format(d))
					.orElse("-");
		};
	}

	/**
	 * The full name of the hovered row, when the column had to cut it.
	 *
	 * <p>A separate pass rather than part of {@link #render}, because this has to be drawn over the
	 * detail panel next door and the table is drawn before it. Only shown when there is something to
	 * recover: a tooltip repeating a name that is already fully legible is noise.
	 */
	void renderHoverName(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY,
			int viewWidth, int viewHeight) {
		if (hoveredRow < 0 || hoveredRow >= candidates.size()) {
			return;
		}

		String name = candidates.get(hoveredRow).displayName();

		if (!isTruncated(font, name)) {
			return;
		}

		Component label = Component.literal(name);
		int boxWidth = font.width(label) + 2 * TOOLTIP_PAD;
		int boxHeight = font.lineHeight + 2 * TOOLTIP_PAD;

		// Pinned inside the screen rather than following the cursor off it: near the right edge the
		// box would otherwise hang past the window and clip the end of the very name it is showing.
		int boxX = Math.clamp(mouseX + 8, 0, Math.max(0, viewWidth - boxWidth));
		int boxY = Math.clamp(mouseY - boxHeight - 2, 0, Math.max(0, viewHeight - boxHeight));

		graphics.fill(boxX, boxY, boxX + boxWidth, boxY + boxHeight, TOOLTIP_BACKGROUND);
		graphics.outline(boxX, boxY, boxWidth, boxHeight, TOOLTIP_EDGE);
		graphics.text(font, label, boxX + TOOLTIP_PAD, boxY + TOOLTIP_PAD, TEXT_NAME);
	}

	private boolean isTruncated(Font font, String name) {
		return font.width(Component.literal(name)) > rightEdge(Column.NAME) - (x + RANK_WIDTH);
	}

	/** Cuts a name to the space the numeric columns left it. Recoverable by hovering the row. */
	private String fitName(Font font, String name) {
		return Labels.fit(font, name, rightEdge(Column.NAME) - (x + RANK_WIDTH));
	}
}
