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
