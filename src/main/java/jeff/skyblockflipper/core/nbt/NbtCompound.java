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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.TreeMap;

/**
 * A generic NBT compound: names to values, where a value is a boxed primitive, a {@code String}, a
 * {@code List}, an array or another compound.
 *
 * <p>Deliberately untyped and schema-free. The blob this parses is half legacy tag and half modern
 * components, so there is no single codec that describes it and every accessor here has to tolerate
 * a key being absent, being a different width than expected, or being something else entirely.
 * Everything returns an {@code Optional} or a default rather than throwing.
 */
public final class NbtCompound {
	private static final NbtCompound EMPTY = new NbtCompound(Map.of());

	private final Map<String, Object> values;

	public NbtCompound(Map<String, Object> values) {
		this.values = Map.copyOf(values);
	}

	public static NbtCompound empty() {
		return EMPTY;
	}

	public Set<String> keys() {
		return values.keySet();
	}

	public boolean contains(String key) {
		return values.containsKey(key);
	}

	public boolean isEmpty() {
		return values.isEmpty();
	}

	public Optional<String> string(String key) {
		return values.get(key) instanceof String s ? Optional.of(s) : Optional.empty();
	}

	/** Any numeric tag, widened. Hypixel is inconsistent about byte versus int for flag-like fields. */
	public OptionalDouble number(String key) {
		return values.get(key) instanceof Number n ? OptionalDouble.of(n.doubleValue()) : OptionalDouble.empty();
	}

	public int intOr(String key, int fallback) {
		OptionalDouble value = number(key);
		return value.isPresent() ? (int) value.getAsDouble() : fallback;
	}

	/** Treats any non-zero numeric value as set, which is how Hypixel writes its boolean flags. */
	public boolean flag(String key) {
		return number(key).orElse(0.0d) != 0.0d;
	}

	public Optional<NbtCompound> compound(String key) {
		return values.get(key) instanceof NbtCompound c ? Optional.of(c) : Optional.empty();
	}

	/** The named compound, or an empty one, for the common "read through it either way" case. */
	public NbtCompound child(String key) {
		return compound(key).orElse(EMPTY);
	}

	public List<Object> list(String key) {
		return values.get(key) instanceof List<?> list ? List.copyOf(list) : List.of();
	}

	public List<String> strings(String key) {
		return list(key).stream().filter(String.class::isInstance).map(String.class::cast).toList();
	}

	/**
	 * Every numeric entry, sorted by name. Used for the enchantment compound, where the keys are
	 * the data; sorting keeps a signature built from it stable across parses.
	 */
	public Map<String, Integer> numericEntries() {
		Map<String, Integer> out = new TreeMap<>();

		values.forEach((key, value) -> {
			if (value instanceof Number n) {
				out.put(key, n.intValue());
			}
		});

		return out;
	}

	/** All entries, for tests and for exploring a blob whose shape is not yet known. */
	public Map<String, Object> entries() {
		return new LinkedHashMap<>(values);
	}

	@Override
	public String toString() {
		return "NbtCompound" + values.keySet();
	}
}
