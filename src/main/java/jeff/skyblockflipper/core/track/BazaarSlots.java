package jeff.skyblockflipper.core.track;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
		 * One item's page: buy instantly, sell instantly, and the two that open an order.
		 *
		 * <p>Its title is {@code Item Upgrades ➜ Transmission Tuner} - the sub-category it was
		 * reached through and then the item - so it is not {@code Bazaar ➜ …} and nothing in it is
		 * fixed enough to match on. Recognised by the buttons it carries instead.
		 */
		PRODUCT,

		/** {@code How many do you want?}, which is the sign that takes the order size. */
		AMOUNT,

		/** The page behind it that takes the price. */
		PRICE,

		/**
		 * Anything else.
		 *
		 * <p>Nothing is highlighted here. Every screen that has been seen is above, and a screen
		 * whose buttons have been renamed lands here rather than being guessed at.
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

	/**
	 * The product page's own buttons, and the sign buttons behind them.
	 *
	 * <p><b>These names are read off screenshots, not off a capture.</b> {@code Custom Amount} is
	 * exact - it was photographed with its tooltip open, {@code Buy Order Quantity / Buy up to 256x. /
	 * Click to specify!} - and the rest are the wording the game uses in front of the player. Each
	 * carries its alternatives, because a button that answers to none of its names highlights
	 * nothing, and this is the one part of the mod not written from measured text. {@code /flip menu}
	 * prints the names of the last menu you opened, which is how they get confirmed.
	 */
	public static final Button CREATE_BUY_ORDER =
			new Button(List.of("Create Buy Order", "Buy Order"), Button.UNANCHORED);
	public static final Button CREATE_SELL_OFFER =
			new Button(List.of("Create Sell Offer", "Sell Offer"), Button.UNANCHORED);
	public static final Button CUSTOM_AMOUNT =
			new Button(List.of("Custom Amount", "Custom amount"), Button.UNANCHORED);
	public static final Button CUSTOM_PRICE =
			new Button(List.of("Custom Price", "Custom price", "Custom Amount Price"),
					Button.UNANCHORED);

	/**
	 * The price page's other button, which posts one increment above the top of the book.
	 *
	 * <p>Photographed live 2026-08-14: {@code Top Order +0.1 / Buy Order Setup / Beat the price of the
	 * top order so yours is filled first. / Ordering: 256x / Unit price: 30,808.0 coins}. It is the
	 * same computation {@code BazaarProduct.outbidBuyOrder} performs, off a book with no poll delay in
	 * front of it, and it needs no sign - so where it offers the plan's price or less it is the button
	 * to press.
	 *
	 * <p><b>Buy wordings only.</b> A sell offer's equivalent undercuts instead of outbidding, and the
	 * rule that decides whether to use this one - "at or under the price the plan quoted" - is upside
	 * down there. Nothing in the NPC worklist sells on the bazaar, so the sell button is left unmatched
	 * rather than matched and mishandled.
	 */
	public static final Button TOP_ORDER_PLUS =
			new Button(List.of("Top Order +0.1", "Best Order +0.1", "Top Order +0.1 coins"),
					Button.UNANCHORED);

	/** The line that button carries the price on, and the only number on it worth reading. */
	private static final Pattern OFFERED_PRICE =
			Pattern.compile("^Unit price: ([\\d,]+(?:\\.\\d+)?) coins$");

	private BazaarSlots() {
	}

	/**
	 * What the button in {@code index} says it will post at, or empty if it does not say.
	 *
	 * <p>Empty is a real answer and the caller falls back to typing the price on the sign. A button
	 * whose lore has been reworded says nothing about its price, and posting at a price read out of a
	 * line that no longer means what it did is worse than typing the number.
	 */
	public static OptionalDouble offeredPrice(CapturedMenu menu, int index) {
		if (menu == null) {
			return OptionalDouble.empty();
		}

		for (CapturedSlot slot : menu.slots()) {
			if (slot.index() != index) {
				continue;
			}

			for (String line : slot.lore()) {
				Matcher matcher = OFFERED_PRICE.matcher(line.trim());

				if (matcher.matches()) {
					return OptionalDouble.of(Double.parseDouble(matcher.group(1).replace(",", "")));
				}
			}
		}

		return OptionalDouble.empty();
	}

	/**
	 * One named button, and where in the last row it was measured.
	 *
	 * @param names   the display names it answers to, matched case-insensitively and whole - a button
	 *                named {@code Cancel Buy Order} must not answer to {@code Buy Order}. More than
	 *                one only where the wording was read off a screenshot rather than a capture, and
	 *                they are tried in order
	 * @param fromEnd its slot counted back from the size of the menu, or {@link #UNANCHORED}
	 */
	public record Button(List<String> names, int fromEnd) {
		public static final int UNANCHORED = -1;

		public Button {
			names = List.copyOf(names);
		}

		public Button(String name, int fromEnd) {
			this(List.of(name), fromEnd);
		}

		/** The first name, which is what a message about this button should say. */
		public String name() {
			return names.getFirst();
		}

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

			for (String name : names) {
				OptionalInt found = named(menu, name);

				if (found.isPresent()) {
					return found;
				}
			}

			return OptionalInt.empty();
		}

		private OptionalInt named(CapturedMenu menu, String name) {
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

		/** Whether this menu carries the button at all, which is how a screen is recognised. */
		public boolean on(CapturedMenu menu) {
			return in(menu).isPresent();
		}
	}

	/**
	 * Which bazaar screen this is, from its title where the title says anything and from its buttons
	 * where it does not.
	 *
	 * <p>Half the bazaar names itself and half does not. Browsing, the orders, the options and both
	 * confirmations are titled unmistakably. A product page is titled
	 * {@code Item Upgrades ➜ Transmission Tuner}, which is a sub-category nobody can enumerate
	 * followed by an item name; the amount page is titled {@code How many do you want?}, which says
	 * nothing about the bazaar at all. Those two are recognised by the buttons on them, which is the
	 * same evidence a click needs anyway.
	 */
	public static Screen screenOf(CapturedMenu menu) {
		if (menu == null) {
			return Screen.UNKNOWN;
		}

		String lower = menu.title().trim().toLowerCase(Locale.ROOT);

		if (OrderMenuParser.isOrdersMenu(menu)) {
			return Screen.ORDERS;
		}

		Screen titled = switch (lower) {
			case "order options" -> Screen.ORDER_OPTIONS;
			case "confirm buy order" -> Screen.CONFIRM_BUY;
			case "confirm sell offer" -> Screen.CONFIRM_SELL;
			default -> lower.startsWith("bazaar") ? Screen.BROWSE : Screen.UNKNOWN;
		};

		if (titled != Screen.UNKNOWN) {
			return titled;
		}

		// The price is asked about first so that a page carrying both buttons reads as the price
		// page, which is the later of the two steps and so the one still to do.
		if (CUSTOM_PRICE.on(menu)) {
			return Screen.PRICE;
		}

		if (CUSTOM_AMOUNT.on(menu)) {
			return Screen.AMOUNT;
		}

		return CREATE_BUY_ORDER.on(menu) || CREATE_SELL_OFFER.on(menu)
				? Screen.PRODUCT
				: Screen.UNKNOWN;
	}

	/** Whether this menu is part of the bazaar at all, by title or by what is on it. */
	public static boolean isBazaarFlow(CapturedMenu menu) {
		return screenOf(menu) != Screen.UNKNOWN;
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
