package jeff.skyblockflipper.core.model;

/**
 * One price level in a bazaar order book.
 *
 * @param pricePerUnit coins per unit at this level
 * @param amount       units available at this level
 * @param orders       number of distinct player orders making up this level
 */
public record OrderLevel(double pricePerUnit, long amount, int orders) {
}
