package jeff.skyblockflipper.core.strategy;

import jeff.skyblockflipper.core.track.TradeEvent;

/**
 * One bazaar click a panel row wants next, abstracted from what produced it.
 *
 * <p>{@link BazaarStep} works out which slot of an open menu to point at, and it used to take an
 * {@link NpcWorklist.Task} - so the green box only ever followed the NPC basket. A worked craft,
 * combine, fusion or spread rests and offers on the same screens, so its steps become the same
 * action here and the box follows whichever type the overlay is showing.
 *
 * <p><b>The side is carried, because buy and sell diverge.</b> A buy order and a sell offer are two
 * different buttons on the product page and two different confirmations, and a buy's price can be
 * posted with Hypixel's own "+0.1" button where a sell's cannot. So the action says which side it is,
 * and {@link BazaarStep} branches on it.
 *
 * <p><b>Only measured screens are guided.</b> A worked job's instant-buy and its off-bazaar transform
 * have no {@link #of(WorkedJob.Step) action} at all: instant buy pays the ask on screens no capture
 * has confirmed, and a transform leaves the bazaar. A box behind a slot nobody measured would be
 * clicked, so both draw nothing.
 *
 * @param price      what to type into the price box, or 0 where the click has no price - a claim and
 *                   a cancel are both a button
 * @param orderSplit how the units divide into orders the bazaar will accept, e.g. {@code 3 x 256 + 112}
 */
public record BazaarAction(Verb verb, TradeEvent.Side side, String itemId, String displayName,
		double price, String orderSplit) {
	/** What the click does, which is what decides the screen flow it belongs to. */
	public enum Verb {
		/** Rest a new order - a buy order or a sell offer, per {@link BazaarAction#side()}. */
		PLACE,

		/** Collect filled units off a resting order. */
		CLAIM,

		/** Take a resting order off the book. */
		CANCEL,

		/** Cancel a resting order and re-post it at a new price. */
		REPRICE
	}

	/** Whether there is a number worth typing into the price box. */
	public boolean hasPrice() {
		return price > 0.0d && (verb == Verb.PLACE || verb == Verb.REPRICE);
	}

	/** An NPC worklist task as a bazaar action, or null for a hold, which needs no click. */
	public static BazaarAction of(NpcWorklist.Task task) {
		if (task == null) {
			return null;
		}

		Verb verb = switch (task.kind()) {
			case CLAIM -> Verb.CLAIM;
			case CANCEL -> Verb.CANCEL;
			case REPRICE -> Verb.REPRICE;
			case PLACE -> Verb.PLACE;
			case HOLD -> null;
		};

		return verb == null
				? null
				: new BazaarAction(verb, TradeEvent.Side.BUY, task.itemId(), task.displayName(),
						task.price(), task.orderSplit());
	}

	/** A worked job's step as a bazaar action, or null for a step with no measured bazaar click. */
	public static BazaarAction of(WorkedJob.Step step) {
		if (step == null) {
			return null;
		}

		return switch (step.stage()) {
			case BUY_ORDER -> new BazaarAction(Verb.PLACE, TradeEvent.Side.BUY, step.itemId(),
					step.displayName(), step.price(), step.orderSplit());
			case SELL_OFFER -> new BazaarAction(Verb.PLACE, TradeEvent.Side.SELL, step.itemId(),
					step.displayName(), step.price(), step.orderSplit());
			// Instant buy pays the ask on screens no capture has confirmed, and a transform leaves the
			// bazaar entirely. Neither is guided.
			case INSTANT_BUY, TRANSFORM -> null;
		};
	}
}
