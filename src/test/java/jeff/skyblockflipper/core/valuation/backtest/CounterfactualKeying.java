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

import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * A {@link Keying} describing items as if one signature term were missing, or as if a finer one had
 * shipped. The two questions every attribute backtest asks.
 *
 * <p>Both are answered by editing the string {@link DecodedItem#signature()} produced, because that
 * method builds its key internally and there is no way to ask it for a key under a different term
 * set. That is the one piece of this harness still coupled to how a signature is spelled, and it is
 * here rather than in the eight tests so it is written once and, unlike the {@code replaceFirst}
 * calls it replaces, actually tested - see {@link SignatureTerms} and {@code SignatureTermsTest}. The
 * coupling goes away with the term model; see
 * {@code docs/adr/0001-defer-the-signature-term-model.md}.
 *
 * <p>Bareness moves with the key: take a term away and an item that carried nothing else becomes
 * bare, which is precisely the effect being measured - unread, a merged Aspect of the Void joins the
 * coarse pool of plain ones. It comes from {@link Bareness}, which is production's conjunction with
 * one clause removable.
 *
 * <p>It was first derived from the key string instead - bare when the key reads {@code id|RARITY} -
 * and that is wrong, because {@code signature()} carries terms {@code isBare} does not care about.
 * A reforged item is bare in production, since Hypixel writes the reforge into the display name the
 * coarse key is built from, and the string rule denied every one of them the coarse fallback. The
 * offline fixture holds no reforged-but-otherwise-bare item, so nothing failed; on the tape it is
 * hundreds of held-out sales.
 */
public final class CounterfactualKeying {
	private CounterfactualKeying() {
	}

	/**
	 * As if {@code term} were never read off the blob.
	 *
	 * @param term the term as it appears in a signature, without the separator - {@code "ethermerge"},
	 *             or a prefix like {@code "dye="} to drop a term that carries a value
	 */
	public static Keying withoutTerm(String term) {
		return keyedBy(item -> SignatureTerms.without(item.signature(), term),
				item -> Bareness.bareExceptFor(item, term));
	}

	/**
	 * As if a finer term shipped alongside the ones that already do.
	 *
	 * @param extraTerm the term to append for this item, or empty to leave its key alone
	 */
	public static Keying withExtraTerm(Function<DecodedItem, String> extraTerm) {
		return keyedBy(item -> SignatureTerms.plus(item.signature(), extraTerm.apply(item)),
				item -> Bareness.bare(item) && extraTerm.apply(item).isEmpty());
	}

	/**
	 * As if the Dark Auction bid were never read: no ratio index, and a bid-carrying item may reach
	 * the coarse pool of every other bid on the same name.
	 *
	 * <p>The state the model was in before {@code winning_bid} shipped, and the baseline the ratio
	 * quote has to beat. Not expressible through {@link #withoutTerm}, because the bid is deliberately
	 * absent from the signature - {@code MIDAS_STAFF|LEGENDARY} is the key at every bid.
	 *
	 * @param withRatioIndex whether the price-to-bid ratio index is still consulted, which separates
	 *                       "the bid is unread" from "the bid is read but the coarse guard is not"
	 */
	public static Keying withTheBidUnread(boolean withRatioIndex) {
		return new Keying() {
			@Override
			public List<String> keys(DecodedItem item) {
				return Keying.PRODUCTION.keys(item);
			}

			@Override
			public Optional<String> bidRatioKey(DecodedItem item) {
				return withRatioIndex ? Keying.PRODUCTION.bidRatioKey(item) : Optional.empty();
			}

			@Override
			public boolean isBare(DecodedItem item) {
				return Bareness.bareIgnoringTheBid(item);
			}
		};
	}

	/**
	 * As {@link #withExtraTerm}, with the price-to-bid ratio index switched off.
	 *
	 * <p>The arm that asks whether a key term could do the ratio quote's job instead of riding
	 * alongside it - {@code MidasBidBacktestTest} scores banding the bid into the key that way. Every
	 * other counterfactual leaves the ratio index alone, because a term and the ratio answer different
	 * questions and only the Midas work has ever put them in competition.
	 */
	public static Keying withExtraTermInsteadOfTheBidRatio(Function<DecodedItem, String> extraTerm) {
		return keyedBy(item -> SignatureTerms.plus(item.signature(), extraTerm.apply(item)),
				item -> Bareness.bareIgnoringTheBid(item) && extraTerm.apply(item).isEmpty(), false);
	}

	/**
	 * A keying built on a rewritten signature.
	 *
	 * <p>Pets keep production's ladder untouched: their rungs are levels rather than terms, no
	 * attribute backtest has ever measured one, and a pet is never bare whatever its key says.
	 *
	 * <p>Package-private for {@link UnreadTerms}, whose key depends on the raw blob and so cannot be
	 * written as a function of the decoded item alone.
	 */
	static Keying keyedBy(Function<DecodedItem, String> key, Predicate<DecodedItem> bare) {
		return keyedBy(key, bare, true);
	}

	private static Keying keyedBy(Function<DecodedItem, String> key, Predicate<DecodedItem> bare,
			boolean withRatioIndex) {
		return new Keying() {
			@Override
			public List<String> keys(DecodedItem item) {
				return item.isPet() ? Keying.PRODUCTION.keys(item) : List.of(key.apply(item));
			}

			@Override
			public Optional<String> bidRatioKey(DecodedItem item) {
				return withRatioIndex && item.hasWinningBid()
						? Optional.of(key.apply(item)) : Optional.empty();
			}

			@Override
			public boolean isBare(DecodedItem item) {
				return bare.test(item);
			}
		};
	}
}
