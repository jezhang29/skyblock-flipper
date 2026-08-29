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
package jeff.skyblockflipper.client.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Where an open container menu actually sits on screen.
 *
 * <p>{@code leftPos}, {@code topPos} and {@code imageWidth} are protected, and
 * {@code BazaarOverlay} has to know all three: it draws beside Hypixel's menu, and a panel that
 * guessed the menu was 176 wide and centred would sit on top of one that was not.
 *
 * <p>The first mixin in the mod, and deliberately only an accessor. Nothing here changes vanilla
 * behaviour, so it cannot break a screen it does not understand.
 */
@Mixin(AbstractContainerScreen.class)
public interface ContainerScreenLayout {
	@Accessor("leftPos")
	int flipper$leftPos();

	@Accessor("topPos")
	int flipper$topPos();

	@Accessor("imageWidth")
	int flipper$imageWidth();

	@Accessor("imageHeight")
	int flipper$imageHeight();
}
