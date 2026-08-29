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

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Scroll state for a panel whose content height is only known once it has been drawn.
 *
 * <p>The detail panel and the guide render prose that wraps at the panel's width, so how tall they
 * are depends on the font, the zoom, and which candidate is selected. Measuring that up front would
 * mean laying the text out twice. Instead each panel draws itself at {@code -offset()} inside a
 * scissor and reports back where it finished, and the scroll range is clamped from the previous
 * frame's measurement - which is only ever wrong for the single frame after the content changes,
 * and self-corrects on the next one.
 */
final class Scroller {
	private static final int STEP = 12;

	private static final int TRACK = 0x30FFFFFF;
	private static final int THUMB = 0xA0FFFFFF;

	private int offset;
	private int contentHeight;
	private int viewHeight;

	int offset() {
		return offset;
	}

	/** Called after drawing, with what the content actually needed and what there was room for. */
	void measured(int contentHeight, int viewHeight) {
		this.contentHeight = contentHeight;
		this.viewHeight = viewHeight;
		clamp();
	}

	/** @return true when the wheel moved something, so the event should not fall through */
	boolean scroll(double amount) {
		if (!overflows()) {
			return false;
		}

		offset -= (int) Math.signum(amount) * STEP;
		clamp();
		return true;
	}

	void reset() {
		offset = 0;
	}

	boolean overflows() {
		return contentHeight > viewHeight;
	}

	/** A two-pixel bar on the right edge, drawn only when there is something out of sight. */
	void renderBar(GuiGraphicsExtractor graphics, int x, int y, int width, int height) {
		if (!overflows()) {
			return;
		}

		int thumbHeight = Math.max(8, height * viewHeight / contentHeight);
		int travel = height - thumbHeight;
		int thumbTop = y + travel * offset / Math.max(1, contentHeight - viewHeight);

		graphics.fill(x + width - 2, y, x + width, y + height, TRACK);
		graphics.fill(x + width - 2, thumbTop, x + width, thumbTop + thumbHeight, THUMB);
	}

	private void clamp() {
		offset = Math.clamp(offset, 0, Math.max(0, contentHeight - viewHeight));
	}
}
