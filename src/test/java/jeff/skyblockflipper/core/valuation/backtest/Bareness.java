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
package jeff.skyblockflipper.core.valuation.backtest;

import jeff.skyblockflipper.core.item.DecodedItem;
import jeff.skyblockflipper.core.valuation.Keying;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Production's bareness test, clause by clause, so one clause can be taken away.
 *
 * <p>A counterfactual arm needs the question <i>would this item be bare if the term under measurement
 * were unread</i>, and {@link Keying#PRODUCTION} answers only the whole conjunction. The clauses are
 * restated here, once, with {@link BarenessTest} asserting the conjunction still equals production's
 * answer. That assertion is the entire justification for the copy existing; without it this is the
 * thirteen-clause retyping that made the old hand-rolled backtests measure a different model.
 *
 * <p>Deriving bareness from the key string instead was tried on this branch and is wrong in a way
 * that is easy to miss: {@code signature()} carries terms {@code isBare} does not care about, so
 * every reforged item read as non-bare and lost the coarse fallback it has in production.
 */
final class Bareness {
	/**
	 * The clauses that correspond to a signature term, by the term's spelling.
	 *
	 * <p>Order is the order {@code signature()} writes them, which is worth keeping only because it
	 * makes the two lists comparable by eye.
	 */
	private static final Map<String, Predicate<DecodedItem>> BY_TERM = new LinkedHashMap<>();

	static {
		BY_TERM.put("stars=", item -> item.stars() > 0);
		BY_TERM.put("recomb", DecodedItem::recombobulated);
		BY_TERM.put("hpb=", item -> item.hotPotatoBooks() > 0);
		BY_TERM.put("ench=", item -> !item.enchantments().isEmpty());
		BY_TERM.put("gems=", item -> !item.gemstones().isEmpty());
		BY_TERM.put("attrs=", item -> !item.attributes().isEmpty());
		BY_TERM.put("runes=", item -> !item.runes().isEmpty());
		BY_TERM.put("potion=", DecodedItem::isPotion);
		BY_TERM.put("quality=", DecodedItem::hasQuality);
		BY_TERM.put("abilityScrolls=", item -> item.isScrollCapableBlade()
				|| !item.abilityScrolls().isEmpty());
		BY_TERM.put("ethermerge", DecodedItem::ethermerged);
		BY_TERM.put("slots", item -> item.unlockedSlots() > 0);
		BY_TERM.put("dye=", DecodedItem::isDyed);
	}

	private Bareness() {
	}

	/**
	 * The two clauses with no term behind them.
	 *
	 * <p>A pet has a key of its own shape entirely, and a Dark Auction bid does not reach the
	 * signature at all - {@code MIDAS_STAFF|LEGENDARY} is the key at every bid - so neither can be
	 * unread by editing a key, and no counterfactual here ever asks to.
	 */
	private static boolean keylessClausesHold(DecodedItem item) {
		return !item.isPet() && !item.hasWinningBid();
	}

	/** Production's answer, restated. */
	static boolean bare(DecodedItem item) {
		return keylessClausesHold(item) && BY_TERM.values().stream().noneMatch(c -> c.test(item));
	}

	/**
	 * Production's answer with the Dark Auction bid clause removed - the state the model was in
	 * before the bid was read at all, when a Midas Staff could be priced off the coarse pool of every
	 * other Midas Staff.
	 *
	 * <p>Separate from {@link #bareExceptFor} because the bid is not a signature term and so cannot be
	 * named there.
	 */
	static boolean bareIgnoringTheBid(DecodedItem item) {
		return !item.isPet() && BY_TERM.values().stream().noneMatch(c -> c.test(item));
	}

	/**
	 * Production's answer with one clause removed - what an item would be if {@code term} were never
	 * read off the blob.
	 *
	 * <p>A term with no clause (a candidate that never shipped, like the power scroll) leaves the
	 * conjunction alone, which is right: unread, it was never keeping anything out of the coarse
	 * index.
	 */
	static boolean bareExceptFor(DecodedItem item, String term) {
		if (!keylessClausesHold(item)) {
			return false;
		}

		return BY_TERM.entrySet().stream()
				.filter(clause -> !clause.getKey().equals(term))
				.noneMatch(clause -> clause.getValue().test(item));
	}

	/** The terms a clause is written against, for the test that pins this list to production's. */
	static Iterable<String> terms() {
		return BY_TERM.keySet();
	}

	static Predicate<DecodedItem> clauseFor(String term) {
		return BY_TERM.get(term);
	}
}
