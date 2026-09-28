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

import jeff.skyblockflipper.client.SkyblockFlipperClient;

import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

import org.lwjgl.glfw.GLFW;

/**
 * The key that opens {@link FlipScreen}.
 *
 * <p>Registered as a real {@link KeyMapping} rather than read straight off GLFW, which is what
 * puts it in Options &rarr; Controls and lets vanilla flag a conflict. Right shift is unbound in
 * vanilla, but a minority of players move sneak onto it, and they should be able to see and fix
 * that in the usual place rather than discover it by sneaking into a screen.
 *
 * <p>Note the Fabric module here is {@code keymapping.v1}, not the {@code keybinding.v1} that
 * older code and most tutorials use.
 */
public final class FlipKeybinds {
	private static final KeyMapping OPEN = new KeyMapping(
			"key.skyblock-flipper.open",
			InputConstants.Type.KEYSYM,
			GLFW.GLFW_KEY_RIGHT_SHIFT,
			KeyMapping.Category.MISC);

	private FlipKeybinds() {
	}

	public static void register() {
		KeyMappingHelper.registerKeyMapping(OPEN);

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			// Drained in a loop rather than tested once: consumeClick reports queued presses, and
			// a dropped frame would otherwise swallow one.
			boolean pressed = false;

			while (OPEN.consumeClick()) {
				pressed = true;
			}

			// No guard on whether a screen is already open: 26.2 exposes no public accessor for
			// the current screen, and none is needed, since vanilla only queues key mapping
			// clicks while nothing is capturing input. A screen that is up receives the key
			// through keyPressed instead, which is where the close-on-same-key path lives.
			if (pressed && SkyblockFlipperClient.config().guiKeybindEnabled) {
				client.setScreenAndShow(new FlipScreen());
			}
		});
	}

	/**
	 * Whether {@code key} is the one currently bound to opening the screen.
	 *
	 * <p>Needed because key mappings do not tick while a screen is open, so the screen has to
	 * recognise its own open key to close on it. Read from the live binding rather than from the
	 * default, so rebinding keeps the toggle working.
	 */
	public static boolean isOpenKey(int key) {
		return KeyMappingHelper.getBoundKeyOf(OPEN).getValue() == key;
	}

	/** The bound key's display name, for the screen's footer hint. */
	public static String boundKeyName() {
		return KeyMappingHelper.getBoundKeyOf(OPEN).getDisplayName().getString();
	}

	/** Opens the screen from somewhere other than the keybind, e.g. a command. */
	public static void open() {
		Minecraft.getInstance().setScreenAndShow(new FlipScreen());
	}
}
