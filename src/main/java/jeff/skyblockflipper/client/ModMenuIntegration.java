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
package jeff.skyblockflipper.client;

import jeff.skyblockflipper.client.gui.FlipConfigScreen;
import jeff.skyblockflipper.client.gui.Settings;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * Wires the settings screen to Mod Menu's config button.
 *
 * <p>Registered on the {@code modmenu} entrypoint, so Fabric only constructs it when Mod Menu is
 * installed; nothing here runs otherwise.
 *
 * <p>Cloth Config is a separate question, and Mod Menu without Cloth is a real combination that has
 * to degrade to an inert button rather than an error. The method reference below loads
 * {@link FlipConfigScreen}, and with it Cloth's types, as soon as this method runs - so it sits
 * behind {@link Settings#available()}, which loads nothing.
 */
public final class ModMenuIntegration implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		if (!Settings.available()) {
			// Mod Menu's own "this mod has no config screen" factory.
			return ModMenuApi.super.getModConfigScreenFactory();
		}

		return FlipConfigScreen::create;
	}
}
