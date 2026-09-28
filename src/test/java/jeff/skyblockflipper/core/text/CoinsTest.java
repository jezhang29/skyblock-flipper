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
package jeff.skyblockflipper.core.text;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CoinsTest {
	@Test
	void abbreviatesAtEachThreshold() {
		assertEquals("900", Coins.format(900L));
		assertEquals("1.0k", Coins.format(1_000L));
		assertEquals("1.00M", Coins.format(1_000_000L));
		assertEquals("1.00B", Coins.format(1_000_000_000L));
	}

	@Test
	void keepsTheSignOnLosses() {
		// A ledger entry that went the wrong way must not read as a gain.
		assertEquals("-1.50M", Coins.format(-1_500_000L));
	}

	@Test
	void roundsRatherThanTruncatingDoubles() {
		assertEquals("1.0k", Coins.format(999.6d));
	}
}
