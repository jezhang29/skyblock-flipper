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
