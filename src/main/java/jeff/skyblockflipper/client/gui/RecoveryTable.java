package jeff.skyblockflipper.client.gui;

import jeff.skyblockflipper.core.recovery.RecoveryOpportunity;
import jeff.skyblockflipper.core.text.Coins;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

import java.util.List;

/** Compact read-only table for recovery opportunities. */
final class RecoveryTable {
	private static final int HEADER_HEIGHT = 14;
	private static final int ROW_HEIGHT = 13;
	private static final int TEXT = 0xFFFFFFFF;
	private static final int DIM = 0xFFAAAAAA;
	private static final int PROFIT = 0xFF55FF55;
	private static final int SELECTED = 0x40FFD700;
	private static final int HOVERED = 0x30FFFFFF;

	private final RecoveryTableModel model = new RecoveryTableModel();
	private int x;
	private int y;
	private int width;
	private int height;
	private int scroll;

	void setBounds(int x, int y, int width, int height) {
		this.x = x;
		this.y = y;
		this.width = width;
		this.height = height;
		clampScroll();
	}

	void setRows(List<RecoveryOpportunity> rows) {
		model.setRows(rows);
		clampScroll();
	}

	RecoveryOpportunity selection() {
		return model.selection();
	}

	boolean isEmpty() {
		return model.rows().isEmpty();
	}

	void render(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY) {
		int profitWidth = font.width(Component.literal("000.00M")) + 7;
		int marginWidth = font.width(Component.literal("000.0%")) + 7;
		int rankWidth = font.width(Component.literal("000")) + 6;
		int nameWidth = Math.max(20, width - profitWidth - marginWidth - rankWidth);
		int textY = y + 2;
		graphics.text(font, Component.literal("#"), x + 3, textY, DIM);
		graphics.text(font, Component.literal("Host"), x + rankWidth, textY, DIM);
		right(graphics, font, "Profit", x + rankWidth + nameWidth + profitWidth - 3, textY, DIM);
		right(graphics, font, "Margin", x + width - 3, textY, DIM);
		graphics.fill(x, y + HEADER_HEIGHT - 1, x + width, y + HEADER_HEIGHT, 0x50FFFFFF);

		int selected = model.selectedIndex();
		graphics.enableScissor(x, y + HEADER_HEIGHT, x + width, y + height);
		for (int visible = 0; visible < visibleRows() && scroll + visible < model.rows().size(); visible++) {
			int index = scroll + visible;
			int rowY = y + HEADER_HEIGHT + visible * ROW_HEIGHT;
			boolean hovered = mouseX >= x && mouseX < x + width
					&& mouseY >= rowY && mouseY < rowY + ROW_HEIGHT;
			if (index == selected) {
				graphics.fill(x, rowY, x + width, rowY + ROW_HEIGHT, SELECTED);
			} else if (hovered) {
				graphics.fill(x, rowY, x + width, rowY + ROW_HEIGHT, HOVERED);
			}
			RecoveryOpportunity row = model.rows().get(index);
			int atY = rowY + 2;
			graphics.text(font, Component.literal(String.valueOf(index + 1)), x + 3, atY, DIM);
			graphics.text(font, Component.literal(fit(font, row.displayName(), nameWidth - 4)),
					x + rankWidth, atY, TEXT);
			right(graphics, font, Coins.format(row.expectedProfit()),
					x + rankWidth + nameWidth + profitWidth - 3, atY, PROFIT);
			right(graphics, font, String.format("%.1f%%", row.margin() * 100.0d),
					x + width - 3, atY, TEXT);
		}
		graphics.disableScissor();
	}

	boolean mouseClicked(double mouseX, double mouseY) {
		if (mouseX < x || mouseX >= x + width || mouseY < y + HEADER_HEIGHT
				|| mouseY >= y + height) {
			return false;
		}
		int row = (int) ((mouseY - y - HEADER_HEIGHT) / ROW_HEIGHT) + scroll;
		if (row < model.rows().size()) {
			model.select(row);
		}
		return true;
	}

	boolean mouseScrolled(double amount) {
		if (model.rows().size() <= visibleRows()) {
			return false;
		}
		scroll -= (int) Math.signum(amount);
		clampScroll();
		return true;
	}

	void moveSelection(int delta) {
		model.move(delta);
		int selected = model.selectedIndex();
		if (selected < scroll) {
			scroll = selected;
		} else if (selected >= scroll + visibleRows()) {
			scroll = selected - visibleRows() + 1;
		}
	}

	private int visibleRows() {
		return Math.max(1, (height - HEADER_HEIGHT) / ROW_HEIGHT);
	}

	private void clampScroll() {
		scroll = Math.clamp(scroll, 0, Math.max(0, model.rows().size() - visibleRows()));
	}

	private static void right(GuiGraphicsExtractor graphics, Font font, String value, int edge,
			int y, int colour) {
		Component text = Component.literal(value);
		graphics.text(font, text, edge - font.width(text), y, colour);
	}

	private static String fit(Font font, String value, int available) {
		if (font.width(Component.literal(value)) <= available) {
			return value;
		}
		String ellipsis = "...";
		return font.plainSubstrByWidth(value, Math.max(0, available - font.width(ellipsis))) + ellipsis;
	}
}
