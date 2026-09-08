/*
 * Skyblock Flipper - a Hypixel Skyblock flipping advisor mod.
 * Copyright (C) 2026 SoupChugger
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package jeff.skyblockflipper.core.api;

import jeff.skyblockflipper.core.model.BazaarProduct;
import jeff.skyblockflipper.core.model.MarketObservation;
import jeff.skyblockflipper.core.model.OrderLevel;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MarketDataTest {
	private static final Instant OLD_SOURCE = Instant.parse("2026-09-01T00:00:00Z");

	@Test
	void unchangedSuccessfulFetchAdvancesHealthButNotContentRevision() {
		MarketData data = new MarketData();
		MarketObservation first = observation(OLD_SOURCE,
				Instant.parse("2026-09-07T12:00:00Z"), 100.0d);
		MarketObservation second = observation(OLD_SOURCE,
				Instant.parse("2026-09-07T12:00:20Z"), 100.0d);

		data.publishBazaar(first);
		data.publishBazaar(second);

		assertEquals(2L, data.bazaarFetchRevision());
		assertEquals(1L, data.bazaarSourceRevision());
		assertEquals(1L, data.bazaarContentRevision());
		assertEquals(1L, data.bazaarRevision());
		assertEquals(BazaarFetch.Status.SUCCESS, data.bazaarFetch().status());
		assertEquals(second.retrievedAt(), data.bazaarFetch().attemptedAt());
		assertEquals(Duration.ofSeconds(10),
				data.bazaarFetchAge(Instant.parse("2026-09-07T12:00:30Z")));
		assertEquals(Duration.ofDays(6).plusHours(12).plusSeconds(30),
				data.bazaarSourceAge(Instant.parse("2026-09-07T12:00:30Z")));
		assertEquals(Duration.ofSeconds(30),
				data.bazaarContentAge(Instant.parse("2026-09-07T12:00:30Z")));
	}

	@Test
	void contentCanChangeWhileSourceTimestampStaysEqual() {
		MarketData data = new MarketData();
		MarketObservation first = observation(OLD_SOURCE,
				Instant.parse("2026-09-07T12:00:00Z"), 100.0d);
		MarketObservation changed = observation(OLD_SOURCE,
				Instant.parse("2026-09-07T12:00:20Z"), 101.0d);

		data.publishBazaar(first);
		data.publishBazaar(changed);

		assertNotEquals(first.contentId(), changed.contentId());
		assertEquals(2L, data.bazaarFetchRevision());
		assertEquals(1L, data.bazaarSourceRevision());
		assertEquals(2L, data.bazaarContentRevision());
		assertEquals(101.0d, data.bazaar().product("TEST_ITEM").orElseThrow()
				.instantBuyPrice().orElseThrow());
	}

	@Test
	void failedFetchPreservesLastBookAndHasDistinctHealth() {
		MarketData data = new MarketData();
		MarketObservation good = observation(OLD_SOURCE,
				Instant.parse("2026-09-07T12:00:00Z"), 100.0d);
		data.publishBazaar(good);
		var before = data.bazaar();

		data.recordBazaarFailure(Instant.parse("2026-09-07T12:00:20Z"), "HTTP 500", false);

		assertSame(before, data.bazaar());
		assertEquals(BazaarFetch.Status.FAILED, data.bazaarFetch().status());
		assertEquals("HTTP 500", data.bazaarFetch().error());
		assertEquals(2L, data.bazaarFetchRevision());
		assertEquals(1L, data.bazaarSourceRevision());
		assertEquals(1L, data.bazaarContentRevision());
		assertEquals(Duration.ofSeconds(10),
				data.bazaarFetchAge(Instant.parse("2026-09-07T12:00:30Z")));
	}

	@Test
	void canonicalContentIdentityDoesNotDependOnMapIterationOrderOrClocks() {
		BazaarProduct first = product("FIRST", 100.0d);
		BazaarProduct second = product("SECOND", 200.0d);
		Map<String, BazaarProduct> ordered = new java.util.LinkedHashMap<>();
		ordered.put("FIRST", first);
		ordered.put("SECOND", second);
		Map<String, BazaarProduct> reversed = new java.util.LinkedHashMap<>();
		reversed.put("SECOND", second);
		reversed.put("FIRST", first);
		MarketObservation one = MarketObservation.bazaar(OLD_SOURCE, Instant.EPOCH, ordered);
		MarketObservation two = MarketObservation.bazaar(OLD_SOURCE.plusSeconds(60),
				Instant.parse("2026-09-07T12:00:00Z"), reversed);

		assertEquals(one.contentId(), two.contentId());
	}

	@Test
	void observationRejectsAContentIdentityFromADifferentBook() {
		MarketObservation original = observation(OLD_SOURCE, Instant.EPOCH, 100.0d);
		BazaarProduct changed = product("TEST_ITEM", 101.0d);

		assertThrows(IllegalArgumentException.class, () -> new MarketObservation(
				MarketObservation.SCHEMA_VERSION, MarketObservation.BAZAAR_ENDPOINT,
				OLD_SOURCE, Instant.EPOCH, original.contentId(), Map.of("TEST_ITEM", changed)));
	}

	private static MarketObservation observation(Instant source, Instant retrieved, double ask) {
		BazaarProduct product = product("TEST_ITEM", ask);
		return MarketObservation.bazaar(source, retrieved, Map.of(product.productId(), product));
	}

	private static BazaarProduct product(String id, double ask) {
		return new BazaarProduct(id,
				List.of(new OrderLevel(ask, 10L, 1), new OrderLevel(ask + 1.0d, 20L, 2)),
				List.of(new OrderLevel(90.0d, 30L, 3)),
				new BazaarProduct.MovingWeek(1_000L, 900L));
	}
}
