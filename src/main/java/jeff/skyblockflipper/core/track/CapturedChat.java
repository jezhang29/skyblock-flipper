package jeff.skyblockflipper.core.track;

/**
 * One server chat line, recorded verbatim so a parser can be written against what Hypixel actually
 * says rather than against what anyone remembered it saying.
 *
 * @param at   wall clock when the client received it, so a captured line can be lined up with the
 *             menu snapshot taken seconds later
 * @param text the line with its formatting codes already stripped, which is the form a parser will
 *             see
 */
public record CapturedChat(long at, String text) implements CaptureRecord {
}
