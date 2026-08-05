package jeff.skyblockflipper.core.track;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the bazaar orders menu into {@link OrderSnapshot}s.
 *
 * <p>Written against the lore in {@code trade-capture-sample.jsonl} and nothing else. Every line
 * this looks for was measured; a slot missing one of them is skipped rather than guessed at.
 *
 * <p>The menu is the only place two things can be seen at all. A fill that happened while the client
 * was closed sends no chat line ever, and a partial fill sends no {@code was filled!} notification,
 * so an order can be sitting on collectable coins with nothing in the log to say so.
 */
public final class OrderMenuParser {
	/** Both the solo and the co-op menu end this way; the co-op one also lists other members. */
	private static final String TITLE_SUFFIX = "Bazaar Orders";

	private static final Pattern SIDE = Pattern.compile("^(BUY|SELL) (.+)$");

	/** {@code Offer amount: 1,344x} on a sell, {@code Order amount: 8x} on a buy. */
	private static final Pattern AMOUNT = Pattern.compile("^(?:Offer|Order) amount: ([\\d,]+)x$");

	/**
	 * {@code Filled: 8/8 100%!} when complete and {@code Filled: 903/1.3k (67.2%)} when not. Only the
	 * numerator is read: the denominator is abbreviated once it passes a thousand, so the total has
	 * to come from the amount line.
	 */
	private static final Pattern FILLED = Pattern.compile("^Filled: ([\\d,]+)/.*$");

	private static final Pattern UNIT_PRICE = Pattern.compile("^Price per unit: ([\\d,]+(?:\\.\\d+)?) coins$");

	/** {@code By: [MVP+] Bee__Bot}, and the rank tag is optional because not every player has one. */
	private static final Pattern OWNER = Pattern.compile("^By: (?:\\[[^]]+] )?(\\S+)$");

	private static final Pattern CLAIM_COINS = Pattern.compile("^You have ([\\d,]+) coins to claim!$");

	private static final Pattern CLAIM_ITEMS = Pattern.compile("^You have ([\\d,]+) items? to claim!$");

	private OrderMenuParser() {
	}

	/** True if this snapshot is the orders menu at all, in either its solo or co-op form. */
	public static boolean isOrdersMenu(CapturedMenu menu) {
		return menu != null && menu.title().endsWith(TITLE_SUFFIX);
	}

	/**
	 * Every order the menu shows, in slot order, including other co-op members'.
	 *
	 * <p>Filtering to your own is {@link #ownedBy}, kept separate because the caller is the only
	 * thing that knows the player's name.
	 */
	public static List<OrderSnapshot> parse(CapturedMenu menu) {
		if (!isOrdersMenu(menu)) {
			return List.of();
		}

		List<OrderSnapshot> orders = new ArrayList<>();

		for (CapturedSlot slot : menu.slots()) {
			read(menu.at(), slot).ifPresent(orders::add);
		}

		return List.copyOf(orders);
	}

	/**
	 * The subset placed by one player.
	 *
	 * @param player the in-game name with no rank tag, matched case-insensitively because the menu
	 *               is the only source for it and a mismatch would silently claim you own nothing
	 */
	public static List<OrderSnapshot> ownedBy(List<OrderSnapshot> orders, String player) {
		if (player == null || player.isBlank()) {
			return List.of();
		}

		return orders.stream().filter(o -> o.owner().equalsIgnoreCase(player)).toList();
	}

	private static Optional<OrderSnapshot> read(long at, CapturedSlot slot) {
		Matcher name = SIDE.matcher(slot.name());

		// Everything else in the menu - the filler, Go Back, Claim All Coins - fails here, which is
		// why the side prefix is what selects an order rather than a slot index. Hypixel repacks the
		// rows as orders come and go.
		if (!name.matches()) {
			return Optional.empty();
		}

		TradeEvent.Side side = name.group(1).equals("BUY") ? TradeEvent.Side.BUY : TradeEvent.Side.SELL;
		long total = 0L;
		long filled = 0L;
		double unitPrice = 0.0d;
		double claimCoins = 0.0d;
		long claimItems = 0L;
		String owner = "";

		for (String line : slot.lore()) {
			Matcher matcher = AMOUNT.matcher(line);

			if (matcher.matches()) {
				total = integer(matcher.group(1));
				continue;
			}

			matcher = FILLED.matcher(line);

			if (matcher.matches()) {
				filled = integer(matcher.group(1));
				continue;
			}

			matcher = UNIT_PRICE.matcher(line);

			if (matcher.matches()) {
				unitPrice = number(matcher.group(1));
				continue;
			}

			matcher = OWNER.matcher(line);

			if (matcher.matches()) {
				owner = matcher.group(1);
				continue;
			}

			matcher = CLAIM_COINS.matcher(line);

			if (matcher.matches()) {
				claimCoins = number(matcher.group(1));
				continue;
			}

			matcher = CLAIM_ITEMS.matcher(line);

			if (matcher.matches()) {
				claimItems = integer(matcher.group(1));
			}
		}

		// An order with no amount line is not an order this can say anything useful about, and a
		// zero total would divide badly downstream.
		if (total == 0L) {
			return Optional.empty();
		}

		return Optional.of(new OrderSnapshot(at, side, slot.itemId(), name.group(2), owner,
				total, filled, unitPrice, claimCoins, claimItems));
	}

	private static long integer(String text) {
		return Long.parseLong(text.replace(",", ""));
	}

	private static double number(String text) {
		return Double.parseDouble(text.replace(",", ""));
	}
}
