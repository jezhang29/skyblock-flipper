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
package jeff.skyblockflipper.core.api;

import com.google.gson.Gson;

import jeff.skyblockflipper.core.item.DecodedItem;
import jeff.skyblockflipper.core.item.ItemDecoder;
import jeff.skyblockflipper.core.model.TimedAuctionSample;
import jeff.skyblockflipper.core.model.TimedListing;
import jeff.skyblockflipper.core.model.dto.EndedAuctionsDto;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The collector's two filters and the fields it carries into a {@link TimedAuctionSample}: only
 * ending-soon listings are decoded and kept, an undecodable one is counted not taped, and the far-
 * future ones never reach the decoder at all.
 */
class TimedAuctionCollectorTest {
	private static final long HOUR = Duration.ofHours(1).toMillis();
	private static final Instant NOW = Instant.ofEpochMilli(1_700_000_000_000L);

	private static DecodedItem sampleItem() throws Exception {
		try (InputStream in = TimedAuctionCollectorTest.class.getResourceAsStream("/item-bytes-sample.json")) {
			String blob = new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8),
					EndedAuctionsDto.class).auctions.getFirst().itemBytes();
			return ItemDecoder.decode(blob).orElseThrow();
		}
	}

	@Test
	void keepsEndingSoonDecodable_dropsFarFuture_countsUndecodable() throws Exception {
		DecodedItem item = sampleItem();
		int[] decodeCalls = {0};
		Function<String, Optional<DecodedItem>> decoder = blob -> {
			decodeCalls[0]++;
			return blob.equals("bad") ? Optional.empty() : Optional.of(item);
		};

		TimedAuctionCollector collector = new TimedAuctionCollector(NOW, Duration.ofHours(3), decoder);

		// Ending in an hour, decodes: taped.
		collector.offer(new TimedListing("soon", 5_000_000L, 0L, NOW.toEpochMilli() + HOUR, "good"));
		// Ending in ten hours: dropped before the decoder is ever called.
		collector.offer(new TimedListing("far", 1L, 0L, NOW.toEpochMilli() + 10L * HOUR, "good"));
		// Ending soon but the blob will not decode: counted, not taped.
		collector.offer(new TimedListing("undecodable", 1L, 0L, NOW.toEpochMilli() + HOUR, "bad"));

		assertEquals(3, collector.seen());
		assertEquals(2, collector.withinWindow(), "soon + undecodable are in the window; far is not");
		assertEquals(1, collector.decodeFailures());
		assertEquals(1, collector.samples().size());
		assertEquals(2, decodeCalls[0], "the far-future listing is never decoded");
	}

	@Test
	void carriesEveryFieldIntoTheSample() throws Exception {
		DecodedItem item = sampleItem();
		TimedAuctionCollector collector = new TimedAuctionCollector(NOW, Duration.ofHours(3),
				blob -> Optional.of(item));

		long end = NOW.toEpochMilli() + 30L * 60_000L;
		collector.offer(new TimedListing("auc-1", 4_000_000L, 6_000_000L, end, "blob"));

		TimedAuctionSample sample = collector.samples().getFirst();
		assertEquals("auc-1", sample.uuid());
		assertEquals(item.signature(), sample.signature());
		assertEquals(Math.max(1, item.count()), sample.count());
		assertEquals(end, sample.end());
		assertEquals(4_000_000L, sample.startingBid());
		assertEquals(6_000_000L, sample.highestBidAmount());
		assertEquals(NOW.toEpochMilli(), sample.sampledAt());
		assertEquals(true, sample.contested(), "6M top bid over a 4M start is contested");
	}

	@Test
	void aListingAtItsScheduledEndIsStillWithinTheWindow() throws Exception {
		DecodedItem item = sampleItem();
		TimedAuctionCollector collector = new TimedAuctionCollector(NOW, Duration.ofHours(3),
				blob -> Optional.of(item));

		// end == now: a stale view of an auction about to close. end - now = 0 <= window, so kept.
		collector.offer(new TimedListing("closing", 1_000L, 0L, NOW.toEpochMilli(), "blob"));
		assertEquals(1, collector.samples().size());
	}

	@Test
	void aThrowingDecoderCountsAsAFailureRatherThanPropagating() throws Exception {
		TimedAuctionCollector collector = new TimedAuctionCollector(NOW, Duration.ofHours(3),
				blob -> {
					throw new IllegalStateException("boom");
				});

		collector.offer(new TimedListing("soon", 1L, 0L, NOW.toEpochMilli() + HOUR, "blob"));
		assertEquals(1, collector.decodeFailures());
		assertEquals(0, collector.samples().size());
	}
}
