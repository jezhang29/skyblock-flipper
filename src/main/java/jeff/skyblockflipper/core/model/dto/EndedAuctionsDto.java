package jeff.skyblockflipper.core.model.dto;

import jeff.skyblockflipper.core.model.EndedAuction;

import java.util.List;

/** Wire format of {@code /v2/skyblock/auctions_ended}. */
public final class EndedAuctionsDto {
	public boolean success;
	public long lastUpdated;
	public List<EndedAuction> auctions;
}
