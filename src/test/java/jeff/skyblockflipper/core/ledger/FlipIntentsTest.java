package jeff.skyblockflipper.core.ledger;

import jeff.skyblockflipper.core.strategy.StrategyKind;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The register that remembers whose flip a buy order is, so the NPC side leaves another strategy's
 * order alone.
 */
class FlipIntentsTest {
	private static final long NOW = 1_754_000_000_000L;

	private static FlipIntents intents(Path dir) {
		return new FlipIntents(dir.resolve("flip-intents.json"), () -> Duration.ofDays(3));
	}

	@Test
	void foreignIsEverythingExceptTheNpcStrategy(@TempDir Path dir) throws IOException {
		FlipIntents intents = intents(dir);

		intents.record("ENCHANTED_LAPIS_LAZULI", StrategyKind.COMBINE, NOW);
		intents.record("ENCHANTED_STRING", StrategyKind.CRAFT, NOW);
		intents.record("GOLD_INGOT", StrategyKind.BAZAAR_SPREAD, NOW);
		intents.record("SULPHUR_ORE", StrategyKind.NPC_FLIP, NOW);

		Set<String> foreign = intents.foreignItems(NOW);

		assertEquals(Set.of("ENCHANTED_LAPIS_LAZULI", "ENCHANTED_STRING", "GOLD_INGOT"), foreign);
		assertFalse(foreign.contains("SULPHUR_ORE"), "an NPC flip is not another strategy's order");
	}

	@Test
	void lastWriteWinsSoAnNpcBasketReleasesAnOldCraftIntent(@TempDir Path dir) throws IOException {
		FlipIntents intents = intents(dir);

		intents.record("ENCHANTED_STRING", StrategyKind.CRAFT, NOW);
		assertTrue(intents.foreignItems(NOW).contains("ENCHANTED_STRING"));

		// The item is now in an NPC basket, so the fresh NPC_FLIP overwrites the craft intent.
		intents.record("ENCHANTED_STRING", StrategyKind.NPC_FLIP, NOW + 1_000L);
		assertFalse(intents.foreignItems(NOW + 1_000L).contains("ENCHANTED_STRING"));
	}

	@Test
	void anIntentExpiresOnceItsWindowIsPast(@TempDir Path dir) throws IOException {
		FlipIntents intents = intents(dir);

		intents.record("ENCHANTED_STRING", StrategyKind.CRAFT, NOW);

		long withinWindow = NOW + Duration.ofDays(2).toMillis();
		long pastWindow = NOW + Duration.ofDays(3).toMillis() + 1L;

		assertTrue(intents.foreignItems(withinWindow).contains("ENCHANTED_STRING"));
		assertTrue(intents.foreignItems(pastWindow).isEmpty(), "past the window it is released");
		assertEquals(StrategyKind.CRAFT, intents.kindFor("ENCHANTED_STRING", withinWindow).orElseThrow());
		assertTrue(intents.kindFor("ENCHANTED_STRING", pastWindow).isEmpty());
	}

	@Test
	void survivesAReloadFromDisk(@TempDir Path dir) throws IOException {
		FlipIntents written = intents(dir);
		written.record("ENCHANTED_LAPIS_LAZULI", StrategyKind.COMBINE, NOW);
		written.record("SULPHUR_ORE", StrategyKind.NPC_FLIP, NOW);

		// A combine source order left resting overnight has to be recognised the next session too.
		FlipIntents reloaded = intents(dir);
		reloaded.load();

		assertEquals(2, reloaded.size());
		assertEquals(StrategyKind.COMBINE,
				reloaded.kindFor("ENCHANTED_LAPIS_LAZULI", NOW).orElseThrow());
		assertEquals(Set.of("ENCHANTED_LAPIS_LAZULI"), reloaded.foreignItems(NOW));
	}

	@Test
	void loadingAMissingFileIsAnEmptyRegisterNotAFailure(@TempDir Path dir) throws IOException {
		FlipIntents intents = intents(dir);
		intents.load();

		assertEquals(0, intents.size());
		assertTrue(intents.foreignItems(NOW).isEmpty());
	}
}
