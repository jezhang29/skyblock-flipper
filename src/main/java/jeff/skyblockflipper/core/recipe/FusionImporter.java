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
package jeff.skyblockflipper.core.recipe;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

import jeff.skyblockflipper.core.recipe.FusionTable.Route;
import jeff.skyblockflipper.core.recipe.FusionTable.Shard;

import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses the bundled {@code fusion-data.json} into a {@link FusionTable}.
 *
 * <p>Twin of {@link NeuRecipeImporter}, but run at load rather than by hand: the source file is a
 * single JSON object small enough to parse on startup, so there is no intermediate table to check
 * in. Regenerate the bundled file by copying a newer {@code public/fusion-data.json} from
 * {@code Campionnn/SkyShards} and repinning the commit in {@code SKYSHARDS-LICENSE}; the schema is
 * pinned by {@link FusionImporterTest}.
 *
 * <h2>The schema, and the one translation it needs</h2>
 *
 * <p>The file has two members. {@code shards} maps an internal code ({@code C1}) to
 * {@code {name, family, rarity, fuse_amount, internal_id}}, where {@code internal_id} is the bazaar
 * id. {@code recipes} maps an output code to {@code {"1"|"2": [[inA, inB], ...]}} - the key is the
 * output quantity and the list is every input pair, in codes. Everything downstream speaks bazaar
 * ids, so this class resolves every code to its {@code internal_id} once and the {@link FusionTable}
 * never sees a code.
 *
 * <p>Two game rules are baked in here so the solver does not re-derive them per route:
 *
 * <ul>
 *   <li><b>{@code fuse_amount} is a property of the input.</b> The cost of a fusion is
 *       {@code minCost(a)*a.fuse_amount + minCost(b)*b.fuse_amount}, so each input carries its own
 *       consume count. Stored on the {@link Shard}, read by the solver, not on the route.</li>
 *   <li><b>Reptile is judged on the inputs.</b> SkyShards' {@code isReptile} is
 *       {@code inputs.some(i => shards[i].family.includes("Reptile"))}, and it earns the crocodile
 *       double-output perk. Pre-computed into {@link Route#reptile()} so the hot loop never touches
 *       a family string.</li>
 * </ul>
 */
public final class FusionImporter {
	private static final Gson GSON = new Gson();

	private FusionImporter() {
	}

	/**
	 * Reads the whole {@code fusion-data.json} document and builds the table.
	 *
	 * <p>A recipe route naming a code the {@code shards} block does not define is dropped rather than
	 * failing the parse - a stray code loses one route, not the graph. On the shipped file no route
	 * is dropped for this reason.
	 */
	public static FusionTable parse(Reader reader) {
		Document document = GSON.fromJson(reader, Document.class);

		if (document == null || document.shards == null || document.recipes == null) {
			return FusionTable.empty();
		}

		Map<String, Shard> byCode = new HashMap<>(document.shards.size());
		Map<String, Shard> byId = new HashMap<>(document.shards.size());

		for (Map.Entry<String, ShardDto> entry : document.shards.entrySet()) {
			ShardDto dto = entry.getValue();

			if (dto == null || dto.internalId == null || dto.internalId.isBlank()) {
				continue;
			}

			Shard shard = new Shard(dto.internalId, dto.name, dto.family, dto.rarity,
					Math.max(1, dto.fuseAmount));
			byCode.put(entry.getKey(), shard);
			byId.put(shard.id(), shard);
		}

		Map<String, List<Route>> byOutput = new HashMap<>(document.recipes.size());

		for (Map.Entry<String, Map<String, List<List<String>>>> output : document.recipes.entrySet()) {
			Shard outputShard = byCode.get(output.getKey());

			if (outputShard == null) {
				continue;
			}

			List<Route> routes = new ArrayList<>();

			for (Map.Entry<String, List<List<String>>> byQty : output.getValue().entrySet()) {
				int quantity = quantity(byQty.getKey());

				if (quantity <= 0 || byQty.getValue() == null) {
					continue;
				}

				for (List<String> pair : byQty.getValue()) {
					addRoute(routes, pair, quantity, byCode);
				}
			}

			if (!routes.isEmpty()) {
				byOutput.put(outputShard.id(), routes);
			}
		}

		return new FusionTable(byId, byOutput);
	}

	private static void addRoute(List<Route> into, List<String> pair, int quantity,
			Map<String, Shard> byCode) {
		if (pair == null || pair.size() != 2) {
			return;
		}

		Shard a = byCode.get(pair.get(0));
		Shard b = byCode.get(pair.get(1));

		if (a == null || b == null) {
			return;
		}

		boolean reptile = isReptile(a) || isReptile(b);
		into.add(new Route(a.id(), b.id(), quantity, reptile));
	}

	/** A family counts as reptile when its name contains {@code Reptile}, matching the reference tool. */
	private static boolean isReptile(Shard shard) {
		return shard.family() != null && shard.family().contains("Reptile");
	}

	/** The quantity key {@code "1"}/{@code "2"} as an int, 0 for anything unparseable. */
	private static int quantity(String key) {
		try {
			return Integer.parseInt(key.trim());
		} catch (NumberFormatException | NullPointerException e) {
			return 0;
		}
	}

	/** Wire form of the whole document. Gson honours the field's generic type when filling it. */
	private static final class Document {
		Map<String, ShardDto> shards;
		Map<String, Map<String, List<List<String>>>> recipes;
	}

	/** Wire form of one shard entry. Only the fields the flip needs are declared. */
	private static final class ShardDto {
		String name;
		String family;
		String rarity;

		@SerializedName("fuse_amount")
		int fuseAmount;

		@SerializedName("internal_id")
		String internalId;
	}
}
