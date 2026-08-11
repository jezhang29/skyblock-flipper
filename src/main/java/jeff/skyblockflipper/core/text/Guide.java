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
		return List.of(START, COMMANDS, COLUMNS, STRATEGIES, ROUTES, NPC_CAP, BASKET, LIQUIDITY,
				LEDGER, SETTINGS, SYNC, LIMITS);
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
					new Term("Optional: the whole bazaar at once", "Steps 3 to 7 work one flip at a "
							+ "time. /flip npc plan instead fills every order slot you have with buy "
							+ "orders under NPC prices, sized so the set fits your bankroll once. It "
							+ "is a different loop: /flip track once so the mod can see your orders, "
							+ "place the whole basket, open Bazaar -> Manage Orders, then work it "
							+ "with /flip npc reprice whenever the reminder says something has been "
							+ "outbid, and sell what filled through /trades. Read the basket section "
							+ "before running one"),
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
			new Term("/flip npc plan", "Every NPC buy order to place right now, sized so that the "
					+ "whole set fits your order slots and your bankroll once. The ranked list sizes "
					+ "each row on its own, so following three of those spends the bankroll three "
					+ "times. Same thing as the Basket tab"),
			new Term("/flip npc reprice", "Checks the buy orders you already have resting: which "
					+ "have been outbid and where to move them, and which have been outbid past the "
					+ "point of being worth a slot. Needs /flip track on and the orders menu opened "
					+ "at least once, because that is the only place your real posted price exists"),
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

	private static final Section NPC_CAP = new Section("npc", "NPC flipping", List.of(
			new Term("The trade", "Post a buy order under the fixed price an NPC pays, then sell "
					+ "what fills through /trades. The exit price cannot move, so an order either "
					+ "fills at your price or does not fill. Coins in an unfilled order are stuck "
					+ "until you cancel it, never lost"),
			new Term("Margin floor", "How wide the gap has to be before a plan is offered, as a "
					+ "percent of the NPC price, in settings. The default 15% is the measured peak: "
					+ "over a day of simulated baskets, 15% made 172.5M against 104.6M at 5%, and "
					+ "above 20% too few items qualify to spend the day's budget"),
			new Term("Chase stop", "The same number read the other way. Never reprice a buy order "
					+ "above the NPC price less the floor - at 15%, above 85% of what the NPC pays. "
					+ "Past there the slot is worth more on another item, and the plan says the "
					+ "exact price to stop at"),
			new Term("Chase cost", "What staying at the front of the book costs over the window, "
					+ "measured from how far the best bid has actually drifted upward per hour. It "
					+ "is taken out of the margin before you see it, so the profit quoted is the "
					+ "profit after repricing"),
			new Term("Edge held", "The fraction of taped samples in which a buy order would have "
					+ "priced under the NPC. These gaps are mostly standing features rather than "
					+ "races: of 223 liquid items with a gap, 204 held it in over 95% of three days "
					+ "of samples. Anything below 95% is skipped, and that protects order slots "
					+ "rather than coins - a gap that vanishes costs you a slot for the window"),
			new Term("Check-in", "How often you intend to come back and move your orders to the top "
					+ "of the book, in settings. It is what fills get measured against: an order "
					+ "repriced every 30 minutes collects the 30-minute rate, and one posted and "
					+ "left alone for eight hours collects about a fifth as much"),
			new Term("Resting window", "How long an NPC buy order is left sitting before you would "
					+ "rather have the coins back, in settings. Nothing is at risk while it rests - "
					+ "the NPC's price cannot move - so this says how long capital may be tied up, "
					+ "and it is the window every NPC plan is sized over"),
			new Term("Daily coin cap", "NPCs stop paying out after a fixed number of coins each "
					+ "day, shared across every item and refilling at UTC midnight. It counts what "
					+ "the NPC hands you, not your profit, so it is spent by the sale price. It is "
					+ "large enough for roughly two eight-hour cycles, so it limits a day rather "
					+ "than a plan"),
			new Term("How much is left", "Counted from your ledger, at the NPC price: units sold on "
					+ "closed flips, plus everything an open NPC position bought to sell on. Open "
					+ "ones count because nothing announces a sale to an NPC, so waiting to be told "
					+ "would mean waiting forever. Flips you never recorded are invisible to it, so "
					+ "the budget will read fuller than it is if you trade outside the mod"),
			new Term("Order slots", "You can rest 14 bazaar orders at once, plus 7 per Bazaar "
					+ "Flipper level, to a maximum of 28, and settings can hold NPC plans to fewer "
					+ "if you share a coop bazaar. One order holds 71,680 units of a stackable item "
					+ "or 256 of an unstackable one, so a plan is trimmed to what your slots hold "
					+ "rather than quoting a size you cannot place"),
			new Term("Orders per line", "A basket line larger than one order says how it divides - "
					+ "3 x 256 + 112 means three full orders and one part-filled one, four slots in "
					+ "all. The mod works out which items stack by looking for an order bigger than "
					+ "256 resting on their book, because Hypixel's item list does not mark reforge "
					+ "stones or Jungle Hearts as unstackable and believing it once produced lines "
					+ "you could not place")));

	/**
	 * The basket, which is a different job from reading a ranked list.
	 *
	 * <p>Its own section rather than more terms under NPC flipping: that section says what the trade
	 * is, and this one is the loop you run - plan, place, come back, reprice, sell. A player who has
	 * understood every NPC term can still place a basket wrong by treating it as a top-ten list.
	 */
	private static final Section BASKET = new Section("basket", "The basket, and working it",
			List.of(
					new Term("What it is", "Every NPC buy order worth placing right now, sized so "
							+ "the whole set fits your order slots, your bankroll and the day's NPC "
							+ "budget exactly once. /flip npc plan prints it and the Basket tab shows "
							+ "it. The ranked list answers a different question: each row there is "
							+ "sized against your whole bankroll on its own, so following the top "
							+ "three would spend it three times"),
					new Term("Place all of it", "A ranking is a menu to choose one thing from. A "
							+ "basket is a list of things to do, and half of one spends the bankroll "
							+ "on half the plan while leaving the same slots idle"),
					new Term("Why it is ordered like that", "By profit per inventory load, not by "
							+ "margin. Measured over a full day: ranking on profit per load made "
							+ "76.4M, ranking on margin as a percent of the NPC price made 4.8M, "
							+ "because that fills every slot with 7-coin items and thousands of "
							+ "inventory loads. A 98% margin on something worth 7 coins is a rounding "
							+ "error once it is holding a slot"),
					new Term("The panel at the bazaar", "With Show basket at the bazaar on, the basket "
						+ "is drawn beside Hypixel's own menu the whole time you are in it: name, "
						+ "price to post, and how the units divide into orders. It re-plans itself "
						+ "every poll and highlights the row for whichever product page you have "
						+ "open. It stays up for a few seconds after the menu closes, which is what "
						+ "puts it on screen while you are typing an amount or a price into the "
						+ "sign, so nothing has to be remembered from chat. It only ever draws - "
						+ "nothing is clicked, filled in or placed for you"),
				new Term("Post at", "The price to type into Create Buy Order - one tenth above "
							+ "the best bid, which is what puts you at the front of the queue. It is "
							+ "not the same as the plan's cost per unit, which also carries the chase "
							+ "cost you have not paid yet"),
					new Term("What ran out", "The line under the totals names the resource that "
							+ "stopped the basket: order slots, coins, the day's NPC budget, or none "
							+ "of them - which means the market has nothing else clearing the "
							+ "filters. Only the first three are things you can change"),
					new Term("NPC coins", "How much of the day's budget this basket would collect, "
							+ "counted at the sale price rather than at the profit. A full basket "
							+ "sits comfortably inside it, which is why the cap limits a day rather "
							+ "than a plan"),
					new Term("The reprice round", "/flip npc reprice, every time you come back: which "
							+ "orders are still on top of the book, which have been outbid and what "
							+ "to move them to, and which have been outbid past the chase stop and "
							+ "should be cancelled. Replayed over one eight-hour cycle of 30-minute "
							+ "check-ins on recorded tape, 78% held, 20% wanted a move and 2% were "
							+ "past the stop"),
					new Term("Why coming back matters", "An order collects only while it is the best "
							+ "bid. Measured per eight-hour cycle: 11.5M posting once and walking "
							+ "away, 59.7M returning every 30 minutes, 73.2M for staying permanently "
							+ "on top. It flattens below an hour, so checking in more often than "
							+ "every 15 minutes buys very little"),
					new Term("The reminder", "You do not have to watch the clock. With Remind me to "
							+ "reprice on, a line appears in chat when the book has actually moved "
							+ "past your orders - never merely because the interval elapsed - and at "
							+ "most once per check-in interval. Click the [reprice] on the end of it "
							+ "to run the round. Asking for a basket or a reprice yourself restarts "
							+ "the interval, so it never arrives on top of the list it points at"),
					new Term("What it takes to be told", "The reminder speaks when the outbid orders "
							+ "are worth more than Minimum profit per flip put together, or when any "
							+ "one order has been chased past the stop. A cancel is always worth "
							+ "saying however small, because it is coins parked in a trade that can "
							+ "no longer pay plus an order slot held by it"),
					new Term("Cancelling costs nothing", "The NPC price cannot move, so an order the "
							+ "book has chased past the stop is coins parked, not coins lost. Cancel "
							+ "it, take the coins back, and put the slot on something else"),
					new Term("It has to see your orders", "Repricing compares the price your order "
							+ "actually rests at against the book, and the bazaar orders menu is the "
							+ "only place that price exists - the plan's own figure includes chasing "
							+ "it had not paid for yet. So turn on /flip track and open the orders "
							+ "menu once, or the command has nothing to work from"),
					new Term("Nothing is placed for you", "Every order in a basket is placed by hand, "
							+ "and so is every reprice. The mod says what to click and never clicks "
							+ "it: automating bazaar orders is a macro, and macros are against "
							+ "Hypixel's rules")));

	private static final Section LIQUIDITY = new Section("liquidity", "Liquidity", List.of(
			new Term("Weekly volume", "Units that changed hands over seven days, counted separately "
					+ "for each side. Instant-bought is how fast sell offers get lifted; "
					+ "instant-sold is how fast buy orders get filled"),
			new Term("Why it caps the plan", "What is resting on the book right now is a snapshot, "
					+ "not a supply. An item with 4000 units under the NPC price but 40 units of "
					+ "weekly turnover is not a 4000-unit opportunity, it is a week of holding "
					+ "something nobody wants. Every plan is sized from the flow, not the depth"),
			new Term("Hauling", "How many inventory loads an NPC plan moves: 36 slots at a 64 "
					+ "stack, or 36 unstackable items - the same stacking the order split is "
					+ "worked out from. There is no walking involved - /trades "
					+ "reaches a shop from anywhere with a booster cookie - so this is a count of "
					+ "how much clicking the plan is, not a limit on it"),
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
