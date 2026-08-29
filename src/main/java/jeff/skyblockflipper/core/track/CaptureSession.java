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
package jeff.skyblockflipper.core.track;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Reads a capture file back into the records {@link CaptureLog} wrote.
 *
 * <p>Order is the point. The file interleaves chat and menus as they happened, and
 * {@link TradeTracker} needs them in that order to say which claim belongs to which order. Reading
 * the two into separate lists and merging them afterwards would work only because the writer
 * happens to be single-threaded.
 *
 * <p>Parsed field by field rather than handed to Gson's record support, because a capture file can
 * end mid-line when a session is killed. An unreadable line is skipped like an unreadable
 * {@link jeff.skyblockflipper.core.ledger.Ledger} entry: losing one snapshot is not worth losing
 * the session around it.
 *
 * <p>Whole-file, so it is for tests and for offline analysis of a finished session. Live tracking
 * feeds {@link TradeTracker} from the client instead and never opens this file.
 */
public final class CaptureSession {
	private final List<CaptureRecord> records;

	private CaptureSession(List<CaptureRecord> records) {
		this.records = List.copyOf(records);
	}

	public static CaptureSession read(Path file) throws IOException {
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			return read(reader);
		}
	}

	public static CaptureSession read(Reader source) throws IOException {
		List<CaptureRecord> records = new ArrayList<>();
		Gson gson = new Gson();

		try (BufferedReader reader = new BufferedReader(source)) {
			for (String line = reader.readLine(); line != null; line = reader.readLine()) {
				if (!line.isBlank()) {
					parse(gson, line).ifPresent(records::add);
				}
			}
		}

		return new CaptureSession(records);
	}

	/** Everything in the file, in the order the client saw it. */
	public List<CaptureRecord> records() {
		return records;
	}

	public List<CapturedChat> chats() {
		return records.stream().filter(CapturedChat.class::isInstance).map(CapturedChat.class::cast).toList();
	}

	public List<CapturedMenu> menus() {
		return records.stream().filter(CapturedMenu.class::isInstance).map(CapturedMenu.class::cast).toList();
	}

	private static Optional<CaptureRecord> parse(Gson gson, String line) {
		try {
			JsonObject json = gson.fromJson(line, JsonObject.class);
			long at = json.get("at").getAsLong();

			return switch (json.get("type").getAsString()) {
				case "chat" -> Optional.of(new CapturedChat(at, json.get("text").getAsString()));
				case "menu" -> Optional.of(menu(at, json));
				default -> Optional.empty();
			};
		} catch (JsonParseException | IllegalStateException | NullPointerException | ClassCastException e) {
			return Optional.empty();
		}
	}

	private static CapturedMenu menu(long at, JsonObject json) {
		List<CapturedSlot> slots = new ArrayList<>();

		for (JsonElement element : json.getAsJsonArray("slots")) {
			JsonObject slot = element.getAsJsonObject();
			List<String> lore = new ArrayList<>();
			JsonArray lines = slot.getAsJsonArray("lore");

			if (lines != null) {
				lines.forEach(text -> lore.add(text.getAsString()));
			}

			slots.add(new CapturedSlot(
					slot.get("index").getAsInt(),
					slot.get("name").getAsString(),
					lore,
					slot.get("itemId").getAsString(),
					slot.get("count").getAsInt(),
					slot.get("customData").getAsString()));
		}

		return new CapturedMenu(at, json.get("title").getAsString(), slots);
	}
}
