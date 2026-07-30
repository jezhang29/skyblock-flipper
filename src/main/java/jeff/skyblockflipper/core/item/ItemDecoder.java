package jeff.skyblockflipper.core.item;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import jeff.skyblockflipper.core.nbt.NbtCompound;
import jeff.skyblockflipper.core.nbt.NbtReader;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Turns an {@code item_bytes} blob into the handful of attributes that decide what an item is worth.
 *
 * <p>The blob is a hybrid: legacy {@code {i: [{id, Count, tag, Damage}]}} carrying
 * {@code tag.ExtraAttributes}, plus a modern {@code components} compound added in the 26.2
 * migration. Both halves matter. {@code ExtraAttributes} holds everything that was paid for -
 * reforge, stars, enchantments, recombobulator, hot potatoes - while rarity is only in
 * {@code components["minecraft:tooltip_style"]}, which reads {@code hypixel_skyblock:<rarity>}.
 *
 * <p>{@code ExtraAttributes.attributes} is the Kuudra and Crimson Isle roll: a compound of two
 * attribute names to their levels. Only 75 of 26,000 sampled listings carry one, but on those it is
 * the dominant term in the price - bare {@code CRIMSON_BOOTS} were asking 1.9M against 16M for a
 * rolled pair - so leaving it unparsed prices attribute gear off sales of a different item.
 *
 * <p>{@code ExtraAttributes.runes} is the same shape, {@code {MUSIC: 3}}, and matters for the same
 * reason pets do: every rune in the game is the item id {@code RUNE}, so unread it pools an entire
 * rarity into one price. It is read for all items, not just runes - a rune applied to a sword is
 * part of what that sword sells for.
 *
 * <p>Except when it is not there at all. Roughly one item in forty carries no tooltip style, and
 * the only remaining statement of its rarity is the last line of its lore. So this falls back to
 * parsing that line, which is exactly the fragile thing the component was supposed to avoid - and
 * silently returning UNKNOWN rarity for a slice of the market would quietly mis-group it instead.
 */
public final class ItemDecoder {
	private static final String TOOLTIP_STYLE = "minecraft:tooltip_style";

	/** Not a real slot, just the list of which slots are unlocked, alongside the slots themselves. */
	private static final String GEM_SLOT_INDEX = "unlocked_slots";

	private static final Gson GSON = new Gson();

	private ItemDecoder() {
	}

	/** @return empty if the blob is unreadable or carries no Skyblock item */
	public static Optional<DecodedItem> decode(String itemBytes) {
		if (itemBytes == null || itemBytes.isBlank()) {
			return Optional.empty();
		}

		try {
			return fromRoot(NbtReader.readItemBytes(itemBytes));
		} catch (IOException e) {
			// A blob we cannot read is one sale missing from the model, not a reason to stop.
			return Optional.empty();
		}
	}

	/** @param root the parsed blob, whose {@code i} list holds the item */
	public static Optional<DecodedItem> fromRoot(NbtCompound root) {
		// The list can hold more than one stack in principle; auctions sell exactly one.
		Object first = root.list("i").stream().findFirst().orElse(null);

		if (!(first instanceof NbtCompound item)) {
			return Optional.empty();
		}

		NbtCompound tag = item.child("tag");
		NbtCompound extra = tag.child("ExtraAttributes");
		String skyblockId = extra.string("id").orElse("");

		if (skyblockId.isEmpty()) {
			// A vanilla stack with no Skyblock identity cannot be priced against anything.
			return Optional.empty();
		}

		NbtCompound display = tag.child("display");
		// Read once: the pet level is in here too, and it is the only place it is stated.
		String name = stripFormatting(display.string("Name").orElse(skyblockId));

		return Optional.of(new DecodedItem(
				skyblockId,
				name,
				item.intOr("Count", 1),
				rarity(item, display),
				extra.string("modifier").orElse(""),
				stars(extra),
				extra.flag("rarity_upgrades"),
				extra.intOr("hot_potato_count", 0),
				extra.child("enchantments").numericEntries(),
				gemstones(extra.child("gems")),
				extra.child("attributes").numericEntries(),
				extra.child("runes").numericEntries(),
				pet(skyblockId, extra, name)));
	}

	/** Strips the section-sign colour and format codes Minecraft embeds in names and lore. */
	public static String stripFormatting(String text) {
		StringBuilder out = new StringBuilder(text.length());

		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);

			if (c == '§' && i + 1 < text.length()) {
				i++;
			} else {
				out.append(c);
			}
		}

		return out.toString().trim();
	}

	private static Rarity rarity(NbtCompound item, NbtCompound display) {
		Rarity fromComponent = Rarity.fromTooltipStyle(
				item.child("components").string(TOOLTIP_STYLE).orElse(null));

		if (fromComponent != Rarity.UNKNOWN) {
			return fromComponent;
		}

		// Older items carry no tooltip style. Their rarity is the last non-empty lore line, which
		// on mythics arrives wrapped in obfuscation codes, hence the strip first.
		List<String> lore = display.strings("Lore");

		for (int i = lore.size() - 1; i >= 0; i--) {
			String line = stripFormatting(lore.get(i));

			if (!line.isBlank()) {
				return Rarity.fromLoreLine(line);
			}
		}

		return Rarity.UNKNOWN;
	}

	/**
	 * Stars live under two different attributes depending on when the item was made:
	 * {@code upgrade_level} now, {@code dungeon_item_level} historically. Reading only the modern
	 * one prices a five-star item as if it were bare.
	 */
	private static int stars(NbtCompound extra) {
		return Math.max(extra.intOr("upgrade_level", 0), extra.intOr("dungeon_item_level", 0));
	}

	/**
	 * Gemstone slots as {@code JASPER=FINE}, sorted. The slot index ({@code JASPER_0},
	 * {@code JASPER_1}) is dropped: which hole a gem sits in does not change what it is worth.
	 */
	private static List<String> gemstones(NbtCompound gems) {
		if (gems.isEmpty()) {
			return List.of();
		}

		List<String> out = new ArrayList<>();

		for (Map.Entry<String, Object> entry : gems.entries().entrySet()) {
			if (entry.getKey().equals(GEM_SLOT_INDEX)) {
				continue;
			}

			String slot = entry.getKey().replaceFirst("_\\d+$", "");
			String quality = switch (entry.getValue()) {
				case String s -> s;
				// Newer gems are a compound of quality plus the uuid of whoever applied it.
				case NbtCompound c -> c.string("quality").orElse("");
				default -> "";
			};

			if (!quality.isEmpty()) {
				out.add(slot + "=" + quality);
			}
		}

		return out.stream().sorted().toList();
	}

	/**
	 * Pets are all one item id with their identity in a JSON string. Without unpacking it, every
	 * pet in the game looks like the same item trading anywhere from 10k to 500M.
	 *
	 * <p>The level is the one part that is not in that JSON. It is in the display name instead, so
	 * {@code name} is passed in rather than the level being derived from {@code petInfo.exp} - see
	 * {@link PetInfo#level()}.
	 */
	private static PetInfo pet(String skyblockId, NbtCompound extra, String name) {
		if (!skyblockId.equals("PET")) {
			return null;
		}

		Optional<String> raw = extra.string("petInfo");

		if (raw.isEmpty()) {
			return null;
		}

		try {
			JsonObject json = GSON.fromJson(raw.get(), JsonObject.class);

			if (json == null || !json.has("type")) {
				return null;
			}

			return new PetInfo(
					json.get("type").getAsString(),
					Rarity.fromName(string(json, "tier")),
					json.has("exp") ? json.get("exp").getAsDouble() : 0.0d,
					levelFromName(name),
					string(json, "heldItem"),
					json.has("candyUsed") ? json.get("candyUsed").getAsInt() : 0,
					string(json, "skin"));
		} catch (JsonParseException | UnsupportedOperationException | IllegalStateException e) {
			return null;
		}
	}

	/**
	 * Reads the {@code [Lvl 100]} prefix Hypixel puts on every pet name.
	 *
	 * <p>Hand-rolled rather than a regex because this runs once per decoded listing on a sweep of
	 * tens of thousands, and because the shape is fixed enough not to need one. Returns 0 for
	 * anything unexpected: an unreadable prefix should cost the level, not the whole pet.
	 *
	 * @param name display name with formatting codes already stripped
	 */
	static int levelFromName(String name) {
		if (!name.startsWith(LEVEL_PREFIX)) {
			return 0;
		}

		int end = name.indexOf(']', LEVEL_PREFIX.length());

		if (end < 0) {
			return 0;
		}

		try {
			return Math.max(0, Integer.parseInt(name.substring(LEVEL_PREFIX.length(), end).trim()));
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	private static final String LEVEL_PREFIX = "[Lvl ";

	private static String string(JsonObject json, String key) {
		return json.has(key) && json.get(key).isJsonPrimitive() ? json.get(key).getAsString() : "";
	}
}
