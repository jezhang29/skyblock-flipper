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

import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * The one door to the settings screen, and the only class allowed to knock on it.
 *
 * <p>{@link FlipConfigScreen} imports Cloth Config, which is an optional dependency: loading that
 * class without Cloth installed is a {@code NoClassDefFoundError}. This class imports nothing of
 * Cloth's, so it is always loadable, and it names {@code FlipConfigScreen} only inside a method
 * body guarded by {@link #available()} - the JVM resolves that reference when the call runs, not
 * when this class does. Every caller goes through here so the guard exists once.
 */
public final class Settings {
	/** Cloth's mod id, and the one the {@code recommends} block in {@code fabric.mod.json} names. */
	private static final String CLOTH = "cloth-config";

	private Settings() {
	}

	/** Whether a settings screen can be opened at all. False means Cloth Config is not installed. */
	public static boolean available() {
		return FabricLoader.getInstance().isModLoaded(CLOTH);
	}

	/**
	 * Opens the settings screen over {@code parent}, which is what it returns to on Save or Cancel.
	 *
	 * <p>A no-op when Cloth is missing rather than a thrown error: callers are buttons and
	 * commands, and every one of them checks {@link #available()} first so it can say something
	 * more useful than nothing happening.
	 */
	public static void open(Screen parent) {
		if (!available()) {
			return;
		}

		Minecraft.getInstance().setScreenAndShow(FlipConfigScreen.create(parent));
	}
}
