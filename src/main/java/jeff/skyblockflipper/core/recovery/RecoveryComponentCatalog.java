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
package jeff.skyblockflipper.core.recovery;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Versioned, explicit assumptions about removable component identities and costs.
 *
 * <p>Entries with no removal cost are still useful for explaining a decoded attachment, but they
 * can never create credited value. This is intentional: drill and rod removal prices need a
 * captured current menu fixture before they are safe to use.
 */
public final class RecoveryComponentCatalog {
	public static final String RULES_VERSION = "recovery-components-2026-08-28-v1";

	public record Entry(String stableComponentId, RecoveryComponentKind kind, String displayName,
			RecoveryExitVenue exitVenue, OptionalLong removalCost, String evidenceVersion) {
		public Entry {
			stableComponentId = Objects.requireNonNull(stableComponentId, "stableComponentId");
			Objects.requireNonNull(kind, "kind");
			displayName = Objects.requireNonNull(displayName, "displayName");
			Objects.requireNonNull(exitVenue, "exitVenue");
			removalCost = Objects.requireNonNull(removalCost, "removalCost");
			evidenceVersion = Objects.requireNonNull(evidenceVersion, "evidenceVersion");
			if (stableComponentId.isBlank() || displayName.isBlank()
					|| exitVenue == RecoveryExitVenue.NONE
					|| (removalCost.isPresent() && removalCost.getAsLong() < 0L)
					|| evidenceVersion.isBlank()) {
				throw new IllegalArgumentException("invalid recovery catalog entry");
			}
		}
	}

	private static final Map<String, Entry> ENTRIES = entries();

	private RecoveryComponentCatalog() {}

	public static Optional<Entry> find(RecoveryAttachment attachment) {
		Objects.requireNonNull(attachment, "attachment");
		Entry entry = ENTRIES.get(attachment.stableComponentId());
		return entry != null && entry.kind() == attachment.kind()
				? Optional.of(entry) : Optional.empty();
	}

	/** Produces the explained zero used when a mapping or current cost is unavailable. */
	public static RecoveryLeg uncredited(RecoveryAttachment attachment) {
		Optional<Entry> found = find(attachment);
		if (found.isEmpty()) {
			return RecoveryLeg.uncredited(attachment.kind(), attachment.stableComponentId(),
					readable(attachment.stableComponentId()), attachment.quantity(),
					RecoveryWarning.UNKNOWN_MAPPING);
		}
		Entry entry = found.orElseThrow();
		if (entry.removalCost().isEmpty()) {
			return RecoveryLeg.uncredited(attachment.kind(), attachment.stableComponentId(),
					entry.displayName(), attachment.quantity(),
					RecoveryWarning.UNKNOWN_REMOVAL_COST);
		}
		throw new IllegalArgumentException("mapped component has enough catalog data to quote");
	}

	private static Map<String, Entry> entries() {
		Map<String, Entry> result = new LinkedHashMap<>();
		Map<String, Long> removalByQuality = Map.of(
				"ROUGH", 1L,
				"FLAWED", 100L,
				"FINE", 10_000L,
				"FLAWLESS", 100_000L,
				"PERFECT", 500_000L);
		String[] gemstoneTypes = {"AMBER", "AMETHYST", "AQUAMARINE", "CITRINE", "JADE",
				"JASPER", "ONYX", "OPAL", "PERIDOT", "RUBY", "SAPPHIRE", "TOPAZ"};
		for (String type : gemstoneTypes) {
			for (Map.Entry<String, Long> quality : removalByQuality.entrySet()) {
				String id = quality.getKey() + "_" + type + "_GEM";
				put(result, new Entry(id, RecoveryComponentKind.GEMSTONE, readable(id),
						RecoveryExitVenue.BAZAAR, OptionalLong.of(quality.getValue()), RULES_VERSION));
			}
		}

		// Captured item_bytes prove these identities. Costs remain deliberately uncredited until a
		// current removal-menu capture proves them.
		addUnknownCost(result, RecoveryComponentKind.DRILL_ENGINE,
				"MITHRIL_DRILL_ENGINE", "TITANIUM_DRILL_ENGINE", "RUBY_POLISHED_DRILL_ENGINE",
				"SAPPHIRE_POLISHED_DRILL_ENGINE", "AMBER_POLISHED_DRILL_ENGINE");
		addUnknownCost(result, RecoveryComponentKind.DRILL_FUEL_TANK,
				"MITHRIL_FUEL_TANK", "TITANIUM_FUEL_TANK", "PERFECTLY_CUT_FUEL_TANK");
		addUnknownCost(result, RecoveryComponentKind.DRILL_UPGRADE_MODULE, "STARFALL_SEASONING");
		addUnknownCost(result, RecoveryComponentKind.GOBLIN_OMELETTE,
				"GOBLIN_OMELETTE", "GOBLIN_OMELETTE_SPICY", "GOBLIN_OMELETTE_SUNNY_SIDE",
				"GOBLIN_OMELETTE_BLUE_CHEESE", "GOBLIN_OMELETTE_PESTO");
		addUnknownCost(result, RecoveryComponentKind.FISHING_HOOK,
				"COMMON_HOOK", "HOTSPOT_HOOK", "PHANTOM_HOOK", "PUDDLE_JUMPER_HOOK");
		addUnknownCost(result, RecoveryComponentKind.FISHING_LINE,
				"SPEEDY_LINE", "SHREDDED_LINE");
		addUnknownCost(result, RecoveryComponentKind.FISHING_SINKER,
				"JUNK_SINKER", "STINGY_SINKER", "HOTSPOT_SINKER", "ICY_SINKER",
				"PRISMARINE_SINKER", "LOTUS_SINKER", "SPONGE_SINKER", "FESTIVE_SINKER");
		return Map.copyOf(result);
	}

	private static void addUnknownCost(Map<String, Entry> entries, RecoveryComponentKind kind,
			String... ids) {
		for (String id : ids) {
			put(entries, new Entry(id, kind, readable(id), RecoveryExitVenue.AH,
					OptionalLong.empty(), RULES_VERSION));
		}
	}

	private static void put(Map<String, Entry> entries, Entry entry) {
		if (entries.put(entry.stableComponentId(), entry) != null) {
			throw new IllegalStateException("duplicate recovery component " + entry.stableComponentId());
		}
	}

	private static String readable(String id) {
		StringBuilder result = new StringBuilder();
		for (String word : id.toLowerCase(Locale.ROOT).split("_")) {
			if (!result.isEmpty()) {
				result.append(' ');
			}
			result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
		}
		return result.toString();
	}
}
