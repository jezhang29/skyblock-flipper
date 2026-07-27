package jeff.skyblockflipper.client.gui;

import jeff.skyblockflipper.core.strategy.FlipCandidate;
import jeff.skyblockflipper.core.text.Coins;
import jeff.skyblockflipper.core.valuation.PriceTrend;
import jeff.skyblockflipper.core.valuation.TrendSnapshot;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

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

	private static final int SPARK_WIDTH = 14;

	/** Gap between one column's text and the next column's, and either edge of the panel. */
	private static final int GAP = 6;

	/** Where the rank number ends and the name may start. */
	private static final int RANK_WIDTH = 16;

	/** What a column click sorts by. Every one of these is already on {@link FlipCandidate}. */
	enum Column {
		NAME("Item", Comparator.comparing(FlipCandidate::displayName)),
		PROFIT("Profit/hr", Comparator.comparingDouble(FlipCandidate::profitPerHour)),
		CAPITAL("Capital", Comparator.comparingLong(FlipCandidate::capitalRequired)),
		RETURN("ROC", Comparator.comparingDouble(FlipCandidate::returnOnCapital)),
		CONFIDENCE("Conf", Comparator.comparingDouble(FlipCandidate::confidence));

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
	private TrendSnapshot trends = TrendSnapshot.empty();

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
	private boolean layoutStale = true;

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
	void setCandidates(List<FlipCandidate> candidates, TrendSnapshot trends) {
		FlipCandidate previous = selection();

		this.candidates = new ArrayList<>(candidates);
		this.trends = trends;
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

		// Clipped so a partially visible final row cannot bleed past the panel.
		graphics.enableScissor(x, top, x + width, y + height);

		for (int i = 0; i < rows && scroll + i < candidates.size(); i++) {
			int index = scroll + i;
			int rowY = top + i * ROW_HEIGHT;
			boolean hovered = mouseX >= x && mouseX < x + width
					&& mouseY >= rowY && mouseY < rowY + ROW_HEIGHT;

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

		rightAligned(graphics, font, Column.PROFIT, cell(Column.PROFIT, candidate), textY, TEXT_PROFIT);
		rightAligned(graphics, font, Column.CAPITAL, cell(Column.CAPITAL, candidate), textY, TEXT);
		rightAligned(graphics, font, Column.RETURN, cell(Column.RETURN, candidate), textY, TEXT_DIM);
		rightAligned(graphics, font, Column.CONFIDENCE, cell(Column.CONFIDENCE, candidate), textY, TEXT_DIM);

		PriceTrend trend = trends.trendFor(candidate.itemId()).orElse(null);
		Sparkline.draw(graphics, trend, x + width - SPARK_WIDTH - 4, rowY + 3, SPARK_WIDTH);
	}

	private void rightAligned(GuiGraphicsExtractor graphics, Font font, Column column, String text,
			int textY, int colour) {
		Component component = Component.literal(text);
		graphics.text(font, component, rightEdge(column) - font.width(component), textY, colour);
	}

	private void renderHeader(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY) {
		int textY = y + (HEADER_HEIGHT - font.lineHeight) / 2;
		boolean overHeader = mouseY >= y && mouseY < y + HEADER_HEIGHT;

		graphics.text(font, Component.literal("#").withStyle(ChatFormatting.DARK_GRAY),
				x + 3, textY, TEXT_DIM);

		for (Column column : Column.values()) {
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
	 * <p>Recomputed only when the data, the sort arrow or the panel size changes, not per frame:
	 * this walks every candidate rather than only the visible ones, so that scrolling does not make
	 * the columns twitch as longer values come into view.
	 */
	private void layout(Font font) {
		if (!layoutStale) {
			return;
		}

		layoutStale = false;

		int edge = x + width - SPARK_WIDTH - GAP;

		// Right to left, because it is the right-hand edge each column is aligned to.
		for (Column column : List.of(Column.CONFIDENCE, Column.RETURN, Column.CAPITAL, Column.PROFIT)) {
			int widest = font.width(Component.literal(column.label() + " v"));

			for (FlipCandidate candidate : candidates) {
				widest = Math.max(widest, font.width(Component.literal(cell(column, candidate))));
			}

			rightEdge.put(column, edge);
			edge -= widest + GAP;
		}

		rightEdge.put(Column.NAME, edge);
	}

	private int rightEdge(Column column) {
		return rightEdge.getOrDefault(column, x + width);
	}

	private int columnLeft(Column column) {
		return column == Column.NAME
				? x + RANK_WIDTH
				: rightEdge(Column.values()[column.ordinal() - 1]) + GAP;
	}

	private int columnRight(Column column) {
		return column == Column.NAME ? rightEdge(Column.NAME) : rightEdge(column);
	}

	private static String cell(Column column, FlipCandidate candidate) {
		return switch (column) {
			case NAME -> candidate.displayName();
			case PROFIT -> Coins.format(candidate.profitPerHour());
			case CAPITAL -> Coins.format(candidate.capitalRequired());
			case RETURN -> String.format("%.0f%%", candidate.returnOnCapital() * 100.0d);
			case CONFIDENCE -> String.format("%.2f", candidate.confidence());
		};
	}

	/**
	 * Cuts a name to the space the numeric columns left it, in pixels rather than characters.
	 *
	 * <p>A character budget is the wrong unit: "Whipped Magma Cream" and "IIIIIIIIIIIIIIIIIII" are
	 * the same length and nothing like the same width.
	 */
	private String fitName(Font font, String name) {
		int available = rightEdge(Column.NAME) - (x + RANK_WIDTH);
		Component full = Component.literal(name);

		if (font.width(full) <= available) {
			return name;
		}

		String ellipsis = "...";
		int budget = available - font.width(Component.literal(ellipsis));

		if (budget <= 0) {
			return "";
		}

		int end = name.length();

		while (end > 0 && font.width(Component.literal(name.substring(0, end))) > budget) {
			end--;
		}

		return name.substring(0, end) + ellipsis;
	}
}
