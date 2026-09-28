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
package jeff.skyblockflipper.core.valuation;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Every product's NPC edge history at one instant, frozen so readers never need a lock.
 *
 * <p>{@link NpcEdgeHistory} is filled by streaming three days of tape on the maintenance thread and
 * then thrown away; this is what it leaves behind on {@code MarketData} for the strategies and the
 * UI to read, the same arrangement {@link TrendSnapshot} has with {@link PriceHistory}.
 *
 * @param edges   per-product history, including products with too few samples to report. Filtering
 *                happens in {@link #edgeFor(String)} rather than here, so {@link #size()} can say
 *                how much of the bazaar was considered and not only how much of it answered
 * @param window  how far back the tape was read
 * @param samples total samples behind these figures, summed once here so {@code /flip status} can
 *                report depth without walking the map
 * @param builtAt when this was computed
 */
public record NpcEdgeSnapshot(
		Map<String, NpcEdge> edges,
		Duration window,
		int samples,
		Instant builtAt
) {
	public NpcEdgeSnapshot {
		edges = Map.copyOf(edges);
	}

	public static NpcEdgeSnapshot empty() {
		return new NpcEdgeSnapshot(Map.of(), Duration.ZERO, 0, Instant.EPOCH);
	}

	/**
	 * This product's edge history, present only when enough of the tape backs it.
	 *
	 * <p>Filtered on {@link NpcEdge#isUsable()} for the same reason
	 * {@link TrendSnapshot#fillStatsFor(String)} is: a persistence fraction measured over an hour is
	 * not a persistence fraction, and a caller handed one would commit an order slot to it.
	 */
	public Optional<NpcEdge> edgeFor(String productId) {
		return Optional.ofNullable(edges.get(productId)).filter(NpcEdge::isUsable);
	}

	/**
	 * Products whose edge is measured rather than unknown, for status reporting.
	 *
	 * <p>Worth reporting separately from {@link #size()}: a client on its first day has tape for
	 * every NPC-priced product and enough of it for none of them.
	 */
	public int productsWithMeasuredEdge() {
		return (int) edges.values().stream().filter(NpcEdge::isUsable).count();
	}

	public boolean isEmpty() {
		return edges.isEmpty();
	}

	/** NPC-priced products the tape had anything at all for. */
	public int size() {
		return edges.size();
	}
}
