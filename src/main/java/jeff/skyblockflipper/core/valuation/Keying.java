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

import jeff.skyblockflipper.core.item.DecodedItem;

import java.util.List;
import java.util.Optional;

/**
 * How an item is described for pricing.
 *
 * <p>Three questions, and they are the only three {@link FairValueModel} asks about an item before
 * it becomes a median: which keys may this be valued under, does it carry a Dark Auction bid worth
 * scaling a ratio by, and is it bare enough for name and rarity to describe it completely.
 *
 * <p>{@link #PRODUCTION} is what ships, and nothing outside a backtest should pass anything else.
 * The seam exists because a backtest's question is almost always counterfactual - <i>what would this
 * cost if the ethermerge were unread</i>, <i>what if a finer term shipped</i> - and
 * {@link DecodedItem#signature()} emits exactly one keying. Before this interface existed, eight
 * backtests answered those questions by rebuilding the model's training and lookup path by hand, so
 * they graded a copy of the model rather than the model: no 200-sample ring, no rung ladder, no
 * ratio index, and {@code isBare} retyped locally with one clause removed. A backtest now passes a
 * different {@code Keying} and gets the real thing around it.
 *
 * <p>Deliberately <b>not</b> a term model - {@code signature()} still builds its string by appending
 * terms, and this interface reads that string rather than producing it. See
 * {@code docs/adr/0001-defer-the-signature-term-model.md} for why, and for what reopening it needs.
 */
public interface Keying {
	/**
	 * Keys this item may be priced against, most specific first. See
	 * {@link DecodedItem#valuationKeys()} for what a later rung costs in confidence.
	 */
	List<String> keys(DecodedItem item);

	/**
	 * The key under which this item's price-to-bid ratio is pooled, or empty if it carries no bid
	 * worth scaling by.
	 *
	 * <p>Empty is also how a backtest switches the ratio index off entirely, which is the arm
	 * {@code MidasBidBacktestTest} calls {@code POOLED}.
	 */
	Optional<String> bidRatioKey(DecodedItem item);

	/** Whether name and rarity describe this item completely. */
	boolean isBare(DecodedItem item);

	/** What ships. */
	Keying PRODUCTION = new Keying() {
		@Override
		public List<String> keys(DecodedItem item) {
			return item.valuationKeys();
		}

		/**
		 * Only the exact signature: a bid is a Midas weapon's whole identity, and no widened rung is
		 * precise enough to scale.
		 */
		@Override
		public Optional<String> bidRatioKey(DecodedItem item) {
			return item.hasWinningBid() ? Optional.of(item.signature()) : Optional.empty();
		}

		/**
		 * Nothing was added to this item, so there is nothing a name-and-rarity match could miss.
		 *
		 * <p>Attribute rolls count even though nobody added them by hand. An attributed item otherwise
		 * carries no attributes at all, so without this line it reads as bare and gets priced off the
		 * coarse pool - which for {@code CRIMSON_BOOTS} mixes 1.9M bare sales with 16M rolled ones and
		 * calls the gap a snipe.
		 *
		 * <p>A rune counts for the same reason. It costs a standalone rune nothing, because Hypixel
		 * writes the rune and its tier into the display name the coarse key is built from, so the two
		 * keys select the same sales anyway. What it stops is a runed sword falling back to a pool of
		 * bare ones.
		 *
		 * <p>A potion is excluded outright, like a pet, and unlike a rune it is not free to exclude.
		 * The display name the coarse key is built from does state the effect, the tier and whether it
		 * splashes, so most of the signature is recoverable from it - but it does not state the alchemy
		 * perks. An enhanced, extended Speed VIII potion is named exactly "Speed VIII Potion", and on
		 * the tape it sells for 82,525 coins against 58,999 for the plain one wearing the same name.
		 *
		 * <p>A named dye is here for completeness rather than for measured harm: on the recorded tape
		 * every dyed sale already fails one of the other clauses, so this one has never yet decided a
		 * lookup. It is the clause that would matter first if that stopped being true, since a dye
		 * moves a price by up to 833x at the item id and the display name never mentions it.
		 *
		 * <p>An ethermerge is the clause the dye was only theoretically: 315 of the 516 merged sales on
		 * the tape carry nothing else, so without this line a merged Aspect of the Void reads as bare
		 * and joins the coarse pool of plain ones - and then every plain Aspect of the Void is quoted
		 * off a pool holding sales worth 4x it. The display name is identical either way.
		 *
		 * <p>A Dark Auction bid is here on the maxed dungeon flag's footing: measured to change nothing
		 * and kept anyway. On the recorded tape the coarse fallback never fires for a bid-carrying
		 * item, because its signature always had sales of its own, so the clause scored byte-identically
		 * to omitting it. What it names is that "Midas Staff" is the display name at every bid, so the
		 * coarse pool behind that name mixes a 3,000,000 coin staff with a 100,000,000 coin one - and
		 * unlike the exact index, no ratio can rescue it, since the pool is not one configuration.
		 *
		 * <p>A dungeon quality roll is the attribute-roll bug again, and worse. Nothing about the
		 * drop's tier reaches its display name, so a maxed tier-10 {@code SKELETON_MASTER_CHESTPLATE}
		 * with no enchantments on it would read as bare and price off a pool whose median is a tier-7
		 * at 2,000,000 coins - against the 113,000,000 the tier-10s actually fetch.
		 *
		 * <p>An unlocked gemstone slot is the ethermerge case once more. A slot paid open costs real
		 * coins and the display name never mentions it, so an otherwise-bare Divan's piece with slots
		 * open reads as bare without this clause and joins the coarse pool of gemmed and unlocked ones -
		 * which is the second half of the ~60M locked-Divan snipe found in play. The count is the
		 * {@code slots} term in the signature; this clause is the guard that stops the coarse fallback
		 * undoing it. See {@code GemstoneSlotBacktestTest}.
		 */
		@Override
		public boolean isBare(DecodedItem item) {
			return !item.isPet()
					&& !item.isScrollCapableBlade()
					&& !item.isDyed()
					&& !item.ethermerged()
					&& !item.hasWinningBid()
					&& !item.isPotion()
					&& !item.hasQuality()
					&& item.stars() == 0
					&& !item.recombobulated()
					&& item.hotPotatoBooks() == 0
					&& item.enchantments().isEmpty()
					&& item.gemstones().isEmpty()
					&& item.unlockedSlots() == 0
					&& item.attributes().isEmpty()
					&& item.runes().isEmpty();
		}

		@Override
		public String toString() {
			return "Keying.PRODUCTION";
		}
	};
}
