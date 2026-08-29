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
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Append-only record of what Hypixel said, in chat and in menus, while capture was on.
 *
 * <p>This is scaffolding for writing a trade tracker, not a thing the mod reads. Nothing prices off
 * it and nothing parses it at runtime: a play session fills it, and its contents become a test
 * fixture that a parser is then written against. Regexes written from memory of a message format
 * fail silently - they match nothing and the tracker simply records no trades - so the parser gets
 * built from measured text instead.
 *
 * <p>Every line is a JSON object with a {@code type} of {@code chat} or {@code menu}. One line per
 * record and never rewritten, so an interrupted session keeps everything up to the interruption.
 *
 * <p>Capped by total bytes: a menu snapshot carries the raw custom-data compound of every slot, and
 * a long session in and out of the bazaar would otherwise fill a disk quietly. Hitting the cap stops
 * writing rather than rotating, because the first hour of a session is worth more than the fifth and
 * silently discarding the early half would be the wrong half to lose.
 */
public final class CaptureLog {
	/** Big enough for several sessions of menus, small enough to notice nothing has gone wrong. */
	public static final long DEFAULT_MAX_BYTES = 32L * 1024 * 1024;

	private final Path file;
	private final long maxBytes;
	private final Gson gson = new Gson();

	private long bytes = -1L;
	private int records;
	private boolean full;

	public CaptureLog(Path file) {
		this(file, DEFAULT_MAX_BYTES);
	}

	public CaptureLog(Path file, long maxBytes) {
		this.file = file;
		this.maxBytes = maxBytes;
	}

	public synchronized void append(CapturedChat chat) throws IOException {
		write("chat", chat);
	}

	public synchronized void append(CapturedMenu menu) throws IOException {
		write("menu", menu);
	}

	/** True once the size cap stopped it recording. Worth telling the player, since it is silent. */
	public synchronized boolean isFull() {
		return full;
	}

	/** Records written since this instance was created, which is what a status line wants. */
	public synchronized int records() {
		return records;
	}

	public synchronized long bytes() {
		return bytes < 0L ? sizeOnDisk() : bytes;
	}

	public Path file() {
		return file;
	}

	private void write(String type, Object payload) throws IOException {
		if (bytes < 0L) {
			bytes = sizeOnDisk();
		}

		if (full) {
			return;
		}

		JsonObject json = gson.toJsonTree(payload).getAsJsonObject();
		// Inserted after the fact rather than carried as a record component, so the records stay
		// plain data with no field that exists only to name their own file format.
		json.addProperty("type", type);

		byte[] line = (gson.toJson(json) + "\n").getBytes(StandardCharsets.UTF_8);

		if (bytes + line.length > maxBytes) {
			full = true;
			return;
		}

		Files.createDirectories(file.getParent());
		Files.write(file, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		bytes += line.length;
		records++;
	}

	private long sizeOnDisk() {
		try {
			return Files.exists(file) ? Files.size(file) : 0L;
		} catch (IOException e) {
			return 0L;
		}
	}
}
