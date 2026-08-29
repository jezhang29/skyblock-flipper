package jeff.skyblockflipper.core.recovery;

import jeff.skyblockflipper.core.item.DecodedItem;
import jeff.skyblockflipper.core.item.DetailedDecodedItem;
import jeff.skyblockflipper.core.item.Rarity;
import jeff.skyblockflipper.core.model.ActiveListing;
import jeff.skyblockflipper.core.model.BazaarProduct;
import jeff.skyblockflipper.core.model.BazaarSnapshot;
import jeff.skyblockflipper.core.model.OrderLevel;
import jeff.skyblockflipper.core.pricing.Fees;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecoveryListingScanTest {
	private static final Instant NOW = Instant.parse("2026-08-28T12:00:00Z");

	@Test
	void composesCleanHostAndGemstoneFromRealizedSalesAndBidDepth() {
		DecodedItem clean = item("DIVAN_HELMET", "Divan's Helmet", List.of());
		RecoveryValueModel.Builder builder = new RecoveryValueModel.Builder(Duration.ofDays(2));
		for (int i = 0; i < 8; i++) {
			builder.add(new DetailedDecodedItem(clean, RecoveryMetadata.EMPTY), 1_000_000.0d + i);
		}
		RecoveryAttachment gem = new RecoveryAttachment(RecoveryComponentKind.GEMSTONE,
				"COMBAT_0", "FINE_RUBY_GEM", 3L);
		DetailedDecodedItem attached = new DetailedDecodedItem(
				item("DIVAN_HELMET", "Divan's Helmet", List.of("RUBY=FINE")),
				new RecoveryMetadata(List.of(gem), Map.of(), Set.of()));
		BazaarProduct rubies = new BazaarProduct("FINE_RUBY_GEM", List.of(),
				List.of(new OrderLevel(100_000.0d, 3L, 2)),
				new BazaarProduct.MovingWeek(1L, 1_680L));
		RecoveryListingScan scan = new RecoveryListingScan(builder.build(),
				new BazaarSnapshot(NOW, Map.of("FINE_RUBY_GEM", rubies)), Fees.none(),
				RecoveryScanPolicy.conservativeDefaults(), NOW);
		ActiveListing listing = new ActiveListing("auction", "Divan's Helmet", Rarity.LEGENDARY,
				500_000L, "unused");

		assertTrue(scan.mightUse(listing));
		scan.offerDecoded(listing, attached);

		RecoveryOpportunity opportunity = scan.results().getFirst();
		assertEquals("auction", opportunity.auctionUuid());
		assertEquals(1, opportunity.componentQuotes().size());
		assertTrue(opportunity.cleanHostQuote().credited());
		assertTrue(opportunity.componentQuotes().getFirst().credited());
		assertTrue(opportunity.expectedProfit() > 0L);
	}

	@Test
	void legacyOutputAndUnknownRemovalCostStayVisibleAtZero() {
		DecodedItem clean = item("DIVAN_DRILL", "Divan's Drill", List.of());
		RecoveryValueModel.Builder builder = new RecoveryValueModel.Builder(Duration.ofDays(2));
		for (int i = 0; i < 8; i++) {
			builder.add(new DetailedDecodedItem(clean, RecoveryMetadata.EMPTY), 5_000_000.0d);
		}
		RecoveryMetadata metadata = new RecoveryMetadata(List.of(new RecoveryAttachment(
				RecoveryComponentKind.DRILL_ENGINE, "engine", "MITHRIL_DRILL_ENGINE", 1L)),
				Map.of("mana_pool", 3), Set.of(RecoveryWarning.PREVIEW_REQUIRED));
		RecoveryListingScan scan = new RecoveryListingScan(builder.build(), BazaarSnapshot.empty(),
				Fees.none(), RecoveryScanPolicy.conservativeDefaults(), NOW);
		scan.offerDecoded(new ActiveListing("drill-auction", "Divan's Drill", Rarity.LEGENDARY,
				1_000_000L, "unused"), new DetailedDecodedItem(clean, metadata));

		RecoveryOpportunity opportunity = scan.results().getFirst();
		assertEquals(2, opportunity.componentQuotes().size());
		assertFalse(opportunity.componentQuotes().get(0).credited());
		assertTrue(opportunity.warnings().contains(RecoveryWarning.UNKNOWN_REMOVAL_COST));
		assertTrue(opportunity.warnings().contains(RecoveryWarning.PREVIEW_REQUIRED));
	}

	private static DecodedItem item(String id, String name, List<String> gems) {
		return new DecodedItem(id, name, 1, Rarity.LEGENDARY, "", 0, false, 0, Map.of(),
				gems, Map.of(), Map.of(), null, null, null, "", false, 0L);
	}
}
