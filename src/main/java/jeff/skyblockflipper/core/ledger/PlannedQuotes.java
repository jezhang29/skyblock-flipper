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
package jeff.skyblockflipper.core.ledger;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * What the mod has recently told the player to buy, so a fill can be matched back to the promise.
 *
 * <p>Without this the ledger can only recognise a trade it was told about by hand: {@code Ledger}
 * matches a buy against an open {@code MANUAL} entry, and the only things that open one are
 * {@code /flip take} and the Take button, both of which read the ranked candidate list. The NPC
 * basket is not that list and has no Take button, so on an account flipping NPC baskets full time
 * every buy arrived unquoted, was dropped by {@code trackUnquotedTrades}, and the ledger stayed
 * empty - taking the capture rate, the fill rate and the daily NPC cap down with it.
 *
 * <p><b>The quote has to be kept from when it was given, not looked up when the fill lands.</b> A
 * buy settles at the claim, which is hours after the advice; by then {@code NpcBasket} has dropped
 * the item from the basket entirely, because an item you already hold is
 * {@code NpcReprice}'s to talk about. Asking the current basket what it thinks of an item you are
 * holding therefore always misses, and misses precisely because the advice was followed.
 *
 * <p>Entries expire on {@link #window}, which should be the resting window: a plan you acted on a
 * day later is not the plan that was quoted, and holding it forever would credit an old promise for
 * a trade taken on today's book. Re-quoting an item replaces its entry, so what is held is always
 * the most recent thing the player was actually shown.
 *
 * <p>Not thread-safe and not persisted. Both are deliberate: it is written and read on the client
 * thread like the ledger itself, and a quote that did not survive a restart is a quote the player
 * was not shown this session.
 */
public final class PlannedQuotes {
	private final Map<String, Entry> quotes = new HashMap<>();
	private final Supplier<Duration> window;

	private record Entry(Quote quote, long at) {
	}

	/**
	 * @param window how long a quote stays good for, read at use time rather than held. The resting
	 *               window is the meaningful length, and {@code /flip reload} may change it, so
	 *               taking a value here would pin the store to whatever the setting was at startup
	 */
	public PlannedQuotes(Supplier<Duration> window) {
		this.window = window == null ? () -> Duration.ZERO : window;
	}

	/** Remembers what the player was just told about this item, replacing any older promise. */
	public void quoted(Quote quote, long now) {
		if (quote == null || quote.itemId().isEmpty()) {
			return;
		}

		quotes.put(quote.itemId(), new Entry(quote, now));
	}

	/**
	 * The promise still standing for this item, if there is one.
	 *
	 * <p>Matched on id, and on display name only when there is no id to match on - the same rule
	 * {@code Ledger} settles trades by, and for the same reason: an item the tracker never saw in a
	 * menu carries no id, and treating that empty string as a key would hand every such trade the
	 * first quote in the map.
	 */
	public Optional<Quote> quoteFor(String itemId, String displayName, long now) {
		Entry entry = itemId != null && !itemId.isEmpty() ? quotes.get(itemId) : null;

		if (entry == null && displayName != null && !displayName.isEmpty()) {
			entry = quotes.values().stream()
					.filter(candidate -> candidate.quote().displayName().equals(displayName))
					.findFirst()
					.orElse(null);
		}

		if (entry == null) {
			return Optional.empty();
		}

		return expired(entry, now) ? Optional.empty() : Optional.of(entry.quote());
	}

	/** Drops what has gone stale. Called on write paths so the map cannot grow without bound. */
	public void prune(long now) {
		quotes.values().removeIf(entry -> expired(entry, now));
	}

	public int size() {
		return quotes.size();
	}

	private boolean expired(Entry entry, long now) {
		Duration valid = window.get();

		return valid == null || now - entry.at() > valid.toMillis();
	}
}
