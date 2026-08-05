package jeff.skyblockflipper.core.track;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
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
