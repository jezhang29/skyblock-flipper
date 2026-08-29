package jeff.skyblockflipper.core.item;

import com.google.gson.Gson;
import jeff.skyblockflipper.core.model.EndedAuction;
import jeff.skyblockflipper.core.model.dto.EndedAuctionsDto;
import jeff.skyblockflipper.core.nbt.NbtCompound;
import jeff.skyblockflipper.core.recovery.RecoveryAttachment;
import jeff.skyblockflipper.core.recovery.RecoveryComponentKind;
import jeff.skyblockflipper.core.recovery.RecoveryWarning;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecoveryItemDecoderTest {
	private static List<EndedAuction> capturedSales;

	@BeforeAll
	static void loadCapturedFixture() throws Exception {
		try (InputStream in = RecoveryItemDecoderTest.class
				.getResourceAsStream("/item-bytes-sample.json")) {
			capturedSales = new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8),
					EndedAuctionsDto.class).auctions;
		}
	}

	@Test
	void detailedDecodeLeavesEveryCapturedOrdinarySignatureUnchanged() {
		for (EndedAuction sale : capturedSales) {
			DecodedItem ordinary = ItemDecoder.decode(sale.itemBytes()).orElseThrow();
			DetailedDecodedItem detailed = ItemDecoder.decodeDetailed(sale.itemBytes()).orElseThrow();

			assertEquals(ordinary, detailed.item());
			assertEquals(ordinary.signature(), detailed.item().signature());
		}
	}

	@Test
	void readsLegacyAndModernGemstoneFormsAndIgnoresEmptySlots() {
		DetailedDecodedItem decoded = decode(extra(Map.of(
				"id", "DIVAN_HELMET",
				"gems", compound(Map.of(
						"unlocked_slots", List.of("JADE_0", "MINING_0"),
						"JADE_0", "FLAWLESS",
						"MINING_0", compound(Map.of("quality", "PERFECT", "uuid", "captured")),
						"MINING_0_gem", "TOPAZ")))));

		assertEquals(List.of("FLAWLESS_JADE_GEM", "PERFECT_TOPAZ_GEM"), decoded.recovery()
				.attachments().stream().map(RecoveryAttachment::stableComponentId).toList());
		assertFalse(decoded.recovery().warnings().contains(RecoveryWarning.MALFORMED_METADATA));
	}

	@Test
	void readsCapturedDrillEngineTankAndGoblinOmeletteFields() {
		DetailedDecodedItem decoded = decode(extra(Map.of(
				"id", "DIVAN_DRILL",
				"drill_part_engine", "amber_polished_drill_engine",
				"engine", compound(Map.of("id", "AMBER_POLISHED_DRILL_ENGINE")),
				"drill_part_fuel_tank", "perfectly_cut_fuel_tank",
				"fuel_tank", compound(Map.of("id", "PERFECTLY_CUT_FUEL_TANK")),
				"drill_part_upgrade_module", "goblin_omelette_blue_cheese",
				"upgrade_module", compound(Map.of("id", "GOBLIN_OMELETTE_BLUE_CHEESE")))));

		assertEquals(List.of(
				RecoveryComponentKind.DRILL_ENGINE,
				RecoveryComponentKind.DRILL_FUEL_TANK,
				RecoveryComponentKind.GOBLIN_OMELETTE),
				decoded.recovery().attachments().stream().map(RecoveryAttachment::kind).toList());
	}

	@Test
	void readsCapturedRodHookLineAndSinkerCompounds() {
		DetailedDecodedItem decoded = decode(extra(Map.of(
				"id", "ROD_OF_THE_SEA",
				"hook", compound(Map.of("part", "hotspot_hook", "uuid", "captured-hook")),
				"line", compound(Map.of("part", "speedy_line", "uuid", "captured-line")),
				"sinker", compound(Map.of("part", "stingy_sinker", "uuid", "captured-sinker")))));

		assertEquals(List.of("HOTSPOT_HOOK", "SPEEDY_LINE", "STINGY_SINKER"), decoded.recovery()
				.attachments().stream().map(RecoveryAttachment::stableComponentId).toList());
	}

	@Test
	void malformedRecoveryDataWarnsWithoutCostingTheOrdinaryDecode() {
		DetailedDecodedItem decoded = decode(extra(Map.of(
				"id", "MITHRIL_DRILL_1",
				"gems", "not a compound",
				"hook", compound(Map.of("uuid", "missing-part")),
				"drill_part_engine", "mithril_drill_engine",
				"engine", compound(Map.of("id", "TITANIUM_DRILL_ENGINE")))));

		assertEquals("MITHRIL_DRILL_1", decoded.item().skyblockId());
		assertTrue(decoded.recovery().attachments().isEmpty());
		assertTrue(decoded.recovery().warnings().contains(RecoveryWarning.MALFORMED_METADATA));
	}

	@Test
	void legacyAttributesRequirePreviewAndCarryNoInventedOutput() {
		DetailedDecodedItem decoded = decode(extra(Map.of(
				"id", "INFERNO_ROD",
				"attributes", compound(Map.of("double_hook", 3, "fishing_speed", 1)))));

		assertEquals(Map.of("double_hook", 3, "fishing_speed", 1),
				decoded.recovery().legacyAttributes());
		assertTrue(decoded.recovery().previewRequired());
		assertTrue(decoded.recovery().attachments().isEmpty());
	}

	private static DetailedDecodedItem decode(NbtCompound extra) {
		return ItemDecoder.detailedFromRoot(new NbtCompound(Map.of("i", List.of(new NbtCompound(
				Map.of("Count", 1, "tag", new NbtCompound(Map.of("ExtraAttributes", extra))))))))
				.orElseThrow();
	}

	private static NbtCompound extra(Map<String, Object> values) {
		return new NbtCompound(values);
	}

	private static NbtCompound compound(Map<String, Object> values) {
		return new NbtCompound(values);
	}
}
