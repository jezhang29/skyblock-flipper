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
package jeff.skyblockflipper.core.model;

import com.google.gson.Gson;

import jeff.skyblockflipper.core.model.dto.BazaarDto;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the single most expensive mistake this codebase can make: swapping the two bazaar sides.
 *
 * <p>Hypixel's {@code buy_summary} is the ask side and {@code sell_summary} is the bid side. If
 * that mapping is ever inverted, every spread in the mod flips sign while still looking like a
 * reasonable number, so nothing crashes and the losses just accumulate quietly.
 *
 * <p>The fixture is a trimmed capture of a real response, so it also fails if Hypixel renames or
 * reorders these fields.
 */
class BazaarDtoTest {
	private static BazaarSnapshot snapshot;

	@BeforeAll
	static void parseFixture() throws Exception {
		try (InputStream in = BazaarDtoTest.class.getResourceAsStream("/bazaar-sample.json")) {
			BazaarDto dto = new Gson().fromJson(
					new InputStreamReader(in, StandardCharsets.UTF_8), BazaarDto.class);
			snapshot = dto.toSnapshot();
		}
	}

	@Test
	void parsesEveryProductInTheFixture() {
		assertEquals(3, snapshot.products().size());
		assertTrue(snapshot.product("ENCHANTED_DIAMOND").isPresent());
	}

	@Test
	void buySummaryBecomesSellOffersAndSellSummaryBecomesBuyOrders() {
		BazaarProduct diamond = snapshot.product("ENCHANTED_DIAMOND").orElseThrow();

		// buy_summary[0] in the fixture is 1308.4 -- the cheapest ask, what you pay to instant-buy.
		assertEquals(1308.4d, diamond.instantBuyPrice().getAsDouble(), 0.05d);

		// sell_summary[0] is 1270.8 -- the highest bid, what you receive to instant-sell.
		assertEquals(1270.8d, diamond.instantSellPrice().getAsDouble(), 0.05d);
	}

	@Test
	void askAlwaysExceedsBid() {
		// The structural invariant. Inverted sides make this fail for every product at once.
		for (BazaarProduct product : snapshot.products().values()) {
			double ask = product.instantBuyPrice().orElseThrow();
			double bid = product.instantSellPrice().orElseThrow();

			assertTrue(ask > bid,
					product.productId() + ": ask " + ask + " must exceed bid " + bid);
		}
	}

	@Test
	void topOfBookIgnoresTheDepthWeightedQuickStatusPrice() {
		BazaarProduct diamond = snapshot.product("ENCHANTED_DIAMOND").orElseThrow();

		// quick_status.buyPrice is 1326.49 in the fixture, well above the true best ask of 1308.4.
		// Pricing off it would overstate instant-buy cost by ~1.4% on every single flip.
		assertTrue(diamond.instantBuyPrice().getAsDouble() < 1320.0d);
	}

	@Test
	void marketMakingSpreadSitsInsideTheInstantSpread() {
		BazaarProduct diamond = snapshot.product("ENCHANTED_DIAMOND").orElseThrow();

		double instantSpread = diamond.instantBuyPrice().getAsDouble()
				- diamond.instantSellPrice().getAsDouble();

		// Posting orders means undercutting the ask and outbidding the bid, so the captured
		// spread is strictly narrower than crossing the book both ways.
		assertTrue(diamond.grossMarketMakingSpread().getAsDouble() < instantSpread);
		assertTrue(diamond.grossMarketMakingSpread().getAsDouble() > 0.0d);
	}

	@Test
	void bottleneckVolumeTakesTheThinnerSide() {
		BazaarProduct diamond = snapshot.product("ENCHANTED_DIAMOND").orElseThrow();

		assertEquals(
				Math.min(diamond.movingWeek().instantBought(), diamond.movingWeek().instantSold()),
				diamond.bottleneckWeeklyVolume());
	}
}
