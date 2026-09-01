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
package jeff.skyblockflipper.core.item;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import jeff.skyblockflipper.core.nbt.NbtCompound;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Real item_bytes tree shape with controllable ExtraAttributes.ability_scroll values. */
public final class WitherScrollNbtFixtures {
	public static final String IMPLOSION = "IMPLOSION_SCROLL";
	public static final String SHADOW_WARP = "SHADOW_WARP_SCROLL";
	public static final String WITHER_SHIELD = "WITHER_SHIELD_SCROLL";

	private WitherScrollNbtFixtures() {
	}

	/** A production item_bytes capture with auction and player identifiers removed. */
	public static String captured(String state) {
		try (InputStream in = WitherScrollNbtFixtures.class.getResourceAsStream(
				"/wither-scroll-item-bytes.json")) {
			if (in == null) {
				throw new IllegalStateException("missing Wither-scroll fixture");
			}
			JsonObject captures = new Gson().fromJson(
					new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
			return captures.get(state).getAsString();
		} catch (java.io.IOException failure) {
			throw new IllegalStateException("could not read Wither-scroll fixture", failure);
		}
	}

	/** A legitimate unscrolled blade: the list is absent from ExtraAttributes. */
	public static NbtCompound unscrolledHyperion() {
		return root("HYPERION", "Hyperion", false, null);
	}

	/** A blade carrying the supplied raw NBT value under ability_scroll. */
	public static NbtCompound hyperionWith(Object abilityScroll) {
		return root("HYPERION", "Hyperion", true, abilityScroll);
	}

	/**
	 * Mirrors the hybrid blob parsed in production: a legacy i/tag/ExtraAttributes stack plus the
	 * modern tooltip-style component used for rarity.
	 */
	public static NbtCompound root(String id, String name, boolean includeAbilityScroll,
			Object abilityScroll) {
		Map<String, Object> extra = new LinkedHashMap<>();
		extra.put("id", id);
		if (includeAbilityScroll) {
			extra.put("ability_scroll", abilityScroll);
		}

		NbtCompound tag = new NbtCompound(Map.of(
				"ExtraAttributes", new NbtCompound(extra),
				"display", new NbtCompound(Map.of("Name", "§6" + name))));
		NbtCompound item = new NbtCompound(Map.of(
				"Count", (byte) 1,
				"tag", tag,
				"components", new NbtCompound(Map.of(
						"minecraft:tooltip_style", "hypixel_skyblock:legendary"))));
		return new NbtCompound(Map.of("i", List.of(item)));
	}
}
