package jeff.skyblockflipper.core.track;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reconciliation against the whole recorded session.
 *
 * <p>The session is one hour of real trading and it contains every case the two streams disagree
 * about: an offer that filled two thirds and announced nothing, three offers on one item resting at
 * once, orders placed before the recording started, and one that left the book while nobody was
 * looking. Every number asserted here came off Hypixel.
 */
class TradeTrackerTest {
	/** The account that ran the capture. LunarV4 is the co-op mate whose orders share the menu. */
	private static final String ME = "Bee__Bot";

	private static CaptureSession session;

	@BeforeAll
	static void loadCapture() throws IOException {
		try (InputStream in = TradeTrackerTest.class.getResourceAsStream("/trade-capture-sample.jsonl")) {
			session = CaptureSession.read(new InputStreamReader(in, StandardCharsets.UTF_8));
		}
	}

	@Test
	void settlesOnlyTheEventsWhereCoinsMoved() {
		List<Settlement> settlements = track().settlements();

		// 7 claims, 10 instant orders, 1 BIN bought, 1 auction collected. The 10 order setups and
		// the 2 listings are intentions, not money: booking a setup would double-count every flip
		// and booking a listing would book a price nobody has paid.
		assertEquals(19, settlements.size());
		assertEquals(7, count(settlements, Settlement.Venue.BAZAAR_ORDER));
		assertEquals(10, count(settlements, Settlement.Venue.BAZAAR_INSTANT));
		assertEquals(2, count(settlements, Settlement.Venue.AUCTION));
	}

	@Test
	void bookTheClaimThatOnlyAPartialFillProduced() {
		// The 1,344 unit offer stopped at 903. No "was filled!" line exists for it anywhere in the
		// session, so this settlement's units come from the claim and its item id from a menu.
		Settlement claim = settlement("Slimeball", 903L);

		assertEquals(Settlement.Venue.BAZAAR_ORDER, claim.venue());
		assertEquals(TradeEvent.Side.SELL, claim.side());
		assertEquals("SLIME_BALL", claim.itemId());
		assertEquals(38.2d, claim.unitPrice());
		// Net of bazaar tax, and 903 x 38.2 is 34,494.6 gross. Multiplying the printed unit price
		// out instead of reading the coins line overstates the take by about 1%.
		assertEquals(34_107.0d, claim.coins());
	}

	@Test
	void keepsThreeOffersOnOneItemApart() {
		// Slimeball rested at 1,344, 395 and 366 units simultaneously and the claims came back as
		// 903, 395 and 366. Only the first is ambiguous by size, and it belongs to the only order
		// big enough to hold it.
		assertEquals(903L, order("Slimeball", 1_344L).claimed());
		assertEquals(395L, order("Slimeball", 395L).claimed());
		assertEquals(366L, order("Slimeball", 366L).claimed());
	}

	@Test
	void remembersTheUnitsACancelledOrderNeverFilled() {
		// 903 filled and 441 refunded. Rewriting the total down to what filled would score a plan
		// that reached two thirds of itself as a complete success, and the fill rate is the one
		// number this feeds.
		TrackedOrder order = order("Slimeball", 1_344L);

		assertEquals(TrackedOrder.Status.CANCELLED, order.status());
		assertEquals(1_344L, order.total());
		assertEquals(903L, order.filled());
		assertEquals(441L, order.total() - order.filled());
	}

	@Test
	void buriesAnOrderThatLeftTheMenuWithNothingSaidAboutIt() {
		// The 793 unit offer was placed, announced filled, never claimed in chat, and is absent
		// from the orders menu 37 minutes later. Chat alone leaves it resting and uncollected for
		// ever, which would show as coins waiting that are not there.
		TrackedOrder order = order("Slimeball", 793L);

		assertEquals(TrackedOrder.Status.VANISHED, order.status());
		assertEquals(793L, order.filled());
		assertEquals(0L, order.claimed());
		assertTrue(track().awaitingClaim().isEmpty());
	}

	@Test
	void reportsFilledUnitsAsWaitingUntilSomethingSaysOtherwise() {
		// The same offer, replayed only up to the fill notification. This is the state a player
		// wants pointed out: 793 units sold and the coins still sitting in the menu.
		TradeTracker tracker = new TradeTracker(ME);

		for (CaptureRecord record : session.records()) {
			if (record.at() > 1_785_883_351_507L) {
				break;
			}

			tracker.accept(record);
		}

		List<TrackedOrder> waiting = tracker.awaitingClaim();

		assertEquals(1, waiting.size());
		assertEquals(793L, waiting.getFirst().unclaimed());
	}

	@Test
	void adoptsOrdersItNeverSawPlaced() {
		// Wisdom I and First Master Star were on the book before the recording started, so no chat
		// line in this file mentions either. A tracker that only believes chat owns neither.
		assertEquals(TrackedOrder.Status.RESTING, order("Wisdom I", 1L).status());
		assertEquals(TrackedOrder.Status.RESTING, order("First Master Star", 1L).status());
		assertEquals("FIRST_MASTER_STAR", order("First Master Star", 1L).itemId());
	}

	@Test
	void neverAdoptsACoopMatesOrder() {
		// LunarV4's 811,618 coin Diamante's Handle sits in the same menu rows as yours.
		assertTrue(track().orders().stream()
				.noneMatch(o -> o.displayName().equals("Diamante's Handle")
						|| o.displayName().equals("Optical Lens")));

		// With no name to match on, the menu half is lost rather than the ownership question being
		// guessed at.
		TradeTracker anonymous = TradeTracker.replay(session, "");

		assertTrue(anonymous.orders().stream().noneMatch(o -> o.displayName().equals("Wisdom I")));
	}

	@Test
	void resolvesItemIdsChatCouldNotHave() {
		// The three names in this session that no string work on the id would produce, and one
		// prefix collision of the kind that breaks name matching: Slimeball against Enchanted
		// Slimeball.
		assertEquals("SLIME_BALL", settlement("Slimeball", 903L).itemId());
		assertEquals("ENCHANTED_SLIME_BALL", settlement("Enchanted Slimeball", 5L).itemId());
		assertEquals("ENCHANTED_ENDSTONE", settlement("Enchanted End Stone", 8L).itemId());

		// The BIN purchase is ided from the auction view, a menu the orders parser ignores entirely.
		assertEquals("FROZEN_BLAZE_HELMET",
				settlement("Ancient Frozen Blaze Helmet ✪✪✪✪✪", 1L).itemId());
	}

	@Test
	void leavesAnItemNoMenuEverShowedWithoutAnId() {
		// Instant-bought and never looked at in a menu. An empty id is the honest answer; guessing
		// one from the name is how ENCHANTED_MELON_BLOCK gets priced as ENCHANTED_MELON.
		assertEquals("", settlement("Oak Log", 1L).itemId());
		assertEquals("", settlement("Young Dragon Fragment", 4L).itemId());
	}

	@Test
	void ignoresAListingPrice() {
		// "created a BIN auction for Rabbit Hat at 970,000 coins!" is the only line that names a
		// listing price, every player's listing produces one, and nobody has paid it.
		assertTrue(track().settlements().stream()
				.noneMatch(s -> s.displayName().equals("Rabbit Hat")));

		// The Ghoul, by contrast, actually sold and was collected.
		assertEquals(99_000.0d, settlement("[Lvl 1] Ghoul", 1L).coins());
		assertEquals("PET", settlement("[Lvl 1] Ghoul", 1L).itemId());
	}

	@Test
	void derivesAUnitPriceForInstantOrdersOnly() {
		// Instant lines print a total and no per-unit figure, so the unit price is the quotient and
		// on a sell it is therefore net of tax, unlike every other unit price here.
		assertEquals(460.0d, settlement("Enchanted Redstone Dust", 8L).unitPrice());
		assertNotEquals(0.0d, settlement("Young Dragon Fragment", 4L).unitPrice());
	}

	@Test
	void survivesASessionWithNoMenusAtAll() {
		// Chat-only is the normal case for a player who never opens the orders menu, and it has to
		// degrade to "no ids, no partial fills seen" rather than to nothing.
		TradeTracker tracker = new TradeTracker(ME);
		session.chats().forEach(tracker::accept);

		assertEquals(19, tracker.settlements().size());
		assertEquals("", tracker.settlements().getFirst().itemId());
		assertFalse(tracker.orders().isEmpty());
	}

	/**
	 * The wording live play produced on 2026-08-04, which the recorded session never contained
	 * because every cancel in it was a sell offer.
	 *
	 * <p>A cancelled buy order refunds coins and names nothing, so the refund amount is the only
	 * evidence of which order it was. Two buy orders rest at once here to check the amount is
	 * really what selects, rather than the cancel landing on whichever order is first.
	 */
	@Test
	void cancelsTheBuyOrderWhoseEscrowMatchesTheRefund() {
		TradeTracker tracker = new TradeTracker(ME);

		tracker.accept(chat("[Bazaar] Buy Order Setup! 311x Purple Candy for 6,554,823 coins."));
		tracker.accept(chat("[Bazaar] Buy Order Setup! 64x Cobblestone for 5,000 coins."));
		tracker.accept(chat("[Bazaar] Cancelled! Refunded 6,554,823 coins from cancelling Buy Order!"));

		assertEquals(TrackedOrder.Status.CANCELLED, order(tracker, "Purple Candy").status());
		assertEquals(TrackedOrder.Status.RESTING, order(tracker, "Cobblestone").status());

		// The 311 units that never filled are what the fill rate is measured from, so the cancel
		// has to leave the original total behind rather than shrinking the order to nothing.
		assertEquals(311L, order(tracker, "Purple Candy").total());
		assertEquals(0L, order(tracker, "Purple Candy").filled());
	}

	/**
	 * A buy order knows its price the moment it is announced, out of the escrow the line quotes.
	 *
	 * <p>Chat never prints a price per unit, so an order used to be unpriced until the orders menu
	 * had been drawn - and an unpriced order is dropped by {@code TrackerService.restingBuyOrders},
	 * which means the basket charged neither a slot nor the coins for it and would offer the same
	 * item again. A buy escrows the gross, so the division is the price.
	 *
	 * <p>311 x 21,076.6 is 6,554,822.6 and the line says 6,554,823, so the recovered price is high
	 * by a thousandth of a coin. That is why {@code NpcReprice} compares to within half an
	 * increment.
	 */
	@Test
	void pricesABuyOrderFromTheCoinsItsSetupLineQuoted() {
		TradeTracker tracker = new TradeTracker(ME);

		tracker.accept(chat("[Bazaar] Buy Order Setup! 311x Purple Candy for 6,554,823 coins."));

		assertEquals(21_076.6d, order(tracker, "Purple Candy").unitPrice(), 0.01d);
	}

	/**
	 * A sell offer's setup line quotes the payout, which is net of tax, so it is not a price.
	 *
	 * <p>Dividing it out would report an offer about 1% under where it really rests - a sell that
	 * looks undercut when it is on top.
	 */
	@Test
	void leavesASellOfferUnpricedUntilAMenuSaysOtherwise() {
		TradeTracker tracker = new TradeTracker(ME);

		tracker.accept(chat("[Bazaar] Sell Offer Setup! 1,344x Slimeball for 50,720 coins."));

		assertEquals(0.0d, order(tracker, "Slimeball").unitPrice());
	}

	/** The menu is still the truth, and a price it does state replaces the derived one. */
	@Test
	void takesThePriceTheOrdersMenuStatesOverTheDerivedOne() {
		TradeTracker tracker = new TradeTracker(ME);

		tracker.accept(chat("[Bazaar] Buy Order Setup! 1,525x Bronze Bowl for 4,154,100 coins."));
		assertEquals(2_724.0d, order(tracker, "Bronze Bowl").unitPrice(), 0.01d);

		tracker.accept(orders(1_000L, "BUY Bronze Bowl", "BRONZE_BOWL", "Order amount: 1,525x",
				"Filled: 0/1.5k (0.0%)", "Price per unit: 2,800.0 coins"));

		assertEquals(2_800.0d, order(tracker, "Bronze Bowl").unitPrice(), 0.01d);
	}

	/**
	 * A menu slot the parser could not read a price off does not erase the one already known.
	 *
	 * <p>{@code OrderMenuParser} reports zero for a slot with no {@code Price per unit} line, and
	 * taking that would turn a priced order back into an unpriced one - the state where the basket
	 * stops counting it.
	 */
	@Test
	void keepsTheDerivedPriceWhenAMenuSlotStatesNone() {
		TradeTracker tracker = new TradeTracker(ME);

		tracker.accept(chat("[Bazaar] Buy Order Setup! 1,525x Bronze Bowl for 4,154,100 coins."));
		tracker.accept(new CapturedMenu(1_000L, "Co-op Bazaar Orders",
				List.of(new CapturedSlot(11, "BUY Bronze Bowl",
						List.of("Order amount: 1,525x", "Filled: 0/1.5k (0.0%)", "By: [MVP+] " + ME),
						"BRONZE_BOWL", 1, ""))));

		assertEquals(2_724.0d, order(tracker, "Bronze Bowl").unitPrice(), 0.01d);
	}

	/** An amount matching no resting order cancels nothing, rather than cancelling the nearest. */
	@Test
	void ignoresACoinRefundThatMatchesNoOrder() {
		TradeTracker tracker = new TradeTracker(ME);

		tracker.accept(chat("[Bazaar] Buy Order Setup! 311x Purple Candy for 6,554,823 coins."));
		tracker.accept(chat("[Bazaar] Cancelled! Refunded 1,000 coins from cancelling Buy Order!"));

		assertEquals(TrackedOrder.Status.RESTING, order(tracker, "Purple Candy").status());
	}

	/**
	 * The Bronze Bowl case, measured live on 2026-08-11.
	 *
	 * <p>A 1,525 unit buy order filled 3 and the player claimed them. The menu still reads
	 * {@code Filled: 3/1.5k} an hour later - that line never goes down - and the worklist kept its
	 * top row saying "claim Bronze Bowl, 3" for the rest of the day. What actually changed at the
	 * claim is that {@code You have 3 items to claim!} stopped being printed.
	 */
	@Test
	void stopsWaitingOnUnitsTheMenuNoLongerHolds() {
		TradeTracker tracker = new TradeTracker(ME);

		tracker.accept(buyOrder(1_000L, 3L, "You have 3 items to claim!"));
		assertEquals(3L, order(tracker, "Bronze Bowl").unclaimed());

		// Nothing in chat: the claim happened in a session this tracker never saw.
		tracker.accept(buyOrder(2_000L, 3L));

		assertEquals(3L, order(tracker, "Bronze Bowl").filled());
		assertEquals(3L, order(tracker, "Bronze Bowl").claimed());
		assertEquals(0L, order(tracker, "Bronze Bowl").unclaimed());
		assertTrue(tracker.awaitingClaim().isEmpty());

		// The other 1,522 are still on the book, so the order is still a position - it just has
		// nothing to collect.
		assertEquals(1_522L, order(tracker, "Bronze Bowl").remaining());
		assertEquals(TrackedOrder.Status.RESTING, order(tracker, "Bronze Bowl").status());
	}

	/** A later fill on the same order is waiting again, so the claim is not a permanent silence. */
	@Test
	void noticesUnitsThatFilledAfterTheLastClaim() {
		TradeTracker tracker = new TradeTracker(ME);

		tracker.accept(buyOrder(1_000L, 3L));
		tracker.accept(buyOrder(2_000L, 11L, "You have 8 items to claim!"));

		assertEquals(8L, order(tracker, "Bronze Bowl").unclaimed());
		assertEquals(3L, order(tracker, "Bronze Bowl").claimed());
	}

	/**
	 * A sell offer names coins rather than units, so only the empty case can be read off it.
	 *
	 * <p>Dividing a taxed coin total by a unit price to recover units would be a guess, and a guess
	 * that came out one unit high would mark a fill as collected that is still sitting there.
	 */
	@Test
	void leavesASellOfferAloneWhileCoinsAreWaiting() {
		TradeTracker tracker = new TradeTracker(ME);

		tracker.accept(sellOffer(1_000L, 903L, "You have 34,107 coins to claim!"));
		assertEquals(903L, order(tracker, "Slimeball").unclaimed());

		tracker.accept(sellOffer(2_000L, 903L));
		assertEquals(0L, order(tracker, "Slimeball").unclaimed());
	}

	private static CapturedMenu buyOrder(long at, long filled, String... claim) {
		return orders(at, "BUY Bronze Bowl", "BRONZE_BOWL", "Order amount: 1,525x",
				"Filled: " + filled + "/1.5k (0.2%)", "Price per unit: 2,724.0 coins", claim);
	}

	private static CapturedMenu sellOffer(long at, long filled, String... claim) {
		return orders(at, "SELL Slimeball", "SLIME_BALL", "Offer amount: 1,344x",
				"Filled: " + filled + "/1.3k (67.2%)", "Price per unit: 38.2 coins", claim);
	}

	private static CapturedMenu orders(long at, String name, String itemId, String amount,
			String filled, String price, String... claim) {
		List<String> lore = new ArrayList<>(List.of(amount, filled, price, "By: [MVP+] " + ME));
		lore.addAll(List.of(claim));

		return new CapturedMenu(at, "Co-op Bazaar Orders",
				List.of(new CapturedSlot(11, name, lore, itemId, 1, "")));
	}

	private static CapturedChat chat(String line) {
		return new CapturedChat(0L, line);
	}

	private static TrackedOrder order(TradeTracker tracker, String displayName) {
		return tracker.orders().stream()
				.filter(o -> o.displayName().equals(displayName))
				.findFirst()
				.orElseThrow();
	}

	private static TradeTracker track() {
		return TradeTracker.replay(session, ME);
	}

	private static Settlement settlement(String displayName, long units) {
		return track().settlements().stream()
				.filter(s -> s.displayName().equals(displayName) && s.units() == units)
				.findFirst()
				.orElseThrow();
	}

	private static TrackedOrder order(String displayName, long total) {
		return track().orders().stream()
				.filter(o -> o.displayName().equals(displayName) && o.total() == total)
				.findFirst()
				.orElseThrow();
	}

	private static long count(List<Settlement> settlements, Settlement.Venue venue) {
		return settlements.stream().filter(s -> s.venue() == venue).count();
	}
}
