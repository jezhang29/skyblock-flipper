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
package jeff.skyblockflipper.core.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlipperConfigTest {
	@Test
	void writesDefaultsWhenNoFileExists(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("nested").resolve("config.json");

		FlipperConfig config = FlipperConfig.load(file);

		assertTrue(Files.exists(file), "first load should seed the file");
		assertEquals(0, config.bazaarFlipperLevel);
		assertTrue(config.pollingEnabled);
	}

	@Test
	void keepsDefaultsForKeysAbsentFromTheFile(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("config.json");

		// The reason FlipperConfig is a mutable class rather than a record: a config written by
		// an older version of the mod must keep working when new settings are added.
		Files.writeString(file, "{\"bankroll\": 250000000}");

		FlipperConfig config = FlipperConfig.load(file);

		assertEquals(250_000_000L, config.bankroll);
		assertEquals(0.6d, config.minConfidence, 1e-9);
		assertTrue(config.hudEnabled);
		assertFalse(config.recoveryAlertsEnabled);
		assertFalse(config.recoveryChatNotifications);
		assertFalse(config.recoveryToastNotifications);
		assertFalse(config.recoveryAlertSound);
		assertEquals(0.15d, config.recoverySafetyBuffer, 1e-9);
	}

	@Test
	void clampsHandEditedValuesThatWouldBreakDownstreamMath(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("config.json");
		Files.writeString(file, """
				{
				  "bankroll": -5,
				  "bazaarFlipperLevel": 99,
				  "minConfidence": 4.2,
				  "minProfitPerFlip": -1000
				}
				""");

		FlipperConfig config = FlipperConfig.load(file);

		// Level 99 would drive the bazaar tax negative and invent profit out of nothing. The perk
		// stops at 2, which is also what the derived bazaar order limit reads.
		assertEquals(2, config.bazaarFlipperLevel);
		assertEquals(0L, config.bankroll);
		assertEquals(1.0d, config.minConfidence, 1e-9);
		assertEquals(0L, config.minProfitPerFlip);
	}

	@Test
	void fallsBackToATopLeftHudRatherThanNullingOut(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("config.json");
		// hudAnchor is a string precisely so a typo cannot become a null enum that NPEs on
		// every frame; the HUD is drawn far from where the mistake was made.
		Files.writeString(file, "{\"hudAnchor\": \"middle-ish\", \"hudLines\": 40}");

		FlipperConfig config = FlipperConfig.load(file);

		assertEquals(HudAnchor.TOP_LEFT, config.anchor());
		assertEquals(10, config.hudLines);
	}

	@Test
	void acceptsAnchorNamesInAnyCase(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("config.json");
		Files.writeString(file, "{\"hudAnchor\": \"bottom_right\"}");

		assertEquals(HudAnchor.BOTTOM_RIGHT, FlipperConfig.load(file).anchor());
	}

	@Test
	void toleratesAnEmptyFile(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("config.json");
		Files.writeString(file, "");

		assertEquals(0, FlipperConfig.load(file).bazaarFlipperLevel);
	}

	@Test
	void survivesSaveAndReload(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("config.json");

		FlipperConfig original = new FlipperConfig();
		original.bazaarFlipperLevel = 2;
		original.bankroll = 1_234_567L;
		original.save(file);

		FlipperConfig reloaded = FlipperConfig.load(file);

		assertEquals(2, reloaded.bazaarFlipperLevel);
		assertEquals(1_234_567L, reloaded.bankroll);
	}
}
