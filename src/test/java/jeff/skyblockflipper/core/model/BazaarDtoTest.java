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
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

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
	private static MarketObservation observation;

	@BeforeAll
	static void parseFixture() throws Exception {
		try (InputStream in = BazaarDtoTest.class.getResourceAsStream("/bazaar-sample.json")) {
			BazaarDto dto = new Gson().fromJson(
					new InputStreamReader(in, StandardCharsets.UTF_8), BazaarDto.class);
			observation = dto.toObservation(Instant.parse("2026-09-07T12:00:00Z"));
			snapshot = observation.snapshot();
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
	void preservesEveryReturnedLevelAndItsCoverage() {
		BazaarProduct diamond = snapshot.product("ENCHANTED_DIAMOND").orElseThrow();

		assertEquals(List.of(1308.4d, 1308.5d, 1308.7d),
				diamond.sellOffers().stream().map(OrderLevel::pricePerUnit).toList());
		assertEquals(List.of(1270.8d, 1270.7d, 1270.6d),
				diamond.buyOrders().stream().map(OrderLevel::pricePerUnit).toList());
		assertEquals(3, diamond.sellOfferCoverage().returnedLevels());
		assertEquals(4L, diamond.sellOfferCoverage().returnedOrders());
		assertEquals(DepthCoverage.State.RETURNED_LEVELS,
				diamond.sellOfferCoverage().state());
	}

	@Test
	void distinguishesAbsentAndReturnedEmptySides() {
		BazaarDto dto = new BazaarDto();
		dto.lastUpdated = 1_000L;
		BazaarDto.ProductDto product = new BazaarDto.ProductDto();
		product.productId = "ONE_SIDED";
		product.asks = null;
		product.bids = List.of();
		dto.products = Map.of("ONE_SIDED", product);

		BazaarProduct mapped = dto.toObservation(Instant.EPOCH)
				.snapshot().product("ONE_SIDED").orElseThrow();

		assertEquals(DepthCoverage.State.FIELD_ABSENT, mapped.sellOfferCoverage().state());
		assertEquals(DepthCoverage.State.RETURNED_EMPTY, mapped.buyOrderCoverage().state());
		assertEquals(ActivityProxy.State.UNAVAILABLE, mapped.activityProxy().state());
		assertTrue(mapped.activityProxy().weeklyAverageInstantBuyActivityPerHour().isEmpty());
	}

	@Test
	void preservesEveryRawQuickStatusFieldWithoutCallingItPersonalExecution() {
		BazaarQuickStatus quick = snapshot.product("ENCHANTED_DIAMOND").orElseThrow().quickStatus();

		assertEquals(BazaarQuickStatus.State.RETURNED, quick.state());
		assertEquals("ENCHANTED_DIAMOND", quick.productId());
		assertEquals(1270.7491068700167d, quick.sellPrice());
		assertEquals(2_227_868L, quick.sellVolume());
		assertEquals(25_113_690L, quick.sellMovingWeek());
		assertEquals(55, quick.sellOrders());
		assertEquals(1326.48539789141d, quick.buyPrice());
		assertEquals(974_378L, quick.buyVolume());
		assertEquals(5_905_860L, quick.buyMovingWeek());
		assertEquals(135, quick.buyOrders());
	}

	@Test
	void observationIsSerializableWithTheFullBook() throws Exception {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
			out.writeObject(observation);
		}

		MarketObservation recovered;
		try (ObjectInputStream in = new ObjectInputStream(
				new ByteArrayInputStream(bytes.toByteArray()))) {
			recovered = (MarketObservation) in.readObject();
		}

		assertEquals(observation, recovered);
		assertEquals(3, recovered.products().get("ENCHANTED_DIAMOND").sellOffers().size());
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
