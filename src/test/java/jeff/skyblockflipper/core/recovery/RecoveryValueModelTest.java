package jeff.skyblockflipper.core.recovery;

import jeff.skyblockflipper.core.item.DecodedItem;
import jeff.skyblockflipper.core.item.DetailedDecodedItem;
import jeff.skyblockflipper.core.item.Rarity;
import jeff.skyblockflipper.core.valuation.ValueEstimate;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecoveryValueModelTest {
	private static DecodedItem item(String id, int stars, List<String> gems) {
		return new DecodedItem(id, id, 1, Rarity.LEGENDARY, "", stars, false, 0,
				Map.of(), gems, Map.of(), Map.of(), null, null, null, "", false, 0L);
	}

	private static RecoveryMetadata metadata(RecoveryAttachment... attachments) {
		return new RecoveryMetadata(List.of(attachments), Map.of(), Set.of());
	}

	private static void add(RecoveryValueModel.Builder builder, DetailedDecodedItem item,
			double price, int times) {
		for (int i = 0; i < times; i++) {
			builder.add(item, price + i);
		}
	}

	@Test
	void cleanHostMaskExcludesPartedSalesEvenWhenProductionSignaturesPoolThem() {
		DecodedItem drill = item("DIVAN_DRILL", 0, List.of());
		DetailedDecodedItem clean = new DetailedDecodedItem(drill, RecoveryMetadata.EMPTY);
		DetailedDecodedItem parted = new DetailedDecodedItem(drill, metadata(new RecoveryAttachment(
				RecoveryComponentKind.DRILL_ENGINE, "drill_part_engine",
				"AMBER_POLISHED_DRILL_ENGINE", 1L)));
		RecoveryValueModel.Builder builder = new RecoveryValueModel.Builder(Duration.ofDays(2));
		add(builder, clean, 100.0d, 6);
		add(builder, parted, 10_000.0d, 20);

		ValueEstimate estimate = builder.build().cleanHostValue(parted).orElseThrow();

		assertEquals(6, estimate.samples());
		assertEquals(103.0d, estimate.median());
	}

	@Test
	void strippingGemstonesAndAttributesPreservesOtherValueTerms() {
		DecodedItem cleanFiveStar = item("DIVAN_HELMET", 5, List.of());
		DecodedItem gemmedFiveStar = item("DIVAN_HELMET", 5, List.of("JADE=PERFECT"));
		DecodedItem gemmedPlain = item("DIVAN_HELMET", 0, List.of("JADE=PERFECT"));
		RecoveryAttachment gem = new RecoveryAttachment(RecoveryComponentKind.GEMSTONE,
				"JADE_0", "PERFECT_JADE_GEM", 1L);
		RecoveryValueModel.Builder builder = new RecoveryValueModel.Builder(Duration.ofDays(2));
		add(builder, new DetailedDecodedItem(cleanFiveStar, RecoveryMetadata.EMPTY), 2_000.0d, 6);

		RecoveryValueModel model = builder.build();

		assertTrue(model.cleanHostValue(new DetailedDecodedItem(gemmedPlain, metadata(gem))).isEmpty());
		assertEquals(2_003.0d, model.cleanHostValue(
				new DetailedDecodedItem(gemmedFiveStar, metadata(gem))).orElseThrow().median());
	}

	@Test
	void onlyBareStandaloneSalesTrainAhComponents() {
		DecodedItem bare = item("AMBER_POLISHED_DRILL_ENGINE", 0, List.of());
		DecodedItem upgraded = new DecodedItem(bare.skyblockId(), bare.displayName(), 1, bare.rarity(),
				"", 1, false, 0, Map.of(), List.of(), Map.of(), Map.of(), null, null,
				null, "", false, 0L);
		RecoveryValueModel.Builder builder = new RecoveryValueModel.Builder(Duration.ofDays(2));
		add(builder, new DetailedDecodedItem(bare, RecoveryMetadata.EMPTY), 1_000.0d, 6);
		add(builder, new DetailedDecodedItem(upgraded, RecoveryMetadata.EMPTY), 99_000.0d, 20);

		ValueEstimate estimate = builder.build()
				.bareComponentValue("AMBER_POLISHED_DRILL_ENGINE").orElseThrow();

		assertEquals(6, estimate.samples());
		assertEquals(1_003.0d, estimate.median());
		assertEquals(0.125d, estimate.salesPerHour(), 0.0001d);
		assertEquals(3.0d, estimate.salesPerHour() * 24.0d, 0.0001d);
	}

	@Test
	void insufficientSamplesAndMalformedMetadataFailClosed() {
		DecodedItem host = item("DIVAN_DRILL", 0, List.of());
		RecoveryValueModel.Builder builder = new RecoveryValueModel.Builder(Duration.ofDays(2));
		add(builder, new DetailedDecodedItem(host, RecoveryMetadata.EMPTY), 1_000.0d, 5);
		RecoveryMetadata malformed = new RecoveryMetadata(List.of(), Map.of(),
				Set.of(RecoveryWarning.MALFORMED_METADATA));

		RecoveryValueModel model = builder.build();

		assertTrue(model.cleanHostValue(new DetailedDecodedItem(host, RecoveryMetadata.EMPTY)).isEmpty());
		assertTrue(model.cleanHostValue(new DetailedDecodedItem(host, malformed)).isEmpty());
		assertTrue(model.bareComponentValue("UNKNOWN").isEmpty());
	}

	@Test
	void onePartedSaleMakesTheFamilyWorthDecodingWithoutInventingAValue() {
		DecodedItem host = item("DIVAN_HELMET", 0, List.of("RUBY=FINE"));
		RecoveryValueModel.Builder builder = new RecoveryValueModel.Builder(Duration.ofDays(2));
		builder.add(new DetailedDecodedItem(host, metadata(new RecoveryAttachment(
				RecoveryComponentKind.GEMSTONE, "COMBAT_0", "FINE_RUBY_GEM", 1L))), 1_000.0d);

		RecoveryValueModel model = builder.build();

		assertTrue(model.mightHaveRecovery(host.displayName(), host.rarity()));
		assertTrue(model.cleanHostValue(new DetailedDecodedItem(host, metadata(
				new RecoveryAttachment(RecoveryComponentKind.GEMSTONE, "COMBAT_0",
						"FINE_RUBY_GEM", 1L)))).isEmpty());
	}
}
