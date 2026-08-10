package jeff.skyblockflipper.core.tape;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Folds lines from another copy of a tape into ours, keeping one line per key.
 *
 * <p>Both this client and the collector on the server tape the same endpoints, and while both are
 * running they write the same day file independently. Copying the server's file over ours would
 * discard everything taped while playing, and appending it blindly would double-count every sale
 * both machines saw - which is most of them. So a merge, keyed on whatever the file's own reader
 * would consider one record: the id for a sale, the snapshot instant plus the product for a bazaar
 * sample, the signature plus the day for a rollup line.
 *
 * <p>Every merge rescans the local file to learn which keys it already holds. That is a sequential
 * read of up to a few hundred megabytes, done with {@link JsonLines} rather than Gson to keep it at
 * disk speed, and it is why the sync runs on its own thread and not on the poller.
 */
final class TapeMerge {
	private TapeMerge() {
	}

	/**
	 * Appends the lines of {@code incoming} whose key is not already in {@code file}.
	 *
	 * <p>Duplicates inside {@code incoming} collapse too, so a caller that fetched overlapping byte
	 * ranges does not have to be careful.
	 *
	 * @return how many lines were appended
	 */
	static int merge(Path file, List<String> incoming, Function<String, String> keyOf)
			throws IOException {
		if (incoming.isEmpty()) {
			return 0;
		}

		Set<String> seen = keysIn(file, keyOf);
		int written = 0;

		Files.createDirectories(file.getParent());

		try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
				StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
			for (String line : incoming) {
				if (line.isBlank()) {
					continue;
				}

				String key = keyOf.apply(line);

				// A line we cannot key is a line we cannot deduplicate, and taking it would mean
				// taking it again on every later sync. Dropping one costs one record.
				if (key == null || !seen.add(key)) {
					continue;
				}

				writer.write(line);
				writer.newLine();
				written++;
			}
		}

		return written;
	}

	private static Set<String> keysIn(Path file, Function<String, String> keyOf) throws IOException {
		Set<String> keys = new HashSet<>();

		if (!Files.isRegularFile(file)) {
			return keys;
		}

		try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			String line;

			while ((line = reader.readLine()) != null) {
				String key = keyOf.apply(line);

				if (key != null) {
					keys.add(key);
				}
			}
		}

		return keys;
	}
}
