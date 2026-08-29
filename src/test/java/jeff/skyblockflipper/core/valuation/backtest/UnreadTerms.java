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
import jeff.skyblockflipper.core.nbt.NbtCompound;
import jeff.skyblockflipper.core.valuation.Keying;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * An attribute nothing decodes, carried alongside the sales of one backtest run.
 *
 * <p>{@link CounterfactualKeying#withExtraTerm} covers the finer-term question whenever the finer
 * term is something {@link DecodedItem} already reads. Half the candidates are not: the power scroll,
 * the drill parts and the raw dye colour are all sitting in {@code ExtraAttributes} unread, so two
 * sales differing only in one of them decode to equal items and no {@link Keying} over a decoded item
 * can tell them apart. That is exactly why they are candidates.
 *
 * <p>So the term is read off the raw blob as the tape streams past and remembered against the sale it
 * came from, <b>by object identity</b>. Value identity would be wrong here and quietly so: two sales
 * of one configuration at one price decode to equal records, and the attribute under measurement is
 * the only thing distinguishing them.
 *
 * <p>Identity is why an instance belongs to exactly one {@link Backtest#holdout} run. A second run
 * decodes the tape again into different objects, and reading this back against those would report
 * that nothing carries the attribute at all. Construct one per arm, pass it to that arm as its
 * observer, and read it back only for that arm's rows.
 */
public final class UnreadTerms implements TapeFixture.SaleVisitor {
	/** The signature term a sale's raw attributes call for, or empty for none. */
	public interface Source {
		String of(DecodedItem item, NbtCompound extra);
	}

	private final Map<DecodedItem, String> byIdentity = new IdentityHashMap<>();
	private final Source source;

	public UnreadTerms(Source source) {
		this.source = source;
	}

	/**
	 * Remembers what this sale carried. Called by {@link Backtest#holdout} for every included sale,
	 * on both sides of the cutoff, before the sale is trained on or priced.
	 */
	@Override
	public void accept(DecodedItem item, NbtCompound extra, long timestamp, double unitPrice) {
		String term = source.of(item, extra);

		if (term != null && !term.isEmpty()) {
			byIdentity.put(item, term);
		}
	}

	/** The term this sale carried, or empty. */
	public String of(DecodedItem item) {
		return byIdentity.getOrDefault(item, "");
	}

	public boolean carries(DecodedItem item) {
		return byIdentity.containsKey(item);
	}

	/** How many sales carried it, across everything observed so far. */
	public int observed() {
		return byIdentity.size();
	}

	/**
	 * The keying that would ship if this term did: production's, with the term appended.
	 *
	 * <p>Bareness moves with it, as {@link CounterfactualKeying} derives it - a scrolled item stops
	 * being bare and leaves the coarse index, which is the coverage half of what these arms cost.
	 */
	public Keying keying() {
		return CounterfactualKeying.keyedBy(item -> SignatureTerms.plus(item.signature(), of(item)),
				item -> Bareness.bare(item) && !carries(item));
	}

	/**
	 * The keying that would ship if this term replaced one that already does.
	 *
	 * <p>For the arms that respell a shipped term rather than adding a new one -
	 * {@code DungeonQualityBacktestTest} scores six ways of writing the quality roll down, five of
	 * which are not what production writes. The shipped spelling is dropped from the key first, so
	 * every arm differs from the others in exactly one term.
	 *
	 * @param shippedTerm the term to drop, spelled as {@link SignatureTerms#without} takes it
	 */
	public Keying keyingInsteadOf(String shippedTerm) {
		return CounterfactualKeying.keyedBy(
				item -> SignatureTerms.plus(SignatureTerms.without(item.signature(), shippedTerm),
						of(item)),
				item -> Bareness.bareExceptFor(item, shippedTerm) && !carries(item));
	}
}
