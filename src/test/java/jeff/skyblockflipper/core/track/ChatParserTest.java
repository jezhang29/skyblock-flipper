package jeff.skyblockflipper.core.track;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The parser against the capture session it was written from.
 *
 * <p>Hand-written expectations would only prove the regexes match the strings someone typed into
 * this file. Every line here came off Hypixel.
 */
class ChatParserTest {
	private static final List<String> LINES = new ArrayList<>();

	@BeforeAll
	static void loadCapture() throws IOException {
		try (InputStream in = ChatParserTest.class.getResourceAsStream("/trade-capture-sample.jsonl")) {
			CaptureSession.read(new InputStreamReader(in, StandardCharsets.UTF_8)).chats()
					.forEach(chat -> LINES.add(chat.text()));
		}
	}

	@Test
	void readsEveryTradeEventInTheSession() {
		Map<TradeEvent.Kind, Integer> counts = new EnumMap<>(TradeEvent.Kind.class);

		for (String line : LINES) {
			ChatParser.parse(0L, line).ifPresent(e -> counts.merge(e.kind(), 1, Integer::sum));
		}

		// Every kind was exercised in the session, so a regex that stops matching shows up here as
		// a count rather than as a tracker that quietly records nothing.
		assertEquals(10, counts.get(TradeEvent.Kind.ORDER_PLACED));
		assertEquals(7, counts.get(TradeEvent.Kind.ORDER_FILLED));
		assertEquals(7, counts.get(TradeEvent.Kind.ORDER_CLAIMED));
		assertEquals(2, counts.get(TradeEvent.Kind.ORDER_CANCELLED));
		assertEquals(10, counts.get(TradeEvent.Kind.INSTANT));
		assertEquals(1, counts.get(TradeEvent.Kind.AUCTION_BOUGHT));
		assertEquals(1, counts.get(TradeEvent.Kind.AUCTION_SOLD));
		assertEquals(2, counts.get(TradeEvent.Kind.AUCTION_LISTED));
	}

	@Test
	void ignoresTheProgressLinesAndTheLobby() {
		// The capture filter is loose on purpose, so most of what reaches the parser is noise.
		assertTrue(ChatParser.parse(0L, "[Bazaar] Executing instant buy...").isEmpty());
		assertTrue(ChatParser.parse(0L, "[Bazaar] Putting goods in escrow...").isEmpty());
		assertTrue(ChatParser.parse(0L, "[Bazaar] Claiming order...").isEmpty());
		assertTrue(ChatParser.parse(0L, "Setting up the auction...").isEmpty());
		assertTrue(ChatParser.parse(0L, null).isEmpty());
	}

	@Test
	void ignoresOtherPlayersBroadcasts() {
		// Both of these are public: every listing in the lobby produces one. The second is the only
		// line in the game that names a BIN listing price, and it still cannot be used, because
		// there is nothing in it that says the listing was yours.
		assertTrue(ChatParser.parse(0L, "[MVP+] Bee__Bot collected an auction for 99,000 coins!").isEmpty());
		assertTrue(ChatParser.parse(0L,
				"[MVP+] Bee__Bot created a BIN auction for Rabbit Hat at 970,000 coins!").isEmpty());
	}

	@Test
	void cannotBeSpokenIntoTheLedgerByAnotherPlayer() {
		// Player chat arrives with the speaker in front of it, and every pattern is anchored, so
		// typing a trade message does not produce a trade.
		assertTrue(ChatParser.parse(0L, "[MVP+] Griefer: You purchased Hyperion for 1 coins!").isEmpty());
		assertTrue(ChatParser.parse(0L,
				"Griefer: [Bazaar] Bought 64x Enchanted Melon for 1 coins!").isEmpty());
	}

	@Test
	void readsAPartialCollection() {
		// The 1,344x offer that filled 903. Chat never said it filled - only a complete fill gets a
		// notification - so this line is the whole record of what those units did.
		TradeEvent event = parse("[Bazaar] Claimed 34,107 coins from selling 903x Slimeball at 38.2 each!");

		assertEquals(TradeEvent.Kind.ORDER_CLAIMED, event.kind());
		assertEquals(TradeEvent.Side.SELL, event.side());
		assertEquals("Slimeball", event.displayName());
		assertEquals(903L, event.units());
		assertEquals(34_107.0d, event.coins());
		assertEquals(38.2d, event.unitPrice());
	}

	@Test
	void keepsCoinsAndUnitPriceApartOnASell() {
		// 395 x 38.1 is 15,049.5, and 14,880 was collected. The per-unit figure Hypixel prints is
		// gross and the coin figure is net of bazaar tax, so anything that needs the money must use
		// coins() and never units() x unitPrice().
		TradeEvent event = parse("[Bazaar] Claimed 14,880 coins from selling 395x Slimeball at 38.1 each!");

		assertEquals(14_880.0d, event.coins());
		assertEquals(15_049.5d, event.units() * event.unitPrice());
	}

	@Test
	void readsABuyOrderClaimWhereTheItemComesFirst() {
		// A buy claim puts the item before the coins and a sell claim puts the coins first. Same
		// event, two sentence shapes.
		TradeEvent event = parse("[Bazaar] Claimed 8x Enchanted End Stone worth 1,791.2 coins bought for 223.9 each!");

		assertEquals(TradeEvent.Side.BUY, event.side());
		assertEquals("Enchanted End Stone", event.displayName());
		assertEquals(8L, event.units());
		assertEquals(1_791.2d, event.coins());
		assertEquals(223.9d, event.unitPrice());
	}

	@Test
	void readsAFillNotificationThroughItsColourCodes() {
		// Fill notifications are the only lines that arrive with formatting intact.
		TradeEvent event = parse("§6[Bazaar] §eYour §6Sell Offer §efor §a366§7x §fSlimeball §ewas filled!");

		assertEquals(TradeEvent.Kind.ORDER_FILLED, event.kind());
		assertEquals(TradeEvent.Side.SELL, event.side());
		assertEquals(366L, event.units());
		assertEquals("Slimeball", event.displayName());
	}

	@Test
	void readsACancelAsUnitsComingBack() {
		TradeEvent event = parse("[Bazaar] Cancelled! Refunded 441x Slimeball from cancelling Sell Offer!");

		assertEquals(TradeEvent.Kind.ORDER_CANCELLED, event.kind());
		assertEquals(TradeEvent.Side.SELL, event.side());
		assertEquals(441L, event.units());
	}

	@Test
	void readsAnAuctionSaleAndAPurchase() {
		TradeEvent sold = parse(
				"You collected 99,000 coins from selling [Lvl 1] Ghoul to [MVP+] Lizzywraith in an auction!");

		assertEquals(TradeEvent.Kind.AUCTION_SOLD, sold.kind());
		assertEquals("[Lvl 1] Ghoul", sold.displayName());
		assertEquals(99_000.0d, sold.coins());

		TradeEvent bought = parse("You purchased Ancient Frozen Blaze Helmet ✪✪✪✪✪ for 25,000,000 coins!");

		assertEquals(TradeEvent.Kind.AUCTION_BOUGHT, bought.kind());
		// Stars and modifier stay in the name. They are what tells this apart from a bare helmet,
		// and an id for it only comes from the menu snapshot.
		assertEquals("Ancient Frozen Blaze Helmet ✪✪✪✪✪", bought.displayName());
		assertEquals(25_000_000.0d, bought.coins());
	}

	@Test
	void tellsAnInstantBuyFromAnInstantSell() {
		assertEquals(TradeEvent.Side.BUY, parse("[Bazaar] Bought 8x Enchanted Ice for 984.0 coins!").side());
		assertEquals(TradeEvent.Side.SELL, parse("[Bazaar] Sold 404x Slimeball for 1,333.2 coins!").side());
	}

	private static TradeEvent parse(String line) {
		Optional<TradeEvent> event = ChatParser.parse(0L, line);
		assertTrue(event.isPresent(), line);
		return event.get();
	}
}
