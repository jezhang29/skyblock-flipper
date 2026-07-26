package jeff.skyblockflipper.core.api;

import jeff.skyblockflipper.core.model.ActiveListing;

/**
 * Receives live listings one at a time as a sweep walks the auction pages.
 *
 * <p>A callback rather than a returned list on purpose. A full sweep is ~46,000 buy-it-now
 * listings carrying about 70MB of item blobs between them; collecting them all so the caller can
 * filter afterwards would mean holding the entire auction house in memory to keep a few dozen
 * rows. Handing each listing over as it is parsed lets the sink keep what it wants and lets the
 * rest of the page be collected immediately.
 */
@FunctionalInterface
public interface ListingSink {
	void offer(ActiveListing listing);
}
