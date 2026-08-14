package jeff.skyblockflipper.client;

import jeff.skyblockflipper.core.api.MarketData;
import jeff.skyblockflipper.core.model.BazaarProduct;
import jeff.skyblockflipper.core.strategy.NpcProbe;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import java.util.Optional;
import java.util.OptionalDouble;

/**
 * The one open {@link NpcProbe}, sampled every time the book moves.
 *
 * <p>Owns the instance and the clock, the same split {@link NpcRoundService} uses: the probe is a
 * record in {@code core} with {@code now} passed in, and this decides when a poll counts as a
 * sample.
 *
 * <p><b>Sampled on the bazaar revision rather than on a timer.</b> That is the same signal
 * {@link CandidateFeed} rebuilds on, so a sample is one reading of one poll and the poll rate is the
 * sample rate. Ticking on wall-clock time would count the same snapshot several times and report a
 * share of polls that was really a share of ticks.
 *
 * <p><b>Memory only.</b> A probe answers a question about one session and does not survive a
 * restart, which the command says when it opens one. Persisting it would mean deciding what a probe
 * means across a gap in which the order may have been filled, cancelled or outbid unobserved, and
 * the whole point of the measurement is that every moment of it was watched.
 *
 * <p>Client thread only.
 */
public final class NpcProbeService {
	private static NpcProbe probe;

	/** The book revision the last sample was taken at, so one poll counts once. */
	private static long sampledRevision = -1L;

	private NpcProbeService() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(client -> sampleIfStale());
	}

	public static Optional<NpcProbe> current() {
		return Optional.ofNullable(probe);
	}

	/** Opens a probe on {@code itemId}, replacing any that was already running. */
	public static NpcProbe open(String itemId, String displayName, double price, double premium) {
		probe = NpcProbe.opened(itemId, displayName, price, premium, System.currentTimeMillis());
		sampledRevision = MarketDataService.data().bazaarRevision();

		return probe;
	}

	/** Ends the probe, returning what it had found. */
	public static Optional<NpcProbe> stop() {
		Optional<NpcProbe> ending = current();

		probe = null;
		sampledRevision = -1L;

		return ending;
	}

	private static void sampleIfStale() {
		if (probe == null) {
			return;
		}

		MarketData data = MarketDataService.data();
		long revision = data.bazaarRevision();

		if (revision == sampledRevision) {
			return;
		}

		sampledRevision = revision;

		// The probe's own order is in this book, so the top bid is the probe's price until somebody
		// posts above it. A product that has left the snapshot entirely is not evidence of being
		// outbid, so it is skipped rather than counted against the probe.
		OptionalDouble topBid = data.bazaar().product(probe.itemId())
				.map(BazaarProduct::instantSellPrice)
				.orElse(OptionalDouble.empty());

		if (topBid.isPresent()) {
			probe = probe.sample(topBid.getAsDouble(), System.currentTimeMillis());
		}
	}
}
