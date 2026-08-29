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

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ItemNameIndexTest {
	private static final ItemNameIndex INDEX = new ItemNameIndex();

	@BeforeAll
	static void learnTheSession() throws IOException {
		try (InputStream in = ItemNameIndexTest.class.getResourceAsStream("/trade-capture-sample.jsonl")) {
			CaptureSession.read(new InputStreamReader(in, StandardCharsets.UTF_8)).menus()
					.forEach(INDEX::learn);
		}
	}

	@Test
	void resolvesNamesThatCollideByPrefix() {
		// The exact failure the ids exist to prevent, in this session's own items.
		assertEquals("SLIME_BALL", INDEX.idFor("Slimeball"));
		assertEquals("ENCHANTED_SLIME_BALL", INDEX.idFor("Enchanted Slimeball"));
	}

	@Test
	void readsAnOrderRowUnderTheNameChatUses() {
		// The menu row is "SELL Slimeball" and the chat line is "Slimeball".
		assertEquals("SLIME_BALL", INDEX.idFor("SELL Slimeball"));
		assertEquals("ENCHANTED_ENDSTONE", INDEX.idFor("BUY Enchanted End Stone"));
	}

	@Test
	void refusesANameTwoItemsHaveClaimed() {
		// The auction creation menu labels its item slot "AUCTION FOR ITEM:" whatever is in it, so
		// in this session that one name arrived carrying PET and then RABBIT_HAT. Picking either
		// puts a wrong item in the ledger, which is worse than putting none there.
		assertEquals("", INDEX.idFor("AUCTION FOR ITEM:"));
	}

	@Test
	void saysNothingAboutWhatItHasNotSeen() {
		assertEquals("", INDEX.idFor("Oak Log"));
		assertEquals("", INDEX.idFor(null));
		assertEquals("", INDEX.idFor(""));
	}

	@Test
	void ignoresSlotsWithNoCustomData() {
		// Enchantment-book orders carry no id at all, and the furniture carries none either.
		assertEquals("", INDEX.idFor("Ultimate Wise I"));
		assertEquals("", INDEX.idFor("Go Back"));
	}
}
