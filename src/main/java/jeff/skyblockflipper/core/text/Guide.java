package jeff.skyblockflipper.core.text;

import java.util.List;

/**
 * What every column, term and number in this mod means.
 *
 * <p>Lives in {@code core} and holds the text once, because chat and the flip screen both explain
 * the same vocabulary and two explanations that drift apart are worse than none. A ranking is only
 * worth acting on if the person acting on it knows what it is claiming, and a column labelled "ROC"
 * with no way to find out what ROC is claims nothing.
 *
 * <p>Written for a player, not for a maintainer: plain words, no field names, no file names, and no
 * measurement dumps. Where a number decided a default, one comparison is enough to say why.
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
		return List.of(START, COMMANDS, COLUMNS, STRATEGIES, ROUTES, NPC_CAP, BASKET, CRAFT, COMBINE,
				LIQUIDITY, LEDGER, SETTINGS, SYNC, LIMITS);
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
					new Term("1. Say how much you can spend", "Open the flip screen (the keybind, or "
							+ "/flip gui) and press Settings, or type /flip config edit. Set Bankroll "
							+ "to the coins you are happy to have tied up at once. Everything is sized "
							+ "to fit inside it, so this is the one setting you cannot skip"),
					new Term("2. Wait for prices to arrive", "Bazaar prices are fetched every 20 "
							+ "seconds, and the first fetch lands a few seconds after you join. /flip "
							+ "status says how old the prices are. Until they arrive the list is empty"),
					new Term("3. Ask for a list", "/flip, typed with nothing after it, ranks every kind of "
							+ "flip together. "
							+ "/flip bazaar, /flip npc, /flip craft, /flip combine and /flip snipe ask for one kind. "
							+ "The list is "
							+ "sorted by profit per hour after fees, so rank 1 is the best thing the "
							+ "mod can see right now"),
					new Term("4. Read one row before trusting it", "Click the row in the flip screen. "
							+ "The panel on the right says what to buy, at what price, how many, and "
							+ "what has to happen for it to pay. Anything the mod is unsure about is "
							+ "listed there as a risk"),
					new Term("5. Take it", "/flip take 1, or the Take button, writes the plan down with "
							+ "the numbers you are looking at. This does not trade for you - you still "
							+ "place the order yourself, in the game. The mod never touches your "
							+ "account"),
					new Term("6. Do the trade in game", "Copy the item name with the Copy name button, "
							+ "paste it into the bazaar search, and place the order the plan describes. "
							+ "The mod is a calculator, not a bot"),
					new Term("7. Say what happened", "When the coins come back, /flip close <id> <units "
							+ "sold> <price each>. The id is the four characters /flip take printed. If "
							+ "the order never filled, use /flip abandon <id> instead"),
					new Term("8. Check whether it works", "/flip ledger lists the flips you have open "
							+ "and two numbers: how much of the promised profit you really got, and how "
							+ "much of what you planned really filled. Those two are the only evidence "
							+ "that any of this is worth doing"),
					new Term("Optional: the whole bazaar at once", "Steps 3 to 7 are one flip at a "
							+ "time. /flip npc plan instead fills every order slot you have with buy "
							+ "orders under what NPCs pay, sized so the whole set fits your bankroll "
							+ "once. It is a different routine: turn on /flip track so the mod can see "
							+ "your orders, place the whole list, open Bazaar -> Manage Orders, then "
							+ "come back with /flip npc reprice whenever the reminder says you have "
							+ "been outbid, and sell what filled through /trades. Read the basket "
							+ "section before running one"),
					new Term("Optional: let it write things down for you", "/flip track fills your "
							+ "ledger from the trades Hypixel announces, so steps 5 and 7 happen on "
							+ "their own for plans you took. Read the Ledger section first - it only "
							+ "records trades against plans you took, unless you tell it otherwise")));

	/** Every command, in one place, because a command you cannot remember does not exist. */
	private static final Section COMMANDS = new Section("commands", "Commands", List.of(
			new Term("/flip", "The ranked list. /flip bazaar, /flip npc, /flip craft, /flip combine and "
					+ "/flip snipe show one kind of flip only"),
			new Term("/flip gui", "The full screen: sortable list, the reasoning behind each row, your "
					+ "ledger and this guide. The keybind opens the same thing"),
			new Term("/flip npc plan", "Everything to do at the bazaar right now: what to collect, "
					+ "cancel and reprice on the orders you already have, then the new orders to place "
					+ "with the slots and coins that are left. The ranked list sizes each row on its "
					+ "own, so following three rows spends your bankroll three times. Same thing as the "
					+ "Basket tab"),
			new Term("/flip npc reprice", "The same list with the new orders left off - what a trip "
					+ "back to a full book is really about. Needs /flip track on and the orders menu "
					+ "opened at least once, because that is the only place your real posted price "
					+ "shows up"),
			new Term("/flip take <rank>", "Writes down the flip on that line of the last list you were "
					+ "shown, at the numbers you were shown"),
			new Term("/flip close <id> <units> <price>", "Closes a flip with what really happened. "
					+ "Price is per item, as the game shows it - fees are taken off for you"),
			new Term("/flip abandon <id>", "Ends a flip that never filled. Its units still count "
					+ "against your fill rate, and nothing about it counts toward your profit rate"),
			new Term("/flip ledger", "Your open flips, coins committed, and how well the mod's promises "
					+ "have held up"),
			new Term("/flip ledger forget <id>", "Deletes an entry as if it had never been written "
					+ "down. For things that were not flips at all"),
			new Term("/flip ledger clear unquoted", "Counts the entries that came from trades the mod "
					+ "never suggested, then deletes them all if you repeat the command with confirm on "
					+ "the end. /flip ledger clear confirm empties the whole ledger"),
			new Term("/flip status", "Whether prices are still updating, how old they are, how much "
					+ "history is stored, and what the last history fetch did"),
			new Term("/flip config", "Prints the settings that change the list, and where they are "
					+ "saved. /flip config edit opens them as a screen"),
			new Term("/flip reload", "Re-reads the settings file after you edited it by hand"),
			new Term("/flip sync", "Fetches your recorder's price history now instead of waiting for "
					+ "the next launch"),
			new Term("/flip hud", "Turns the corner list of top flips on and off"),
			new Term("/flip track", "Turns on filling your ledger from your real trades"),
			new Term("/flip capture", "Turns on saving Hypixel's raw trade messages to a file. A repair "
					+ "tool - leave it off unless something has stopped being read correctly"),
			new Term("/flip menu", "Prints the buttons of the last menu you had open and which slot "
					+ "each one is in. You cannot type in chat with a menu open, so open the menu, "
					+ "close it, then run this. It is how a button the green box has stopped finding "
					+ "gets identified"),
			new Term("/flip guide <section>", "This guide, one section at a time")));

	private static final Section COLUMNS = new Section("columns", "Columns", List.of(
			new Term("#", "The row's place in the list as you have it sorted. /flip take <#> writes "
					+ "down that row at the numbers in front of you"),
			new Term("Item", "The name Hypixel's own item list gives it, which is not always the name "
					+ "you remember. If another item's name starts with this one, the row says so - the "
					+ "bazaar search will show you both"),
			new Term("Profit/hr", "Coins per hour after every fee, and what the list is sorted on. Not "
					+ "the margin: a 15% margin on something that trades four a day is worth less than "
					+ "2% on something moving half a million an hour"),
			new Term("Capital", "Coins tied up to run the plan for an hour. Never more than the share "
					+ "of your bankroll one flip is allowed to spend"),
			new Term("ROC", "Return on capital: profit as a percentage of the coins it needs. How hard "
					+ "your coins work, rather than how many come back. At equal profit, the flip using "
					+ "fewer coins wins, because the rest of your bankroll is free for another one"),
			new Term("Fill", "How long the slower half of the flip should take to finish. A tilde in "
					+ "front means it is an estimate from the item's weekly trading rather than from "
					+ "fills this game has watched, which takes about an hour of play to start "
					+ "measuring. A dash means the market never clears an order this big"),
			new Term("Outbid", "How often somebody posts just inside your resting order, counted from "
					+ "recorded history. It decides whether a buy order fills or just sits: a wide "
					+ "margin on an item where you are outbid five times an hour is a margin you never "
					+ "actually get"),
			new Term("Conf", "Confidence, 0 to 1: how much the numbers behind the row deserve to be "
					+ "trusted. High for a fixed NPC price you buy into instantly, lower for anything "
					+ "resting on an estimate, a wait, or a thin market")));

	private static final Section STRATEGIES = new Section("strategies", "Kinds of flip", List.of(
			new Term("Bazaar", "Buy low and sell high on the same item: post a buy order, wait, post a "
					+ "sell offer, keep the gap. You are being paid for waiting by people who want "
					+ "coins or materials right now"),
			new Term("NPC", "The bazaar price has dropped below the fixed price a shop NPC pays. There "
					+ "is no bazaar tax on it, because selling to an NPC is not a bazaar trade"),
			new Term("Craft", "Buy the materials for a recipe on the bazaar, craft it, and sell the "
					+ "result back on the bazaar. You are paid for the work of putting it together, "
					+ "and it uses none of the daily coin limit NPC flips run into"),
			new Term("Combine", "Buy cheap low-tier enchanted books, combine them up to a dearer tier at "
					+ "the anvil, and sell the top tier. You are paid for the anvil tedium, so the honest "
					+ "measure is coins per combine, not per hour - the main list ranks it low on purpose"),
			new Term("Snipe", "An auction listed below what that exact item has really been selling "
					+ "for. Worth is learned from completed sales only, never from what other people "
					+ "are asking"),
			new Term("Attributes", "Kuudra and Crimson Isle gear rolls two attributes, each with a "
					+ "level, and the level is most of the price - a level 1 roll is worth about what "
					+ "the plain item is, a high one several times that. So an item is only compared "
					+ "against sales with the same roll at the same level. That is a small pool, so "
					+ "attribute gear often shows no price at all rather than a confident wrong one"),
			new Term("Pet level", "A pet's level is written into its name and is most of what it is "
					+ "worth: the same pet at level 1 and level 100 can differ several times over. Pets "
					+ "are compared against their own level first, then nearby levels, then any level, "
					+ "taking the first with enough sales behind it. Anything past the first step is "
					+ "marked as less certain and says so in the risks"),
			new Term("Dark Auction bid", "A Midas weapon's stats depend on the coins spent to win it, "
					+ "and the item remembers that bid. So these are priced from what others sold for "
					+ "per coin bid, applied to this one's bid, instead of lumping a three million coin "
					+ "staff in with a hundred million coin one"),
			new Term("Ledger", "The flips you took and what they really did. The only part of this mod "
					+ "that can tell you whether the rest of it works"),
			new Term("Stars and essence", "Hypixel publishes what each star costs, and every ingredient "
					+ "trades on the bazaar, so a starred item's essence bill is worked out exactly "
					+ "rather than guessed. It is priced at what starring one yourself would cost "
					+ "today. Cost is not worth: the market sets its own premium for the work, and "
					+ "sometimes pays under cost. What it tells you is how much of an asking price is "
					+ "just materials you could buy yourself")));

	private static final Section ROUTES = new Section("routes", "Buying: order or instant?", List.of(
			new Term("Instant buy", "Take the cheapest sell offer on the board. You get the items now "
					+ "and pay the higher price for them"),
			new Term("Buy order", "Post an offer a tenth of a coin above the best one and wait for "
					+ "somebody to sell into it. You pay the lower price, which on a wide market is "
					+ "most of the profit, but it only fills as fast as people sell"),
			new Term("Which one", "For NPC flips both are priced and the better one by profit per hour "
					+ "is what the plan says, with the other shown underneath. Waiting costs nothing "
					+ "against an NPC price, which cannot move away from you; a bazaar price can, which "
					+ "is why bazaar flips come with a rule for when to give up instead")));

	private static final Section CRAFT = new Section("craft", "Crafting to sell", List.of(
			new Term("The trade", "/flip craft lists recipes whose materials cost less on the bazaar "
					+ "than the finished item sells for. Every price is a live bazaar price on both "
					+ "ends, so nothing here depends on guessing what an item is worth"),
			new Term("Which way it is moving", "A recipe is only offered while its margin is holding. "
					+ "The recorded drift of the finished item and of every material is combined into "
					+ "one number - what the materials are doing, weighted by what they cost, against "
					+ "what the output is doing - and a recipe whose margin is closing faster than "
					+ "your adverse-drift setting is left out. An output falling on materials falling "
					+ "faster is a widening margin and stays in"),
			new Term("How it is sold", "Always as a sell offer a tenth of a coin under the cheapest "
					+ "one on the board, never dumped into the buy orders and never sold to an NPC. "
					+ "Measured across the whole recipe list, offering it is worth about ten times "
					+ "dumping it, and an NPC sale would eat into the daily coin limit your NPC "
					+ "flipping needs"),
			new Term("How the materials are bought", "Both ways are priced and the better one by "
					+ "profit per hour is what you are shown. Buy orders are usually the winner by a "
					+ "long way, because farmed materials are dumped into buy orders constantly and "
					+ "bought instantly hardly ever, so an order both costs less and fills faster"),
			new Term("Order slots", "Each material bought on an order takes a slot, and so does the "
					+ "sell offer at the end. Those are the same slots your NPC basket wants, so a "
					+ "limit in settings caps how many one job may take. A job over the limit is shown "
					+ "with its materials bought instantly instead, which needs only the one slot"),
			new Term("Working one", "Click a craft row in the flip screen and the bazaar panel "
					+ "follows that job instead of the basket: the materials, the craft and the sell "
					+ "offer, each with the price and the amount to type, beside Hypixel's own menu. "
					+ "Click a name or a number to copy it. The prices are re-worked every poll, so "
					+ "what the panel shows is the book as it is, not as it was when you picked the "
					+ "row. /flip craft stop puts the basket back, and so does clicking any other "
					+ "kind of flip"),
			new Term("Falling behind", "If the flip stops clearing while you are working it - the "
					+ "output crashes, a material climbs - the panel says so and stops giving prices "
					+ "rather than quoting numbers that no longer earn anything"),
			new Term("Unlocks", "Recipes have collection and skill requirements, and nothing here "
					+ "knows what you have unlocked. The requirement is printed with the job; if you "
					+ "cannot craft it yet, the materials you bought are just materials"),
			new Term("What it does not do", "Every material is priced at what it costs to buy. "
					+ "Whether crafting an ingredient yourself would be cheaper than buying it is not "
					+ "worked out")));

	private static final Section COMBINE = new Section("combine", "Combining books", List.of(
			new Term("The trade", "/flip combine lists enchanted books that are cheaper bought low and "
					+ "combined up than bought at the tier you sell. Two books of one tier make one of "
					+ "the next at the anvil, so a tier-10 book is sixteen tier-6 books and fifteen "
					+ "merges. Both ends are live bazaar prices"),
			new Term("Coins per combine, not per hour", "The books are thin, so this makes little per "
					+ "hour and the main list ranks it low. Its point is the return per anvil click, "
					+ "which the row prints as a note: a player who cannot sit at the game all day is "
					+ "spending clicks, not hours, and this pays a lot of coins for a few of them"),
			new Term("Where it sources", "The cheapest source tier is not always the bottom one. A "
					+ "low tier can carry a fat buy-order price from everyone else combining it, so the "
					+ "mod prices every listed tier below the target and buys from the cheapest, which "
					+ "is often a tier or two up"),
			new Term("The middles are dead", "You combine straight through the tiers between, never "
					+ "trading them. Their books are nearly empty and that is fine - nothing rests on "
					+ "them"),
			new Term("How it is sold", "Always a sell offer a tenth of a coin under the cheapest one on "
					+ "the target's board. The high tiers have huge spreads, so dumping into the buy "
					+ "orders is a loss almost everywhere. The exit uses none of the NPC daily coin "
					+ "limit"),
			new Term("The fantasy-price guard", "A book is only offered when its top tier has at least "
					+ "fifteen sell offers resting. A tier priced by a single seller at a made-up number "
					+ "is not a real price, and this is the filter that tells the two apart"),
			new Term("Working one", "Click a combine row in the flip screen and the bazaar panel "
					+ "follows that job instead of the basket: the source buy, the anvil merges and the "
					+ "sell offer, each with the price and the amount to type, beside Hypixel's own menu. "
					+ "Click a name or a number to copy it. The prices are re-worked every poll, so the "
					+ "panel shows the book as it is, not as it was when you picked the row. /flip combine "
					+ "stop puts the basket back, and so does clicking any other kind of flip"),
			new Term("Unverified", "Nothing here has been combined and sold in play yet, and the anvil "
					+ "is assumed to cost no coins. Treat the first runs as a test")));

	private static final Section NPC_CAP = new Section("npc", "NPC flipping", List.of(
			new Term("The trade", "Post a buy order under the fixed price a shop NPC pays, then sell "
					+ "what fills through /trades. The selling price cannot move, so an order either "
					+ "fills at your price or does not fill at all. Coins in an unfilled order are "
					+ "stuck until you cancel it, never lost"),
			new Term("Minimum gap under the NPC price", "How much cheaper than the NPC you have to buy "
					+ "before the flip is offered, in settings. The default 15% earned the most in "
					+ "testing: a smaller gap is eaten by chasing the price up, and a bigger one leaves "
					+ "too few items to fill your slots"),
			new Term("Where to stop chasing", "The same number read backwards. Never raise a buy order "
					+ "above the NPC price minus that gap - at 15%, never above 85% of what the NPC "
					+ "pays. Past there the slot is worth more on another item, and the plan tells you "
					+ "the exact price to stop at"),
			new Term("What chasing costs", "Staying at the front of the queue means raising your price "
					+ "now and then, and that is measured from how fast the best offer has really been "
					+ "climbing. It is taken off before you see the profit, so the number shown is the "
					+ "profit after chasing"),
			new Term("Paying to stay on top", "Spending those same coins on your opening price "
					+ "instead, so the order sits above the crowd and is not outbid until the crowd "
					+ "climbs to it. This was a setting and was removed. Recorded prices said it "
					+ "worked, but every one of those recordings came from a market with none of your "
					+ "own orders in it. Tried overnight in play, an order posted 3.9% above the book "
					+ "held first place for about ten minutes of eleven hours: a rival simply parks a "
					+ "coin or two above your order, whatever you paid. Post at the plain price and "
					+ "raise it when you check in"),
			new Term("Probe", "/flip npc probe <item> works out a higher price for one item, then "
					+ "watches whether anyone outbids an order left there. All the recorded history "
					+ "comes from a market with none of your orders in it, so whether rivals will "
					+ "climb above whatever is on top is the one thing history cannot answer - it "
					+ "answered no, which is why paying to stay on top is gone. The probe stays, "
					+ "because it is how you would find out otherwise. Run one per item, on as many "
					+ "items as you like, and /flip npc probe reports them all. A fill ends that "
					+ "item's probe. It is remembered only while the game is open"),
			new Term("Nudge", "Being outbid by the bazaar's own \"+0.1\" button rather than by somebody "
					+ "deliberately pricing the item. Across three days these were most of the upward "
					+ "moves and under 1% of the climbing, so they are not what a higher opening price "
					+ "would be paid to sit above. The probe reports an outbid of a coin or less as a "
					+ "nudge, and counts how often your order took first place back"),
			new Term("Item names", "Anywhere the mod asks for an item you can type the name you read in "
					+ "game, in any capitalisation and any word order. The internal codes cannot be "
					+ "guessed from names - Nether Wart Distillate is NETHER_STALK_DISTILLATE - so do "
					+ "not try. Tab completes both, and a name matching several items lists them "
					+ "instead of picking one"),
			new Term("What the basket should favour", "Which budget the basket spends first. Favouring "
					+ "fewer trips picks the items that earn most per inventory load, which keeps the "
					+ "carrying down. Favouring coins picks the most per order slot, which is what "
					+ "really runs out - about a third more coins for about three times the hauling"),
			new Term("Gaps that hold", "Most gaps under the NPC price are permanent features rather "
					+ "than brief races: of 223 busy items with a gap, 204 kept it in more than 95% of "
					+ "three days of samples. Anything below that is skipped. This protects order slots "
					+ "rather than coins - a gap that closes costs you a slot for the day, not money"),
			new Term("Check in every", "How often you intend to come back and move your orders to the "
					+ "front of the queue, in settings. It is what fills are measured against: an order "
					+ "you tidy every 30 minutes collects far more than one posted and left alone all "
					+ "day. It is also how long a reprice list keeps its prices"),
			new Term("What to set it to", "30 minutes, unless you know you will do better. Over an "
					+ "eight hour stretch, coming back hourly earns about 54M, every 30 minutes about "
					+ "60M, and every 15 minutes about 67M, so anywhere from 15 minutes to an hour is "
					+ "close to the default. Set it to what you will really do: plans are sized with "
					+ "it, so claiming 15 minutes and behaving like an hour promises fills you never "
					+ "collect"),
			new Term("Give up on an order after", "How long a buy order may sit before you would rather "
					+ "have the coins back, in settings. Nothing is at risk while it waits, because the "
					+ "NPC price cannot move, so this only says how long your coins may be tied up"),
			new Term("Daily NPC coin limit", "NPCs stop buying after they have paid out a fixed number "
					+ "of coins each day, shared across every item and reset at midnight UTC. It counts "
					+ "what the NPC hands you, not your profit. It is big enough for roughly two eight "
					+ "hour stretches, so it limits a day rather than a single plan"),
			new Term("How much is left", "Counted from your ledger at the NPC price: what closed flips "
					+ "sold, plus everything your open NPC flips bought to sell on. Open ones count "
					+ "because nothing announces a sale to an NPC. Flips you never wrote down are "
					+ "invisible to it, so the budget looks fuller than it is if you trade outside the "
					+ "mod"),
			new Term("Order slots", "You can have 14 bazaar orders resting at once, plus 7 per Bazaar "
					+ "Flipper level up to 28, and settings can hold NPC plans to fewer if you share a "
					+ "coop bazaar. One order holds 71,680 of a stackable item or 256 of an unstackable "
					+ "one, so plans are trimmed to what your slots can hold"),
			new Term("Orders per line", "A line needing more than one order says how it splits - 3 x "
					+ "256 + 112 means three full orders and one part order, four slots in all. The mod "
					+ "works out what stacks by watching for orders bigger than 256 on the market, "
					+ "because Hypixel's item list wrongly marks things like reforge stones as "
					+ "stackable and believing it produced lines you could not place")));

	/**
	 * The basket, which is a different job from reading a ranked list.
	 *
	 * <p>Its own section rather than more terms under NPC flipping: that section says what the trade
	 * is, and this one is the loop you run - plan, place, come back, reprice, sell. A player who has
	 * understood every NPC term can still place a basket wrong by treating it as a top-ten list.
	 */
	private static final Section BASKET = new Section("basket", "The basket, and working it",
			List.of(
					new Term("What it is", "One list of things to click: the orders already resting "
							+ "first - anything filled to collect, anything to cancel, anything outbid "
							+ "- and then the new orders to place with whatever slots and coins are "
							+ "left. /flip npc plan prints it, the Basket tab shows it, and the panel "
							+ "at the bazaar draws it. The ranked list answers a different question: "
							+ "every row there is sized against your whole bankroll on its own, so "
							+ "following the top three would spend it three times"),
					new Term("The order to work it in", "Top down, which is not the order the coins are "
							+ "in. Collect first, because those coins are already earned and the items "
							+ "cannot go to an NPC while they sit in the order. Cancel next, because "
							+ "that hands back the order slots everything below needs. Then the price "
							+ "changes, then the new orders, which need the coins the cancels just "
							+ "returned"),
					new Term("Place all of it", "A ranking is a menu you pick one thing from. A basket "
							+ "is a list of jobs, and doing half of it spends your bankroll on half a "
							+ "plan while leaving the same slots idle"),
					new Term("Coming back later", "You never have to choose between repricing and "
							+ "planning again - it is one list and it already knows. Orders you have "
							+ "resting are taken out of the slots and the coins before anything new is "
							+ "sized. If nothing new appears, every slot is already working"),
					new Term("Finishing a part-placed line", "A line of 1,024 unstackable items is four "
							+ "orders and you type them one at a time. The row stays until the whole "
							+ "line is up and counts down as you place it - 1,024 as 4 x 256, then 768 "
							+ "as 3 x 256 - so what is left to type is always on the row, and the note "
							+ "beside it says how many are already resting. It stops the moment "
							+ "anything else asks about that item, so you never end up bidding against "
							+ "yourself with two prices on one item"),
					new Term("Why it is ordered like that", "By profit per inventory load rather than "
							+ "by margin. Over a full day, ordering by profit per load earned about "
							+ "76M and ordering by margin earned under 5M, because ordering by margin "
							+ "fills every slot with 7-coin items and thousands of trips. A 98% margin "
							+ "on something worth 7 coins is nothing once it is holding a slot"),
					new Term("The panel at the bazaar", "With \"Show basket at the bazaar\" on, the "
							+ "list is drawn beside Hypixel's menu the whole time you are in it: what "
							+ "to do, the price to type, and how the items split into orders. It "
							+ "updates itself as prices come in and highlights the row for whichever "
							+ "item page you have open. It stays up for a few seconds after the menu "
							+ "closes, which is what keeps it on screen while you type an amount or a "
							+ "price into the sign. With nothing to click it still draws one line - how "
							+ "many orders have been outbid, and how long until they are next looked "
							+ "at - because a panel that vanishes looks like one that has broken"),
					new Term("Scrolling and copying it", "The wheel scrolls the panel when the list is "
							+ "longer than the screen. Clicking a row copies the item name for the "
							+ "search sign; clicking the price or the amount on the second line copies "
							+ "that number instead. The Basket tab has the same three as buttons. "
							+ "Nothing is ever clicked, typed or placed for you, and a click that lands "
							+ "on the panel never reaches the menu behind it"),
					new Term("The green box in the menu", "With \"Highlight the slot to click\" on, the "
							+ "slot the top row needs next has a green box behind it, the whole way "
							+ "through an order: Search, the item in the results, Create Buy Order, the "
							+ "amount sign, the price sign, then confirm. On your resting orders it "
							+ "lands on the row itself - left click to collect one that has filled, "
							+ "right click for its options. It is worked out from the menu in front of "
							+ "you, and where that cannot be done nothing is drawn: two orders on one "
							+ "item at different prices, a page for another item, a button Hypixel has "
							+ "renamed. Nothing is clicked for you"),
					new Term("Top Order +0.1 or the price sign", "On the price page the box goes on "
							+ "Hypixel's own \"Top Order +0.1\" button whenever the price it offers is "
							+ "at or below the plan's price. That button reads the live market, so it "
							+ "cannot be out of date, and it is one click instead of typing. Where it "
							+ "offers more than the plan, the market has moved up since the plan was "
							+ "made and the box goes on Custom Price instead: paying more than planned "
							+ "spends the profit the flip is made of, so type the number and let the "
							+ "order wait for the market to come back"),
					new Term("What to type on the sign", "The amount and the price are typed on a sign "
							+ "that replaces the menu asking for them. So the panel keeps the number on "
							+ "screen for a minute and a half after the menu closes and says which box "
							+ "it is for, and every screen along the way refreshes that. Long item "
							+ "names are offered shortened, because a sign only holds so much text; "
							+ "the bazaar searches on the start of a name, so the short form finds the "
							+ "same item"),
					new Term("Which side it sits on", "Left, Right or Automatic. Automatic takes "
							+ "whichever side of Hypixel's menu has more room, which gives the widest "
							+ "panel but moves it: Hypixel's menus differ in width, so it changes sides "
							+ "as you cross the bazaar's screens. Left and Right keep it where you put "
							+ "it and only move if there is no room at all"),
					new Term("Post at", "The price to type into Create Buy Order - a tenth of a coin "
							+ "above the best offer, which puts you at the front of the queue. It is "
							+ "not the same as the plan's cost per item, which also allows for the "
							+ "chasing you have not paid for yet"),
					new Term("What ran out", "The line under the totals names what stopped the basket: "
							+ "order slots, coins, the day's NPC limit, or none of them - which means "
							+ "the market has nothing else worth buying. Only the first three are "
							+ "things you can change"),
					new Term("NPC coins", "How much of the day's NPC limit this basket would use up, "
							+ "counted at the selling price rather than at the profit. A full basket "
							+ "sits comfortably inside it"),
					new Term("The reprice round", "/flip npc reprice, every time you come back: which "
							+ "orders are still in front, which have been outbid and what to move them "
							+ "to, and which have been chased too far and should be cancelled. Replayed "
							+ "over an eight hour stretch with 30 minute check-ins, about 78% held "
							+ "their place, 20% wanted moving and 2% were past the point of chasing"),
					new Term("Why it is a round", "The prices are fixed when the round opens and do not "
							+ "move while you work it, so you can read a number, walk to the menu and "
							+ "type it. Chasing the market instead is hopeless on these items: one live "
							+ "sample of Transmission Tuner had five bots sitting a tenth of a coin "
							+ "apart, so an order posted at the top was outbid before you could reach "
							+ "an NPC. Being one step down for up to one check-in costs about 1%, and "
							+ "chasing every move by hand is not possible at all"),
					new Term("The price to type", "Use the bazaar's own \"+0.1 coins\" button. It works "
							+ "out the same thing the mod does, from the live market in front of you, "
							+ "and the mod is always a little behind that. The number on the row is "
							+ "what to expect and what the profit was worked out from, not something "
							+ "you have to match exactly. The only extra rule is where to stop "
							+ "chasing, and a row that has already passed that point is taken off the "
							+ "list rather than left for you to catch"),
					new Term("When the next round opens", "One check-in later than the last one, "
							+ "whether or not you finished it. Nothing you do opens one early - leaving "
							+ "the bazaar means nothing, since you have to leave it to sell to the NPC. "
							+ "In between, the panel and the list say how many orders have been outbid "
							+ "and how long until they are looked at"),
					new Term("Why an order can be missing", "An order has to be at least one check-in "
							+ "old to enter a round, so orders you just placed are not immediately "
							+ "something to reprice. Orders that were already resting the first time "
							+ "the mod saw your menu are exempt - it cannot tell how old those are, and "
							+ "logging in to a basket that was outbid overnight is the case that "
							+ "matters most"),
					new Term("Collecting and dead orders do not wait", "Coins waiting to be collected "
							+ "are already earned and they block the items from leaving the order. An "
							+ "order chased too far, or one that has sat past its time limit, is a "
							+ "trade that is over with your coins stuck in it. Neither improves by "
							+ "waiting for the next round, so both are listed as soon as they are true"),
					new Term("When a price change is worth making", "It is worth it when the fills you "
							+ "would win back over the rest of the interval, times the margin, beat "
							+ "your minimum profit per flip. On a busy item a repost might be worth 3k "
							+ "over half an hour and is dropped; the same order on a quiet item can be "
							+ "worth 10k and is kept. Where nothing has been measured yet it is always "
							+ "offered, because a fresh install has no history and guessing would mute "
							+ "the mod hardest for the people who know least"),
					new Term("One row per item", "A reprice row covers every order you hold on that "
							+ "item - \"cancel 4, repost 4 x 256\" - because four rows quoting one "
							+ "price is four times the reading for one decision"),
					new Term("Place first or cancel first", "The bazaar cannot edit a resting order, so "
							+ "changing a price means cancelling and posting again, and in between the "
							+ "item is collecting nothing. Where a spare slot and the coins for one "
							+ "order are free, the row says post the new one first and skip that gap. "
							+ "Where they are not, the row is held: its price, its slot and its coins "
							+ "stay reserved for the rest of the round, so nothing else can spend them "
							+ "while you are halfway through"),
					new Term("Why coming back matters", "An order only earns while it is the best "
							+ "offer. Over an eight hour stretch: about 11M for posting once and "
							+ "walking away, about 60M for coming back every 30 minutes, and about 73M "
							+ "for staying in first place the whole time. It flattens out below an "
							+ "hour, so checking in more often than every 15 minutes buys very little"),
					new Term("The reminder", "You do not have to watch the clock. With \"Remind me to "
							+ "reprice\" on you get a pop-up in the corner, a line in chat and a note "
							+ "once per round that has work in it. Three at once because Skyblock chat "
							+ "scrolls fast enough to lose a line; the pop-up is the one that waits for "
							+ "you. Click [reprice] on the end of the chat line to run the round. "
							+ "Asking for a plan yourself, or having the panel on screen at the bazaar, "
							+ "uses up that round's reminder, so it never arrives on top of the list it "
							+ "points at"),
					new Term("What it counts", "The rows in the round, not every order the market has "
							+ "moved past. An order the round did not fix a price for is not in the "
							+ "list the reminder opens, so counting it would send you to a list without "
							+ "it. Something to collect or cancel that turns up part way through a "
							+ "round is listed straight away and announced at the next opening, which "
							+ "is at most one check-in away"),
					new Term("If it never says anything", "Most likely nothing has ever shown it your "
							+ "orders. Hypixel's chat line about a placement gives the size and the "
							+ "total but never the price per item, and the price is the whole point of "
							+ "a reprice - so open Bazaar -> Manage Orders once per session. It now "
							+ "says so itself: if you see \"cannot see your orders\", that is this. "
							+ "Otherwise check /flip track is on, and remember that asking for a plan "
							+ "yourself uses up the reminder"),
					new Term("What it takes to be told", "The price changes in the round have to be "
							+ "worth more than your minimum profit per flip put together, or one order "
							+ "has to have been chased too far or have items waiting to be collected. A "
							+ "cancel is always worth saying however small, because it is coins parked "
							+ "in a trade that can no longer pay, holding a slot"),
					new Term("Cancelling costs nothing", "The NPC price cannot move, so an order the "
							+ "market has left behind is coins parked, not coins lost. Cancel it, take "
							+ "the coins back, and put the slot on something else"),
					new Term("Part fills", "A buy order that fills part way says nothing at all in chat "
							+ "- Hypixel only announces a complete fill - so the mod reads the amount "
							+ "out of Bazaar -> Manage Orders instead, which is right whether or not "
							+ "you were watching. There is nothing special to do: collect what filled, "
							+ "and leave the rest resting or reprice it like any other order. The list "
							+ "says both, as two rows"),
					new Term("Collect", "Filled items stay inside the order until you press Claim. "
							+ "Those coins are already earned but you cannot spend them, and the items "
							+ "cannot be carried to an NPC at all, so this is the one row worth "
							+ "interrupting you for however small it is. It is always listed first, "
							+ "even for an order you are about to cancel"),
					new Term("Orders that take forever", "An order can be priced correctly, sitting in "
							+ "first place, and simply not filling, because nobody is selling that item "
							+ "today. Past the time limit you set, the list says cancel however healthy "
							+ "it looks. You get the whole unspent stake back, so the only question is "
							+ "whether the slot is worth more elsewhere - and after a full window of "
							+ "nothing, it is"),
					new Term("How old it thinks your orders are", "It counts from the first time it saw "
							+ "the order, not from when you placed it, because nothing is remembered "
							+ "between launches. So an order placed yesterday and first seen in today's "
							+ "menu gets a fresh time limit. It gives up late rather than wrongly"),
					new Term("It has to see your orders", "Repricing compares the price your order "
							+ "really rests at against the market, and the bazaar orders menu is the "
							+ "only place that price exists. So turn on /flip track and open that menu "
							+ "once, or the command has nothing to work from"),
					new Term("Nothing is placed for you", "Every order in a basket is placed by hand, "
							+ "and so is every price change. The mod says what to click and never "
							+ "clicks it: automating bazaar orders is a macro, and macros are against "
							+ "Hypixel's rules")));

	private static final Section LIQUIDITY = new Section("liquidity", "How busy an item is", List.of(
			new Term("Weekly volume", "How many changed hands over seven days, counted separately for "
					+ "each side. One tells you how fast sell offers get taken; the other how fast buy "
					+ "orders get filled"),
			new Term("Why it limits the plan", "What is on the board right now is a snapshot, not a "
					+ "supply. An item with 4,000 units sitting under the NPC price but only 40 traded "
					+ "in a week is not a 4,000 unit opportunity, it is a week of holding something "
					+ "nobody wants. Plans are sized on how much really trades"),
			new Term("Hauling", "How many inventory loads a plan means carrying: 35 slots at a stack of "
					+ "64, or 35 unstackable items. There is no walking involved, since /trades reaches "
					+ "a shop from anywhere with a booster cookie, so this is a count of how much "
					+ "clicking the plan is"),
			new Term("Time to fill", "How long your order should take at the size the plan asks for. "
					+ "Two things set it: how fast people trade that side of the market, and how long "
					+ "you stay at the front of the queue"),
			new Term("Outbid rate", "How often somebody posts just inside your price, counted from "
					+ "recorded history rather than assumed. Once you are outbid your order earns "
					+ "nothing until the market comes back to it, so a wide gap on a crowded item is "
					+ "worth much less per hour than it looks"),
			new Term("Measured or assumed", "Where the mod has enough history for an item it says how "
					+ "fast fills really arrive. Where it does not, it falls back to a flat assumption "
					+ "and says so in the risks. An assumed number is never shown as a measured one")));

	private static final Section LEDGER = new Section("ledger", "Ledger", List.of(
			new Term("Capture rate", "The coins you really made divided by the coins the mod promised, "
					+ "counting only what filled. Below 100% means the promises are optimistic, and it "
					+ "is the number worth watching"),
			new Term("Fill rate", "How many items really filled out of how many you planned. A high "
					+ "capture rate with a low fill rate means the flips that work are the ones you "
					+ "rarely get"),
			new Term("Promises are frozen", "A flip's promised numbers are stored when you take it and "
					+ "never worked out again, because by the time a fill goes badly the market has "
					+ "already moved in whichever direction made it go badly"),
			new Term("Abandon", "Ends a flip that never filled, with no selling price. Its items count "
					+ "against your fill rate and nothing about it touches your capture rate, which is "
					+ "the honest answer: an order you gave up on is not a flip that went badly, it is "
					+ "one that never happened. Select it on the Ledger tab and press Abandon, or use "
					+ "/flip abandon <id>"),
			new Term("Automatic recording", "/flip track fills the ledger from the trades Hypixel "
					+ "announces instead of you typing them in. A buy claims the plan you took for that "
					+ "item and a sale closes it, and a flip can close in pieces because one order "
					+ "often fills in pieces. Off by default, because a wrong entry is worse than an "
					+ "empty ledger"),
			new Term("It ignores your shopping", "Recording only counts trades against plans the mod "
					+ "gave you. Buying materials to use, selling what you farmed - none of that "
					+ "reaches the ledger, because a buy with nothing to sell against it would sit open "
					+ "forever and drag your fill rate down. Turn on \"Also record trades the mod never "
					+ "suggested\" in settings if you flip by hand and want that measured too"),
			new Term("A basket line is a plan", "You do not have to take a basket line by hand for it "
					+ "to be recorded. Every line the basket shows you is remembered for as long as an "
					+ "order may rest, and buying that item within that time is booked against what the "
					+ "line promised. Without this the ledger stayed empty for anyone flipping baskets, "
					+ "and the daily NPC limit read as untouched no matter how much you bought"),
			new Term("Recorded with no promise", "What that setting produces: a trade the mod never "
					+ "suggested. It counts toward your fill rate but stays out of your capture rate, "
					+ "because there was no promise to fall short of and counting zero as the promise "
					+ "would report every ordinary trade as a total failure"),
			new Term("Forget", "Deletes an entry outright - /flip ledger forget <id>, or select it on "
					+ "the Ledger tab and press Forget twice. Abandon says a plan did not work out and "
					+ "keeps its items in your fill rate; Forget says it was never a flip and takes it "
					+ "out of every number. If recording picked up a pile of ordinary trading before "
					+ "you noticed, /flip ledger clear unquoted removes all of it at once and leaves "
					+ "the flips you took"),
			new Term("Capture", "/flip capture writes Hypixel's raw trade messages to a file, and is a "
					+ "repair tool rather than something you need. It exists so the part of the mod "
					+ "that reads chat can be written against real wording. Nothing reads the file "
					+ "while you play; leave it off unless a Skyblock update stops trades being read "
					+ "correctly"),
			new Term("Open your orders menu", "Recording reads chat, and Hypixel never announces an "
					+ "order that filled part way. The bazaar orders menu is the only place that shows "
					+ "up, so opening it now and then is what keeps your ledger honest")));

	private static final Section SETTINGS = new Section("settings", "Settings that change the list",
			List.of(
					new Term("Where they are", "The Settings button on this screen, or /flip config "
							+ "edit, or the settings file that /flip config points at. All three write "
							+ "the same file and re-sort the list straight away"),
					new Term("Bankroll", "Coins you are willing to put to work. It is a limit, not an "
							+ "instruction to spend: every plan is sized to fit inside it, so raising "
							+ "it makes the top of the list bigger flips, not better ones"),
					new Term("Skip items already falling", "Drops a bazaar flip whose price has already "
							+ "fallen more than you allow. Buy orders fill fastest exactly while people "
							+ "are dumping, so the gap you were promised is the one you least want "
							+ "filled. 0 turns it off"),
					new Term("Minimum auction discount", "How far under normal price an auction has to "
							+ "be listed before it is worth a look. It is also what keeps the search "
							+ "affordable, since nearly every listing is thrown out by it before "
							+ "anything is worked out, so lowering it costs search time as well"),
					new Term("Minimum confidence", "Hides auction finds the mod is less sure of than "
							+ "this. It does nothing to bazaar or NPC flips, which price off a live "
							+ "market rather than an estimate"),
					new Term("How long you will wait for a fill", "How long you are happy to leave a "
							+ "bazaar order resting. Plans are sized on what should fill inside it, so "
							+ "a long wait promotes slow items and a short one keeps only what fills "
							+ "while you watch. It re-sorts the list without hiding anything")));

	private static final Section SYNC = new Section("sync", "History from another machine", List.of(
			new Term("What it is", "A download of the price history a recorder on another machine kept "
					+ "while this game was closed. Sales history is the one thing that cannot be "
					+ "recovered later: Hypixel only reports the last minute of finished auctions, so "
					+ "an hour nobody recorded is an hour nobody can price against"),
			new Term("When it runs", "By itself, a few seconds after the game starts, in the "
					+ "background, so nothing waits for it. /flip sync forces one mid-session and /flip "
					+ "status says what the last one did. Fetching again during a session is normally "
					+ "off, because while you are playing this game records the same prices itself"),
			new Term("Merged, not copied", "Both machines record the same things, so each has sales the "
					+ "other missed. The two are folded together by sale, and nothing on either side is "
					+ "overwritten or counted twice"),
			new Term("What it costs", "Only what the recorder added since last time. The first one is "
					+ "the whole stored history, which is hundreds of megabytes; after that it is "
					+ "minutes of data"),
			new Term("If it fails", "A line in the log and nothing else. Your own history still works "
					+ "and the mod keeps running from it; the next attempt picks up where this one "
					+ "stopped")));

	private static final Section LIMITS = new Section("limits", "What this does not do", List.of(
			new Term("Prices go stale", "Everything here comes from the last update. Check the market "
					+ "again before you commit coins to any of it")));
}
