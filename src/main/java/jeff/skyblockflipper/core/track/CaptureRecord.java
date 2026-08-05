package jeff.skyblockflipper.core.track;

/**
 * One line of a capture file, either something Hypixel said or something it drew.
 *
 * <p>The two are only useful together and only in the order they arrived: a claim line means one
 * thing after the menu that showed a partial fill and another thing before it. Sealed because those
 * two forms are the whole file format, and a third would be a change to {@link CaptureLog} before
 * it was a change to a reader.
 */
public sealed interface CaptureRecord permits CapturedChat, CapturedMenu {
	/** Wall clock when the client saw it. */
	long at();
}
