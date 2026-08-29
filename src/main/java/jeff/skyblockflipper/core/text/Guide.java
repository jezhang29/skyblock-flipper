/*
 * Skyblock Flipper - a Hypixel Skyblock flipping advisor mod.
 * Copyright (C) 2026 SoupChugger
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package jeff.skyblockflipper.core.text;

import java.util.List;

/**
 * What every column, term and number in this mod means.
 *
 * <p>Lives in {@code core} and holds the text once, because chat and the flip screen both explain
 * the same vocabulary and two explanations that drift apart are worse than none. A ranking is only
 * worth acting on if the person acting on it knows what each column is claiming.
 *
 * <p>Written for a player, not for a maintainer: plain words, no jargon, no field names, no file
 * names, no measurement dumps. Keep every entry concise; where a number decided a default, one
 * comparison is enough to say why.
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
		return List.of(START, COMMANDS, COLUMNS, STRATEGIES, ROUTES, NPC_CAP, BASKET, JOBS, CRAFT,
				COMBINE, LIQUIDITY, LEDGER, SETTINGS, SYNC, LIMITS);
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
					new Term("1. Say how much you can spend", "Open the flip screen and press Settings, "
							+ "or type /flip config edit. Set Bankroll to the coins you will tie up at "
							+ "once. Everything is sized to fit inside it, so you cannot skip this"),
					new Term("2. Wait for prices to arrive", "Bazaar prices arrive a few seconds after "
							+ "you join and refresh every 20 seconds. /flip status says how old they "
							+ "are; until they land the list is empty"),
					new Term("3. Ask for a list", "/flip, typed alone, ranks every kind of flip "
							+ "together. /flip bazaar, /flip npc, /flip craft, /flip combine, /flip "
							+ "fusion and /flip "
							+ "snipe ask for one kind. It is sorted by profit per hour after fees, so "
							+ "rank 1 is the best thing on offer"),
					new Term("4. Read one row before trusting it", "Click the row in the flip screen. "
							+ "The right panel says what to buy, at what price, how many, and what has "
							+ "to happen for it to pay; anything uncertain is listed as a risk"),
					new Term("5. Take it", "/flip take 1, or the Take button, writes the plan down at "
							+ "the numbers you see. It does not trade for you: you place the order "
							+ "yourself, in game. The mod never touches your account"),
					new Term("6. Do the trade in game", "Copy the item name with the Copy name button, "
							+ "paste it into the bazaar search, and place the order the plan describes"),
					new Term("7. Say what happened", "When the coins come back, /flip close <id> <units "
							+ "sold> <price each>. The id is the four characters /flip take printed. If "
							+ "the order never filled, use /flip abandon <id> instead"),
					new Term("8. Check whether it works", "/flip ledger shows your open flips and two "
							+ "numbers: how much of the promised profit you really got, and how much of "
							+ "what you planned really filled. That is the only proof this works"),
					new Term("Optional: the whole bazaar at once", "/flip npc plan fills every order "
							+ "slot with buy orders under what NPCs pay, sized to fit your bankroll "
							+ "once. Turn on /flip track, place the whole list, open Bazaar -> Manage "
							+ "Orders, then /flip npc reprice when the reminder says you were outbid, "
							+ "and sell what filled through /trades. Read the basket section first"),
					new Term("Optional: let it write things down for you", "/flip track fills your "
							+ "ledger from the trades Hypixel announces, so steps 5 and 7 happen on "
							+ "their own for plans you took. Read the Ledger section first")));

	/** Every command, in one place, because a command you cannot remember does not exist. */
	private static final Section COMMANDS = new Section("commands", "Commands", List.of(
			new Term("/flip", "The ranked list. /flip bazaar, /flip npc, /flip craft, /flip combine, "
					+ "/flip fusion and /flip snipe show one kind of flip only"),
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
			new Term("/flip jobs", "The flips you are working and the clicks each one still needs, "
					+ "with what your order tracker has seen done. Same list as the Jobs tab and the "
					+ "bazaar panel. /flip jobs stop <name> drops one, /flip jobs stop drops all"),
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
			new Term("Capital", "Coins tied up to run the plan for an hour, never more than one flip is "
					+ "allowed to spend"),
			new Term("Fill", "How long the slower half of the flip should take to finish. A tilde in "
					+ "front means it is an estimate from the item's weekly trading rather than from "
					+ "fills this game has watched, which takes about an hour of play to start "
					+ "measuring. A dash means the market never clears an order this big"),
			new Term("Outbid", "How often somebody posts just inside your resting order, counted from "
					+ "recorded history. It decides whether a buy order fills or just sits: a wide "
					+ "margin on an item where you are outbid five times an hour is a margin you never "
					+ "actually get")));

	private static final Section STRATEGIES = new Section("strategies", "Kinds of flip", List.of(
			new Term("Bazaar", "Buy low and sell high on the same item: post a buy order, wait, post a "
					+ "sell offer, keep the gap. You are paid for waiting by people who want coins or "
					+ "materials right now"),
			new Term("NPC", "The bazaar price has dropped below the fixed price a shop NPC pays. No "
					+ "bazaar tax applies, because selling to an NPC is not a bazaar trade"),
			new Term("Craft", "Buy a recipe's materials on the bazaar, craft it, and sell the result "
					+ "back. You are paid for the assembly, and it uses none of the daily NPC coin "
					+ "limit"),
			new Term("Combine", "Buy cheap low-tier enchanted books, combine them up at the anvil, and "
					+ "sell the top tier. You are paid for the anvil work, so it is measured in coins "
					+ "per combine and ranks low on the main list"),
			new Term("Fusion", "Buy cheap attribute shards, fuse them up at the Fusion Machine, and "
					+ "sell the output. An input can itself be fused from cheaper shards, so a flip is "
					+ "a small tree. The per-click return is large, but ten shards go in for two out, "
					+ "so read /flip fusion and mind the haul"),
			new Term("Snipe", "An auction listed below what that exact item has really been selling "
					+ "for. Worth is learned from completed sales only, never from asking prices"),
			new Term("Attributes", "Kuudra and Crimson Isle gear rolls two attributes with levels, and "
					+ "the level is most of the price. An item is compared only against sales with the "
					+ "same roll at the same level, a small pool, so it often shows no price rather "
					+ "than a wrong one"),
			new Term("Pet level", "A pet's level is in its name and is most of its worth: the same pet "
					+ "at level 1 and 100 can differ several times over. Pets are priced against their "
					+ "own level first, then nearby levels, then any, taking the first with enough "
					+ "sales; later steps are marked less certain in the risks"),
			new Term("Dark Auction bid", "A Midas weapon's stats depend on the coins bid to win it, "
					+ "and it remembers the bid. These are priced per coin bid, applied to this one's "
					+ "bid, so a 3M staff is not lumped in with a 100M one"),
			new Term("Ledger", "The flips you took and what they really did. The only part of this mod "
					+ "that tells you whether the rest works"),
			new Term("Stars and essence", "Hypixel publishes what each star costs and every ingredient "
					+ "trades on the bazaar, so a starred item's essence bill is worked out exactly, "
					+ "priced at what starring one today would cost. Cost is not worth: it tells you "
					+ "how much of an asking price is just materials you could buy yourself")));

	private static final Section ROUTES = new Section("routes", "Buying: order or instant?", List.of(
			new Term("Instant buy", "Take the cheapest sell offer on the board: the items now, at the "
					+ "higher price"),
			new Term("Buy order", "Post an offer a tenth of a coin above the best and wait for someone "
					+ "to sell into it. You pay the lower price, most of the profit on a wide market, "
					+ "but it fills only as fast as people sell"),
			new Term("Which one", "For NPC flips both are priced and the better by profit per hour is "
					+ "the plan, the other shown underneath. Waiting costs nothing against an NPC "
					+ "price, which cannot move away; a bazaar price can, so bazaar flips get a give-up "
					+ "rule instead")));

	/**
	 * Working several flips at once, which is the thing the screen used to make impossible.
	 *
	 * <p>Its own section rather than a line in each strategy, because the point of it is the part
	 * no single strategy owns: what you have open across all of them, and what is still to click.
	 */
	private static final Section JOBS = new Section("jobs", "Flips you are working", List.of(
			new Term("What a worked flip is", "A bazaar spread, craft or combine you told the mod you "
					+ "are doing. Select its row on any tab and press Work; it then has a block on the "
					+ "Jobs tab and a section on the bazaar panel until you stop it"),
			new Term("Several at once", "As many as you like. A craft's materials rest for an hour "
					+ "while a combine's books fill and a spread sits on the book, so one flip at a "
					+ "time is not how a session goes. They are listed in the order you picked, above "
					+ "the NPC basket"),
			new Term("Selecting is not working", "Clicking a row selects it so you can read the panel. "
					+ "Nothing is committed and nothing you are working is dropped. Only Work starts a "
					+ "flip, and pressing it on one you are working stops that one"),
			new Term("The progress marks", "[ ] nothing on the book yet, [~] an order is resting, [x] "
					+ "filled and collected. They come from your order tracker, so with /flip track "
					+ "off every step reads blank and the count says untracked. A guessed mark would "
					+ "be worse than none"),
			new Term("Steps nothing can see", "Crafting at a bench and merging at an anvil leave no "
					+ "bazaar trace, so those steps get no mark. Only buy orders and the sell offer "
					+ "count toward done"),
			new Term("Stopping one", "Press Stop working on the Jobs tab, or /flip jobs stop <name>. "
					+ "It only stops the mod telling you about the flip: orders on the book are left "
					+ "where they are, and the NPC side keeps leaving them alone while they rest"),
			new Term("Falling behind", "If a flip stops clearing its gates while you work it - the "
					+ "output crashes, a material climbs - its block says so and stops giving prices "
					+ "rather than quoting numbers that no longer earn"),
			new Term("Committed", "The right side adds up what every worked flip has tied up and what "
					+ "it makes if it all fills. Nothing else adds them: the ranking quotes each flip "
					+ "as if it were the only one")));

	private static final Section CRAFT = new Section("craft", "Crafting to sell", List.of(
			new Term("The trade", "/flip craft lists recipes whose materials cost less on the bazaar "
					+ "than the finished item sells for. Both ends are live bazaar prices, so nothing "
					+ "here depends on guessing an item's worth"),
			new Term("Which way it is moving", "A recipe is offered only while its margin holds. The "
					+ "drift of the output and of each material, weighted by cost, is combined into "
					+ "one number, and a recipe whose margin is closing faster than your adverse-drift "
					+ "setting is left out. An output falling on materials falling faster stays in"),
			new Term("How it is sold", "As a sell offer a tenth of a coin under the cheapest on the "
					+ "board, never dumped into buy orders or sold to an NPC. Offering is worth about "
					+ "ten times dumping, and an NPC sale would eat your daily NPC coin limit"),
			new Term("How the materials are bought", "Both ways are priced and the better by profit "
					+ "per hour is shown. Buy orders usually win by a lot: farmed materials are dumped "
					+ "into buy orders constantly, so an order costs less and fills faster"),
			new Term("Order slots", "Each material on an order takes a slot, and so does the sell "
					+ "offer, all shared with your NPC basket, so a settings limit caps how many one "
					+ "job may take. A job over the limit buys its materials instantly instead, using "
					+ "one slot"),
			new Term("Working one", "Select a craft row and press Work. The bazaar panel then carries "
					+ "the job - materials, craft and sell offer, each with its price and amount - "
					+ "beside Hypixel's menu; click a name or number to copy it. Prices are re-worked "
					+ "every poll. Several crafts run at once; /flip craft stop drops them all"),
			new Term("Falling behind", "If the flip stops clearing while you work it - the output "
					+ "crashes, a material climbs - the panel says so and stops giving prices rather "
					+ "than quoting numbers that no longer earn"),
			new Term("Unlocks", "Recipes have collection and skill requirements the mod cannot see. "
					+ "The requirement is printed with the job; if you cannot craft it yet, the "
					+ "materials you bought are just materials"),
			new Term("What it does not do", "Every material is priced at what it costs to buy. Whether "
					+ "crafting an ingredient yourself would be cheaper is not worked out")));

	private static final Section COMBINE = new Section("combine", "Combining books", List.of(
			new Term("The trade", "/flip combine lists enchanted books cheaper to buy low and combine "
					+ "up than to buy at the tier you sell. Two books of one tier make one of the next "
					+ "at the anvil, so a tier-10 book is sixteen tier-6 books. Both ends are live "
					+ "bazaar prices"),
			new Term("Coins per combine, not per hour", "The books are thin, so this makes little per "
					+ "hour and ranks low on the main list. Its point is the return per anvil click: "
					+ "for a player short on time it pays a lot of coins for a few clicks"),
			new Term("Where it sources", "The cheapest source tier is not always the bottom one: a low "
					+ "tier can carry a fat buy-order price from everyone else combining it, so the "
					+ "mod prices every tier below the target and buys the cheapest"),
			new Term("The middles are dead", "You combine straight through the tiers between, never "
					+ "trading them. Their books are nearly empty, which is fine - nothing rests on "
					+ "them"),
			new Term("How it is sold", "A sell offer a tenth of a coin under the cheapest on the "
					+ "target's board. The high tiers have huge spreads, so dumping into buy orders "
					+ "loses almost everywhere. The exit uses no NPC daily coin limit"),
			new Term("The fantasy-price guard", "A book is offered only when its top tier has at least "
					+ "fifteen sell offers resting. A tier priced by a single seller at a made-up "
					+ "number is not a real price, and this filter tells the two apart"),
			new Term("Working one", "Select a combine row and press Work. The bazaar panel then carries "
					+ "the job - the source buy, the anvil merges and the sell offer, each with its "
					+ "price and amount - beside Hypixel's menu; click a name or number to copy it. "
					+ "Prices are re-worked every poll. /flip combine stop drops them all"),
			new Term("Unverified", "Nothing here has been combined and sold in play yet, and the anvil "
					+ "is assumed to cost no coins. Treat the first runs as a test")));

	private static final Section NPC_CAP = new Section("npc", "NPC flipping", List.of(
			new Term("The trade", "Post a buy order under the fixed price a shop NPC pays, then sell "
					+ "what fills through /trades. The selling price cannot move, so an order fills at "
					+ "your price or not at all. Coins in an unfilled order are stuck until you cancel "
					+ "it, never lost"),
			new Term("Minimum gap under the NPC price", "How much cheaper than the NPC you must buy "
					+ "before a flip is offered, in settings. The default 15% earned most: a smaller "
					+ "gap is eaten by chasing the price up, a bigger one leaves too few items to fill "
					+ "your slots"),
			new Term("Where to stop chasing", "The same number backwards: never raise a buy order "
					+ "above the NPC price minus that gap - at 15%, never above 85% of what the NPC "
					+ "pays. Past there the slot is worth more elsewhere, and the plan gives the exact "
					+ "stop price"),
			new Term("What chasing costs", "Staying at the front means raising your price now and "
					+ "then, measured from how fast the best offer has really climbed. It is taken off "
					+ "before you see the profit, so the figure shown is after chasing"),
			new Term("Paying to stay on top", "Spending those coins on your opening price instead, so "
					+ "the order sits above the crowd. This was a setting and was removed: recorded "
					+ "prices said it worked, but all came from a market with none of your orders in "
					+ "it. In play, an order posted 3.9% high held first place ten minutes of eleven "
					+ "hours - a rival just parks a coin above you. Post plain and raise on check-in"),
			new Term("Probe", "/flip npc probe <item> works out a higher price and watches whether "
					+ "anyone outbids an order left there - the one thing history cannot answer, since "
					+ "it holds no markets with your orders in them. Run one per item on as many items "
					+ "as you like; /flip npc probe reports them all. A fill ends its probe, and it is "
					+ "remembered only while the game is open"),
			new Term("Nudge", "Being outbid by the bazaar's own \"+0.1\" button rather than by someone "
					+ "deliberately pricing the item. Over three days these were most of the upward "
					+ "moves but under 1% of the climbing. The probe reports an outbid of a coin or "
					+ "less as a nudge and counts how often you took first place back"),
			new Term("Item names", "Type the name you read in game, in any case and word order. The "
					+ "internal codes cannot be guessed - Nether Wart Distillate is "
					+ "NETHER_STALK_DISTILLATE - so do not try. Tab completes both, and a name matching "
					+ "several items lists them"),
			new Term("What the basket should favour", "Which budget the basket spends first. Fewer "
					+ "trips picks the items earning most per inventory load, so you carry less. Coins "
					+ "picks the most per order slot, which is what really runs out - about a third "
					+ "more coins for about three times the hauling"),
			new Term("Gaps that hold", "Most gaps under the NPC price are permanent, not brief races: "
					+ "of 223 busy items with a gap, 204 kept it in over 95% of three days of samples. "
					+ "Anything below that is skipped. This protects slots, not coins - a gap that "
					+ "closes costs a slot for the day, not money"),
			new Term("Check in every", "How often you will come back and move your orders to the "
					+ "front, in settings. It is what fills are measured against, and how long a "
					+ "reprice list keeps its prices: an order tidied every 30 minutes collects far "
					+ "more than one left all day"),
			new Term("What to set it to", "30 minutes, unless you know better. Over eight hours, "
					+ "hourly earns about 54M, every 30 minutes about 60M, every 15 about 67M, so 15 "
					+ "minutes to an hour is all close. Set it to what you will really do: plans are "
					+ "sized on it, so claiming 15 and acting like 60 promises fills you never collect"),
			new Term("Give up on an order after", "How long a buy order may sit before you would "
					+ "rather have the coins back, in settings. Nothing is at risk while it waits, so "
					+ "this only says how long your coins may be tied up"),
			new Term("Daily NPC coin limit", "NPCs stop buying after paying out a fixed number of "
					+ "coins each day, shared across every item and reset at midnight UTC. It counts "
					+ "what the NPC hands you, not your profit, and is big enough for roughly two "
					+ "eight-hour stretches"),
			new Term("How much is left", "Counted from your ledger at the NPC price: what closed flips "
					+ "sold, plus what your open NPC flips bought to sell on. Open ones count because "
					+ "nothing announces an NPC sale. Flips you never wrote down are invisible, so the "
					+ "budget looks fuller than it is"),
			new Term("Order slots", "14 bazaar orders rest at once, plus 7 per Bazaar Flipper level up "
					+ "to 28, and settings can hold NPC plans to fewer. One order holds 71,680 of a "
					+ "stackable item or 256 of an unstackable one, so plans are trimmed to what your "
					+ "slots can hold"),
			new Term("Orders per line", "A line needing more than one order shows the split - 3 x 256 "
					+ "+ 112 is three full orders and one part, four slots. The mod learns what stacks "
					+ "by watching for orders bigger than 256, because Hypixel's item list wrongly "
					+ "marks things like reforge stones as stackable")));

	/**
	 * The basket, which is a different job from reading a ranked list.
	 *
	 * <p>Its own section rather than more terms under NPC flipping: that section says what the trade
	 * is, and this one is the loop you run - plan, place, come back, reprice, sell. A player who has
	 * understood every NPC term can still place a basket wrong by treating it as a top-ten list.
	 */
	private static final Section BASKET = new Section("basket", "The basket, and working it",
			List.of(
					new Term("What it is", "One list of things to click: the resting orders first - "
							+ "anything to collect, cancel or that was outbid - then the new orders to "
							+ "place with the slots and coins left. /flip npc plan prints it, the "
							+ "Basket tab shows it, the bazaar panel draws it. The ranked list is "
							+ "different: every row is sized against your whole bankroll, so the top "
							+ "three would spend it three times"),
					new Term("The order to work it in", "Top down, not the order the coins are in. "
							+ "Collect first, since those coins are earned and the items cannot reach an "
							+ "NPC while they sit. Cancel next, which frees the slots below. Then the "
							+ "price changes, then the new orders, which need the coins the cancels "
							+ "returned"),
					new Term("Place all of it", "A ranking is a menu you pick one thing from. A basket "
							+ "is a list of jobs, and doing half spends your bankroll on half a plan "
							+ "while leaving the same slots idle"),
					new Term("Coming back later", "One list that already knows - you never choose "
							+ "between repricing and planning. Resting orders are taken out of the slots "
							+ "and coins before anything new is sized; if nothing new appears, every "
							+ "slot is already working"),
					new Term("Finishing a part-placed line", "A line of 1,024 unstackable items is four "
							+ "orders typed one at a time. The row stays and counts down as you place "
							+ "it - 1,024 as 4 x 256, then 768 as 3 x 256 - so what is left is always on "
							+ "the row. It stops the moment anything else asks about that item, so you "
							+ "never bid against yourself"),
					new Term("Why it is ordered like that", "By profit per inventory load, not by "
							+ "margin. Over a full day, per load earned about 76M and by margin under "
							+ "5M, because margin fills every slot with 7-coin items and thousands of "
							+ "trips. A 98% margin on a 7-coin item is nothing once it holds a slot"),
					new Term("The panel at the bazaar", "With \"Show basket at the bazaar\" on, the "
							+ "list is drawn beside Hypixel's menu while you are in it: what to do, the "
							+ "price to type, how the items split into orders. It updates as prices come "
							+ "in and highlights the row for the page you have open. It lingers a few "
							+ "seconds after the menu closes so it stays up while you type into the "
							+ "sign, and always draws at least how many orders were outbid"),
					new Term("Scrolling and copying it", "The wheel scrolls the panel. Clicking a row "
							+ "copies the item name for the search sign; clicking the price or amount on "
							+ "the second line copies that number. The Basket tab has the same three as "
							+ "buttons. Nothing is clicked or typed for you, and a click on the panel "
							+ "never reaches the menu behind it"),
					new Term("The green box in the menu", "With \"Highlight the slot to click\" on, the "
							+ "slot the top row needs next has a green box behind it the whole way "
							+ "through an order: Search, the result, Create Buy Order, the amount and "
							+ "price signs, confirm. On resting orders it lands on the row - left click "
							+ "to collect, right click for options. Where the menu cannot be read "
							+ "nothing is drawn, and nothing is clicked for you"),
					new Term("Top Order +0.1 or the price sign", "On the price page the box goes on "
							+ "Hypixel's \"Top Order +0.1\" button whenever it offers at or below the "
							+ "plan's price - one click, and never out of date since it reads the live "
							+ "market. Where it offers more, the market has moved up, so the box goes on "
							+ "Custom Price: type the number and let the order wait, rather than pay "
							+ "away the profit"),
					new Term("What to type on the sign", "The amount and price are typed on a sign that "
							+ "replaces the menu, so the panel keeps the number on screen for a minute "
							+ "and a half and says which box it is for. Long names are offered "
							+ "shortened, since a sign holds little text and the bazaar searches on the "
							+ "start of a name"),
					new Term("Which side it sits on", "Left, Right or Automatic. Automatic takes the "
							+ "roomier side of Hypixel's menu, the widest panel, but changes sides as "
							+ "you cross screens. Left and Right stay put and move only with no room at "
							+ "all"),
					new Term("Post at", "The price to type into Create Buy Order - a tenth of a coin "
							+ "above the best offer, which puts you at the front. Not the same as the "
							+ "plan's cost per item, which also allows for chasing you have not paid "
							+ "for yet"),
					new Term("What ran out", "The line under the totals names what stopped the basket: "
							+ "order slots, coins, the day's NPC limit, or none - meaning the market has "
							+ "nothing else worth buying. Only the first three are yours to change"),
					new Term("NPC coins", "How much of the day's NPC limit this basket would use, "
							+ "counted at the selling price, not the profit. A full basket sits "
							+ "comfortably inside it"),
					new Term("The reprice round", "/flip npc reprice, every time you come back: which "
							+ "orders are still in front, which were outbid and where to move them, and "
							+ "which were chased too far and should be cancelled. Over eight hours with "
							+ "30-minute check-ins, about 78% held, 20% wanted moving, 2% were past "
							+ "chasing"),
					new Term("Why it is a round", "Prices are fixed when the round opens and do not move "
							+ "while you work it, so you can read a number, walk to the menu and type "
							+ "it. Chasing live is hopeless here - one Transmission Tuner sample had "
							+ "five bots a tenth of a coin apart. Being one step down for up to a "
							+ "check-in costs about 1%"),
					new Term("The price to type", "Use the bazaar's \"+0.1 coins\" button; it works out "
							+ "the same thing from the live market, which the mod trails a little. The "
							+ "number on the row is what to expect and what the profit was based on, not "
							+ "something to match exactly. A row past the stop-chasing point is dropped "
							+ "rather than left for you"),
					new Term("When the next round opens", "One check-in after the last, whether or not "
							+ "you finished it. Nothing opens one early - leaving the bazaar means "
							+ "nothing, since you must leave to sell to the NPC. Between rounds the panel "
							+ "and list say how many orders were outbid and how long until they are "
							+ "looked at"),
					new Term("Why an order can be missing", "An order must be at least one check-in old "
							+ "to enter a round, so ones you just placed are not repriced yet. Orders "
							+ "already resting when the mod first saw your menu are exempt - it cannot "
							+ "tell their age - which is the login-after-outbid-overnight case that "
							+ "matters most"),
					new Term("Collecting and dead orders do not wait", "Coins to collect are already "
							+ "earned and block the items from leaving. An order chased too far, or past "
							+ "its time limit, is over with your coins stuck. Neither improves by "
							+ "waiting, so both are listed as soon as they are true"),
					new Term("When a price change is worth making", "When the fills you win back over "
							+ "the rest of the interval, times the margin, beat your minimum profit per "
							+ "flip. A busy-item repost worth 3k over half an hour is dropped; the same "
							+ "on a quiet item worth 10k is kept. With nothing measured yet it is always "
							+ "offered"),
					new Term("One row per item", "A reprice row covers every order you hold on that "
							+ "item - \"cancel 4, repost 4 x 256\" - because four rows quoting one price "
							+ "is four times the reading for one decision"),
					new Term("Place first or cancel first", "The bazaar cannot edit a resting order, so "
							+ "a price change means cancel and repost, and in between the item collects "
							+ "nothing. With a spare slot and coins free, the row says post first and "
							+ "skip that gap; otherwise it is held, its slot and coins reserved for the "
							+ "rest of the round"),
					new Term("Why coming back matters", "An order earns only while it is the best offer. "
							+ "Over eight hours: about 11M for posting once and walking away, about 60M "
							+ "for every 30 minutes, about 73M for staying first the whole time. It "
							+ "flattens below an hour, so under every 15 minutes buys little"),
					new Term("The reminder", "With \"Remind me to reprice\" on you get a pop-up, a chat "
							+ "line and a note once per round that has work in it - three at once "
							+ "because chat scrolls fast, and the pop-up waits for you. Click [reprice] "
							+ "on the chat line to run the round. Asking for a plan yourself uses up that "
							+ "round's reminder"),
					new Term("What it counts", "The rows in the round, not every order the market moved "
							+ "past. An order the round fixed no price for is not in the list the "
							+ "reminder opens. Something to collect or cancel that turns up mid-round is "
							+ "listed at once and announced at the next opening, at most a check-in "
							+ "away"),
					new Term("If it never says anything", "Most likely nothing has shown it your orders. "
							+ "Hypixel's placement line gives the size and total but never the price per "
							+ "item, which is the whole point of a reprice - so open Bazaar -> Manage "
							+ "Orders once a session. If you see \"cannot see your orders\", that is "
							+ "this; otherwise check /flip track is on"),
					new Term("What it takes to be told", "The round's price changes must be worth more "
							+ "than your minimum profit per flip put together, or an order must be chased "
							+ "too far or have items to collect. A cancel is always worth saying, since "
							+ "it is coins parked in a trade that can no longer pay"),
					new Term("Cancelling costs nothing", "The NPC price cannot move, so an order the "
							+ "market left behind is coins parked, not lost. Cancel it, take the coins "
							+ "back, and put the slot on something else"),
					new Term("Part fills", "A buy order that fills part way says nothing in chat - "
							+ "Hypixel announces only a complete fill - so the mod reads the amount from "
							+ "Bazaar -> Manage Orders, right whether or not you watched. Collect what "
							+ "filled and leave the rest resting or reprice it; the list shows both, as "
							+ "two rows"),
					new Term("Collect", "Filled items stay in the order until you press Claim. Those "
							+ "coins are earned but unspendable, and the items cannot go to an NPC, so "
							+ "this is the one row worth interrupting you however small. It is always "
							+ "listed first, even for an order you are about to cancel"),
					new Term("Orders that take forever", "An order can be priced right, sitting first, "
							+ "and still not fill because nobody is selling that item today. Past your "
							+ "time limit the list says cancel however healthy it looks: you get the "
							+ "whole stake back, so the only question is whether the slot is worth more "
							+ "elsewhere"),
					new Term("How old it thinks your orders are", "It counts from the first time it saw "
							+ "the order, not when you placed it, since nothing is remembered between "
							+ "launches. An order placed yesterday but first seen today gets a fresh "
							+ "time limit; it gives up late rather than wrongly"),
					new Term("It has to see your orders", "Repricing compares the price your order rests "
							+ "at against the market, and the bazaar orders menu is the only place that "
							+ "price exists. Turn on /flip track and open that menu once, or the command "
							+ "has nothing to work from"),
					new Term("Nothing is placed for you", "Every order and every price change is placed "
							+ "by hand. The mod says what to click and never clicks it: automating "
							+ "bazaar orders is a macro, and macros are against Hypixel's rules")));

	private static final Section LIQUIDITY = new Section("liquidity", "How busy an item is", List.of(
			new Term("Weekly volume", "How many changed hands over seven days, counted per side: one "
					+ "for how fast sell offers get taken, the other for how fast buy orders fill"),
			new Term("Why it limits the plan", "What is on the board now is a snapshot, not a supply. "
					+ "4,000 units under the NPC price but only 40 traded in a week is not a 4,000-unit "
					+ "chance, it is a week of holding what nobody wants. Plans are sized on what "
					+ "really trades"),
			new Term("Hauling", "How many inventory loads a plan means carrying: 35 slots at a stack "
					+ "of 64. There is no walking - /trades reaches a shop from anywhere with a booster "
					+ "cookie - so this counts how much clicking the plan is"),
			new Term("Time to fill", "How long your order should take at the plan's size, set by how "
					+ "fast people trade that side and how long you stay at the front of the queue"),
			new Term("Outbid rate", "How often someone posts just inside your price, from recorded "
					+ "history. While outbid your order earns nothing until the market comes back, so a "
					+ "wide gap on a crowded item is worth much less per hour than it looks"),
			new Term("Measured or assumed", "Where the mod has enough history it says how fast fills "
					+ "really arrive; where it does not, it uses a flat assumption and says so in the "
					+ "risks. An assumed number is never shown as measured")));

	private static final Section LEDGER = new Section("ledger", "Ledger", List.of(
			new Term("Capture rate", "The coins you really made over the coins the mod promised, "
					+ "counting only what filled. Below 100% means the promises run optimistic; it is "
					+ "the number worth watching"),
			new Term("Fill rate", "How many items really filled out of how many you planned. A high "
					+ "capture rate with a low fill rate means the flips that work are the ones you "
					+ "rarely get"),
			new Term("Promises are frozen", "A flip's promised numbers are stored when you take it and "
					+ "never redone, because by the time a fill goes badly the market has already moved "
					+ "the way that made it go badly"),
			new Term("Abandon", "Ends a flip that never filled, with no selling price. Its items count "
					+ "against your fill rate and nothing touches your capture rate: an order you gave "
					+ "up on never happened, it did not go badly. Select it on the Ledger tab and press "
					+ "Abandon, or /flip abandon <id>"),
			new Term("Automatic recording", "/flip track fills the ledger from the trades Hypixel "
					+ "announces. A buy claims the plan you took for that item and a sale closes it, in "
					+ "pieces if the order fills in pieces. Off by default, since a wrong entry is "
					+ "worse than an empty ledger"),
			new Term("It ignores your shopping", "Recording counts only trades against plans the mod "
					+ "gave you. Materials you buy to use and what you farmed do not reach the ledger, "
					+ "since a buy with nothing to sell against would sit open forever. Turn on \"Also "
					+ "record trades the mod never suggested\" if you flip by hand"),
			new Term("A basket line is a plan", "You need not take a basket line by hand for it to be "
					+ "recorded. Each line is remembered for as long as an order may rest, and buying "
					+ "that item in that time is booked against what the line promised. Without this "
					+ "the ledger stayed empty for basket flippers and the NPC limit read as untouched"),
			new Term("Recorded with no promise", "What that setting produces: a trade the mod never "
					+ "suggested. It counts toward your fill rate but not your capture rate, since "
					+ "there was no promise to fall short of and counting zero would fail every "
					+ "ordinary trade"),
			new Term("Forget", "Deletes an entry outright - /flip ledger forget <id>, or select it and "
					+ "press Forget twice. Abandon says a plan did not work out and keeps its items in "
					+ "your fill rate; Forget says it was never a flip and removes it from every "
					+ "number. /flip ledger clear unquoted removes a pile of ordinary trading at once"),
			new Term("Capture", "/flip capture writes Hypixel's raw trade messages to a file, a repair "
					+ "tool you do not normally need. It lets the chat-reading code be written against "
					+ "real wording. Nothing reads the file while you play; leave it off unless an "
					+ "update stops trades being read"),
			new Term("Open your orders menu", "Recording reads chat, and Hypixel never announces an "
					+ "order that filled part way. The bazaar orders menu is the only place it shows, "
					+ "so opening it now and then keeps your ledger honest")));

	private static final Section SETTINGS = new Section("settings", "Settings that change the list",
			List.of(
					new Term("Where they are", "The Settings button on this screen, /flip config edit, "
							+ "or the file /flip config points at; all three write the same file and "
							+ "re-sort the list at once"),
					new Term("Bankroll", "Coins you will put to work. A limit, not a target: every plan "
							+ "is sized to fit inside it, so raising it makes bigger flips, not better "
							+ "ones"),
					new Term("Skip items already falling", "Drops a bazaar flip whose price has already "
							+ "fallen more than you allow, because buy orders fill fastest while people "
							+ "dump. 0 turns it off"),
					new Term("Minimum auction discount", "How far under normal price an auction must be "
							+ "listed to be worth a look. It also keeps the search fast, since most "
							+ "listings are thrown out by it first"),
					new Term("Hide shaky auction finds", "Hides auction snipes without enough recent "
							+ "sales of the same item behind the price to trust. Bazaar and NPC flips, "
							+ "which price off a live market, ignore it"),
					new Term("How long you will wait for a fill", "How long you will leave a bazaar "
							+ "order resting. Plans are sized on what fills inside it, so a long wait "
							+ "promotes slow items and a short one keeps only what fills fast")));

	private static final Section SYNC = new Section("sync", "History from another machine", List.of(
			new Term("What it is", "A download of the price history a recorder on another machine kept "
					+ "while this game was closed. Sales history cannot be recovered later: Hypixel "
					+ "reports only the last minute of finished auctions, so an unrecorded hour is one "
					+ "nobody can price against"),
			new Term("When it runs", "By itself, a few seconds after startup, in the background. /flip "
					+ "sync forces one mid-session and /flip status says what the last did. Fetching "
					+ "again during a session is normally off, since this game records the same prices "
					+ "while you play"),
			new Term("Merged, not copied", "Both machines record the same things, so each has sales the "
					+ "other missed. They are folded together by sale, and nothing is overwritten or "
					+ "counted twice"),
			new Term("What it costs", "Only what the recorder added since last time. The first fetch "
					+ "is the whole stored history, hundreds of megabytes; after that, minutes of data"),
			new Term("If it fails", "A line in the log and nothing else. Your own history still works "
					+ "and the mod runs from it; the next attempt picks up where this one stopped")));

	private static final Section LIMITS = new Section("limits", "What this does not do", List.of(
			new Term("Prices go stale", "Everything here comes from the last update. Check the market "
					+ "again before you commit coins to any of it")));
}
