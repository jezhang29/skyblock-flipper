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
package jeff.skyblockflipper.core.recovery;

import com.google.gson.Gson;
import jeff.skyblockflipper.core.item.ItemDecoder;
import jeff.skyblockflipper.core.model.EndedAuction;
import jeff.skyblockflipper.core.model.dto.EndedAuctionsDto;
import jeff.skyblockflipper.core.valuation.FairValueModel;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RecoveryValuationModelsTest {
	@Test
	void jointStreamLeavesOrdinaryValuationsFieldForFieldUnchanged() throws Exception {
		List<EndedAuction> sales;
		try (InputStream in = getClass().getResourceAsStream("/item-bytes-sample.json")) {
			sales = new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8),
					EndedAuctionsDto.class).auctions;
		}
		Instant now = Instant.ofEpochMilli(sales.stream().mapToLong(EndedAuction::timestamp).max()
				.orElseThrow() + 1_000L);
		Duration window = Duration.ofDays(10);
		FairValueModel ordinary = FairValueModel.from(sales, now, window);
		RecoveryValuationModels.Builder joint = RecoveryValuationModels.builder(now, window);
		sales.forEach(joint::add);
		FairValueModel throughJointPass = joint.build().ordinary();

		assertEquals(ordinary.salesConsidered(), throughJointPass.salesConsidered());
		assertEquals(ordinary.pricedConfigurations(), throughJointPass.pricedConfigurations());
		for (EndedAuction sale : sales) {
			ItemDecoder.decode(sale.itemBytes()).ifPresent(item -> assertEquals(
					ordinary.valueOf(item), throughJointPass.valueOf(item)));
		}
	}
}
