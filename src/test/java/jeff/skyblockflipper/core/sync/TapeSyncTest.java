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

import jeff.skyblockflipper.core.model.EndedAuction;
import jeff.skyblockflipper.core.tape.SalesTape;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sync exists to recover hours of sales history that cannot be recovered any other way, so its
 * failure modes are the expensive kind: a merge that duplicates biases every median computed
 * afterwards, and one that silently skips bytes leaves a hole nothing downstream can detect.
 */
class TapeSyncTest {
	private static final Gson GSON = new Gson();

	@TempDir
	Path root;

	private Path serverTape;
	private Path localTape;

	private static String today() {
		return LocalDate.now(ZoneOffset.UTC) + ".jsonl";
	}

	private void setUp() throws IOException {
		serverTape = root.resolve("server/tape");
		localTape = root.resolve("local/tape");
		Files.createDirectories(serverTape);
		Files.createDirectories(localTape);
	}

	private static String sale(String id, long price) {
		return GSON.toJson(new EndedAuction(id, "seller", "buyer",
				System.currentTimeMillis(), price, true, "blob"));
	}

	private void serverHas(String fileName, String... ids) throws IOException {
		StringBuilder body = new StringBuilder();

		for (String id : ids) {
			body.append(sale(id, 100L)).append('\n');
		}

		Files.writeString(serverTape.resolve(fileName), body.toString(), StandardCharsets.UTF_8,
				StandardOpenOption.CREATE, StandardOpenOption.APPEND);
	}

	private void localHas(String fileName, String... ids) throws IOException {
		StringBuilder body = new StringBuilder();

		for (String id : ids) {
			body.append(sale(id, 100L)).append('\n');
		}

		Files.writeString(localTape.resolve(fileName), body.toString(), StandardCharsets.UTF_8,
				StandardOpenOption.CREATE, StandardOpenOption.APPEND);
	}

	private List<String> localIds(String fileName) throws IOException {
		List<String> ids = new ArrayList<>();

		for (String line : Files.readAllLines(localTape.resolve(fileName), StandardCharsets.UTF_8)) {
			if (!line.isBlank()) {
				ids.add(GSON.fromJson(line, EndedAuction.class).auctionId());
			}
		}

		return ids;
	}

	private TapeSync.Target target() {
		return new TapeSync.Target("tape", localTape, 30, SalesTape::isTapeFile,
				new SalesTape(localTape, 30)::merge);
	}

	@Test
	void firstSyncDownloadsEveryFileInTheWindow() throws Exception {
		setUp();
		serverHas(today(), "a", "b", "c");

		try (FakeTapeServer server = new FakeTapeServer(root.resolve("server"), "")) {
			TapeSync.Result result = new TapeSync(server.baseUrl(), "").pull(target());

			assertEquals(3, result.linesMerged());
			assertEquals(1, result.filesRead());
			assertEquals(List.of("a", "b", "c"), localIds(today()));
		}
	}

	@Test
	void secondSyncFetchesOnlyWhatTheServerAppended() throws Exception {
		setUp();
		serverHas(today(), "a", "b");

		try (FakeTapeServer server = new FakeTapeServer(root.resolve("server"), "")) {
			TapeSync sync = new TapeSync(server.baseUrl(), "");
			long firstBytes = sync.pull(target()).bytesFetched();

			serverHas(today(), "c");
			TapeSync.Result second = sync.pull(target());

			assertEquals(1, second.linesMerged());
			assertEquals(List.of("a", "b", "c"), localIds(today()));
			// The offset in the request is the whole point: the second pass asked for the tail,
			// and moved one record's worth of bytes rather than all three.
			assertEquals(List.of("bytes=" + firstBytes + "-"), server.ranges);
			assertTrue(second.bytesFetched() < firstBytes,
					"expected an incremental fetch, got " + second.bytesFetched()
							+ " bytes against a first pass of " + firstBytes);
		}
	}

	@Test
	void anUnchangedServerFileIsNotRefetched() throws Exception {
		setUp();
		serverHas(today(), "a", "b");

		try (FakeTapeServer server = new FakeTapeServer(root.resolve("server"), "")) {
			TapeSync sync = new TapeSync(server.baseUrl(), "");
			sync.pull(target());
			TapeSync.Result second = sync.pull(target());

			assertEquals(0, second.filesRead());
			assertEquals(0, second.bytesFetched());
			assertTrue(server.ranges.isEmpty(), "a file at its known size should not be requested");
		}
	}

	@Test
	void salesThisClientAlreadyTapedAreNotDoubled() throws Exception {
		setUp();
		// The overlap the merge exists for: both machines were up, and both recorded b and c.
		localHas(today(), "b", "c", "d");
		serverHas(today(), "a", "b", "c");

		try (FakeTapeServer server = new FakeTapeServer(root.resolve("server"), "")) {
			TapeSync.Result result = new TapeSync(server.baseUrl(), "").pull(target());

			assertEquals(1, result.linesMerged());
			assertEquals(List.of("b", "c", "d", "a"), localIds(today()));
		}
	}

	@Test
	void aServerFileShorterThanOurOffsetIsReadFromTheStart() throws Exception {
		setUp();
		serverHas(today(), "a", "b", "c");

		try (FakeTapeServer server = new FakeTapeServer(root.resolve("server"), "")) {
			TapeSync sync = new TapeSync(server.baseUrl(), "");
			sync.pull(target());

			// The server's data directory was rebuilt: same name, different and shorter contents.
			Files.delete(serverTape.resolve(today()));
			serverHas(today(), "x");

			TapeSync.Result result = sync.pull(target());

			assertEquals(1, result.linesMerged());
			assertEquals(List.of("a", "b", "c", "x"), localIds(today()));
		}
	}

	@Test
	void aHalfWrittenTrailingLineWaitsForTheNextSync() throws Exception {
		setUp();
		serverHas(today(), "a");
		// The collector was mid-append when the response was generated.
		Files.writeString(serverTape.resolve(today()), "{\"auction_id\":\"b\",\"pri",
				StandardCharsets.UTF_8, StandardOpenOption.APPEND);

		try (FakeTapeServer server = new FakeTapeServer(root.resolve("server"), "")) {
			TapeSync sync = new TapeSync(server.baseUrl(), "");

			assertEquals(1, sync.pull(target()).linesMerged());
			assertEquals(List.of("a"), localIds(today()));

			// Now the line is complete, and the offset left below it means it is read whole.
			Files.writeString(serverTape.resolve(today()), "ce\":5}\n", StandardCharsets.UTF_8,
					StandardOpenOption.APPEND);

			assertEquals(1, sync.pull(target()).linesMerged());
			assertEquals(List.of("a", "b"), localIds(today()));
		}
	}

	@Test
	void aServerThatIgnoresRangeStillMergesOnlyTheNewLines() throws Exception {
		setUp();
		serverHas(today(), "a", "b");

		try (FakeTapeServer server = new FakeTapeServer(root.resolve("server"), "")) {
			TapeSync sync = new TapeSync(server.baseUrl(), "");
			sync.pull(target());

			server.ignoreRange = true;
			serverHas(today(), "c");

			assertEquals(1, sync.pull(target()).linesMerged());
			assertEquals(List.of("a", "b", "c"), localIds(today()));
		}
	}

	@Test
	void daysOlderThanLocalRetentionAreNeverDownloaded() throws Exception {
		setUp();
		String stale = LocalDate.now(ZoneOffset.UTC).minusDays(9) + ".jsonl";
		serverHas(stale, "old");
		serverHas(today(), "new");

		try (FakeTapeServer server = new FakeTapeServer(root.resolve("server"), "")) {
			TapeSync.Target target = new TapeSync.Target("tape", localTape, 3,
					SalesTape::isTapeFile, new SalesTape(localTape, 3)::merge);

			assertEquals(1, new TapeSync(server.baseUrl(), "").pull(target).filesRead());
			assertFalse(Files.exists(localTape.resolve(stale)),
					"a day the local prune would delete on its next pass should not be fetched");
		}
	}

	@Test
	void theRollupIsPulledEvenThoughItHasNoDay() throws Exception {
		setUp();
		Files.writeString(serverTape.resolve("daily.jsonl"),
				"{\"s\":\"SIG\",\"d\":\"2026-01-01\",\"m\":5,\"lo\":4,\"hi\":6,\"n\":2}\n",
				StandardCharsets.UTF_8);

		try (FakeTapeServer server = new FakeTapeServer(root.resolve("server"), "")) {
			assertEquals(1, new TapeSync(server.baseUrl(), "").pull(target()).linesMerged());
			assertTrue(Files.exists(localTape.resolve("daily.jsonl")));
		}
	}

	@Test
	void aRollupDayAlreadyHeldIsNotAppendedTwice() throws Exception {
		setUp();
		String line = "{\"s\":\"SIG\",\"d\":\"2026-01-01\",\"m\":5,\"lo\":4,\"hi\":6,\"n\":2}\n";
		Files.writeString(serverTape.resolve("daily.jsonl"), line, StandardCharsets.UTF_8);
		Files.writeString(localTape.resolve("daily.jsonl"), line, StandardCharsets.UTF_8);

		try (FakeTapeServer server = new FakeTapeServer(root.resolve("server"), "")) {
			assertEquals(0, new TapeSync(server.baseUrl(), "").pull(target()).linesMerged());
			assertEquals(1, Files.readAllLines(localTape.resolve("daily.jsonl")).size());
		}
	}

	@Test
	void anIndexEntryNamingAPathIsIgnored() throws Exception {
		setUp();
		// Not written through the fake server's own directory listing, which cannot produce this;
		// the test is that the client refuses the name regardless of who sent it.
		Set<String> offered = new HashSet<>();
		TapeSync.Target target = new TapeSync.Target("tape", localTape, 30,
				name -> {
					offered.add(name);
					return SalesTape.isTapeFile(name);
				},
				new SalesTape(localTape, 30)::merge);

		serverHas(today(), "a");
		Files.writeString(serverTape.resolve("notes.txt"), "hello\n", StandardCharsets.UTF_8);

		try (FakeTapeServer server = new FakeTapeServer(root.resolve("server"), "")) {
			assertEquals(1, new TapeSync(server.baseUrl(), "").pull(target).filesRead());
		}

		assertTrue(offered.contains("notes.txt"), "the index entry should have been considered");
		assertFalse(Files.exists(localTape.resolve("notes.txt")));
		assertFalse(SalesTape.isTapeFile("../../evil.jsonl"));
		assertFalse(SalesTape.isTapeFile("2026-08-09.jsonl.bak"));
	}

	@Test
	void aRejectedTokenFailsTheSyncRatherThanTheTape() throws Exception {
		setUp();
		serverHas(today(), "a");

		try (FakeTapeServer server = new FakeTapeServer(root.resolve("server"), "expected")) {
			TapeSync sync = new TapeSync(server.baseUrl(), "wrong");

			SyncException thrown = assertThrows(SyncException.class, () -> sync.pull(target()));
			assertTrue(thrown.getMessage().contains("403"), thrown.getMessage());
			assertFalse(Files.exists(localTape.resolve(today())));

			assertEquals(1, new TapeSync(server.baseUrl(), "expected").pull(target()).linesMerged());
		}
	}

	@Test
	void offsetsAreDiscardedWhenTheServerChanges() throws Exception {
		setUp();
		serverHas(today(), "a", "b");

		try (FakeTapeServer first = new FakeTapeServer(root.resolve("server"), "")) {
			new TapeSync(first.baseUrl(), "").pull(target());
		}

		// A different collector, whose byte offsets have nothing to do with the last one's.
		try (FakeTapeServer second = new FakeTapeServer(root.resolve("server"), "")) {
			TapeSync.Result result = new TapeSync(second.baseUrl(), "").pull(target());

			// Everything is re-read, and the merge is what keeps the tape from doubling.
			assertEquals(0, result.linesMerged());
			assertEquals(List.of("a", "b"), localIds(today()));
			assertTrue(second.ranges.isEmpty(), "a new source starts from the beginning");
		}
	}
}
