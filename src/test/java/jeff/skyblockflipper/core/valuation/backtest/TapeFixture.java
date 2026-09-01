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
import jeff.skyblockflipper.core.item.ItemDecoder;
import jeff.skyblockflipper.core.nbt.NbtCompound;
import jeff.skyblockflipper.core.nbt.NbtReader;
import jeff.skyblockflipper.core.tape.SalesTape;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reading the recorded tape, for the backtests that measure against it.
 *
 * <p>Every one of them needs the same four things - where the tape is, a decoded pass over it, when
 * the newest sale landed, and a median - and before this module existed each carried its own copy.
 * Two of the copies were byte-identical across files and a third had already drifted, hardcoding a
 * sample floor its siblings took as a parameter.
 *
 * <p>Each sale is offered with its raw {@code ExtraAttributes} alongside the decoded item, which is
 * what {@code UnreadAttributeProbeTest} exists on: it ranks attributes {@link DecodedItem} does not
 * read yet, so a decoded view alone would blind it to exactly what it is looking for.
 *
 * <p>Streamed, never collected. A day of sales tape is ~265MB of blob and the recorded tape is
 * several days of it.
 */
public final class TapeFixture {
	/** Everything on disk. Retention is the collector's business, not a backtest's. */
	public static final int ALL_DAYS = 365;

	private static final String DEFAULT_TAPE_DIR = "run/config/skyblock-flipper/tape";

	private TapeFixture() {
	}

	/** The decoded item, its raw {@code ExtraAttributes}, when it sold, and its unit price. */
	public interface SaleVisitor {
		void accept(DecodedItem item, NbtCompound extra, long timestamp, double unitPrice);
	}

	/**
	 * Streams every buy-it-now sale on the tape.
	 *
	 * <p>Bid auctions are skipped here for the same reason {@link jeff.skyblockflipper.core.valuation.FairValueModel}
	 * skips them: an auction that ended on one uncontested bid says what nobody else was awake for.
	 */
	public static void forEachSale(SaleVisitor visitor) throws Exception {
		tape().forEachRecent(ALL_DAYS, sale -> {
			if (!sale.bin() || sale.price() <= 0L) {
				return;
			}

			NbtCompound root;

			try {
				root = NbtReader.readItemBytes(sale.itemBytes());
			} catch (Exception e) {
				return;
			}

			if (!(root.list("i").stream().findFirst().orElse(null) instanceof NbtCompound stack)) {
				return;
			}

			NbtCompound extra = stack.child("tag").child("ExtraAttributes");

			ItemDecoder.fromRoot(root).ifPresent(item -> visitor.accept(item, extra, sale.timestamp(),
					(double) sale.price() / Math.max(1, item.count())));
		});
	}

	/** When the newest sale on the tape landed, which every holdout cutoff is measured back from. */
	public static long newestTimestamp() throws Exception {
		long[] newest = {0L};
		tape().forEachRecent(ALL_DAYS, sale -> newest[0] = Math.max(newest[0], sale.timestamp()));

		assertTrue(newest[0] > 0L, "no sales on the tape at " + tapeDir());
		return newest[0];
	}

	/** Each retained UTC day and the newest sale timestamp recorded in it, oldest first. */
	public static Map<LocalDate, Long> retainedDayEnds() throws IOException {
		Map<LocalDate, Long> ends = new TreeMap<>();
		tape().forEachRecent(ALL_DAYS, sale -> {
			if (sale.timestamp() <= 0L) {
				return;
			}

			LocalDate day = Instant.ofEpochMilli(sale.timestamp())
					.atZone(ZoneOffset.UTC).toLocalDate();
			ends.merge(day, sale.timestamp(), Math::max);
		});
		return Collections.unmodifiableMap(ends);
	}

	public static SalesTape tape() {
		return new SalesTape(Path.of(tapeDir()), 3650);
	}

	public static String tapeDir() {
		return System.getProperty("skyblockflipper.tapeDir", DEFAULT_TAPE_DIR);
	}

	/**
	 * The same median the model uses, for the pool-shape tests that compare medians directly rather
	 * than building a model.
	 *
	 * @param minSamples the floor below which a pool says nothing. Pass
	 *                   {@code ValueEstimate.MIN_SAMPLES} to ask what the model would answer, or 1 to
	 *                   describe a pool whatever its size
	 */
	public static OptionalDouble median(List<Double> values, int minSamples) {
		if (values == null || values.size() < minSamples) {
			return OptionalDouble.empty();
		}

		List<Double> sorted = values.stream().sorted().toList();
		return OptionalDouble.of(sorted.get(sorted.size() / 2));
	}
}
