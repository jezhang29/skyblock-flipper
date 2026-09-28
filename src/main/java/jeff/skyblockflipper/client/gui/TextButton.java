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
package jeff.skyblockflipper.client.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * A button that lives inside the flip screen's zoomed coordinate space.
 *
 * <p>Vanilla's {@code Button} is a {@code Screen} widget, and widgets are laid out, drawn and
 * hit-tested by the screen in unscaled GUI pixels. The flip screen draws its own contents under a
 * matrix scale so that a dense table still fits at GUI scale 6, and a widget cannot follow it
 * there: it would render at full size next to shrunken panels, and its click box would sit
 * somewhere else again. Three buttons is not enough plumbing to be worth the mismatch.
 */
final class TextButton {
	private static final int FACE = 0xFF3A3A3A;
	private static final int FACE_HOVER = 0xFF585858;
	private static final int EDGE = 0xFF7A7A7A;
	private static final int LABEL = 0xFFFFFFFF;
	private static final int LABEL_DISABLED = 0xFF777777;

	private Component label;
	private final Runnable action;

	private int x;
	private int y;
	private int width;
	private int height;

	TextButton(String label, Runnable action) {
		this.label = Component.literal(label);
		this.action = action;
	}

	/**
	 * Re-labels the button in place, for the one whose label is the state it is in - Work against
	 * Stop working. Its bounds do not follow, so the wider of the two labels is what to size it at:
	 * a button that resizes as the selection changes moves out from under the cursor.
	 */
	void setLabel(String text) {
		label = Component.literal(text);
	}

	void setBounds(int x, int y, int width, int height) {
		this.x = x;
		this.y = y;
		this.width = width;
		this.height = height;
	}

	int width() {
		return width;
	}

	/** Width that fits the label with room to breathe, for laying a row of these out. */
	int preferredWidth(Font font) {
		return font.width(label) + 10;
	}

	void render(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY, boolean enabled) {
		boolean hovered = enabled && contains(mouseX, mouseY);

		graphics.fill(x, y, x + width, y + height, hovered ? FACE_HOVER : FACE);
		graphics.outline(x, y, width, height, EDGE);

		graphics.text(font, label,
				x + (width - font.width(label)) / 2,
				y + (height - font.lineHeight) / 2 + 1,
				enabled ? LABEL : LABEL_DISABLED);
	}

	/** @return true when the click was on this button, in which case the action has already run */
	boolean clicked(double mouseX, double mouseY) {
		if (!contains(mouseX, mouseY)) {
			return false;
		}

		action.run();
		return true;
	}

	private boolean contains(double mouseX, double mouseY) {
		return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
	}
}
