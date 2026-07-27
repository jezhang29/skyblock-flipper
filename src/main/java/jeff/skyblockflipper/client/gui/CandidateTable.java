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
import java.util.List;

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

	/** Long names would otherwise push the numeric columns off the panel. */
	private static final int MAX_NAME_CHARS = 24;

	private static final int SPARK_WIDTH = 14;

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

	void setBounds(int x, int y, int width, int height) {
		this.x = x;
		this.y = y;
		this.width = width;
		this.height = height;
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

		graphics.text(font, Component.literal(shorten(candidate.displayName())),
				columnX(Column.NAME), textY, TEXT_NAME);

		graphics.text(font, Component.literal(Coins.format(candidate.profitPerHour())),
				columnX(Column.PROFIT), textY, TEXT_PROFIT);

		graphics.text(font, Component.literal(Coins.format(candidate.capitalRequired())),
				columnX(Column.CAPITAL), textY, TEXT);

		graphics.text(font, Component.literal(
						String.format("%.0f%%", candidate.returnOnCapital() * 100.0d)),
				columnX(Column.RETURN), textY, TEXT_DIM);

		graphics.text(font, Component.literal(String.format("%.2f", candidate.confidence())),
				columnX(Column.CONFIDENCE), textY, TEXT_DIM);

		PriceTrend trend = trends.trendFor(candidate.itemId()).orElse(null);
		Sparkline.draw(graphics, trend, x + width - SPARK_WIDTH - 4, rowY + 3, SPARK_WIDTH);
	}

	private void renderHeader(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY) {
		int textY = y + (HEADER_HEIGHT - font.lineHeight) / 2;
		boolean overHeader = mouseY >= y && mouseY < y + HEADER_HEIGHT;

		graphics.text(font, Component.literal("#").withStyle(ChatFormatting.DARK_GRAY),
				x + 3, textY, TEXT_DIM);

		for (Column column : Column.values()) {
			int columnX = columnX(column);
			int columnWidth = font.width(Component.literal(column.label())) + 8;
			boolean hovered = overHeader && mouseX >= columnX && mouseX < columnX + columnWidth;

			String label = column.label() + (column == sortColumn ? (descending ? " v" : " ^") : "");
			int colour = column == sortColumn ? TEXT : (hovered ? TEXT : TEXT_DIM);

			graphics.text(font, Component.literal(label), columnX, textY, colour);
		}

		graphics.fill(x, y + HEADER_HEIGHT - 1, x + width, y + HEADER_HEIGHT, HEADER_RULE);
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
			int columnX = columnX(column);
			int columnWidth = columnWidthFor(column);

			if (mouseX >= columnX && mouseX < columnX + columnWidth) {
				// Clicking the active column reverses it; a new column starts descending, which
				// is what "best first" means for every column except the name.
				if (column == sortColumn) {
					descending = !descending;
				} else {
					sortColumn = column;
					descending = column != Column.NAME;
				}

				applySort();
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
	 * Column positions as fractions of the panel, so the table reflows with the window instead of
	 * clipping on small GUI scales.
	 */
	private int columnX(Column column) {
		return switch (column) {
			case NAME -> x + 16;
			case PROFIT -> x + (int) (width * 0.42d);
			case CAPITAL -> x + (int) (width * 0.60d);
			case RETURN -> x + (int) (width * 0.76d);
			case CONFIDENCE -> x + (int) (width * 0.86d);
		};
	}

	private int columnWidthFor(Column column) {
		Column[] all = Column.values();
		int index = column.ordinal();

		return index + 1 < all.length
				? columnX(all[index + 1]) - columnX(column)
				: x + width - columnX(column);
	}

	private static String shorten(String name) {
		return name.length() <= MAX_NAME_CHARS ? name : name.substring(0, MAX_NAME_CHARS - 1) + "…";
	}
}
