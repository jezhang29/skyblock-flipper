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

	/**
	 * The product page as photographed on 2026-08-13: titled with the sub-category it was reached
	 * through and then the item, which is why it is recognised by its buttons instead.
	 */
	private static CapturedMenu productPage() {
		return new CapturedMenu(0L, "Item Upgrades ➜ Transmission Tuner", List.of(
				new CapturedSlot(10, "Buy Instantly", List.of(), "", 1, ""),
				new CapturedSlot(11, "Create Buy Order", List.of(), "", 1, ""),
				new CapturedSlot(13, "Sell Instantly", List.of(), "", 1, ""),
				new CapturedSlot(15, "Create Sell Offer", List.of(), "", 1, ""),
				new CapturedSlot(31, "Close", List.of(), "", 1, "")));
	}

	/** The amount page, with the tooltip that was photographed with it. */
	private static CapturedMenu amountPage() {
		return new CapturedMenu(0L, "How many do you want?", List.of(
				new CapturedSlot(10, "1x", List.of(), "", 1, ""),
				new CapturedSlot(12, "16x", List.of(), "", 1, ""),
				new CapturedSlot(14, "32x", List.of(), "", 32, ""),
				new CapturedSlot(16, "Custom Amount",
						List.of("Buy Order Quantity", "", "Buy up to 256x.", "", "Click to specify!"),
						"", 1, ""),
				new CapturedSlot(31, "Close", List.of(), "", 1, "")));
	}

	@Test
	void pointsAtCreateBuyOrderOnTheProductPage() {
		// Never Buy Instantly: that pays the ask, which is the price the whole plan exists to avoid.
		Optional<BazaarStep.Step> step = BazaarStep.next(
				task(NpcWorklist.Kind.PLACE, "TRANSMISSION_TUNER", "Transmission Tuner"), 0.0d,
				productPage());

		assertTrue(step.isPresent());
		assertEquals(11, step.get().slot());
	}

	@Test
	void pointsAtTheAmountSignAndSaysWhatToTypeOnIt() {
		NpcWorklist.Task task = new NpcWorklist.Task(NpcWorklist.Kind.PLACE, "TRANSMISSION_TUNER",
				"Transmission Tuner", 100.0d, 880L, "3 x 256 + 112", 0.0d, 0L, "");

		Optional<BazaarStep.Step> step = BazaarStep.next(task, 0.0d, amountPage());

		assertTrue(step.isPresent());
		assertEquals(16, step.get().slot());
		assertTrue(step.get().opensASign());

		// One order, not the whole line: 880 typed into a box that takes 256 is rejected.
		assertEquals("256", step.get().type());
	}

	@Test
	void quotesThePriceToATenthOnThePriceSign() {
		CapturedMenu pricePage = new CapturedMenu(0L, "At what price are you buying?", List.of(
				new CapturedSlot(13, "Best Offer", List.of(), "", 1, ""),
				new CapturedSlot(16, "Custom Price", List.of("Click to specify!"), "", 1, "")));

		NpcWorklist.Task task = new NpcWorklist.Task(NpcWorklist.Kind.PLACE, "TRANSMISSION_TUNER",
				"Transmission Tuner", 84_999.94d, 256L, "256", 0.0d, 0L, "");

		Optional<BazaarStep.Step> step = BazaarStep.next(task, 0.0d, pricePage);

		assertTrue(step.isPresent());
		assertEquals(16, step.get().slot());

		// A tenth of a coin, which is the precision the bazaar's own box takes.
		assertEquals("84999.9", step.get().type());
	}

	@Test
	void saysNothingAboutAScreenNobodyHasMeasured() {
		// A menu with none of the bazaar's buttons on it. The wording of the place flow was read off
		// screenshots, so a rename is the likely way this arrives - and the answer is silence.
		CapturedMenu unknown = new CapturedMenu(0L, "Item Upgrades ➜ Transmission Tuner", List.of(
				new CapturedSlot(10, "Buy It Now", List.of(), "", 1, ""),
				new CapturedSlot(11, "Place An Order", List.of(), "", 1, "")));

		assertTrue(BazaarStep.next(task(NpcWorklist.Kind.PLACE, "", "Transmission Tuner"), 0.0d,
				unknown).isEmpty());
	}

	@Test
	void saysNothingOnTheAmountSignWithNoSizeToType() {
		NpcWorklist.Task task = new NpcWorklist.Task(NpcWorklist.Kind.PLACE, "X", "Thing", 1.0d, 0L,
				"", 0.0d, 0L, "");

		assertTrue(BazaarStep.next(task, 0.0d, amountPage()).isEmpty());
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
