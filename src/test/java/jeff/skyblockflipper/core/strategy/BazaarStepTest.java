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
import static org.junit.jupiter.api.Assertions.assertNull;
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

	/** An NPC basket task as the bazaar action the step is worked out from. */
	private static BazaarAction npc(NpcWorklist.Kind kind, String itemId, String name) {
		return BazaarAction.of(
				new NpcWorklist.Task(kind, itemId, name, 100.0d, 64L, "64", 0.0d, 0L, ""));
	}

	@Test
	void sendsAPlaceToTheSearchSignWithTheNameToType() {
		Optional<BazaarStep.Step> step = BazaarStep.next(
				npc(NpcWorklist.Kind.PLACE, "ENCHANTED_FEATHER", "Enchanted Feather"), 0.0d,
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
				npc(NpcWorklist.Kind.PLACE, "ENCHANTED_FEATHER", "Enchanted Feather"), 0.0d,
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
				npc(NpcWorklist.Kind.CLAIM, "", name), 0.0d, orders);

		assertTrue(step.isPresent());
		assertEquals(claimable.index(), step.get().slot());
		assertEquals(BazaarStep.Click.LEFT, step.get().click());
	}

	@Test
	void claimsAFilledRowBeforeCancellingIt() {
		CapturedMenu orders = withAClaimableRow();
		CapturedSlot claimable = orders.slots().stream()
				.filter(s -> s.lore().contains("Click to claim!")).findFirst().orElseThrow();
		String name = claimable.name().replaceFirst("^(BUY|SELL) ", "");

		Optional<BazaarStep.Step> step = BazaarStep.next(
				npc(NpcWorklist.Kind.CANCEL, "", name), 0.0d, orders);

		assertTrue(step.isPresent());
		assertEquals(claimable.index(), step.get().slot());
		assertEquals(BazaarStep.Click.LEFT, step.get().click());
		assertTrue(step.get().label().startsWith("claim first"), step.get().label());
	}

	@Test
	void claimsAFilledRowBeforeRepricingIt() {
		// Reported live: the reprice pointed at the options menu, but clicking the row claims every
		// filled unit, and the order cannot be repriced until they are out of it.
		CapturedMenu orders = withAClaimableRow();
		CapturedSlot claimable = orders.slots().stream()
				.filter(s -> s.lore().contains("Click to claim!")).findFirst().orElseThrow();
		String name = claimable.name().replaceFirst("^(BUY|SELL) ", "");

		Optional<BazaarStep.Step> step = BazaarStep.next(
				npc(NpcWorklist.Kind.REPRICE, "", name), 0.0d, orders);

		assertTrue(step.isPresent());
		assertEquals(claimable.index(), step.get().slot());
		assertEquals(BazaarStep.Click.LEFT, step.get().click());
		assertEquals("claim first, then reprice", step.get().label());
	}

	@Test
	void leftClicksAnUnfilledRowToOpenItsOptions() {
		Optional<BazaarStep.Step> step = BazaarStep.next(
				BazaarAction.of(new NpcWorklist.Task(NpcWorklist.Kind.REPRICE, "", "Optical Lens",
						84_621.2d, 64L, "64", 0.0d, 0L, "")),
				84_621.2d, menu("Co-op Bazaar Orders"));

		assertTrue(step.isPresent());
		assertEquals(BazaarStep.Click.LEFT, step.get().click());
	}

	@Test
	void willNotPickBetweenTwoOrdersOnOneItem() {
		// Two Diamante's Handle offers rest in this menu. Without a price to tell them apart, the
		// wrong one is a cancelled order that was fine.
		assertTrue(BazaarStep.next(npc(NpcWorklist.Kind.CANCEL, "", "Diamante's Handle"), 0.0d,
				menu("Co-op Bazaar Orders")).isEmpty());

		assertTrue(BazaarStep.next(npc(NpcWorklist.Kind.CANCEL, "", "Diamante's Handle"),
				811_618.4d, menu("Co-op Bazaar Orders")).isPresent());
	}

	@Test
	void pointsAtCancelOnTheOptionsScreen() {
		Optional<BazaarStep.Step> step = BazaarStep.next(
				npc(NpcWorklist.Kind.CANCEL, "", "Optical Lens"), 0.0d, menu("Order options"));

		assertTrue(step.isPresent());
		assertEquals(13, step.get().slot());
	}

	@Test
	void pointsAtTheConfirmButtonOnEitherConfirmScreen() {
		assertEquals(13, BazaarStep.next(npc(NpcWorklist.Kind.PLACE, "", "Enchanted Feather"), 0.0d,
				menu("Confirm Buy Order")).orElseThrow().slot());
		assertEquals(13, BazaarStep.next(npc(NpcWorklist.Kind.PLACE, "", "Enchanted Feather"), 0.0d,
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
				npc(NpcWorklist.Kind.PLACE, "TRANSMISSION_TUNER", "Transmission Tuner"), 0.0d,
				productPage());

		assertTrue(step.isPresent());
		assertEquals(11, step.get().slot());
	}

	@Test
	void pointsAtCreateBuyOrderOnAProductPageHypixelCutTheTitleOf() {
		// Photographed live 2026-08-14 with no box on it. The title is 31 characters, and the rule
		// that let a prefix match only at 32 left this page unmatched and so unhighlighted.
		CapturedMenu page = new CapturedMenu(0L, "Revenant Horror ➜ Revenant Cata", List.of(
				new CapturedSlot(10, "Buy Instantly", List.of(), "", 1, ""),
				new CapturedSlot(11, "Create Buy Order", List.of("Click to setup Buy Order!"),
						"minecraft:filled_map", 1, "")));

		assertEquals(31, page.title().length());

		Optional<BazaarStep.Step> step = BazaarStep.next(
				npc(NpcWorklist.Kind.PLACE, "REVENANT_CATALYST", "Revenant Catalyst"), 0.0d, page);

		assertTrue(step.isPresent());
		assertEquals(11, step.get().slot());
	}

	@Test
	void pointsAtTheAmountSignAndSaysWhatToTypeOnIt() {
		BazaarAction action = BazaarAction.of(new NpcWorklist.Task(NpcWorklist.Kind.PLACE,
				"TRANSMISSION_TUNER", "Transmission Tuner", 100.0d, 880L, "3 x 256 + 112", 0.0d, 0L,
				""));

		Optional<BazaarStep.Step> step = BazaarStep.next(action, 0.0d, amountPage());

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

		BazaarAction action = BazaarAction.of(new NpcWorklist.Task(NpcWorklist.Kind.PLACE,
				"TRANSMISSION_TUNER", "Transmission Tuner", 84_999.94d, 256L, "256", 0.0d, 0L, ""));

		Optional<BazaarStep.Step> step = BazaarStep.next(action, 0.0d, pricePage);

		assertTrue(step.isPresent());
		assertEquals(16, step.get().slot());

		// A tenth of a coin, which is the precision the bazaar's own box takes.
		assertEquals("84999.9", step.get().type());
	}

	/**
	 * The price page as photographed 2026-08-14, both buttons with the lore they carried.
	 *
	 * @param offered what the Top Order +0.1 button says it will post at
	 */
	private static CapturedMenu pricePage(String offered) {
		return new CapturedMenu(0L, "How much do you want to pay?", List.of(
				new CapturedSlot(11, "Top Order +0.1", List.of("Buy Order Setup", "",
						"Beat the price of the top order so", "yours is filled first.", "",
						"Ordering: 256x", "Unit price: " + offered + " coins", "",
						"Click to proceed!"), "", 1, ""),
				new CapturedSlot(15, "Custom Price", List.of("Buy Order Setup", "",
						"Set the price per unit you're willing", "to pay.", "", "Ordering: 256x", "",
						"Click to specify!"), "", 1, ""),
				new CapturedSlot(31, "Close", List.of(), "", 1, "")));
	}

	private static BazaarAction placeAt(double price) {
		return BazaarAction.of(new NpcWorklist.Task(NpcWorklist.Kind.PLACE, "TRANSMISSION_TUNER",
				"Transmission Tuner", price, 256L, "256", 0.0d, 0L, ""));
	}

	@Test
	void pressesHypixelsOwnButtonWhereItOffersThePlansPrice() {
		// Photographed together: the plan said 30808.0 and the button offered 30,808.0, because both
		// are top bid plus one increment off the same book. One click beats typing seven characters.
		Optional<BazaarStep.Step> step =
				BazaarStep.next(placeAt(30_808.0d), 0.0d, pricePage("30,808.0"));

		assertTrue(step.isPresent());
		assertEquals(11, step.get().slot());
		assertFalse(step.get().opensASign());
	}

	@Test
	void takesTheButtonWhenTheBookHasFallenSinceThePlanWasPriced() {
		// Cheaper than planned and still the top of the book: strictly the better order.
		assertEquals(11, BazaarStep.next(placeAt(30_808.0d), 0.0d, pricePage("30,102.4"))
				.orElseThrow().slot());
	}

	@Test
	void typesThePriceWhereTheButtonWouldPostOverThePlan() {
		// The book moved up while the player walked to the menu. Following it spends the margin the
		// plan was built on, so the planned price gets typed and the order rests behind the top.
		Optional<BazaarStep.Step> step =
				BazaarStep.next(placeAt(30_808.0d), 0.0d, pricePage("31,400.0"));

		assertTrue(step.isPresent());
		assertEquals(15, step.get().slot());
		assertEquals("30808.0", step.get().type());
	}

	@Test
	void typesThePriceWhenTheButtonDoesNotSayWhatItWouldPostAt() {
		CapturedMenu reworded = new CapturedMenu(0L, "How much do you want to pay?", List.of(
				new CapturedSlot(11, "Top Order +0.1", List.of("Click to proceed!"), "", 1, ""),
				new CapturedSlot(15, "Custom Price", List.of("Click to specify!"), "", 1, "")));

		assertEquals(15, BazaarStep.next(placeAt(30_808.0d), 0.0d, reworded).orElseThrow().slot());
	}

	@Test
	void saysNothingAboutAScreenNobodyHasMeasured() {
		// A menu with none of the bazaar's buttons on it. The wording of the place flow was read off
		// screenshots, so a rename is the likely way this arrives - and the answer is silence.
		CapturedMenu unknown = new CapturedMenu(0L, "Item Upgrades ➜ Transmission Tuner", List.of(
				new CapturedSlot(10, "Buy It Now", List.of(), "", 1, ""),
				new CapturedSlot(11, "Place An Order", List.of(), "", 1, "")));

		assertTrue(BazaarStep.next(npc(NpcWorklist.Kind.PLACE, "", "Transmission Tuner"), 0.0d,
				unknown).isEmpty());
	}

	@Test
	void saysNothingOnTheAmountSignWithNoSizeToType() {
		BazaarAction action = BazaarAction.of(new NpcWorklist.Task(NpcWorklist.Kind.PLACE, "X",
				"Thing", 1.0d, 0L, "", 0.0d, 0L, ""));

		assertTrue(BazaarStep.next(action, 0.0d, amountPage()).isEmpty());
	}

	@Test
	void saysNothingWhenTheOpenScreenCannotServeTheRow() {
		assertTrue(BazaarStep.next(npc(NpcWorklist.Kind.PLACE, "", "Enchanted Feather"), 0.0d,
				menu("Co-op Bazaar Orders")).isEmpty());
		assertTrue(BazaarStep.next(npc(NpcWorklist.Kind.CANCEL, "", "Nothing Resting"), 0.0d,
				menu("Co-op Bazaar Orders")).isEmpty());
		assertTrue(BazaarStep.next((BazaarAction) null, 0.0d, menu("Order options")).isEmpty());
	}

	// --- Worked jobs: the other types feed BazaarStep the same way, off their steps ---

	private static WorkedJob.Step step(WorkedJob.Stage stage, double price) {
		return new WorkedJob.Step(stage, stage.label(), "TRANSMISSION_TUNER", "Transmission Tuner",
				price, 256L, "256", 0);
	}

	@Test
	void sendsASellOfferToCreateSellOfferNotCreateBuyOrder() {
		// A worked craft or spread exits with a sell offer. On the product page that is a different
		// button from the buy order every NPC flip opens.
		Optional<BazaarStep.Step> step = BazaarStep.next(
				BazaarAction.of(step(WorkedJob.Stage.SELL_OFFER, 100.0d)), 0.0d, productPage());

		assertTrue(step.isPresent());
		assertEquals(15, step.get().slot());
	}

	@Test
	void aBuyOrderStepOpensTheBuyOrderJustLikeAnNpcPlace() {
		Optional<BazaarStep.Step> step = BazaarStep.next(
				BazaarAction.of(step(WorkedJob.Stage.BUY_ORDER, 100.0d)), 0.0d, productPage());

		assertTrue(step.isPresent());
		assertEquals(11, step.get().slot());
	}

	@Test
	void aSellOfferTypesItsPriceRatherThanPressingTheOutbidButton() {
		// The "+0.1" button outbids the top of the book, which is a buy's move. A sell undercuts the
		// cheapest ask, so even where the button is present the sell price gets typed on the sign.
		Optional<BazaarStep.Step> step = BazaarStep.next(
				BazaarAction.of(step(WorkedJob.Stage.SELL_OFFER, 30_808.0d)), 0.0d,
				pricePage("30,102.4"));

		assertTrue(step.isPresent());
		assertEquals(15, step.get().slot());
		assertTrue(step.get().opensASign());
		assertEquals("30808.0", step.get().type());
	}

	@Test
	void doesNotGuideAnInstantBuyOrATransform() {
		// Both happen on screens no capture has confirmed - instant buy on its own confirm, a transform
		// off the bazaar entirely - so neither becomes an action to point a box at.
		assertNull(BazaarAction.of(step(WorkedJob.Stage.INSTANT_BUY, 0.0d)));
		assertNull(BazaarAction.of(step(WorkedJob.Stage.TRANSFORM, 0.0d)));
	}
}
