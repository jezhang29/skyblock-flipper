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
package jeff.skyblockflipper.core.sync;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Pulls the collector's tape down from the server and folds it into the local one.
 *
 * <p>The collector runs on a machine that never sleeps, so its tape has the hours this client was
 * closed for - hours that {@code auctions_ended} will not answer for again. Until now closing the
 * gap meant remembering to run a script before launching the game. This does the same fetch and the
 * same merge from inside the mod, so forgetting is no longer possible.
 *
 * <p>It is a pull and never a mirror. Both machines tape the same endpoints, so the local file
 * holds sales the server never saw and the server's holds the ones taped while the game was shut;
 * the merge keeps the union, keyed the way each tape's own reader keys a record. Overwriting either
 * side would throw away real history.
 *
 * <p>Incremental by byte offset. Tape files are append-only, so after the first sync each one is
 * fetched with {@code Range: bytes=<already merged>-} and only the tail crosses the wire. The
 * offsets live in {@link SyncState} beside the tape.
 *
 * <p>Minecraft-free like the rest of {@code core}: it is handed a base URL, a directory and a merge
 * function, and looks nothing up for itself.
 */
public final class TapeSync {
	private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
	private static final String SUFFIX = ".jsonl";

	/** The header the server checks. Nothing here is secret, but an open directory invites crawlers. */
	static final String TOKEN_HEADER = "X-Tape-Token";

	/**
	 * How much line data to hold before handing a batch to the merge.
	 *
	 * <p>A day file is a quarter of a gigabyte and Minecraft's heap is not large, so the download is
	 * never materialised. Each batch costs one rescan of the local file to rebuild its key set,
	 * which is why this is megabytes rather than kilobytes.
	 */
	private static final int BATCH_BYTES = 16 * 1024 * 1024;

	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
	private static final Duration INDEX_TIMEOUT = Duration.ofSeconds(30);
	/** A first sync of a full day is hundreds of megabytes over a home connection. */
	private static final Duration FETCH_TIMEOUT = Duration.ofMinutes(15);

	/** Appends lines to one tape file, returning how many were not already held. */
	@FunctionalInterface
	public interface Merger {
		int merge(String fileName, List<String> lines) throws IOException;
	}

	/**
	 * One tape to sync.
	 *
	 * @param remotePath     directory name under the base URL, e.g. {@code tape}
	 * @param localDirectory where this client keeps its copy
	 * @param retentionDays  how long the local tape keeps a day, so days that would be pruned on the
	 *                       next maintenance pass are never downloaded
	 * @param accepts        which remote names belong to this tape; everything else is ignored,
	 *                       which is what keeps a remote index from naming a path of its own choosing
	 * @param merger         the tape's own keyed merge
	 */
	public record Target(String remotePath, Path localDirectory, int retentionDays,
			Predicate<String> accepts, Merger merger) {
	}

	/** What one sync moved, for the log line and {@code /flip sync}. */
	public record Result(int filesRead, int linesMerged, long bytesFetched) {
		public Result plus(Result other) {
			return new Result(filesRead + other.filesRead, linesMerged + other.linesMerged,
					bytesFetched + other.bytesFetched);
		}

		public static Result empty() {
			return new Result(0, 0, 0L);
		}
	}

	/** One entry of the server's directory index, as nginx's {@code autoindex_format json} writes it. */
	private static final class IndexEntry {
		String name;
		String type;
		long size;
	}

	private final String baseUrl;
	private final String token;
	private final HttpClient http;
	private final Gson gson = new Gson();

	public TapeSync(String baseUrl, String token) {
		this(baseUrl, token, HttpClient.newBuilder()
				.connectTimeout(CONNECT_TIMEOUT)
				.followRedirects(HttpClient.Redirect.NORMAL)
				.build());
	}

	TapeSync(String baseUrl, String token, HttpClient http) {
		this.baseUrl = trimSlash(baseUrl);
		this.token = token == null ? "" : token.trim();
		this.http = http;
	}

	/**
	 * Fetches whatever the server has added and merges it in.
	 *
	 * <p>Files are taken oldest first, so an interrupted sync leaves the tape contiguous rather than
	 * holed. Progress is saved per file, so the next run resumes where this one stopped.
	 *
	 * @throws SyncException on anything that stopped the sync; partial progress is already on disk
	 */
	public Result pull(Target target) throws SyncException {
		SyncState state = SyncState.load(target.localDirectory(), source(target));
		LocalDate cutoff = LocalDate.now(ZoneOffset.UTC).minusDays(Math.max(1, target.retentionDays()));
		Result total = Result.empty();

		try {
			for (IndexEntry entry : index(target.remotePath())) {
				if (!wanted(entry, target, cutoff)) {
					continue;
				}

				total = total.plus(pullFile(target, state, entry));
			}
		} finally {
			// Saved even on the way out of a failure: every offset in it describes bytes already
			// merged, and dropping them would re-download work that is already on disk.
			try {
				state.save();
			} catch (IOException e) {
				throw new SyncException("could not save sync state", e);
			}
		}

		return total;
	}

	/** True for a file this tape wants and has not aged out of its retention window. */
	private static boolean wanted(IndexEntry entry, Target target, LocalDate cutoff) {
		if (entry.name == null || (entry.type != null && !entry.type.equals("file"))) {
			return false;
		}

		if (!target.accepts().test(entry.name)) {
			return false;
		}

		LocalDate day = dayNamed(entry.name);
		// The rollup has no day and never ages out; it is the whole reason retention is affordable.
		return day == null || !day.isBefore(cutoff);
	}

	private List<IndexEntry> index(String remotePath) throws SyncException {
		URI uri = URI.create(baseUrl + "/" + remotePath + "/");
		HttpResponse<InputStream> response = send(request(uri).GET().timeout(INDEX_TIMEOUT).build(), uri);

		if (response.statusCode() != 200) {
			closeQuietly(response.body());
			throw new SyncException("HTTP " + response.statusCode() + " listing " + uri);
		}

		try (InputStream body = response.body()) {
			List<IndexEntry> entries = gson.fromJson(new InputStreamReader(body, StandardCharsets.UTF_8),
					new TypeToken<List<IndexEntry>>() {
					}.getType());

			if (entries == null) {
				throw new SyncException("empty directory index at " + uri);
			}

			entries.sort((a, b) -> String.valueOf(a.name).compareTo(String.valueOf(b.name)));
			return entries;
		} catch (JsonParseException e) {
			throw new SyncException("directory index at " + uri
					+ " is not JSON; the server needs autoindex_format json", e);
		} catch (IOException e) {
			throw new SyncException("failed reading the directory index at " + uri, e);
		}
	}

	/**
	 * Downloads and merges the part of one remote file this client has not seen.
	 *
	 * <p>A remote file shorter than the offset we hold is not the file we were reading - the day was
	 * rotated, or the server's data directory was rebuilt - so it restarts from the beginning rather
	 * than resuming into the middle of unrelated bytes.
	 */
	private Result pullFile(Target target, SyncState state, IndexEntry entry) throws SyncException {
		long offset = state.offsetOf(entry.name);

		if (entry.size > 0L && entry.size < offset) {
			state.forget(entry.name);
			offset = 0L;
		}

		if (entry.size > 0L && entry.size == offset) {
			return Result.empty();
		}

		final long start = offset;
		URI uri = URI.create(baseUrl + "/" + target.remotePath() + "/" + entry.name);
		HttpRequest.Builder builder = request(uri).GET().timeout(FETCH_TIMEOUT);

		if (start > 0L) {
			builder.header("Range", "bytes=" + start + "-");
		}

		HttpResponse<InputStream> response = send(builder.build(), uri);
		int status = response.statusCode();

		if (status == 416) {
			// The server's copy ends where ours does. Nothing new, and not an error.
			closeQuietly(response.body());
			return Result.empty();
		}

		if (status != 200 && status != 206) {
			closeQuietly(response.body());
			throw new SyncException("HTTP " + status + " fetching " + uri);
		}

		// A 200 to a ranged request means the server ignored the range and sent the whole file, so
		// the bytes we already merged have to be dropped here instead.
		long skip = status == 200 ? start : 0L;
		long[] progress = {start, 0L};

		try (InputStream body = response.body()) {
			drain(body, skip, lines -> {
				int merged = target.merger().merge(entry.name, lines);
				progress[1] += merged;
			}, consumed -> progress[0] = start + consumed);
		} catch (IOException e) {
			throw new SyncException("failed merging " + entry.name + " from " + uri, e);
		} finally {
			state.record(entry.name, progress[0]);
		}

		return new Result(1, (int) progress[1], progress[0] - start);
	}

	/** Receives one batch of complete lines. */
	private interface Batch {
		void accept(List<String> lines) throws IOException;
	}

	/** Told how many bytes of complete, merged lines have been consumed so far. */
	private interface Progress {
		void at(long consumed);
	}

	/**
	 * Splits the response into batches of whole lines.
	 *
	 * <p>Byte-oriented rather than reader-oriented because the offset it reports is a byte offset
	 * into the server's file, and re-encoding each line to measure it would double the work on a
	 * quarter-gigabyte download. Splitting on {@code \n} is safe in UTF-8: no continuation byte can
	 * be 0x0A.
	 *
	 * <p>A trailing partial line is deliberately not counted. It is a record the server was still
	 * writing when the response was cut, and leaving the offset below it means the next sync fetches
	 * it whole.
	 */
	private static void drain(InputStream in, long skip, Batch sink, Progress progress)
			throws IOException {
		in.skipNBytes(skip);

		ByteArrayOutputStream line = new ByteArrayOutputStream(4096);
		List<String> batch = new ArrayList<>();
		byte[] buffer = new byte[64 * 1024];
		long consumed = 0L;
		int batchBytes = 0;
		int read;

		while ((read = in.read(buffer)) > 0) {
			for (int i = 0; i < read; i++) {
				byte b = buffer[i];

				if (b != '\n') {
					line.write(b);
					continue;
				}

				consumed += line.size() + 1L;
				batchBytes += line.size();
				batch.add(line.toString(StandardCharsets.UTF_8).stripTrailing());
				line.reset();

				if (batchBytes >= BATCH_BYTES) {
					sink.accept(batch);
					progress.at(consumed);
					batch = new ArrayList<>();
					batchBytes = 0;
				}
			}
		}

		if (!batch.isEmpty()) {
			sink.accept(batch);
		}

		progress.at(consumed);
	}

	private HttpRequest.Builder request(URI uri) {
		HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
				.header("User-Agent", "skyblock-flipper");

		if (!token.isEmpty()) {
			builder.header(TOKEN_HEADER, token);
		}

		return builder;
	}

	private HttpResponse<InputStream> send(HttpRequest request, URI uri) throws SyncException {
		try {
			return http.send(request, HttpResponse.BodyHandlers.ofInputStream());
		} catch (IOException e) {
			throw new SyncException("could not reach " + uri, e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new SyncException("interrupted fetching " + uri, e);
		}
	}

	private String source(Target target) {
		return baseUrl + "/" + target.remotePath();
	}

	private static LocalDate dayNamed(String name) {
		if (!name.endsWith(SUFFIX)) {
			return null;
		}

		try {
			return LocalDate.parse(name.substring(0, name.length() - SUFFIX.length()), DAY);
		} catch (DateTimeParseException e) {
			return null;
		}
	}

	private static String trimSlash(String url) {
		String trimmed = url == null ? "" : url.trim();

		while (trimmed.endsWith("/")) {
			trimmed = trimmed.substring(0, trimmed.length() - 1);
		}

		return trimmed;
	}

	private static void closeQuietly(InputStream stream) {
		try {
			stream.close();
		} catch (IOException ignored) {
			// Draining a body we are about to report an error about adds nothing.
		}
	}
}
