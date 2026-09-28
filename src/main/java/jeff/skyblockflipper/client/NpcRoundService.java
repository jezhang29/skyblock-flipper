/*
 * Skyblock Flipper - a Hypixel Skyblock flipping advisor mod.
 * Copyright (C) 2026 SoupChugger
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package jeff.skyblockflipper.client;

import jeff.skyblockflipper.client.track.TrackerService;
import jeff.skyblockflipper.core.config.FlipperConfig;
import jeff.skyblockflipper.core.strategy.NpcReprice;
import jeff.skyblockflipper.core.strategy.NpcRound;

import java.time.Duration;
import java.util.List;

/**
 * The one open {@link NpcRound}, and the clock that supersedes it.
 *
 * <p>{@code NpcRound} is a record in {@code core} with {@code now} passed in, which leaves somebody
 * to own the instance and decide when a new one replaces it. That is this, and it is a single
 * instance on purpose: the panel beside the bazaar menu, the Basket tab, {@code /flip npc reprice}
 * and the reminder are four views of one trip, and a round fetched separately by each of them would
 * let two of them quote prices frozen at different moments.
 *
 * <p><b>Opened lazily, on the interval and nothing else.</b> A round is not opened because the book
 * moved, because a menu was closed, or because the last one was finished - the interval is the rate
 * limit on opening at all, which is what stops a worked round from immediately producing another and
 * putting the mod back to chasing every book move. {@link NpcRound#elapsed} is the whole rule.
 *
 * <p>Client thread only, like {@link CandidateFeed}: every caller is a tick or a render path.
 */
public final class NpcRoundService {
	/** The round in hand, or null when there is nothing to freeze one over. */
	private static NpcRound round;

	private NpcRoundService() {
	}

	/**
	 * The round to work from, opening a fresh one where the last has run out.
	 *
	 * <p>Cheap on the common path: an open round costs one comparison, so a tick handler may call
	 * this every tick. Opening runs a review over the resting orders only - not a pass over the book
	 * - and happens once per interval.
	 *
	 * <p>Null in three cases, all of which mean the same thing: there is no basket on the book to be
	 * on a clock about. Tracking off, no book to price against, or nothing resting. A caller handed
	 * null reviews straight off the book, which is what it honestly has.
	 */
	public static NpcRound current() {
		long now = System.currentTimeMillis();

		if (round != null && !round.elapsed(now)) {
			return round;
		}

		// A round whose interval has run out is dropped rather than kept until a new one can be
		// opened. Its prices were frozen against a book an interval ago, and a stale number typed
		// into a price box is worse than no number at all.
		round = null;

		FlipperConfig config = SkyblockFlipperClient.config();

		if (!TrackerService.enabled() || !MarketDataService.data().hasBazaar()) {
			return null;
		}

		// Only the orders the NPC side owns: a craft ingredient or combine source order must not enter
		// a round, or its reprice would be frozen and offered as an NPC click for the interval.
		List<NpcReprice.Order> resting = FlipIntentsService.mine(
				TrackerService.restingBuyOrders(), now);

		// Nothing on the book: opening here would spend the interval on an empty round, and the mod
		// would then be inside a round for the whole half hour after the first basket is placed.
		if (resting.isEmpty()) {
			return null;
		}

		round = NpcRound.open(now, Duration.ofMinutes(config.npcCheckInMinutes),
				NpcReprice.review(resting, CandidateFeed.context(), now));

		return round;
	}

	/**
	 * Drops the open round, so the next call opens one against the current settings.
	 *
	 * <p>For {@code /flip reload} and the settings screen. A round freezes the interval along with
	 * the prices, so an edit to {@code npcCheckInMinutes} would otherwise not be felt until the round
	 * that was opened under the old one ended - which is correct while the player is working a list,
	 * and wrong when they have just gone into the settings to change it.
	 */
	public static void clear() {
		round = null;
	}
}
