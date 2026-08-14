package jeff.skyblockflipper.core.strategy;

import jeff.skyblockflipper.core.track.CaptureSession;
import jeff.skyblockflipper.core.track.CapturedMenu;
import jeff.skyblockflipper.core.track.CapturedSlot;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Against the same trimmed capture {@code BazaarSlotsTest} uses. */
class BazaarStepTest {
	private static List<CapturedMenu> menus;

	@BeforeAll
	static void load() throws IOException {
		try (InputStream stream = BazaarStepTest.class.getResourceAsStream("/bazaar-menus.jsonl");
				Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
			menus = CaptureSession.read(reader).menus();
		}
	}

	private static CapturedMenu menu(String title) {
		return menus.stream().filter(m -> m.title().equals(title)).findFirst().orElseThrow();
	}

	/** The one orders menu in the fixture holding a row that has filled and not been collected. */
	private static CapturedMenu withAClaimableRow() {
		return menus.stream()
				.filter(m -> m.slots().stream().anyMatch(s -> s.lore().contains("Click to claim!")))
				.findFirst()
				.orElseThrow();
	}

	private static NpcWorklist.Task task(NpcWorklist.Kind kind, String itemId, String name) {
		return new NpcWorklist.Task(kind, itemId, name, 100.0d, 64L, "64", 0.0d, 0L, "");
	}

	@Test
	void sendsAPlaceToTheSearchSignWithTheNameToType() {
		Optional<BazaarStep.Step> step = BazaarStep.next(
				task(NpcWorklist.Kind.PLACE, "ENCHANTED_FEATHER", "Enchanted Feather"), 0.0d,
				menu("Bazaar ➜ Mining"));

		assertTrue(step.isPresent());
		assertEquals(45, step.get().slot());
		assertEquals(BazaarStep.Click.LEFT, step.get().click());
		assertTrue(step.get().opensASign());
		assertEquals("Enchanted Feather", step.get().type());
	}

	@Test
	void sendsAPlaceToTheItemsOwnTileOnceTheSearchHasRun() {
		Optional<BazaarStep.Step> step = BazaarStep.next(
				task(NpcWorklist.Kind.PLACE, "ENCHANTED_FEATHER", "Enchanted Feather"), 0.0d,
				menu("Bazaar ➜ \"feath\""));

		assertTrue(step.isPresent());
		assertEquals(12, step.get().slot());
		assertFalse(step.get().opensASign());
	}

	@Test
	void leftClicksAFilledRowToClaimIt() {
		// The one row in the fixture carrying "Click to claim!".
		CapturedMenu orders = withAClaimableRow();
		CapturedSlot claimable = orders.slots().stream()
				.filter(s -> s.lore().contains("Click to claim!")).findFirst().orElseThrow();
		String name = claimable.name().replaceFirst("^(BUY|SELL) ", "");

		Optional<BazaarStep.Step> step = BazaarStep.next(
				task(NpcWorklist.Kind.CLAIM, "", name), 0.0d, orders);

		assertTrue(step.isPresent());
		assertEquals(claimable.index(), step.get().slot());
		assertEquals(BazaarStep.Click.LEFT, step.get().click());
	}

	@Test
	void rightClicksTheSameRowToCancelIt() {
		CapturedMenu orders = withAClaimableRow();
		CapturedSlot claimable = orders.slots().stream()
				.filter(s -> s.lore().contains("Click to claim!")).findFirst().orElseThrow();
		String name = claimable.name().replaceFirst("^(BUY|SELL) ", "");

		Optional<BazaarStep.Step> step = BazaarStep.next(
				task(NpcWorklist.Kind.CANCEL, "", name), 0.0d, orders);

		assertTrue(step.isPresent());
		assertEquals(claimable.index(), step.get().slot());
		assertEquals(BazaarStep.Click.RIGHT, step.get().click());
	}

	@Test
	void leftClicksAnUnfilledRowToOpenItsOptions() {
		Optional<BazaarStep.Step> step = BazaarStep.next(
				task(NpcWorklist.Kind.REPRICE, "", "Optical Lens"), 84_621.2d,
				menu("Co-op Bazaar Orders"));

		assertTrue(step.isPresent());
		assertEquals(BazaarStep.Click.LEFT, step.get().click());
	}

	@Test
	void willNotPickBetweenTwoOrdersOnOneItem() {
		// Two Diamante's Handle offers rest in this menu. Without a price to tell them apart, the
		// wrong one is a cancelled order that was fine.
		assertTrue(BazaarStep.next(task(NpcWorklist.Kind.CANCEL, "", "Diamante's Handle"), 0.0d,
				menu("Co-op Bazaar Orders")).isEmpty());

		assertTrue(BazaarStep.next(task(NpcWorklist.Kind.CANCEL, "", "Diamante's Handle"),
				811_618.4d, menu("Co-op Bazaar Orders")).isPresent());
	}

	@Test
	void pointsAtCancelOnTheOptionsScreen() {
		Optional<BazaarStep.Step> step = BazaarStep.next(
				task(NpcWorklist.Kind.CANCEL, "", "Optical Lens"), 0.0d, menu("Order options"));

		assertTrue(step.isPresent());
		assertEquals(13, step.get().slot());
	}

	@Test
	void pointsAtTheConfirmButtonOnEitherConfirmScreen() {
		assertEquals(13, BazaarStep.next(task(NpcWorklist.Kind.PLACE, "", "Enchanted Feather"), 0.0d,
				menu("Confirm Buy Order")).orElseThrow().slot());
		assertEquals(13, BazaarStep.next(task(NpcWorklist.Kind.PLACE, "", "Enchanted Feather"), 0.0d,
				menu("Confirm Sell Offer")).orElseThrow().slot());
	}

	@Test
	void saysNothingAboutAScreenNobodyHasMeasured() {
		// A product page, which is titled with the item's own name. Until a capture session records
		// one, this is the state that has to stay silent rather than guess a slot.
		CapturedMenu product = new CapturedMenu(0L, "Enchanted Feather", List.of(
				new CapturedSlot(10, "Buy Instantly", List.of(), "", 1, "")));

		assertTrue(BazaarStep.next(task(NpcWorklist.Kind.PLACE, "", "Enchanted Feather"), 0.0d,
				product).isEmpty());
	}

	@Test
	void saysNothingWhenTheOpenScreenCannotServeTheRow() {
		assertTrue(BazaarStep.next(task(NpcWorklist.Kind.PLACE, "", "Enchanted Feather"), 0.0d,
				menu("Co-op Bazaar Orders")).isEmpty());
		assertTrue(BazaarStep.next(task(NpcWorklist.Kind.CANCEL, "", "Nothing Resting"), 0.0d,
				menu("Co-op Bazaar Orders")).isEmpty());
		assertTrue(BazaarStep.next(null, 0.0d, menu("Order options")).isEmpty());
	}
}
