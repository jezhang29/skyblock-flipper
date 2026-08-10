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
		return List.of(START, COMMANDS, COLUMNS, STRATEGIES, ROUTES, NPC_CAP, LIQUIDITY, LEDGER,
				SETTINGS, SYNC, LIMITS);
	}

	/**
	 * The walkthrough, first because everything else assumes you have already done it.
	 *
	 * <p>Numbered, and in the order a first session actually happens: money in, list, one flip,
	 * proof it worked. The other sections define words; this one is the only place that says what to
	 * do, and a player who reads nothing else should still be able to run a flip from it.
	 */
	private static final Section START = new Section("start", "Walkthrough: your first flip",
			List.of(
					new Term("1. Tell it your bankroll", "Open the flip screen (the keybind, or "
							+ "/flip gui) and press Settings, or type /flip config edit. Set Bankroll "
							+ "to the coins you are willing to have tied up at once. Everything is "
							+ "sized to fit inside it, so this is the one setting you cannot skip"),
					new Term("2. Wait for the first poll", "The bazaar is fetched every 20 seconds "
							+ "and the first fetch lands a few seconds after you join. /flip status "
							+ "says how old the data is. Until it arrives the list is empty"),
					new Term("3. Ask for a list", "/flip, with nothing after it, is the ranking "
							+ "across every strategy. /flip bazaar, /flip npc and /flip snipe ask for "
							+ "one kind. The list is sorted by profit per hour after fees, and rank 1 "
							+ "is the best plan the mod can see right now"),
					new Term("4. Read one row before trusting it", "Click the row in the flip screen "
							+ "and the panel on the right says what the plan is: what to buy, at what "
							+ "price, how many, and what has to happen for it to pay. Anything the "
							+ "mod is unsure about is listed there as a risk"),
					new Term("5. Take it", "/flip take 1, or the Take button, writes the plan into "
							+ "your ledger with the numbers you are looking at frozen into it. This "
							+ "does not trade for you - you still place the order yourself, in the "
							+ "game. The mod never touches your account"),
					new Term("6. Do the trade in game", "Copy the item name with the Copy name button, "
							+ "paste it into the bazaar search, and place the order the plan "
							+ "describes. The mod is a calculator, not a bot"),
					new Term("7. Record what happened", "When the coins come back, /flip close <id> "
							+ "<units sold> <price each>. The id is the four characters /flip take "
							+ "printed. If the order never filled, /flip abandon <id> instead"),
					new Term("8. Check whether it works", "/flip ledger prints your open positions and "
							+ "two numbers: how much of the quoted profit you actually got, and how "
							+ "much of what you planned actually filled. Those two are the only "
							+ "evidence that any of this is worth doing"),
					new Term("Optional: let it record for you", "/flip track fills the ledger from "
							+ "the trades Hypixel announces, so steps 5 and 7 happen on their own for "
							+ "plans you took. Read the Ledger section first - it only records trades "
							+ "against plans you took unless you tell it otherwise")));

	/** Every command, in one place, because a command you cannot remember does not exist. */
	private static final Section COMMANDS = new Section("commands", "Commands", List.of(
			new Term("/flip", "The ranked list. /flip bazaar, /flip npc and /flip snipe restrict it "
					+ "to one strategy"),
			new Term("/flip gui", "The full screen: sortable list, the reasoning behind each row, "
					+ "your ledger and this guide. The keybind opens the same thing"),
			new Term("/flip take <rank>", "Records the flip on that line of the last list you were "
					+ "shown, at the numbers you were shown"),
			new Term("/flip close <id> <units> <price>", "Closes a position with what actually "
					+ "happened. Price is per unit, as displayed - fees are applied for you"),
			new Term("/flip abandon <id>", "Ends a position that never filled. Its units count "
					+ "against the fill rate and nothing reaches the capture rate"),
			new Term("/flip ledger", "Open positions, committed capital, capture rate and fill rate"),
			new Term("/flip ledger forget <id>", "Deletes an entry as though it had never been "
					+ "recorded. For entries that are not flips at all"),
			new Term("/flip ledger clear unquoted", "Counts the entries that came from trades the mod "
					+ "never quoted, then deletes them all if you repeat the command with confirm on "
					+ "the end. /flip ledger clear confirm empties the whole ledger"),
			new Term("/flip status", "Whether polling is alive, how old the data is, what the tape "
					+ "holds, and what the last sync did"),
			new Term("/flip config", "Prints the settings that change the list, and where the file "
					+ "is. /flip config edit opens them as a screen"),
			new Term("/flip reload", "Re-reads the config file after you edited it by hand"),
			new Term("/flip sync", "Pulls the collector's tape now instead of waiting for the next "
					+ "launch"),
			new Term("/flip hud", "Toggles the on-screen list of top flips"),
			new Term("/flip track", "Toggles filling the ledger from your real trades"),
			new Term("/flip capture", "Toggles recording Hypixel's raw trade messages to a file. A "
					+ "development tool - the parser it was collected for is written, so leave it "
					+ "off unless something stops being read correctly"),
			new Term("/flip guide <section>", "This guide, one section at a time")));

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
					+ "maximum capital per flip in your settings, which is a share of your bankroll "
					+ "rather than all of it"),
			new Term("ROC", "Return on capital: profit divided by capital, as a percent. How hard "
					+ "the coins work rather than how many coins come back. A 10% ROC flip beats a "
					+ "1% one at equal profit per hour, because it leaves the rest of your bankroll "
					+ "free for another flip"),
			new Term("Fill", "How long the slower leg is expected to take to turn the whole plan "
					+ "over. A tilde in front of it means the figure comes from an assumed share of "
					+ "the item's weekly flow rather than from displacement this client has "
					+ "recorded, which needs about an hour of continuous uptime to start "
					+ "measuring. A dash means the book never clears a position this size"),
			new Term("Outbid", "How often somebody posts inside your resting order, measured from "
					+ "recorded history. This is what decides whether a buy order fills or sits: a "
					+ "wide margin on an item you get outbid five times an hour is a margin you "
					+ "never actually get"),
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
			new Term("Dark Auction bid", "A Midas weapon's stats scale with the coins burned to win "
					+ "it, and the item remembers that bid. So these are not priced from what other "
					+ "Midas weapons sold for - which mixes a three million coin staff with a "
					+ "hundred million coin one - but from what they sold for per coin bid, applied "
					+ "to the bid this one carries. A staff bought for twice as much is quoted at "
					+ "about twice as much off the same sales"),
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

	private static final Section NPC_CAP = new Section("npccap", "The daily NPC coin cap", List.of(
			new Term("What it is", "NPCs stop paying out after a fixed number of coins each day, "
					+ "shared across every item and refilling at UTC midnight. It counts what the "
					+ "NPC hands you, not your profit, so it is spent by the sale price"),
			new Term("Why it changes the ranking", "An expensive item burns the budget far faster "
					+ "than a cheap one at the same profit per hour. Enchanted Diamond Blocks at "
					+ "204,800 each exhaust a 500M budget in about fifteen minutes; Fig Logs at 8 "
					+ "each could not spend it in a week. Plans are therefore ranked over a session "
					+ "rather than an hour, because a daily limit cannot be expressed hourly"),
			new Term("Cap efficiency", "Coins of profit per coin of budget spent, which is just the "
					+ "margin divided by the NPC price. When the cap is what limits you rather than "
					+ "your time, this is the number that decides what to spend the day on"),
			new Term("Resting window", "How long an NPC buy order is left sitting before you would "
					+ "rather have the coins back, in settings. Nothing is at risk while it rests - "
					+ "the NPC's price cannot move, so an order fills at your price or is "
					+ "cancelled - so this says how long capital may be tied up, and raising it "
					+ "promotes slow books over ones that fill while you watch"),
			new Term("How much is left", "Counted from your ledger: closed NPC flips, units sold "
					+ "times the NPC price. Flips you never recorded are invisible to it, so the "
					+ "budget will read fuller than it is if you trade outside the mod"),
			new Term("Order slots", "You can rest 14 bazaar orders at once, plus 7 per Bazaar "
					+ "Flipper level. One order holds 71,680 units of a stackable item or 256 of an "
					+ "unstackable one, so a large plan in unstackable items needs several slots and "
					+ "is rejected if it needs more than you have")));

	private static final Section LIQUIDITY = new Section("liquidity", "Liquidity", List.of(
			new Term("Weekly volume", "Units that changed hands over seven days, counted separately "
					+ "for each side. Instant-bought is how fast sell offers get lifted; "
					+ "instant-sold is how fast buy orders get filled"),
			new Term("Why it caps the plan", "What is resting on the book right now is a snapshot, "
					+ "not a supply. An item with 4000 units under the NPC price but 40 units of "
					+ "weekly turnover is not a 4000-unit opportunity, it is a week of holding "
					+ "something nobody wants. Every plan is sized from the flow, not the depth"),
			new Term("Trips", "NPC flips are also capped by hand: 36 inventory slots at a 64 stack, "
					+ "twelve round trips an hour. Buying is instant, selling is not. Unstackable "
					+ "items are the same 36 slots holding one each, so a trip carries 36 of them"),
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
					+ "press Abandon, or use /flip abandon <id>"),
			new Term("Automatic tracking", "/flip track fills the ledger from the trades Hypixel "
					+ "reports, instead of you typing them in. A buy claims the plan you took for "
					+ "that item, a sale closes it, and a position can close in pieces because one "
					+ "order often fills in pieces. Off by default, because a wrong entry is worse "
					+ "than an empty ledger"),
			new Term("It ignores your shopping", "Tracking only records trades against plans you "
					+ "took. Buying materials to use, selling what you farmed - none of that reaches "
					+ "the ledger, because a buy with nothing to sell against it would sit open for "
					+ "good and drag the fill rate down with it. Turn on \"Also track trades the mod "
					+ "never quoted\" in settings if you flip by hand and want that measured too"),
			new Term("Tracked without a quote", "What that setting produces: a trade the mod never "
					+ "suggested. It counts toward the fill rate but stays out of the capture rate, "
					+ "because there was no quote to fall short of and counting zero as the promise "
					+ "would report every ordinary trade as a total failure"),
			new Term("Forget", "Deletes an entry outright - /flip ledger forget <id>, or select it "
					+ "on the Ledger tab and press Forget twice. Abandon says a plan did not work "
					+ "out and keeps its units in the fill rate; Forget says the entry was never a "
					+ "flip and takes it out of every number. If tracking recorded a pile of ordinary "
					+ "trading before you noticed, /flip ledger clear unquoted removes all of it at "
					+ "once and leaves the flips you took"),
			new Term("Capture", "/flip capture writes Hypixel's raw trade messages to a file, and is "
					+ "a development tool rather than something you need. It exists so the parser "
					+ "that reads chat could be written against real wording. Nothing reads the file "
					+ "at runtime; leave it off unless a Skyblock update stops trades being read "
					+ "correctly and fresh wording has to be collected"),
			new Term("Open your orders menu", "Tracking reads chat, and Hypixel announces a partial "
					+ "fill nowhere in it. The bazaar orders menu is the only place an order that "
					+ "stopped half way shows up, so opening it now and then is what keeps a "
					+ "tracked position honest")));

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

	private static final Section SYNC = new Section("sync", "Collector sync", List.of(
			new Term("What it is", "A pull of the tape a collector on another machine recorded "
					+ "while this game was closed. Sales history is the one thing the mod cannot "
					+ "backfill: Hypixel only reports the last minute of completed auctions, so an "
					+ "hour nobody was recording is an hour nobody can price against"),
			new Term("When it runs", "By itself, about five seconds after the poller starts, on its "
					+ "own thread, so nothing waits for it and there is nothing to run by hand. "
					+ "/flip sync forces one mid-session; /flip status says what the last one did. "
					+ "Re-sync every is normally 0, meaning startup only - while this client is "
					+ "running it already tapes what the collector taped"),
			new Term("Merged, not copied", "Both machines record the same endpoints, so each holds "
					+ "sales the other missed. The two are folded together by sale id, and nothing "
					+ "on either side is overwritten or counted twice"),
			new Term("What it costs", "Only bytes the collector added since last time. The first "
					+ "sync is the whole retention window, which is hundreds of megabytes; after "
					+ "that it is minutes of data"),
			new Term("If it fails", "A log line and nothing else. The local tape is still the tape "
					+ "and the mod keeps working from it; the next sync picks up where this one "
					+ "stopped")));

	private static final Section LIMITS = new Section("limits", "What this does not do", List.of(
			new Term("Prices go stale", "Everything here is from the last poll. Re-check the book "
					+ "before you commit coins to any of it")));
}
