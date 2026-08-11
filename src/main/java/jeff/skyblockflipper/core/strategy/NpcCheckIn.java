package jeff.skyblockflipper.core.strategy;

import java.util.List;
import java.util.Optional;

/**
 * Whether a resting basket is worth interrupting the player for.
 *
 * <p>{@link NpcReprice} answers "what do I do with these orders" for a player who has already
 * decided to come back. This answers the question before it: is it time to come back at all. The
 * two are separate because most of the money in this trade is in returning - 11.5M per eight-hour
 * cycle posting once and walking away against 59.7M returning every 30 minutes - and a player who
 * forgets to return is quoting five times what they will make.
 *
 * <p><b>Elapsed time is not on its own a reason to interrupt anyone.</b> A reminder that fires
 * every check-in interval whether or not anything has moved is trained away within a session, and
 * then the one that mattered is ignored too. So this fires on the book having moved past the
 * orders, and the interval is only a rate limit on saying so.
 *
 * <p>Pure and in {@code core} for the same reason the rest of the money math is: the rule for what
 * deserves an interruption is worth a test, and the clock and the chat line around it are not.
 */
public final class NpcCheckIn {
	private NpcCheckIn() {
	}

	/**
	 * What is waiting, as a summary rather than a list. The list is what {@code /flip npc reprice}
	 * is for, and printing it unasked would be the same interruption twice.
	 *
	 * @param repriceCount  orders that have been outbid and are still worth chasing
	 * @param cancelCount   orders the book has chased past the stop
	 * @param profitAtStake coins the reprices are still worth if they fill at the advised price
	 * @param capitalToFree coins the cancels would hand back, which are otherwise parked in a trade
	 *                      that can no longer pay
	 */
	public record Due(int repriceCount, int cancelCount, double profitAtStake, long capitalToFree) {
		public int orders() {
			return repriceCount + cancelCount;
		}
	}

	/**
	 * The reason to interrupt, if there is one.
	 *
	 * <p>Two ways to qualify, because a check-in is one trip that settles every order at once:
	 *
	 * <ul>
	 *   <li><b>Any cancel.</b> The book has caught the NPC price on that item, so the coins are
	 *       parked in a trade that cannot pay and an order slot is held by it. That is worth saying
	 *       however small it is, because the fix frees the binding resource.
	 *   <li><b>Reprices worth {@code minProfit} in total.</b> Summed rather than judged one at a
	 *       time: eight orders worth 20k each are a trip worth making even where one is not.
	 * </ul>
	 *
	 * @param minProfit the same floor a single flip has to clear to be worth showing at all,
	 *                  {@code FlipperConfig.minProfitPerFlip}
	 */
	public static Optional<Due> due(List<NpcReprice.Advice> advice, double minProfit) {
		int reprices = 0;
		int cancels = 0;
		double profit = 0.0d;
		long capital = 0L;

		for (NpcReprice.Advice entry : advice) {
			switch (entry.action()) {
				case REPRICE -> {
					reprices++;
					profit += entry.profitAtStake();
				}
				case CANCEL -> {
					cancels++;
					capital += entry.capitalAtStake();
				}
				case HOLD -> {
				}
			}
		}

		if (cancels == 0 && (reprices == 0 || profit < minProfit)) {
			return Optional.empty();
		}

		return Optional.of(new Due(reprices, cancels, profit, capital));
	}
}
