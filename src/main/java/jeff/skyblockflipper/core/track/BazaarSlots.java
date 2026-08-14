package jeff.skyblockflipper.core.track;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;

/**
 * Which slot of an open bazaar menu is the one to click.
 *
 * <p>Everything here was measured from the 850 menu records in the user's own capture file,
 * 2026-08-09, and the fixture {@code bazaar-menus.jsonl} is nine of them trimmed out. Two facts from
 * that measurement decide the whole design.
 *
 * <p><b>A slot index is not stable across menus of the same name.</b> The orders menu is 36, 45 or 54
 * slots depending on how many orders are resting, and its buttons sit in the last row: over 236
 * records {@code Claim All Coins} is at 32, 41 and 50, which is {@code size - 4} every time. So a
 * button is located from the end of the menu, never from an absolute number. {@code Order options}
 * moves for a different reason - {@code Cancel Order} is at 13 normally and at 11 on a part-filled
 * order, where a {@code Flip Order} button appears beside it - so there the index is no help at all.
 *
 * <p><b>So the name decides and the anchor only breaks ties.</b> A button is found by its display
 * name; the {@code size - n} anchor is used to pick between two slots that share one, and never to
 * return a slot whose name did not match. If Hypixel renames a button, nothing is highlighted. That
 * is the intended failure: this drives a green box drawn behind a slot the player is about to click,
 * and pointing at the wrong slot is worse than pointing at nothing.
 *
 * <p><b>A product tile is not the same thing as a category tile.</b> Both live in the same grid and
 * both carry an item id - the Oddities page shows a {@code Modifiers} category whose stack is
 * {@code RECOMBOBULATOR_3000}, which is also a real product - so matching on the id alone opens the
 * wrong page. The lore line {@code Click to view details!} is what separates them, and it is on every
 * product tile in the capture and on no category tile.
 */
public final class BazaarSlots {
	/** What kind of bazaar screen a menu is, as far as its title can say. */
	public enum Screen {
		/** {@code Bazaar ➜ Mining}, {@code Bazaar ➜ "feather"}: categories, or search results. */
		BROWSE,

		/** {@code Co-op Bazaar Orders}: the resting orders, and where a reprice starts. */
		ORDERS,

		/** {@code Order options}: one order, with cancel and flip. */
		ORDER_OPTIONS,

		CONFIRM_BUY,
		CONFIRM_SELL,

		/**
		 * Anything else, including the three screens an order is actually placed on.
		 *
		 * <p>A product page and the amount and price pages behind it are titled with the item's own
		 * name, so no title match can find them, and the capture file contains none of them - see
		 * {@link CaptureFilter#keepMenu(String, long, long)}, which was widened to record them. Until
		 * a session has them, they are {@code UNKNOWN} and nothing is highlighted there.
		 */
		UNKNOWN
	}

	/** Rows of a chest menu, which is what the {@code size - n} anchors are counted back from. */
	private static final int COLUMNS = 9;

	/** Product tiles live in these columns on a browse page. Column 0 is the category strip. */
	private static final int CONTENT_FIRST_COLUMN = 2;
	private static final int CONTENT_LAST_COLUMN = 7;

	/** The last lore line of a tile that opens a product page, and of nothing else. */
	private static final String PRODUCT_MARKER = "Click to view details!";

	/**
	 * The buttons this mod points at, each with the offset from the end of the menu it was measured
	 * at. {@code UNANCHORED} means the measurement found it at a fixed index instead.
	 */
	public static final Button SEARCH = new Button("Search", 9);
	public static final Button MANAGE_ORDERS = new Button("Manage Orders", 4);
	public static final Button CLAIM_ALL_COINS = new Button("Claim All Coins", 4);
	public static final Button GO_BACK = new Button("Go Back", 6);
	public static final Button CANCEL_ORDER = new Button("Cancel Order", Button.UNANCHORED);
	public static final Button FLIP_ORDER = new Button("Flip Order", Button.UNANCHORED);
	public static final Button CONFIRM_BUY_ORDER = new Button("Buy Order", Button.UNANCHORED);
	public static final Button CONFIRM_SELL_OFFER = new Button("Sell Offer", Button.UNANCHORED);

	private BazaarSlots() {
	}

	/**
	 * One named button, and where in the last row it was measured.
	 *
	 * @param name    the display name, matched case-insensitively and exactly - a button named
	 *                {@code Cancel Buy Order} must not answer to {@code Buy Order}
	 * @param fromEnd its slot counted back from the size of the menu, or {@link #UNANCHORED}
	 */
	public record Button(String name, int fromEnd) {
		public static final int UNANCHORED = -1;

		/**
		 * The slot this button is in, or empty if the menu has no slot with this name.
		 *
		 * <p>Several slots can share a name - the orders menu has held two {@code SELL Diamante's
		 * Handle} tiles at once - and then the anchor decides. With no anchor and no single match,
		 * this is empty rather than a guess.
		 */
		public OptionalInt in(CapturedMenu menu) {
			if (menu == null) {
				return OptionalInt.empty();
			}

			List<Integer> matches = new ArrayList<>();

			for (CapturedSlot slot : menu.slots()) {
				if (slot.name().equalsIgnoreCase(name)) {
					matches.add(slot.index());
				}
			}

			if (matches.size() == 1) {
				return OptionalInt.of(matches.getFirst());
			}

			if (matches.isEmpty() || fromEnd == UNANCHORED) {
				return OptionalInt.empty();
			}

			int anchor = size(menu) - fromEnd;
			return matches.contains(anchor) ? OptionalInt.of(anchor) : OptionalInt.empty();
		}
	}

	/**
	 * Which bazaar screen this is.
	 *
	 * <p>Titles only, and the same ones {@link BazaarMenu} matches on, because the title is all a
	 * closed menu offers. The contents are what {@link Button} then checks against.
	 */
	public static Screen screenOf(CapturedMenu menu) {
		if (menu == null) {
			return Screen.UNKNOWN;
		}

		String lower = menu.title().trim().toLowerCase(Locale.ROOT);

		if (OrderMenuParser.isOrdersMenu(menu)) {
			return Screen.ORDERS;
		}

		return switch (lower) {
			case "order options" -> Screen.ORDER_OPTIONS;
			case "confirm buy order" -> Screen.CONFIRM_BUY;
			case "confirm sell offer" -> Screen.CONFIRM_SELL;
			default -> lower.startsWith("bazaar") ? Screen.BROWSE : Screen.UNKNOWN;
		};
	}

	/**
	 * How many slots the menu has, rounded up to whole rows.
	 *
	 * <p>A capture holds only the slots that had an item in them, so the size is inferred from the
	 * highest index. That is exact for every bazaar menu measured, because all of them fill the last
	 * row: the lowest-numbered thing ever seen in it is {@code Claim All Coins} at {@code size - 4},
	 * which still rounds up to the right multiple of nine.
	 */
	public static int size(CapturedMenu menu) {
		int highest = -1;

		for (CapturedSlot slot : menu.slots()) {
			highest = Math.max(highest, slot.index());
		}

		return (highest + COLUMNS) / COLUMNS * COLUMNS;
	}

	/**
	 * The tile that opens {@code itemId}'s product page, or empty if this page does not show it.
	 *
	 * <p>Matched on Hypixel's own item id where the stack carries one. It often does not: 11 of the
	 * 133 product tiles in the capture have no custom data at all, every one an enchanted book or a
	 * shard, so the display name is the fallback rather than an afterthought. Both are checked only
	 * inside the content grid and only on tiles carrying {@link #PRODUCT_MARKER}, which is what keeps
	 * a category icon sharing an id with a real product from answering for it.
	 */
	public static OptionalInt productTile(CapturedMenu menu, String itemId, String displayName) {
		if (menu == null || screenOf(menu) != Screen.BROWSE) {
			return OptionalInt.empty();
		}

		int size = size(menu);
		int byName = -1;
		int namesMatched = 0;

		for (CapturedSlot slot : menu.slots()) {
			if (!isContent(slot.index(), size) || !isProductTile(slot)) {
				continue;
			}

			if (!itemId.isEmpty() && itemId.equals(slot.itemId())) {
				return OptionalInt.of(slot.index());
			}

			if (!displayName.isEmpty() && displayName.equalsIgnoreCase(slot.name())) {
				// Held rather than returned, so an exact id match further down the page still wins.
				byName = slot.index();
				namesMatched++;
			}
		}

		// Two tiles under one name is a page nothing can be said about safely. It does not happen on
		// the pages measured, and a search that produced it would be pointing at a coin flip.
		return namesMatched == 1 ? OptionalInt.of(byName) : OptionalInt.empty();
	}

	/** Whether a slot is in the grid a browse page puts its product tiles in. */
	private static boolean isContent(int index, int size) {
		int column = index % COLUMNS;
		int row = index / COLUMNS;

		return column >= CONTENT_FIRST_COLUMN && column <= CONTENT_LAST_COLUMN
				&& row >= 1 && row <= size / COLUMNS - 2;
	}

	private static boolean isProductTile(CapturedSlot slot) {
		return !slot.name().isEmpty() && slot.lore().contains(PRODUCT_MARKER);
	}
}
