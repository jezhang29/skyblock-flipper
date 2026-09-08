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
package jeff.skyblockflipper.core.track;

import jeff.skyblockflipper.core.evidence.AccountScope;
import jeff.skyblockflipper.core.evidence.ClaimClass;
import jeff.skyblockflipper.core.evidence.CoverageState;
import jeff.skyblockflipper.core.evidence.EndpointSemantics;
import jeff.skyblockflipper.core.evidence.EvidenceRef;
import jeff.skyblockflipper.core.evidence.EvidenceSource;
import jeff.skyblockflipper.core.evidence.NumericClassification;
import jeff.skyblockflipper.core.evidence.NumericEvidence;
import jeff.skyblockflipper.core.evidence.ObservationId;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads Bazaar order menus without converting unreadable quantities into exact zeroes. */
public final class OrderMenuParser {
	public static final String PARSER_VERSION = "bazaar-order-menu-v2";

	private static final String TITLE_SUFFIX = "Bazaar Orders";
	private static final Pattern SIDE = Pattern.compile("^(BUY|SELL) (.+)$");
	private static final Pattern AMOUNT = Pattern.compile("^(?:Offer|Order) amount: ([\\d,]+)x$");
	private static final Pattern FILLED_LINE = Pattern.compile("^Filled: ([^/]+)/.*$");
	private static final Pattern EXACT_INTEGER = Pattern.compile("^[\\d,]+$");
	private static final Pattern ABBREVIATED = Pattern.compile("^[\\d,.]+[kKmMbB]$");
	private static final Pattern UNIT_PRICE =
			Pattern.compile("^Price per unit: ([\\d,]+(?:\\.\\d+)?) coins$");
	private static final Pattern OWNER = Pattern.compile("^By: (?:\\[[^]]+] )?(\\S+)$");
	private static final Pattern CLAIM_COINS =
			Pattern.compile("^You have ([\\d,]+(?:\\.\\d+)?) coins to claim!$");
	private static final Pattern CLAIM_ITEMS =
			Pattern.compile("^You have ([\\d,]+) items? to claim!$");
	private static final double PRICE_EPSILON = 0.05d;

	private OrderMenuParser() {
	}

	public static boolean isOrdersMenu(CapturedMenu menu) {
		return menu != null && menu.title().endsWith(TITLE_SUFFIX);
	}

	/** Canonical parse retaining every recognized row and its uncertainty. */
	public static Optional<OrderMenuObservation> parseObservation(CapturedMenu menu) {
		if (!isOrdersMenu(menu)) {
			return Optional.empty();
		}
		List<ObservedOrderRow> rows = new ArrayList<>();
		for (CapturedSlot slot : menu.slots()) {
			read(menu, slot).ifPresent(rows::add);
		}
		return Optional.of(new OrderMenuObservation(menu.at(), menu.title(), menu.coverage(), rows,
				PARSER_VERSION));
	}

	/**
	 * Temporary exact-only adapter for legacy consumers.
	 *
	 * <p>A row whose fill count is approximate, bounded, unknown, or conflicting is omitted rather
	 * than represented as zero. New tracking consumes {@link #parseObservation} directly.
	 */
	public static List<OrderSnapshot> parse(CapturedMenu menu) {
		return parseObservation(menu).stream()
				.flatMap(observation -> observation.rows().stream())
				.flatMap(row -> row.legacySnapshot().stream())
				.toList();
	}

	public static OptionalInt slotOf(CapturedMenu menu, TradeEvent.Side side, String displayName,
			double unitPrice) {
		if (displayName == null || displayName.isBlank()) {
			return OptionalInt.empty();
		}
		List<ObservedOrderRow> matches = parseObservation(menu).stream()
				.flatMap(observation -> observation.rows().stream())
				.filter(row -> row.side() == side && row.displayName().equalsIgnoreCase(displayName))
				.filter(row -> unitPrice <= 0.0d || row.unitPrice()
						.map(price -> Math.abs(price.doubleValue() - unitPrice) <= PRICE_EPSILON)
						.orElse(false))
				.toList();
		return matches.size() == 1 ? OptionalInt.of(matches.getFirst().slotIndex()) : OptionalInt.empty();
	}

	public static List<OrderSnapshot> ownedBy(List<OrderSnapshot> orders, String player) {
		if (player == null || player.isBlank()) {
			return List.of();
		}
		return orders.stream().filter(order -> order.owner().equalsIgnoreCase(player)).toList();
	}

	private static Optional<ObservedOrderRow> read(CapturedMenu menu, CapturedSlot slot) {
		Matcher name = SIDE.matcher(slot.name());
		if (!name.matches()) {
			return Optional.empty();
		}

		TradeEvent.Side side = name.group(1).equals("BUY")
				? TradeEvent.Side.BUY : TradeEvent.Side.SELL;
		long total = 0L;
		String filledRaw = null;
		Optional<BigDecimal> unitPrice = Optional.empty();
		Optional<BigDecimal> claimCoins = Optional.empty();
		OptionalLong claimItems = OptionalLong.empty();
		String owner = "";

		for (String line : slot.lore()) {
			Matcher matcher = AMOUNT.matcher(line);
			if (matcher.matches()) {
				total = integer(matcher.group(1));
				continue;
			}
			matcher = FILLED_LINE.matcher(line);
			if (matcher.matches()) {
				filledRaw = matcher.group(1);
				continue;
			}
			if (line.startsWith("Filled:")) {
				filledRaw = line;
				continue;
			}
			matcher = UNIT_PRICE.matcher(line);
			if (matcher.matches()) {
				unitPrice = Optional.of(decimal(matcher.group(1)));
				continue;
			}
			matcher = OWNER.matcher(line);
			if (matcher.matches()) {
				owner = matcher.group(1);
				continue;
			}
			matcher = CLAIM_COINS.matcher(line);
			if (matcher.matches()) {
				claimCoins = Optional.of(decimal(matcher.group(1)));
				continue;
			}
			matcher = CLAIM_ITEMS.matcher(line);
			if (matcher.matches()) {
				claimItems = OptionalLong.of(integer(matcher.group(1)));
			}
		}

		if (total <= 0L) {
			return Optional.empty();
		}

		EvidenceRef rowEvidence = evidence(menu, slot, owner, "filled");
		EvidenceRef claimEvidence = evidence(menu, slot, owner, "claim-items");
		NumericEvidence<Long> filled = filled(filledRaw, total, claimItems, rowEvidence,
				claimEvidence);
		return Optional.of(new ObservedOrderRow(menu.at(), slot.index(), side, slot.itemId(),
				name.group(2), owner, total, filled, unitPrice, claimCoins, claimItems, slot.lore()));
	}

	private static NumericEvidence<Long> filled(String raw, long total, OptionalLong claimItems,
			EvidenceRef rowEvidence, EvidenceRef claimEvidence) {
		if (raw != null && EXACT_INTEGER.matcher(raw).matches()) {
			long value = integer(raw);
			if (value > total || (claimItems.isPresent() && claimItems.getAsLong() > value)) {
				return new NumericEvidence.Conflict<>(List.of(rowEvidence, claimEvidence),
						List.of("filled text and exact claim refer to different states",
								"the row changed while the menu was captured"));
			}
			return new NumericEvidence.Exact<>(value, List.of(rowEvidence));
		}

		if (claimItems.isPresent()) {
			long claimed = claimItems.getAsLong();
			if (claimed > total) {
				return new NumericEvidence.Conflict<>(List.of(rowEvidence, claimEvidence),
						List.of("claim belongs to another order", "captured total is stale"));
			}
			if (claimed == total) {
				return new NumericEvidence.Exact<>(total, List.of(claimEvidence));
			}
			return new NumericEvidence.Bounded<>(claimed, total, EndpointSemantics.CLOSED_CLOSED,
					List.of(claimEvidence), List.of("exact unclaimed items prove at least this fill"));
		}

		if (raw != null && ABBREVIATED.matcher(raw).matches()) {
			return new NumericEvidence.Approximate<>(raw, "abbreviated menu text",
					List.of("order total is exactly " + total, "no inverse formatting rule is known"),
					List.of(rowEvidence));
		}

		return new NumericEvidence.Unknown<>(
				raw == null ? "filled line missing" : "filled numerator is malformed",
				List.of("an exact numerator or independent claim evidence"), List.of(rowEvidence));
	}

	private static EvidenceRef evidence(CapturedMenu menu, CapturedSlot slot, String owner,
			String field) {
		String id = "captured-menu:" + menu.at() + ":" + slot.index() + ":" + field;
		AccountScope scope = new AccountScope("capture-unresolved", Optional.empty(),
				owner.isBlank() ? Optional.empty() : Optional.of(owner));
		CoverageState coverage = switch (menu.coverage()) {
			case COMPLETE -> CoverageState.COMPLETE;
			case PARTIAL -> CoverageState.PARTIAL;
			case UNKNOWN -> CoverageState.UNKNOWN;
		};
		return new EvidenceRef(EvidenceRef.CURRENT_SCHEMA_VERSION, new ObservationId(id),
				EvidenceSource.BAZAAR_MENU, Optional.of(id), Optional.empty(), Optional.empty(),
				Instant.ofEpochMilli(menu.at()), Optional.empty(), scope, PARSER_VERSION,
				ClaimClass.C_EMPIRICAL_OBSERVATION, Optional.of(NumericClassification.MEASURED),
				Optional.empty(), Optional.empty(), coverage);
	}

	private static long integer(String text) {
		return Long.parseLong(text.replace(",", ""));
	}

	private static BigDecimal decimal(String text) {
		return new BigDecimal(text.replace(",", ""));
	}
}
