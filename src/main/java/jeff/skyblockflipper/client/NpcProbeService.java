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
import jeff.skyblockflipper.core.api.MarketData;
import jeff.skyblockflipper.core.model.BazaarProduct;
import jeff.skyblockflipper.core.strategy.NpcProbe;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;

/**
 * The open {@link NpcProbe}s, sampled every time the book moves.
 *
 * <p>Owns the instances and the clock, the same split {@link NpcRoundService} uses: a probe is a
 * record in {@code core} with {@code now} passed in, and this decides when a poll counts as a
 * sample.
 *
 * <p><b>One per item, several at once.</b> The question a probe asks is per item - the competition
 * on Revenant Catalyst has nothing to do with the competition on Bronze Bowl - and answering it for
 * one item at a time would take one session per item. A basket is what the premium would be turned
 * on for, so a basket is what has to be measurable in one night.
 *
 * <p><b>Sampled on the bazaar revision rather than on a timer.</b> That is the same signal
 * {@link CandidateFeed} rebuilds on, so a sample is one reading of one poll and the poll rate is the
 * sample rate. Ticking on wall-clock time would count the same snapshot several times and report a
 * share of polls that was really a share of ticks.
 *
 * <p><b>A filled order stops its own probe.</b> Fills come from {@link TrackerService}, which reads
 * them off chat and the orders menu. Without that signal the book alone reports a fill as a
 * permanent success - the order is gone, so nothing is above it ever again. See
 * {@link NpcProbe#filled}.
 *
 * <p><b>Memory only.</b> A probe answers a question about one session and does not survive a
 * restart, which the command says when it opens one. Persisting it would mean deciding what a probe
 * means across a gap in which the order may have been filled, cancelled or outbid unobserved, and
 * the whole point of the measurement is that every moment of it was watched.
 *
 * <p>Client thread only.
 */
public final class NpcProbeService {
	/** Item id to probe, in the order they were opened, which is the order they are reported in. */
	private static final Map<String, NpcProbe> PROBES = new LinkedHashMap<>();

	/** The book revision the last sample was taken at, so one poll counts once. */
	private static long sampledRevision = -1L;

	private NpcProbeService() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(client -> sampleIfStale());
	}

	/** Every open probe, oldest first. */
	public static List<NpcProbe> all() {
		return List.copyOf(PROBES.values());
	}

	public static Optional<NpcProbe> forItem(String itemId) {
		return Optional.ofNullable(PROBES.get(itemId));
	}

	public static boolean isEmpty() {
		return PROBES.isEmpty();
	}

	/** Opens a probe on {@code itemId}, replacing any already running on that same item. */
	public static NpcProbe open(String itemId, String displayName, double price, double premium) {
		NpcProbe opened = NpcProbe.opened(itemId, displayName, price, premium,
				System.currentTimeMillis());

		PROBES.put(itemId, opened);
		sampledRevision = MarketDataService.data().bazaarRevision();

		return opened;
	}

	/** Ends one item's probe, returning what it had found. */
	public static Optional<NpcProbe> stop(String itemId) {
		return Optional.ofNullable(PROBES.remove(itemId));
	}

	/** Ends every probe, returning what they had found. */
	public static List<NpcProbe> stopAll() {
		List<NpcProbe> ending = all();

		PROBES.clear();
		sampledRevision = -1L;

		return ending;
	}

	private static void sampleIfStale() {
		if (PROBES.isEmpty()) {
			return;
		}

		MarketData data = MarketDataService.data();
		long revision = data.bazaarRevision();

		if (revision == sampledRevision) {
			return;
		}

		sampledRevision = revision;
		long now = System.currentTimeMillis();

		for (NpcProbe probe : new ArrayList<>(PROBES.values())) {
			PROBES.put(probe.itemId(), sample(probe, data, now));
		}
	}

	private static NpcProbe sample(NpcProbe probe, MarketData data, long now) {
		if (probe.isFilled()) {
			return probe;
		}

		// Checked before the book, because the book cannot tell a filled order from an unbeatable
		// one: both look like nothing bidding above your price.
		if (filledSinceOpening(probe)) {
			return probe.filled(now);
		}

		// The probe's own order is in this book, so the top bid is the probe's price until somebody
		// posts above it. A product that has left the snapshot entirely is not evidence of being
		// outbid, so it is skipped rather than counted against the probe.
		OptionalDouble topBid = data.bazaar().product(probe.itemId())
				.map(BazaarProduct::instantSellPrice)
				.orElse(OptionalDouble.empty());

		return topBid.isPresent() ? probe.sample(topBid.getAsDouble(), now) : probe;
	}

	/**
	 * Whether the tracker has seen this item fill since the probe was opened.
	 *
	 * <p>Item-level rather than order-level, which is the resolution the tracker works at: another
	 * buy order of your own filling on the same item ends the probe early. That is why the command
	 * says not to probe an item you are also trading.
	 */
	private static boolean filledSinceOpening(NpcProbe probe) {
		if (!TrackerService.enabled()) {
			return false;
		}

		Set<String> filled = TrackerService.filledSince(probe.startedAt());

		return filled.contains(probe.itemId());
	}
}
