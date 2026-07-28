package jeff.skyblockflipper.core.text;

import java.util.List;

/**
 * What every column, term and number in this mod means.
 *
 * <p>Lives in {@code core} and holds the text once, because chat and the flip screen both explain
 * the same vocabulary and two explanations that drift apart are worse than none. A ranking is only
 * worth acting on if the person acting on it knows what it is claiming, and a column labelled "ROC"
 * with no way to find out what ROC is claims nothing.
 */
public final class Guide {
	/** One thing that needed explaining, and its explanation. */
	public record Term(String name, String meaning) {
	}

	/**
	 * @param keyword what a player types to ask for this section, e.g. {@code /flip guide columns}
	 */
	public record Section(String keyword, String heading, List<Term> terms) {
		public Section {
			terms = List.copyOf(terms);
		}
	}

	private Guide() {
	}

	public static List<Section> sections() {
		return List.of(COLUMNS, STRATEGIES, ROUTES, LIQUIDITY, LEDGER, SETTINGS, LIMITS);
	}

	private static final Section COLUMNS = new Section("columns", "Columns", List.of(
			new Term("#", "Rank in the list as currently sorted. /flip take <#> records the flip on "
					+ "that line at the numbers you are looking at"),
			new Term("Item", "The name Hypixel's own item list gives it, which is not always the "
					+ "name you remember. Where another item's name starts with this one, the "
					+ "candidate carries a note saying so - the bazaar search will show you both"),
			new Term("Profit/hr", "Net coins per hour after every fee, and the axis everything is "
					+ "ranked on. Not margin: a 15% spread on something that trades four units a "
					+ "day is worth less than 2% on something moving 500k an hour"),
			new Term("Capital", "Coins tied up to run the plan for one hour. Never more than the "
					+ "bankroll in your config"),
			new Term("ROC", "Return on capital: profit divided by capital, as a percent. How hard "
					+ "the coins work rather than how many coins come back. A 10% ROC flip beats a "
					+ "1% one at equal profit per hour, because it leaves the rest of your bankroll "
					+ "free for another flip"),
			new Term("Conf", "Confidence, 0 to 1: how much the inputs deserve to be trusted. High "
					+ "for a fixed NPC price you instant-buy into, lower for anything resting on an "
					+ "estimate, a fill you have to wait for, or a thin book"),
			new Term("Sparkline", "The last few price samples for the item, green rising, red "
					+ "falling, grey flat. Empty until the bazaar tape has enough history")));

	private static final Section STRATEGIES = new Section("strategies", "Strategies", List.of(
			new Term("Bazaar", "Market making. Post a buy order, wait, post a sell offer, collect "
					+ "the spread. You are being paid for providing immediacy to people who want "
					+ "coins or materials right now"),
			new Term("NPC", "The bazaar price has fallen below the fixed price an NPC pays. Pure "
					+ "arbitrage against a constant, with no sales tax, because selling to an NPC "
					+ "is not a bazaar transaction"),
			new Term("Snipe", "An auction listed below what that exact item configuration has "
					+ "actually been selling for. Fair value is learned from completed sales only, "
					+ "never from active listings"),
			new Term("Attributes", "Kuudra and Crimson Isle gear rolls two attributes, each with a "
					+ "level. The level is most of the price - a level 1 roll is worth about what "
					+ "the bare item is, a high one can be worth several times it - so an item is "
					+ "only ever priced against sales carrying the same roll at the same level. "
					+ "That is a small pool, so attribute gear will often show no valuation at all "
					+ "rather than a confident wrong one"),
			new Term("Pet level", "Hypixel writes a pet's level into its name, and it is most of "
					+ "what the pet is worth - the same pet at level 1 and level 100 differs by two "
					+ "to twelve times. Pets are priced against their own level first, then against "
					+ "a band of nearby levels, then against sales at any level, taking the first "
					+ "with enough sales behind it. Anything below the first rung is discounted for "
					+ "confidence and says so in the risks, because a pet priced off every level at "
					+ "once is the mistake this replaced"),
			new Term("Ledger", "Flips you took, and what they actually did. The only part of this "
					+ "mod that can tell you whether the rest of it works"),
			new Term("Stars and essence", "Hypixel publishes what every star level costs, and every "
					+ "ingredient trades on the bazaar, so a starred item's essence bill is "
					+ "calculated rather than estimated - the only number here with no sample size "
					+ "behind it. It is quoted at the ask, what starring one yourself would cost "
					+ "today; a buy order fills a few percent cheaper. Cost is not value: the market "
					+ "sets its own premium for the work, and sometimes pays under cost when essence "
					+ "has moved. What it tells you is how much of an asking price is a commodity "
					+ "you could buy yourself")));

	private static final Section ROUTES = new Section("routes", "Buying: order or instant?", List.of(
			new Term("Instant buy", "Cross the spread and take the lowest sell offer. You get the "
					+ "stock now and pay the ask for it"),
			new Term("Buy order", "Post a bid a tenth above the best one and wait for someone to "
					+ "dump into it. You pay the bid instead of the ask, which on a wide book is "
					+ "most of the profit - but it fills only as fast as people sell"),
			new Term("Which one", "For NPC flips both are priced and the better one by profit per "
					+ "hour is what the plan says, with the other reported underneath it. Waiting "
					+ "costs nothing against an NPC price, which cannot move away from you; against "
					+ "a bazaar price it can, which is why bazaar spreads are quoted with a cancel "
					+ "rule instead")));

	private static final Section LIQUIDITY = new Section("liquidity", "Liquidity", List.of(
			new Term("Weekly volume", "Units that changed hands over seven days, counted separately "
					+ "for each side. Instant-bought is how fast sell offers get lifted; "
					+ "instant-sold is how fast buy orders get filled"),
			new Term("Why it caps the plan", "What is resting on the book right now is a snapshot, "
					+ "not a supply. An item with 4000 units under the NPC price but 40 units of "
					+ "weekly turnover is not a 4000-unit opportunity, it is a week of holding "
					+ "something nobody wants. Every plan is sized from the flow, not the depth"),
			new Term("Trips", "NPC flips are also capped by hand: 36 inventory slots at a 64 stack, "
					+ "twelve round trips an hour. Buying is instant, selling is not"),
			new Term("Time to fill", "How long your order is expected to take at the size the plan "
					+ "quotes. Two things set it: how fast people trade with that side of the book, "
					+ "and how long you stay at the front of it"),
			new Term("Outbid rate", "How often somebody posts inside your price, measured from "
					+ "recorded history rather than assumed. Once you are outbid your order stops "
					+ "collecting anything until the market comes back to it, so a wide spread on a "
					+ "heavily contested book is worth much less an hour than it looks"),
			new Term("Measured or assumed", "Where the mod has enough recorded history for an item "
					+ "it says how fast fills actually arrive. Where it does not, it falls back to a "
					+ "flat assumption and says so in the risks. The number is never presented as "
					+ "measured when it is not")));

	private static final Section LEDGER = new Section("ledger", "Ledger", List.of(
			new Term("Capture rate", "Coins you realized divided by coins the mod quoted, on filled "
					+ "units only. Below 100% means the quotes are optimistic; that is the number "
					+ "worth watching"),
			new Term("Fill rate", "Units that actually filled divided by units planned. A high "
					+ "capture rate on a low fill rate means the flips that work are the ones you "
					+ "rarely get"),
			new Term("Quotes are frozen", "A position's quote is stored when you take it and never "
					+ "re-derived, because by the time a fill goes badly the book has already moved "
					+ "in whichever direction made it go badly"),
			new Term("Abandon", "Closes a position that never filled, without a realized price. Its "
					+ "units count against the fill rate and nothing about it reaches the capture "
					+ "rate, which is the honest answer: an order you gave up on is not a flip that "
					+ "went badly, it is one that never happened. Select it on the Ledger tab and "
					+ "press Abandon, or use /flip abandon <id>")));

	private static final Section SETTINGS = new Section("settings", "Settings that change the list",
			List.of(
					new Term("Where they are", "The Settings button on this screen, or /flip config "
							+ "edit, or the config file with /flip config. Every one of them writes "
							+ "the same file and re-ranks immediately"),
					new Term("Bankroll", "Coins you are willing to deploy. It is a cap on capital, "
							+ "not an instruction to spend it: every plan is sized to fit inside it, "
							+ "so raising it makes the top of the list bigger flips, not better ones"),
					new Term("Max adverse drift", "Rejects a bazaar candidate whose price has already "
							+ "fallen by more than this fraction over the trend window. Buy orders "
							+ "fill fastest exactly while people are dumping, so the spread you were "
							+ "quoted is the one you least want to be filled on. 0 turns it off"),
					new Term("Min snipe discount", "How far under fair value an auction has to be "
							+ "listed before it is worth a look. It is also what keeps a sweep "
							+ "affordable - almost every listing fails it before its item data is "
							+ "parsed at all - so lowering it costs scan time as well as precision"),
					new Term("Min confidence", "Hides snipes the valuation is less sure of than this. "
							+ "It has no effect on bazaar or NPC flips, which price off a live book "
							+ "rather than an estimate"),
					new Term("Fill horizon", "How long you are willing to leave an order resting. "
							+ "Plans are sized on what is expected to fill inside it, so a long "
							+ "horizon promotes slow items you would have to be patient with and a "
							+ "short one keeps only what fills while you are watching. It changes "
							+ "the order of the list without hiding anything from it")));

	private static final Section LIMITS = new Section("limits", "What this does not do", List.of(
			new Term("Advisory only", "It surfaces numbers and rankings. It does not click, buy, "
					+ "sell, relist or touch your inventory, and it never will"),
			new Term("Prices go stale", "Everything here is from the last poll. Re-check the book "
					+ "before you commit coins to any of it")));
}
