package jeff.skyblockflipper.client;

import jeff.skyblockflipper.core.api.MarketData;
import jeff.skyblockflipper.core.recovery.RecoveryOpportunity;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import java.util.List;

/** Recovery's independent immutable client cache, keyed only by the recovery scan revision. */
public final class RecoveryFeed {
	private static volatile List<RecoveryOpportunity> cached = List.of();
	private static long cachedRevision = -1L;

	private RecoveryFeed() {}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(client -> refreshIfStale());
	}

	public static List<RecoveryOpportunity> current() {
		refreshIfStale();
		return cached;
	}

	public static long revision() {
		refreshIfStale();
		return cachedRevision;
	}

	public static void invalidate() {
		cachedRevision = -1L;
	}

	static List<RecoveryOpportunity> refresh(MarketData data) {
		long revision = data.recoveryRevision();
		if (revision != cachedRevision) {
			cached = List.copyOf(data.recoveryOpportunities());
			cachedRevision = revision;
		}
		return cached;
	}

	private static void refreshIfStale() {
		refresh(MarketDataService.data());
	}
}
