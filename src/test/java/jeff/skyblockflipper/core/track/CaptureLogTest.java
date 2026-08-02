package jeff.skyblockflipper.core.track;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaptureLogTest {
	@TempDir
	Path dir;

	@Test
	void writesOneTaggedJsonObjectPerRecord() throws IOException {
		CaptureLog log = new CaptureLog(dir.resolve("nested/capture.jsonl"));

		log.append(new CapturedChat(1_000L, "[Bazaar] Your Buy Order was filled!"));
		log.append(new CapturedMenu(2_000L, "Your Bazaar Orders", List.of(
				new CapturedSlot(11, "Enchanted Melon", List.of("Filled: 64/64"),
						"ENCHANTED_MELON_BLOCK", 1, "{ExtraAttributes:{id:\"ENCHANTED_MELON_BLOCK\"}}"))));

		List<String> lines = Files.readAllLines(log.file());
		assertEquals(2, lines.size());

		Gson gson = new Gson();
		JsonObject chat = gson.fromJson(lines.get(0), JsonObject.class);
		assertEquals("chat", chat.get("type").getAsString());
		assertEquals(1_000L, chat.get("at").getAsLong());

		JsonObject menu = gson.fromJson(lines.get(1), JsonObject.class);
		assertEquals("menu", menu.get("type").getAsString());
		assertEquals("Your Bazaar Orders", menu.get("title").getAsString());
		// The item id is the whole reason menus are captured as well as chat.
		assertEquals("ENCHANTED_MELON_BLOCK",
				menu.getAsJsonArray("slots").get(0).getAsJsonObject().get("itemId").getAsString());
	}

	@Test
	void appendsToWhatIsAlreadyThere() throws IOException {
		Path file = dir.resolve("capture.jsonl");
		new CaptureLog(file).append(new CapturedChat(1L, "first"));
		new CaptureLog(file).append(new CapturedChat(2L, "second"));

		// A session that starts after a crash must not overwrite the session that crashed.
		assertEquals(2, Files.readAllLines(file).size());
	}

	@Test
	void stopsAtTheSizeCapRatherThanFillingTheDisk() throws IOException {
		CaptureLog log = new CaptureLog(dir.resolve("capture.jsonl"), 200L);

		for (int i = 0; i < 50; i++) {
			log.append(new CapturedChat(i, "a line long enough to matter against a 200 byte cap"));
		}

		assertTrue(log.isFull());
		assertTrue(log.bytes() <= 200L, "wrote past the cap: " + log.bytes());
		assertEquals(log.records(), Files.readAllLines(log.file()).size());
	}

	@Test
	void reportsAnEmptyFileRatherThanFailingOnOne() {
		CaptureLog log = new CaptureLog(dir.resolve("never-written.jsonl"));

		assertEquals(0L, log.bytes());
		assertEquals(0, log.records());
		assertFalse(log.isFull());
	}
}
