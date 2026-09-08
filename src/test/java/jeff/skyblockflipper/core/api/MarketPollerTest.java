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

import jeff.skyblockflipper.core.config.ScanSettings;
import jeff.skyblockflipper.core.model.BazaarProduct;
import jeff.skyblockflipper.core.model.MarketObservation;
import jeff.skyblockflipper.core.model.OrderLevel;
import jeff.skyblockflipper.core.tape.BazaarTape;
import jeff.skyblockflipper.core.tape.SalesTape;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MarketPollerTest {
	@Test
	void pollPublishesTheCompleteObservationAndNoOpFetchHealth(@TempDir Path directory)
			throws Exception {
		Instant source = Instant.parse("2026-09-01T00:00:00Z");
		MarketObservation first = observation(source, Instant.parse("2026-09-07T12:00:00Z"));
		MarketObservation second = observation(source, Instant.parse("2026-09-07T12:00:20Z"));
		StubApi api = new StubApi(first, second);
		MarketData data = new MarketData();
		MarketPoller poller = poller(api, data, directory);

		poller.pollBazaar();
		poller.pollBazaar();

		assertEquals(second, data.bazaarObservation().orElseThrow());
		assertEquals(2L, data.bazaarFetchRevision());
		assertEquals(1L, data.bazaarSourceRevision());
		assertEquals(1L, data.bazaarContentRevision());
	}

	@Test
	void failedPollRecordsFetchFailureWithoutDiscardingTheBook(@TempDir Path directory)
			throws Exception {
		MarketObservation good = observation(Instant.parse("2026-09-01T00:00:00Z"),
				Instant.parse("2026-09-07T12:00:00Z"));
		StubApi api = new StubApi(good);
		MarketData data = new MarketData();
		MarketPoller poller = poller(api, data, directory);
		poller.pollBazaar();
		api.fail = new ApiException("rate limited", true);

		ApiException thrown = assertThrows(ApiException.class, poller::pollBazaar);

		assertEquals("rate limited", thrown.getMessage());
		assertEquals(BazaarFetch.Status.FAILED, data.bazaarFetch().status());
		assertEquals(true, data.bazaarFetch().rateLimited());
		assertEquals(good.contentId(), data.bazaarContentId().orElseThrow());
		assertEquals(2L, data.bazaarFetchRevision());
		assertEquals(1L, data.bazaarContentRevision());
	}

	private static MarketPoller poller(HypixelApi api, MarketData data, Path directory) {
		ScanSettings settings = new ScanSettings(false, 2, 0.1d, 1_000_000L,
				false, 14, 24, 20);
		return new MarketPoller(api, data,
				new SalesTape(directory.resolve("sales"), 14),
				new BazaarTape(directory.resolve("bazaar"), 14),
				() -> settings, ignored -> {
				});
	}

	private static MarketObservation observation(Instant source, Instant retrieved) {
		BazaarProduct product = new BazaarProduct("TEST_ITEM",
				List.of(new OrderLevel(100.0d, 10L, 1)),
				List.of(new OrderLevel(90.0d, 10L, 1)),
				new BazaarProduct.MovingWeek(1_000L, 900L));
		return MarketObservation.bazaar(source, retrieved, Map.of("TEST_ITEM", product));
	}

	private static final class StubApi extends HypixelApi {
		private final List<MarketObservation> observations;
		private int index;
		private ApiException fail;

		private StubApi(MarketObservation... observations) {
			this.observations = List.of(observations);
		}

		@Override
		public MarketObservation fetchBazaarObservation() throws ApiException {
			if (fail != null) {
				throw fail;
			}
			return observations.get(index++);
		}
	}
}
