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
package jeff.skyblockflipper.core.config;

/**
 * The settings the background poller needs, as a plain snapshot.
 *
 * <p>Handed to the poller through a supplier rather than copied once at startup, so a
 * {@code /flip reload} changes what the next sweep does without restarting anything.
 *
 * @param scanAuctions            whether to sweep the auction house at all. It is roughly 70MB per
 *                                sweep, which is a real cost to somebody's connection
 * @param valuationWindowDays     how far back realized sales are trusted as evidence of current value
 * @param minDiscount             how far under fair value a listing must be to be worth decoding
 * @param exactMinDiscount        the exact gate's own, smaller discount, applied after a listing is
 *                                matched to its full decoded signature and only when that estimate
 *                                is well-backed. Equal to {@code minDiscount} leaves the exact gate
 *                                at the coarse margin
 * @param maxPrice                listings above this cannot be acted on, so they are not examined
 * @param bazaarTapeEnabled       whether to record bazaar top-of-book to disk
 * @param bazaarTapeRetentionDays how many days of bazaar tape to keep
 * @param trendWindowHours        how far back the in-memory trend indicators look
 * @param bazaarPollSeconds       how often to refetch the bazaar book. Read once when the poller
 *                                starts rather than per sweep, like {@code trendWindowHours}: it is
 *                                a schedule, and a schedule cannot change under a running executor
 */
public record ScanSettings(
		boolean scanAuctions,
		int valuationWindowDays,
		double minDiscount,
		double exactMinDiscount,
		long maxPrice,
		boolean bazaarTapeEnabled,
		int bazaarTapeRetentionDays,
		int trendWindowHours,
		int bazaarPollSeconds,
		int bazaarFlipperLevel,
		RecoverySettings recovery
) {
	/**
	 * Source compatibility for callers that predate recovery's fee-aware active scan. The exact gate
	 * defaults to the coarse margin, so these callers see the pre-exact-gate behaviour unchanged.
	 */
	public ScanSettings(boolean scanAuctions, int valuationWindowDays, double minDiscount,
			long maxPrice, boolean bazaarTapeEnabled, int bazaarTapeRetentionDays,
			int trendWindowHours, int bazaarPollSeconds) {
		this(scanAuctions, valuationWindowDays, minDiscount, minDiscount, maxPrice, bazaarTapeEnabled,
				bazaarTapeRetentionDays, trendWindowHours, bazaarPollSeconds, 0,
				new FlipperConfig().recoverySettings());
	}
}
