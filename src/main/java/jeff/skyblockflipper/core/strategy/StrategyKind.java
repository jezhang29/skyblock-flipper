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
package jeff.skyblockflipper.core.strategy;

import java.util.Arrays;
import java.util.List;

/** The things you can actually get paid for, plus where each one's edge comes from. */
public enum StrategyKind {
	/** Providing immediacy: post orders on both sides and collect the spread. */
	BAZAAR_SPREAD("Bazaar", "immediacy", true),

	/** Bazaar price has fallen below a fixed NPC buy price. Rare and short-lived. */
	NPC_FLIP("NPC", "mispricing", true),

	/** Turning cheap inputs into an expensive output. */
	CRAFT("Craft", "transformation", true),

	/** Combining low-tier enchanted books up to a dearer tier at the anvil. */
	COMBINE("Combine", "transformation", true),

	/** Fusing cheap attribute shards up to a dearer shard at the Fusion Machine. */
	FUSION("Fusion", "transformation", true),

	/** Knowing what a specific item configuration is worth when the market does not. */
	AUCTION_VALUE("Auction", "valuation", false);

	private final String label;
	private final String edge;
	private final boolean atBazaar;

	StrategyKind(String label, String edge, boolean atBazaar) {
		this.label = label;
		this.edge = edge;
		this.atBazaar = atBazaar;
	}

	public String label() {
		return label;
	}

	/** What you are being paid for. Useful for explaining why a flip is expected to work. */
	public String edge() {
		return edge;
	}

	/**
	 * Whether this strategy's clicks happen at Hypixel's bazaar menu.
	 *
	 * <p>The single source of truth for what the in-bazaar overlay lists. A snipe is a bid on the
	 * auction house, a different screen entirely, so it is the one kind that is not at the bazaar. A
	 * new kind marked {@code true} here appears in the overlay's type selector with no overlay edit.
	 */
	public boolean atBazaar() {
		return atBazaar;
	}

	/** The bazaar flip types, in declaration order, which is what the overlay's type selector shows. */
	public static List<StrategyKind> bazaarKinds() {
		return Arrays.stream(values()).filter(StrategyKind::atBazaar).toList();
	}
}
