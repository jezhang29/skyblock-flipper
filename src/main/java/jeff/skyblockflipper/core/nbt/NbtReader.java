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
package jeff.skyblockflipper.core.nbt;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * A minimal reader for the uncompressed NBT format, written here rather than borrowed from vanilla.
 *
 * <p>Two reasons it has to be its own thing. {@code core} must not import {@code net.minecraft}, or
 * none of the valuation work is testable without launching a game. And more importantly, an
 * {@code item_bytes} blob is <em>not</em> a valid item stack after the 26.2 migration: it is legacy
 * {@code {i: [{id, Count, tag, Damage}]}} with {@code tag.ExtraAttributes} intact, plus a modern
 * {@code components} compound bolted on. No vanilla codec reads that, so building an
 * {@code ItemStack} from it fails or, worse, silently drops half the fields that carry the value.
 *
 * <p>Every input here came off the internet, so the parser is bounded: nesting depth and payload
 * lengths are capped so a corrupt or hostile blob costs an exception rather than the heap.
 */
public final class NbtReader {
	private static final int TAG_END = 0;
	private static final int TAG_BYTE = 1;
	private static final int TAG_SHORT = 2;
	private static final int TAG_INT = 3;
	private static final int TAG_LONG = 4;
	private static final int TAG_FLOAT = 5;
	private static final int TAG_DOUBLE = 6;
	private static final int TAG_BYTE_ARRAY = 7;
	private static final int TAG_STRING = 8;
	private static final int TAG_LIST = 9;
	private static final int TAG_COMPOUND = 10;
	private static final int TAG_INT_ARRAY = 11;
	private static final int TAG_LONG_ARRAY = 12;

	/** Deep enough for any real item; shallow enough that a crafted blob cannot blow the stack. */
	private static final int MAX_DEPTH = 64;

	/** No legitimate item carries a million-element array. */
	private static final int MAX_ELEMENTS = 1 << 20;

	private NbtReader() {
	}

	/** Decodes the base64, gunzips it and parses the root compound. */
	public static NbtCompound readItemBytes(String base64) throws IOException {
		byte[] compressed;

		try {
			compressed = Base64.getDecoder().decode(base64);
		} catch (IllegalArgumentException e) {
			throw new IOException("item_bytes is not valid base64", e);
		}

		try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
			return read(in);
		}
	}

	/** Parses a root compound, including the name that the file format writes before it. */
	public static NbtCompound read(InputStream in) throws IOException {
		DataInputStream data = new DataInputStream(in);
		int type = data.readUnsignedByte();

		if (type != TAG_COMPOUND) {
			throw new IOException("expected a root compound, got tag " + type);
		}

		data.readUTF();
		return readCompound(data, 0);
	}

	private static NbtCompound readCompound(DataInputStream in, int depth) throws IOException {
		if (depth > MAX_DEPTH) {
			throw new IOException("NBT nested deeper than " + MAX_DEPTH);
		}

		Map<String, Object> values = new LinkedHashMap<>();

		while (true) {
			int type = in.readUnsignedByte();

			if (type == TAG_END) {
				return new NbtCompound(values);
			}

			values.put(in.readUTF(), readValue(in, type, depth + 1));
		}
	}

	private static Object readValue(DataInputStream in, int type, int depth) throws IOException {
		return switch (type) {
			case TAG_BYTE -> in.readByte();
			case TAG_SHORT -> in.readShort();
			case TAG_INT -> in.readInt();
			case TAG_LONG -> in.readLong();
			case TAG_FLOAT -> in.readFloat();
			case TAG_DOUBLE -> in.readDouble();
			case TAG_BYTE_ARRAY -> in.readNBytes(length(in));
			case TAG_STRING -> in.readUTF();
			case TAG_LIST -> readList(in, depth);
			case TAG_COMPOUND -> readCompound(in, depth);
			case TAG_INT_ARRAY -> readIntArray(in);
			case TAG_LONG_ARRAY -> readLongArray(in);
			default -> throw new IOException("unknown NBT tag " + type);
		};
	}

	private static List<Object> readList(DataInputStream in, int depth) throws IOException {
		int elementType = in.readUnsignedByte();
		int size = length(in);

		// An empty list is written with element type TAG_END, which is not readable as a value.
		if (elementType == TAG_END || size == 0) {
			return List.of();
		}

		List<Object> out = new ArrayList<>(Math.min(size, 1024));

		for (int i = 0; i < size; i++) {
			out.add(readValue(in, elementType, depth + 1));
		}

		return out;
	}

	private static int[] readIntArray(DataInputStream in) throws IOException {
		int[] out = new int[length(in)];

		for (int i = 0; i < out.length; i++) {
			out[i] = in.readInt();
		}

		return out;
	}

	private static long[] readLongArray(DataInputStream in) throws IOException {
		long[] out = new long[length(in)];

		for (int i = 0; i < out.length; i++) {
			out[i] = in.readLong();
		}

		return out;
	}

	/**
	 * Reads a length and refuses to trust it. A corrupt length is the one field that turns a
	 * malformed blob into an allocation the size of the heap, so it is checked before anything is
	 * sized from it.
	 */
	private static int length(DataInputStream in) throws IOException {
		int length = in.readInt();

		if (length < 0) {
			throw new EOFException("negative NBT length " + length);
		}

		if (length > MAX_ELEMENTS) {
			throw new IOException("NBT payload of " + length + " elements is implausible");
		}

		return length;
	}
}
