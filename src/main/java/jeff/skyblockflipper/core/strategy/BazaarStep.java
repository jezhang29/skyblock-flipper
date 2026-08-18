package jeff.skyblockflipper.core.strategy;

import jeff.skyblockflipper.core.model.Stacking;
import jeff.skyblockflipper.core.track.BazaarMenu;
import jeff.skyblockflipper.core.track.BazaarSlots;
import jeff.skyblockflipper.core.track.CapturedMenu;
import jeff.skyblockflipper.core.track.CapturedSlot;
import jeff.skyblockflipper.core.track.OrderMenuParser;
import jeff.skyblockflipper.core.track.TradeEvent;

import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.function.IntFunction;

/**
 * The one click a worklist row is asking for on the menu that is open.
 *
 * <p>{@link NpcWorklist} says what to do - reprice this, place that - and {@link BazaarSlots} says
 * where a named thing is on a menu. This is the join: given a row and an open screen, which slot to
 * point at, which button to press, and what to type if that click opens a sign.
 *
 * <p><b>Nothing here clicks anything.</b> It returns a slot for something else to draw a box behind.
 * The mod does not send inventory packets, and a step it cannot work out is
 * {@link Optional#empty()} - a wrong box is worse than no box, because the player trusts it and
 * clicks.
 *
 * <p><b>Left or right is measured, not assumed.</b> An orders row that has filled says
 * {@code Click to claim!} and {@code Right-Click for options!} in its own lore, and one that has not
 * says {@code Click to view options!} - 494 rows against 2,493 in the capture. So the lore of the row
 * in front of the player decides the click: options are a left click on an unfilled row, and a filled
 * row is claimed with a left click before anything else is done to it.
 *
 * <p><b>The place flow runs the whole way.</b> Search, the item's tile, Create Buy Order, the amount
 * sign, the price sign, confirm. The three middle screens are recognised by their buttons rather
 * than their titles, because a product page is titled {@code Item Upgrades ➜ Transmission Tuner} and
 * the amount page {@code How many do you want?}. Their button names come from screenshots rather
 * than a capture - see {@link BazaarSlots#CREATE_BUY_ORDER} - so each answers to more than one
 * wording, and a wording none of them matches highlights nothing.
 */
public final class BazaarStep {
	/** Which mouse button the step is asking for. */
	public enum Click {
		LEFT,
		RIGHT
	}

	/**
	 * One highlighted slot, and what the player does there.
	 *
	 * @param slot  index into the open menu's own slots, so a renderer can ask the screen where it is
	 * @param click which button, from the lore of the slot itself where that varies
	 * @param label what this click does, short enough for a panel about seventy pixels wide
	 * @param type  what to type on the sign this click opens, or empty when it opens no sign
	 */
	public record Step(int slot, Click click, String label, String type) {
		static Step of(int slot, Click click, String label) {
			return new Step(slot, click, label, "");
		}

		public boolean opensASign() {
			return !type.isEmpty();
		}
	}

	/** The lore line a filled orders row carries, and an unfilled one does not. */
	private static final String CLAIMABLE = "Click to claim!";

	private BazaarStep() {
	}

	/**
	 * Where to click for {@code task} on {@code menu}, or empty if this screen cannot serve it.
	 *
	 * <p>Empty is the ordinary answer most of the time: the player is on the orders menu and the row
	 * is a place, or on a screen nothing has measured. The caller draws nothing and says nothing.
	 *
	 * @param restingPrice the price the order already on the book rests at, which is what tells two
	 *                     rows on one item apart. Pass 0 where the task has no order behind it, or
	 *                     where the caller does not know it - the row is then found only if it is the
	 *                     item's only one
	 */
	public static Optional<Step> next(NpcWorklist.Task task, double restingPrice, CapturedMenu menu) {
		if (task == null || menu == null) {
			return Optional.empty();
		}

		return switch (BazaarSlots.screenOf(menu)) {
			case ORDERS -> onOrders(task, restingPrice, menu);
			case ORDER_OPTIONS -> onOrderOptions(task, menu);
			case BROWSE -> onBrowse(task, menu);
			case PRODUCT -> onProduct(task, menu);
			case AMOUNT -> sign(BazaarSlots.CUSTOM_AMOUNT, menu, "how many",
					String.valueOf(Stacking.firstOrder(task.orderSplit())));
			case PRICE -> onPrice(task, menu);
			case CONFIRM_BUY -> confirm(BazaarSlots.CONFIRM_BUY_ORDER, menu, "confirm the order");
			case CONFIRM_SELL -> confirm(BazaarSlots.CONFIRM_SELL_OFFER, menu, "confirm the offer");
			case UNKNOWN -> Optional.empty();
		};
	}

	/**
	 * One item's page.
	 *
	 * <p>Always the button that opens an order rather than the one that trades instantly. The whole
	 * strategy is resting an order at a price the book has to come to; buying instantly pays the ask,
	 * which is the price the plan was built to avoid.
	 */
	private static Optional<Step> onProduct(NpcWorklist.Task task, CapturedMenu menu) {
		// The page has to be this row's item. Opening something else's page and being told to place
		// an order on it is how the wrong item gets bought - and the title is the only thing on the
		// page that says which item it is.
		if (task.kind() == NpcWorklist.Kind.CLAIM
				|| BazaarMenu.productPageFor(menu.title(), List.of(task.displayName())).isEmpty()) {
			return Optional.empty();
		}

		return at(BazaarSlots.CREATE_BUY_ORDER.in(menu),
				slot -> Step.of(slot, Click.LEFT, "buy order"));
	}

	/**
	 * The page that takes the price: Hypixel's own button where it agrees with the plan, the sign
	 * otherwise.
	 *
	 * <p><b>The button is the better click when it offers the plan's price or less.</b> It posts one
	 * increment above the top of the book, read off the book with no poll between - the same
	 * computation the plan's own price is, and always the fresher of the two. Where it comes out at or
	 * under what the plan quoted, pressing it costs one click instead of a sign, and it cannot be
	 * beaten on queue position by the number the plan wants typed.
	 *
	 * <p><b>Above the plan's price it is the wrong click.</b> The book has moved up since the plan was
	 * priced, and the button would post over the ceiling the margin was worked out against - so the
	 * price gets typed, and the order rests behind the top until the book comes back to it. That is the
	 * strategy: an NPC flip's profit is the gap to a fixed NPC price, and chasing past the plan's price
	 * spends it.
	 *
	 * <p><b>A premium order never uses the button.</b> When the drift premium lifts the plan's price
	 * above the live top of the book ({@link NpcWorklist.Task#postsAboveBook()}), the order is meant to
	 * rest above the book so it holds the top through the window's drift. The button posts one increment
	 * above the current top, which is below the premium price by exactly the premium - pressing it would
	 * spend the whole thing and leave the order at the front for a poll rather than the window. So the
	 * premium price is always typed on the sign, whatever the button offers.
	 */
	private static Optional<Step> onPrice(NpcWorklist.Task task, CapturedMenu menu) {
		if (!task.hasPrice()) {
			return Optional.empty();
		}

		if (task.postsAboveBook()) {
			return sign(BazaarSlots.CUSTOM_PRICE, menu, "price per unit",
					String.format("%.1f", task.price()));
		}

		OptionalInt button = BazaarSlots.TOP_ORDER_PLUS.in(menu);

		if (button.isPresent()) {
			OptionalDouble offered = BazaarSlots.offeredPrice(menu, button.getAsInt());

			if (offered.isPresent() && offered.getAsDouble() <= task.price() + PRICE_EPSILON) {
				return Optional.of(Step.of(button.getAsInt(), Click.LEFT,
						String.format("+0.1, %.1f", offered.getAsDouble())));
			}
		}

		return sign(BazaarSlots.CUSTOM_PRICE, menu, "price per unit",
				String.format("%.1f", task.price()));
	}

	/**
	 * Half of the bazaar's own price increment, which is the finest two prices can differ by.
	 *
	 * <p>The button's price and the plan's are computed the same way off two polls of one book, so
	 * they land on the same tenth of a coin when the book has not moved. This is what stops a
	 * rounding difference in the last digit reading as "the book moved up".
	 */
	private static final double PRICE_EPSILON = 0.05d;

	/**
	 * A page whose button opens a sign, with the number to type on it.
	 *
	 * <p>The amount page offers 1x, 16x, a stack and a custom amount, and the plan's size is almost
	 * never one of the first three - so it is the sign every time. A step with nothing to type is no
	 * step: pointing at a sign without saying what goes on it is the state the player was already in.
	 */
	private static Optional<Step> sign(BazaarSlots.Button button, CapturedMenu menu, String label,
			String type) {
		if (type.isEmpty() || "0".equals(type)) {
			return Optional.empty();
		}

		return at(button.in(menu), slot -> new Step(slot, Click.LEFT, label, type));
	}

	/**
	 * The resting orders. Every row that is not a place starts here.
	 *
	 * <p>A sell offer is a resting SELL and a buy order a resting BUY, and the worklist's rows are
	 * about orders that were placed to buy from the book - so the side is read from the row that is
	 * there rather than assumed from the task.
	 */
	private static Optional<Step> onOrders(NpcWorklist.Task task, double restingPrice,
			CapturedMenu menu) {
		if (task.kind() == NpcWorklist.Kind.PLACE) {
			// Nothing to point at: the order does not exist yet. Manage Orders is where the player
			// already is, and the next click is the one that leaves for the browse page.
			return Optional.empty();
		}

		OptionalInt slot = row(menu, task.displayName(), restingPrice);

		if (slot.isEmpty()) {
			return Optional.empty();
		}

		boolean claimable = claimable(menu, slot.getAsInt());

		if (task.kind() == NpcWorklist.Kind.CLAIM) {
			return claimable
					? Optional.of(Step.of(slot.getAsInt(), Click.LEFT, "claim"))
					: Optional.empty();
		}

		// A row with items waiting is claimed before it is anything else. Reported live 2026-08-14 on
		// a Flaming Heart reprice: the filled units have to come out of the order first, and clicking
		// the row claims all of them, so the claim is not a step the player can skip past on the way
		// to the options menu. The worklist emits its own CLAIM row for this whenever the tracker
		// knows about the fill; where it does not - filled with the client closed, or an order it
		// never saw opened - the row's own lore is the only thing that says so, and this is where it
		// is read.
		if (claimable) {
			return Optional.of(Step.of(slot.getAsInt(), Click.LEFT,
					task.kind() == NpcWorklist.Kind.CANCEL
							? "claim first, then cancel"
							: "claim first, then reprice"));
		}

		return Optional.of(Step.of(slot.getAsInt(), Click.LEFT,
				task.kind() == NpcWorklist.Kind.CANCEL ? "open, then cancel" : "open, then reprice"));
	}

	/** Both sides are tried because the orders menu names the side and the worklist does not. */
	private static OptionalInt row(CapturedMenu menu, String displayName, double restingPrice) {
		OptionalInt sell = OrderMenuParser.slotOf(menu, TradeEvent.Side.SELL, displayName, restingPrice);
		return sell.isPresent()
				? sell
				: OrderMenuParser.slotOf(menu, TradeEvent.Side.BUY, displayName, restingPrice);
	}

	private static boolean claimable(CapturedMenu menu, int index) {
		for (CapturedSlot slot : menu.slots()) {
			if (slot.index() == index) {
				return slot.lore().contains(CLAIMABLE);
			}
		}

		return false;
	}

	private static Optional<Step> onOrderOptions(NpcWorklist.Task task, CapturedMenu menu) {
		if (task.kind() != NpcWorklist.Kind.CANCEL && task.kind() != NpcWorklist.Kind.REPRICE) {
			return Optional.empty();
		}

		// Both end here. A reprice is a cancel and then a fresh order at the new price, which is what
		// the panel's price column is for; Flip Order is a different trade and is never pointed at.
		String label = task.kind() == NpcWorklist.Kind.CANCEL ? "cancel" : "cancel, then re-post";

		return at(BazaarSlots.CANCEL_ORDER.in(menu), slot -> Step.of(slot, Click.LEFT, label));
	}

	/**
	 * A browse or search page.
	 *
	 * <p>The item's own tile if this page is showing it, and the Search button otherwise. Browsing by
	 * category would take three clicks and land on a page the mod would then have to page through;
	 * the search sign takes the name straight there.
	 */
	private static Optional<Step> onBrowse(NpcWorklist.Task task, CapturedMenu menu) {
		if (task.kind() == NpcWorklist.Kind.CLAIM) {
			return at(BazaarSlots.MANAGE_ORDERS.in(menu),
					slot -> Step.of(slot, Click.LEFT, "your orders"));
		}

		OptionalInt tile = BazaarSlots.productTile(menu, task.itemId(), task.displayName());

		if (tile.isPresent()) {
			return Optional.of(Step.of(tile.getAsInt(), Click.LEFT, "open " + task.displayName()));
		}

		return at(BazaarSlots.SEARCH.in(menu),
				slot -> new Step(slot, Click.LEFT, "search", task.displayName()));
	}

	private static Optional<Step> confirm(BazaarSlots.Button button, CapturedMenu menu, String label) {
		return at(button.in(menu), slot -> Step.of(slot, Click.LEFT, label));
	}

	/** A step built from a slot that was found, and nothing from one that was not. */
	private static Optional<Step> at(OptionalInt slot, IntFunction<Step> step) {
		return slot.isPresent() ? Optional.of(step.apply(slot.getAsInt())) : Optional.empty();
	}
}
