package jeff.skyblockflipper.core.track;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a Hypixel chat line into a {@link TradeEvent}.
 *
 * <p>Every pattern here was copied out of a recorded capture session rather than remembered, and
 * {@code trade-capture-sample.jsonl} is the session. Wordings that were never observed are not
 * guessed at: an unrecognised line returns empty, which is the correct answer for the hundreds of
 * lines the loose capture filter also lets through.
 *
 * <p>Two rules the wording forces:
 *
 * <ul>
 * <li>Every pattern is anchored at the start of the line. Hypixel prefixes player chat with the
 *     speaker, so an unanchored match on {@code You purchased ...} lets any player in the lobby
 *     write a trade into your ledger by typing it.
 * <li>Only second-person lines are parsed. The public broadcast forms - {@code [MVP+] Name created
 *     a BIN auction for ... at 970,000 coins!} - are the only lines that carry a listing price, and
 *     they are emitted for every player's listing, so the price is unusable and the line is
 *     ignored.
 * </ul>
 */
public final class ChatParser {
	/** A coin or unit figure: thousands separators, and a decimal part on prices under a thousand. */
	private static final String NUMBER = "([\\d,]+(?:\\.\\d+)?)";

	private static final Pattern FORMATTING = Pattern.compile("§.");

	private static final Pattern ORDER_PLACED = Pattern.compile(
			"^\\[Bazaar] (Buy Order|Sell Offer) Setup! " + NUMBER + "x (.+?) for " + NUMBER + " coins\\.$");

	private static final Pattern ORDER_FILLED = Pattern.compile(
			"^\\[Bazaar] Your (Buy Order|Sell Offer) for " + NUMBER + "x (.+?) was filled!$");

	private static final Pattern SELL_CLAIMED = Pattern.compile(
			"^\\[Bazaar] Claimed " + NUMBER + " coins from selling " + NUMBER + "x (.+?) at " + NUMBER + " each!$");

	private static final Pattern BUY_CLAIMED = Pattern.compile(
			"^\\[Bazaar] Claimed " + NUMBER + "x (.+?) worth " + NUMBER + " coins bought for " + NUMBER + " each!$");

	private static final Pattern ORDER_CANCELLED = Pattern.compile(
			"^\\[Bazaar] Cancelled! Refunded " + NUMBER + "x (.+?) from cancelling (Buy Order|Sell Offer)!$");

	private static final Pattern INSTANT = Pattern.compile(
			"^\\[Bazaar] (Bought|Sold) " + NUMBER + "x (.+?) for " + NUMBER + " coins!$");

	private static final Pattern AUCTION_BOUGHT = Pattern.compile(
			"^You purchased (.+?) for " + NUMBER + " coins!$");

	private static final Pattern AUCTION_SOLD = Pattern.compile(
			"^You collected " + NUMBER + " coins from selling (.+?) to .+ in an auction!$");

	private static final Pattern AUCTION_LISTED = Pattern.compile(
			"^BIN Auction started for (.+?)!$");

	private ChatParser() {
	}

	/**
	 * @param at   when the line arrived, carried through onto the event
	 * @param line the raw line; formatting codes are stripped here because Hypixel sends the fill
	 *             notifications with their colours intact and everything else without
	 */
	public static Optional<TradeEvent> parse(long at, String line) {
		if (line == null) {
			return Optional.empty();
		}

		String text = FORMATTING.matcher(line).replaceAll("").trim();

		Matcher matcher = ORDER_PLACED.matcher(text);

		if (matcher.matches()) {
			return event(at, TradeEvent.Kind.ORDER_PLACED, side(matcher.group(1)), matcher.group(3),
					number(matcher.group(2)), number(matcher.group(4)), 0.0d);
		}

		matcher = ORDER_FILLED.matcher(text);

		if (matcher.matches()) {
			return event(at, TradeEvent.Kind.ORDER_FILLED, side(matcher.group(1)), matcher.group(3),
					number(matcher.group(2)), 0.0d, 0.0d);
		}

		matcher = SELL_CLAIMED.matcher(text);

		if (matcher.matches()) {
			return event(at, TradeEvent.Kind.ORDER_CLAIMED, TradeEvent.Side.SELL, matcher.group(3),
					number(matcher.group(2)), number(matcher.group(1)), number(matcher.group(4)));
		}

		matcher = BUY_CLAIMED.matcher(text);

		if (matcher.matches()) {
			return event(at, TradeEvent.Kind.ORDER_CLAIMED, TradeEvent.Side.BUY, matcher.group(2),
					number(matcher.group(1)), number(matcher.group(3)), number(matcher.group(4)));
		}

		matcher = ORDER_CANCELLED.matcher(text);

		if (matcher.matches()) {
			return event(at, TradeEvent.Kind.ORDER_CANCELLED, side(matcher.group(3)), matcher.group(2),
					number(matcher.group(1)), 0.0d, 0.0d);
		}

		matcher = INSTANT.matcher(text);

		if (matcher.matches()) {
			TradeEvent.Side side = matcher.group(1).equals("Bought")
					? TradeEvent.Side.BUY
					: TradeEvent.Side.SELL;
			return event(at, TradeEvent.Kind.INSTANT, side, matcher.group(3),
					number(matcher.group(2)), number(matcher.group(4)), 0.0d);
		}

		matcher = AUCTION_BOUGHT.matcher(text);

		if (matcher.matches()) {
			return event(at, TradeEvent.Kind.AUCTION_BOUGHT, TradeEvent.Side.BUY, matcher.group(1),
					1L, number(matcher.group(2)), 0.0d);
		}

		matcher = AUCTION_SOLD.matcher(text);

		if (matcher.matches()) {
			return event(at, TradeEvent.Kind.AUCTION_SOLD, TradeEvent.Side.SELL, matcher.group(2),
					1L, number(matcher.group(1)), 0.0d);
		}

		matcher = AUCTION_LISTED.matcher(text);

		if (matcher.matches()) {
			return event(at, TradeEvent.Kind.AUCTION_LISTED, TradeEvent.Side.SELL, matcher.group(1),
					1L, 0.0d, 0.0d);
		}

		return Optional.empty();
	}

	private static Optional<TradeEvent> event(long at, TradeEvent.Kind kind, TradeEvent.Side side,
			String displayName, double units, double coins, double unitPrice) {
		return Optional.of(new TradeEvent(at, kind, side, displayName, Math.round(units), coins, unitPrice));
	}

	private static TradeEvent.Side side(String label) {
		return label.equals("Buy Order") ? TradeEvent.Side.BUY : TradeEvent.Side.SELL;
	}

	private static double number(String text) {
		return Double.parseDouble(text.replace(",", ""));
	}
}
