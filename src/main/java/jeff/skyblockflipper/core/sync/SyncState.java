package jeff.skyblockflipper.core.sync;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * How much of each remote tape file has already been merged, in bytes.
 *
 * <p>This is what makes the sync incremental. Tape files are append-only on both sides, so bytes
 * below the recorded offset are bytes this client has already seen and deduplicated; only the range
 * above it has to cross the network. Without it every sync would re-download whole day files to
 * discover that it already held all of them.
 *
 * <p>An offset only means something relative to one server's copy of a file, so the source URL is
 * stored alongside and a change to it discards every offset. Resuming another machine's byte count
 * against a different file would skip whatever the new server wrote below that mark, and nothing
 * downstream would ever notice the hole.
 *
 * <p>Written next to the tape it describes. Losing it costs bandwidth on the next sync and nothing
 * else - the merge is keyed, so re-reading bytes already merged appends nothing.
 */
final class SyncState {
	private static final String FILE = "sync-state.json";
	private static final Gson GSON = new Gson();

	/** Gson's view of the file. Mutable fields for the same reason {@code FlipperConfig} uses them. */
	private static final class Stored {
		String source;
		Map<String, Long> offsets;
	}

	private final Path file;
	private final String source;
	private final Map<String, Long> offsets = new TreeMap<>();

	private SyncState(Path file, String source, Map<String, Long> offsets) {
		this.file = file;
		this.source = source;
		this.offsets.putAll(offsets);
	}

	static SyncState load(Path directory, String source) {
		Path path = directory.resolve(FILE);

		if (!Files.isRegularFile(path)) {
			return new SyncState(path, source, Map.of());
		}

		try {
			Stored stored = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), Stored.class);

			if (stored == null || stored.offsets == null || !source.equals(stored.source)) {
				return new SyncState(path, source, Map.of());
			}

			return new SyncState(path, source, stored.offsets);
		} catch (IOException | JsonSyntaxException e) {
			// An unreadable state file is a slow sync, not a broken one.
			return new SyncState(path, source, Map.of());
		}
	}

	long offsetOf(String fileName) {
		return offsets.getOrDefault(fileName, 0L);
	}

	void record(String fileName, long offset) {
		offsets.put(fileName, offset);
	}

	/** Forgets a file, so the next sync reads it from the start. */
	void forget(String fileName) {
		offsets.remove(fileName);
	}

	void save() throws IOException {
		Stored stored = new Stored();
		stored.source = source;
		stored.offsets = new HashMap<>(offsets);

		Files.createDirectories(file.getParent());
		Files.writeString(file, GSON.toJson(stored), StandardCharsets.UTF_8);
	}
}
