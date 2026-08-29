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

import com.google.gson.Gson;

import jeff.skyblockflipper.core.item.DecodedItem;
import jeff.skyblockflipper.core.item.ItemDecoder;
import jeff.skyblockflipper.core.item.Rarity;
import jeff.skyblockflipper.core.model.EndedAuction;
import jeff.skyblockflipper.core.model.dto.EndedAuctionsDto;
import jeff.skyblockflipper.core.valuation.Keying;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Bareness} is still production's conjunction, clause for clause.
 *
 * <p>The whole reason a copy of {@code isBare} is tolerable in a backtest is that this test fails
 * when the two disagree. Without it, the copy is the drift that made the eight hand-rolled backtests
 * grade a model nobody runs - each had retyped the clauses with one missing, and nothing said so.
 *
 * <p>Runs in an ordinary build. The backtests that consume {@link Bareness} need a recorded tape and
 * are opt-in, so a disagreement would otherwise surface only on the machine that has one.
 */
class BarenessTest {
	private static List<DecodedItem> items;

	@BeforeAll
	static void loadFixture() throws Exception {
		items = new ArrayList<>();

		try (InputStream in = BarenessTest.class.getResourceAsStream("/item-bytes-sample.json")) {
			for (EndedAuction sale : new Gson().fromJson(
					new InputStreamReader(in, StandardCharsets.UTF_8), EndedAuctionsDto.class).auctions) {
				ItemDecoder.decode(sale.itemBytes()).ifPresent(items::add);
			}
		}

		assertFalse(items.isEmpty(), "the fixture decoded no items");
	}

	@Test
	void theCopyAgreesWithProductionOnEveryFixtureItem() {
		for (DecodedItem item : items) {
			assertEquals(Keying.PRODUCTION.isBare(item), Bareness.bare(item),
					item.skyblockId() + " with signature '" + item.signature() + "' is bare in "
							+ "production and not in the backtest copy, or the other way round");
		}
	}

	/**
	 * Every clause is reachable, so the agreement above is not agreement about nothing.
	 *
	 * <p>Only checks the clauses the fixture exercises - it is 154 real sales, not a combinatorial
	 * sweep - but it fails if a clause stops being able to fire at all, which is what a renamed
	 * accessor or a changed decode would look like.
	 */
	@Test
	void everyClauseIsWrittenAgainstATermThatExists() {
		int fired = 0;

		for (String term : Bareness.terms()) {
			assertNotNull(Bareness.clauseFor(term), "no clause for term " + term);

			if (items.stream().anyMatch(item -> Bareness.clauseFor(term).test(item))) {
				fired++;
			}
		}

		assertTrue(fired >= 4, "the fixture should exercise several bareness clauses, and only "
				+ fired + " fired");
	}

	/**
	 * Dropping a clause can only widen bareness, never narrow it.
	 *
	 * <p>The property every counterfactual arm rests on: unreading a term moves items into the coarse
	 * index and never out of it, so a coverage difference between two arms is the term's doing.
	 */
	@Test
	void unreadingATermOnlyEverMakesMoreItemsBare() {
		for (DecodedItem item : items) {
			for (String term : Bareness.terms()) {
				if (Bareness.bare(item)) {
					assertTrue(Bareness.bareExceptFor(item, term),
							item.skyblockId() + " is bare but stopped being bare when " + term
									+ " was unread");
				}
			}
		}
	}

	/**
	 * A reforge is not a bareness clause, and that is deliberate rather than an omission.
	 *
	 * <p>Hypixel writes the reforge into the display name - "Heroic Aspect of the End" - so the coarse
	 * key of name and rarity already separates a reforged item from a plain one, the same argument
	 * that keeps runes cheap. It is stated here because deriving bareness from the signature string
	 * instead gets exactly this case wrong, silently, on every reforged sale.
	 */
	@Test
	void aReforgedItemWithNothingElseOnItIsBare() {
		DecodedItem heroic = new DecodedItem("ASPECT_OF_THE_END", "Heroic Aspect of the End", 1,
				Rarity.RARE, "heroic", 0, false, 0, Map.of(), List.of(), Map.of(), Map.of(),
				null, null, null, "", false, 0L);

		assertEquals("ASPECT_OF_THE_END|RARE|reforge=heroic", heroic.signature(),
				"the reforge is expected in the signature - if it has moved, this test is about "
						+ "nothing");
		assertTrue(Keying.PRODUCTION.isBare(heroic),
				"a reforge is not a bareness clause, so a reforged item with nothing else on it must "
						+ "still reach the coarse index");
		assertTrue(Bareness.bare(heroic), "the backtest copy disagreed with production about a "
				+ "reforged item, which is the case the string-derived rule got wrong");
	}
}
