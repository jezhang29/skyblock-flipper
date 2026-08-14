package jeff.skyblockflipper.core.track;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaptureFilterTest {
	@Test
	void keepsTheShapesOfLineATradeCouldProduce() {
		// Not assertions about Hypixel's exact wording - finding that out is what the capture is
		// for. Each of these is a wording the filter must not be narrow enough to miss.
		assertTrue(CaptureFilter.keepChat("[Bazaar] Your Buy Order for 64x Enchanted Melon was filled!"));
		assertTrue(CaptureFilter.keepChat("[Auction] You purchased Aspect of the End for 1,200,000 coins!"));
		assertTrue(CaptureFilter.keepChat("You collected 950,000 coins from selling Hyperion"));
		assertTrue(CaptureFilter.keepChat("Your auction was cancelled!"));
		assertTrue(CaptureFilter.keepChat("Bazaar! Bought 160x Enchanted Sugar"));
	}

	@Test
	void dropsChatThatCouldNotBeATrade() {
		assertFalse(CaptureFilter.keepChat("Player123 joined the lobby"));
		assertFalse(CaptureFilter.keepChat(""));
		assertFalse(CaptureFilter.keepChat(null));
	}

	@Test
	void dropsTheModsOwnOutput() {
		// The mod prints coin figures constantly. Capturing them would put the mod's own reports of
		// trades into the file a trade parser is written from.
		assertFalse(CaptureFilter.keepChat("[Flipper] Took the flip at 1,200,000 coins on 64 units."));
	}

	@Test
	void keepsTradingMenusAndIgnoresTheRest() {
		assertTrue(CaptureFilter.keepMenu("Bazaar"));
		assertTrue(CaptureFilter.keepMenu("Your Bazaar Orders"));
		assertTrue(CaptureFilter.keepMenu("Auction House"));
		assertTrue(CaptureFilter.keepMenu("Manage Auctions"));

		assertFalse(CaptureFilter.keepMenu("Your Island"));
		assertFalse(CaptureFilter.keepMenu("Large Chest"));
		assertFalse(CaptureFilter.keepMenu(""));
		assertFalse(CaptureFilter.keepMenu(null));
	}

	@Test
	void keepsAnyMenuOpenedJustAfterTheBazaar() {
		// The screens an order is placed on are titled with the item's own name, so the keyword list
		// cannot see them and 850 menu records from 2026-08-09 contain none. Proximity is the only
		// handle there is on them.
		long bazaarAt = 1_000_000L;

		assertTrue(CaptureFilter.keepMenu("Enchanted Melon", bazaarAt + 2_000L, bazaarAt));
		assertTrue(CaptureFilter.keepMenu("Enchanted Melon", bazaarAt + 30_000L, bazaarAt));
	}

	@Test
	void dropsAMenuOpenedLongAfterTheBazaar() {
		long bazaarAt = 1_000_000L;

		assertFalse(CaptureFilter.keepMenu("Large Chest", bazaarAt + 31_000L, bazaarAt));

		// No bazaar menu this session at all, which is the state the trail starts in.
		assertFalse(CaptureFilter.keepMenu("Large Chest", bazaarAt, 0L));
		assertFalse(CaptureFilter.keepMenu("", bazaarAt, bazaarAt));
		assertFalse(CaptureFilter.keepMenu(null, bazaarAt, bazaarAt));
	}
}
