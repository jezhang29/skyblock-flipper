package jeff.skyblockflipper.core.strategy;

import jeff.skyblockflipper.core.track.BazaarSlots;
import jeff.skyblockflipper.core.track.CapturedMenu;
import jeff.skyblockflipper.core.track.CapturedSlot;
import jeff.skyblockflipper.core.track.OrderMenuParser;
import jeff.skyblockflipper.core.track.TradeEvent;

import java.util.Optional;
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
 * says {@code Click to view options!} - 494 rows against 2,493 in the capture. So claiming is a left
 * click on a filled row and cancelling one is a right click on the same row, and the lore of the row
 * in front of the player decides which.
 *
 * <p><b>Three screens are still missing.</b> A product page and the amount and price pages behind it
 * are titled with the item's own name and were never captured, so a place stops at the search
 * result. See {@code docs/trade-capture.md} for the session that finishes it.
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
			case CONFIRM_BUY -> confirm(BazaarSlots.CONFIRM_BUY_ORDER, menu, "confirm the order");
			case CONFIRM_SELL -> confirm(BazaarSlots.CONFIRM_SELL_OFFER, menu, "confirm the offer");
			case UNKNOWN -> Optional.empty();
		};
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

		// A filled row hands its left click to claiming, so options move to the right button. The row
		// itself says which of the two it is.
		return Optional.of(Step.of(slot.getAsInt(), claimable ? Click.RIGHT : Click.LEFT,
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
