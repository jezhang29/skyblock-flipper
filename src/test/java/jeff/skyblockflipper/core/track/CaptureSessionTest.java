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

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaptureSessionTest {
	@Test
	void readsTheSessionInTheOrderItHappened() throws IOException {
		CaptureSession session = sample();

		assertEquals(124, session.records().size());
		assertEquals(88, session.chats().size());
		assertEquals(36, session.menus().size());

		// Interleaved, not chats-then-menus. A tracker that sees the claim before the menu that
		// explains it reads the same file differently.
		List<CaptureRecord> records = session.records();
		assertInstanceOf(CapturedMenu.class, records.getFirst());
		assertTrue(records.stream().anyMatch(CapturedChat.class::isInstance));

		for (int i = 1; i < records.size(); i++) {
			assertTrue(records.get(i).at() >= records.get(i - 1).at());
		}
	}

	@Test
	void skipsALineItCannotRead() throws IOException {
		// A capture file ends wherever the session was killed, so the last line can be half a
		// record. Losing the session around it would be the wrong trade.
		String text = """
				{"at":1,"text":"[Bazaar] Claiming order...","type":"chat"}
				{"at":2,"title":"Co-op Bazaar Orders","slots":[],"type":"menu"}
				{"at":3,"text":"truncated
				""";

		CaptureSession session = CaptureSession.read(new StringReader(text));

		assertEquals(2, session.records().size());
		assertEquals(1, session.chats().size());
		assertEquals(1, session.menus().size());
	}

	private static CaptureSession sample() throws IOException {
		try (InputStream in = CaptureSessionTest.class.getResourceAsStream("/trade-capture-sample.jsonl")) {
			return CaptureSession.read(new InputStreamReader(in, StandardCharsets.UTF_8));
		}
	}
}
