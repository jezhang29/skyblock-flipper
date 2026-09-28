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
package jeff.skyblockflipper.core.tape;

/**
 * Pulls one top-level field out of a JSON line without parsing the line.
 *
 * <p>Exists for one job: building the set of keys already present in a tape file so a merge can
 * skip them. A sales day file is a quarter of a gigabyte whose every line carries a kilobyte and a
 * half of base64 item blob, and handing all of that to Gson to read back a sixteen-character
 * auction id costs tens of seconds per merge. A scan for {@code "auction_id":} runs at the speed of
 * the disk.
 *
 * <p>Safe only because of what these lines are: Gson output for records this project defines, so
 * the field appears once, at a predictable place, with no whitespace around the colon and no
 * escapes in the values used as keys (auction ids are hex, product ids are upper-case identifiers,
 * days are dates). It is not a JSON parser and must not be used as one - anything that needs a
 * value rather than a key still goes through Gson.
 */
final class JsonLines {
	private JsonLines() {
	}

	/**
	 * The value of {@code name}, quotes stripped, or null when the field is absent.
	 *
	 * <p>Takes the first occurrence, which is the top-level one: every record here declares its key
	 * components first, and Gson writes components in declaration order.
	 */
	static String field(String line, String name) {
		String needle = "\"" + name + "\":";
		int at = line.indexOf(needle);

		if (at < 0) {
			return null;
		}

		int from = at + needle.length();

		if (from >= line.length()) {
			return null;
		}

		if (line.charAt(from) == '"') {
			int end = line.indexOf('"', from + 1);
			return end < 0 ? null : line.substring(from + 1, end);
		}

		int end = from;

		while (end < line.length() && line.charAt(end) != ',' && line.charAt(end) != '}') {
			end++;
		}

		String value = line.substring(from, end).trim();
		return value.isEmpty() ? null : value;
	}

	/** Two fields joined, or null if either is missing, so a partial key is never a match. */
	static String pair(String line, String first, String second) {
		String a = field(line, first);
		String b = field(line, second);
		return a == null || b == null ? null : a + "|" + b;
	}
}
