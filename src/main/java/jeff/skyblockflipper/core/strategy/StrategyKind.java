package jeff.skyblockflipper.core.strategy;

/** The things you can actually get paid for, plus where each one's edge comes from. */
public enum StrategyKind {
	/** Providing immediacy: post orders on both sides and collect the spread. */
	BAZAAR_SPREAD("Bazaar", "immediacy"),

	/** Bazaar price has fallen below a fixed NPC buy price. Rare and short-lived. */
	NPC_FLIP("NPC", "mispricing"),

	/** Turning cheap inputs into an expensive output. */
	CRAFT("Craft", "transformation"),

	/** Combining low-tier enchanted books up to a dearer tier at the anvil. */
	COMBINE("Combine", "transformation"),

	/** Fusing cheap attribute shards up to a dearer shard at the Fusion Machine. */
	FUSION("Fusion", "transformation"),

	/** Knowing what a specific item configuration is worth when the market does not. */
	AUCTION_VALUE("Auction", "valuation");

	private final String label;
	private final String edge;

	StrategyKind(String label, String edge) {
		this.label = label;
		this.edge = edge;
	}

	public String label() {
		return label;
	}

	/** What you are being paid for. Useful for explaining why a flip is expected to work. */
	public String edge() {
		return edge;
	}
}
