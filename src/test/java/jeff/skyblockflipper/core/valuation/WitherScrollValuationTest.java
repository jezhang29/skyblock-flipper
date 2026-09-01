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
package jeff.skyblockflipper.core.valuation;

import jeff.skyblockflipper.core.item.DecodedItem;
import jeff.skyblockflipper.core.item.ItemDecoder;
import jeff.skyblockflipper.core.item.Rarity;
import jeff.skyblockflipper.core.item.WitherScrollNbtFixtures;
import jeff.skyblockflipper.core.model.ActiveListing;
import jeff.skyblockflipper.core.model.BazaarSnapshot;
import jeff.skyblockflipper.core.model.ItemCatalog;
import jeff.skyblockflipper.core.pricing.Fees;
import jeff.skyblockflipper.core.strategy.AuctionValueStrategy;
import jeff.skyblockflipper.core.strategy.StrategyContext;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static jeff.skyblockflipper.core.item.WitherScrollNbtFixtures.IMPLOSION;
import static jeff.skyblockflipper.core.item.WitherScrollNbtFixtures.SHADOW_WARP;
import static jeff.skyblockflipper.core.item.WitherScrollNbtFixtures.WITHER_SHIELD;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WitherScrollValuationTest {
	private static final Instant NOW = Instant.parse("2026-09-01T12:00:00Z");
	private static final Duration WINDOW = Duration.ofDays(2);

	private static DecodedItem unscrolled() {
		return ItemDecoder.fromRoot(WitherScrollNbtFixtures.unscrolledHyperion()).orElseThrow();
	}

	private static DecodedItem fullyScrolled() {
		return ItemDecoder.fromRoot(WitherScrollNbtFixtures.hyperionWith(
				List.of(IMPLOSION, SHADOW_WARP, WITHER_SHIELD))).orElseThrow();
	}

	private static FairValueModel model(DecodedItem first, int firstSamples, double firstPrice,
			DecodedItem second, int secondSamples, double secondPrice) {
		FairValueModel.Builder builder = FairValueModel.builder(NOW, WINDOW);
		for (int i = 0; i < firstSamples; i++) {
			builder.add(first, firstPrice + i, NOW.minusSeconds(i + 1L).toEpochMilli());
		}
		for (int i = 0; i < secondSamples; i++) {
			builder.add(second, secondPrice + i,
					NOW.minusSeconds(firstSamples + i + 1L).toEpochMilli());
		}
		return builder.build();
	}

	@Test
	void everyWitherBladeFamilyNamesItsEmptyScrollStateAndRejectsCoarseFallback() {
		for (String id : List.of("HYPERION", "ASTRAEA", "SCYLLA", "VALKYRIE", "NECRON_BLADE")) {
			DecodedItem blade = ItemDecoder.fromRoot(WitherScrollNbtFixtures.root(
					id, id, false, null)).orElseThrow();

			assertTrue(blade.signature().contains("abilityScrolls=none"), id);
			assertFalse(Keying.PRODUCTION.isBare(blade), id);
		}
	}

	@Test
	void unscrolledBladeCannotUseACoarsePoolBuiltFromScrolledSales() {
		DecodedItem full = fullyScrolled();
		DecodedItem none = unscrolled();
		FairValueModel model = model(full, 6, 1_064_000_000.0d, none, 0, 0.0d);

		assertEquals(1_064_000_003.0d, model.valueOf(full).orElseThrow().median());
		assertTrue(model.roughValueOf("Hyperion", Rarity.LEGENDARY).isPresent());
		assertTrue(model.valueOf(none).isEmpty());
	}

	@Test
	void fewerThanSixExactComparablesProduceNoValuationEvenWithACoarsePool() {
		DecodedItem full = fullyScrolled();
		DecodedItem none = unscrolled();
		FairValueModel model = model(full, 6, 1_064_000_000.0d, none, 5, 510_000_000.0d);

		assertTrue(model.roughValueOf("Hyperion", Rarity.LEGENDARY).isPresent());
		assertTrue(model.valueOf(none).isEmpty());
	}

	@Test
	void incidentShapeIsSafeEndToEndThroughDecodeModelScanAndStrategy() {
		DecodedItem full = fullyScrolled();
		DecodedItem none = unscrolled();
		FairValueModel model = model(full, 8, 1_064_000_000.0d, none, 0, 0.0d);
		UnderpricedScan scan = new UnderpricedScan(model, 0.15d, 2_000_000_000L);
		ActiveListing listing = new ActiveListing("unscrolled-auction", "Hyperion",
				Rarity.LEGENDARY, 510_000_000L, "fixture supplied below");

		// The coarse pool sees a half-price listing, then ItemDecoder reveals the exact no-scroll key.
		scan.offerDecoded(listing,
				() -> ItemDecoder.fromRoot(WitherScrollNbtFixtures.unscrolledHyperion()));

		assertEquals(1, scan.decoded());
		assertTrue(model.valueOf(none).isEmpty());
		assertTrue(scan.results().isEmpty());

		StrategyContext context = new StrategyContext(BazaarSnapshot.empty(), ItemCatalog.empty(),
				scan.results(), Fees.none(), 2_000_000_000L, 0L, 0.0d);
		assertTrue(new AuctionValueStrategy().findCandidates(context).isEmpty());
	}

	@Test
	void temporaryContainmentBlocksExactOrdinaryValuesAtBothBoundaries() {
		DecodedItem none = unscrolled();
		FairValueModel model = model(none, 8, 510_000_000.0d, fullyScrolled(), 0, 0.0d);
		ValueEstimate exact = model.valueOf(none).orElseThrow();
		ActiveListing listing = new ActiveListing("contained", "Hyperion", Rarity.LEGENDARY,
				300_000_000L, "fixture supplied below");
		UnderpricedScan scan = new UnderpricedScan(model, 0.15d, 2_000_000_000L);

		scan.offerDecoded(listing,
				() -> ItemDecoder.fromRoot(WitherScrollNbtFixtures.unscrolledHyperion()));
		assertTrue(scan.results().isEmpty());

		PricedListing injected = new PricedListing(listing.withoutBlob(), none, exact);
		StrategyContext context = new StrategyContext(BazaarSnapshot.empty(), ItemCatalog.empty(),
				List.of(injected), Fees.none(), 2_000_000_000L, 0L, 0.0d);
		assertTrue(new AuctionValueStrategy().findCandidates(context).isEmpty());
	}
}
