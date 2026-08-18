# Bazaar-to-NPC flipping: the measured plan

Agreed 2026-08-09 after a full grilling session. Everything here is measured against the live
bazaar, `/v2/resources/skyblock/items`, and three days of the user's own `bazaar-tape`
(2026-08-08 to 2026-08-10, 5.0-minute sampling, ~735 samples per product). **Nothing in this
document is a guess unless it says so.**

This supersedes the design story in `NpcFlipStrategy`'s javadoc, which is wrong about which
constraint binds. See "What the existing code gets wrong" below.

## The shape of the trade

Buy on the bazaar with a resting buy order, sell to an NPC at a price that never moves.

**The user confirmed the two game facts this rests on** (2026-08-09, in play):

- Any NPC shop's sell slot buys any item at its `npc_sell_price`. There is no item-to-shop
  mapping to build, and the API price is correct — they tested it live and made money.
- With a booster cookie, `/trades` reaches a shop from anywhere. **There is no walking.** Any
  plan phrased in terms of "trips to an NPC" is modelling a cost that does not exist.

**The risk model, which is much shorter than a bazaar spread flip's:**

- No coin loss from the market moving. The exit price is fixed, so a buy order either fills at
  your price or does not fill. An unfilled order is cancelled, not lost.
- Coins in an unfilled order are stuck, not lost.
- The only true coin-loss path is a wrong `npc_sell_price`. Measured live by the user and
  correct, so **no verification gate ships** — the basket uses API pricing directly.

Everything else in this document is therefore about **opportunity cost**: 21 order slots, the
bankroll, the 500M daily cap, and the user's time.

## Settled parameters

| setting | value | why |
| --- | --- | --- |
| daily NPC coin cap | 500,000,000, global, hard | Confirmed in play. Not a soft ceiling. **Do not re-open.** |
| minimum margin | 15% of the NPC price | Peak of a measured sweep; see below |
| chase stop | the same 15% — never reprice above `npc × 0.85` | One threshold, one meaning |
| edge persistence | gap present in ≥95% of tape samples | Order-slot efficiency, not safety |
| ranking key | profit per inventory slot-load | 16x better than cap efficiency |
| check-in interval | 30 minutes default | User will check in several times an hour; also the length of a reprice round |
| reprice delivery | rounds with frozen prices, never per book move | See below and `docs/adr/0002-reprice-in-rounds.md` |
| resting window | 8 hours | One cycle |
| order slots | configurable, default all available | Coop members need slots too |
| book-depth guard | **none** | Rejected; see below |
| NPC price verification | **none** | Rejected; API confirmed correct in play |

## Measurements

### The opportunity is large and it is structural, not a race

- 2,123 bazaar products; **816 have an `npc_sell_price`**.
- Right now: 60 have a best ask below the NPC price, **260 have a best bid below it**, 223 of
  those with ≥10k weekly instant-sells.
- **204 of those 223 held the gap in ≥95% of three days of samples.** Only 5 flicker.

`NpcFlipStrategy`'s risk line "Edge closes fast once others notice" is false for the buy-order
route. These gaps are permanent features of the book.

The reason they persist is boring: the items carrying huge percentage margins are cheap.
`LUSHLILAC` is a 98% margin and 7.4 coins a unit. Nobody arbitrages it because the clicking is
not worth it. **A large persistent margin is not evidence of a fake NPC price** — an idea tried
here and abandoned.

### The binding constraint is not the one the code models

Measured on a live basket: the 500M cap never binds inside one cycle, and hauling used **34 of
864** slot-loads. What binds is **capital and the 21 order slots**, shared across items.
`NpcFlipStrategy` sizes every candidate independently against `maxCapitalPerFlip()`, so a ranked
list double-counts the bankroll.

### Ranking key: profit per slot-load, not cap efficiency

Full day, 2 cycles, hard 500M cap:

| ranking key | profit/day | cap spent | hauling used |
| --- | --- | --- | --- |
| margin per slot-load | **76.4M** | 500M | 307 / 4,320 |
| cap efficiency (`margin / npc`) | 4.8M | 9.9M | 8,640 / 4,320 |

Cap efficiency is **16x worse** — it picks 9-coin items and drowns the player in hauling.
`NpcFlipStrategy.notes()` currently prints cap efficiency on every candidate as the figure to
optimise. **Delete that note.** A player following it loses 16x.

A Lagrangian blend `margin·stack / (1 + λ·npc·stack)` peaks at 79.8M/day against 76.4M — **4.4%**,
not worth a tuning parameter that drifts with the book. Rejected.

Cap efficiency is a **bad ranking key and a good floor**. That is not a contradiction: the floor
evicts items that waste the binding budget without dictating the order.

### Margin floor: 15%, not 5%

30-minute repricing, 95% persistence, 2 cycles:

| floor | profit/day | cap used | pool |
| --- | --- | --- | --- |
| 0% | 75.7M | 500M | 190 |
| 5% | 104.6M | 500M | 122 |
| 10% | 136.3M | 500M | 100 |
| **15%** | **172.5M** | 500M | 87 |
| 20% | 161.1M | 368M | 74 |
| 30% | 152.0M | 325M | 66 |

Above 20% the pool is too small to spend the cap. 15% is the peak.

### Fill: measured displacement, horizon = check-in interval

**The flat 25% share was the worst number in the first draft and it was wrong in both
directions.** `FillModel` already measures displacement properly and **759 products have ≥200
samples** on the tape. Top-bid displacement on basket items runs **1.0 to 6.6 lifts an hour**.

| fill model | profit / 8h cycle |
| --- | --- |
| flat 25% | 40.1M |
| measured, post once and never return | **11.5M** |
| measured, always at the top of the book | 73.2M |

So a post-and-walk-away plan built on the flat share is **3.5x optimistic**.

`FillModel` explicitly models a player who does not chase (`FillModel.java:33-36`). That is right
for spread flipping and wrong here: repricing up eats a **fixed, known** margin with a computable
stop, and there is no undercut spiral because the exit cannot move. Measured 8-hour chase cost as
a share of the NPC price: `CLIPPED_WINGS` 0.00%, `BEADY_EYES` 0.56% against a 34% margin,
`MANTID_CLAW` 14.4% against 30%.

**The clean formulation: the fill horizon is the check-in interval, not the resting window.**
That is already exactly what `FillModel.estimate` takes.

| reprice every | reprices/cycle | profit/cycle |
| --- | --- | --- |
| 8h (never) | 1 | 11.1M |
| 4h | 2 | 21.2M |
| 2h | 4 | 37.9M |
| 1h | 8 | 54.2M |
| **30 min** | **16** | **59.7M** |
| 15 min | 32 | 67.3M |

Flattens below an hour. Per extra reprice round: 8.35M going from 4h to 2h, 4.08M from 2h to 1h,
then **690k from 1h to 30 min and 475k from 30 to 15**. The knee is at an hour, and the 30-minute
default sits just past it. The whole 15-minute-to-1-hour range is within ±11% of it, so this is not
a setting worth agonising over — but it is also the fill horizon plans are sized with, so setting it
shorter than you will really work quotes fills you never collect.

**Re-measured 2026-08-11 on the user's own tape**, four days, the 117 products the basket draws
from: top-bid lifts run a **median 2.09 an hour** (p25 0.89, p75 3.35, max 6.08), which lands inside
the 1.0–6.6 range measured on three days in August. So after 30 minutes away, 58% of a basket's
orders have been outbid, against 39% after 15 minutes and 76% after an hour. The reminder therefore
has something to say at essentially every interval whatever it is set to: shortening it buys
proportionally more interruptions, not more warnings that would otherwise have been missed.

**No automated repricing.** The mod says which orders need bumping; the player clicks. Automating
it is a macro and against Hypixel's rules.

### Persistence filter: keep it, for the right reason

Honest backtest — split the tape 8 hours before its end, build candidates from history only,
judge against what actually happened:

| persistence cohort | n | realized / quoted | went negative |
| --- | --- | --- | --- |
| ≥95% | 161 | 1.04 | **0 / 161** |
| 50–95% | 22 | 0.89 | 2 / 22 |
| <50% | 9 | 1.00 | 1 / 9 |

`NECROMANCER_BROOCH` quoted an 80% margin, realized **−10%**, held its edge 30.5% of the holdout.

**Re-measured 2026-08-10 with the holdout rolled across the tape**, which is what
`NpcPersistenceBacktestTest` now ships (`./gradlew test -PtapeBacktest`). One eight-hour holdout is
one evening of one market: run again on a quiet night, no cohort went negative at all and the table
above says nothing. Stepping the cutoff every 6 hours through 13 days of tape — 39 cutoffs, 4,692
candidate-windows, each judged on the mean margin available to a top-of-book order across the eight
hours after its cutoff:

| persistence cohort | windows | realized / quoted | gap closed inside the window |
| --- | --- | --- | --- |
| ≥95% | 3,868 | 1.00 | **0.2%** |
| 50–95% | 533 | 0.97 | 3.2% |
| <50% | 291 | 0.82 | **17.2%** |

Same finding, two orders of magnitude more evidence: a gap that has stood up is 86x less likely to
close inside the window you are committing a slot for.

**Two things to keep straight.** A naive profit sweep says the filter *costs* 17%; that sweep
books every planned unit as filled at the quoted price and is structurally blind to the risk.
Trust the backtest, not the sweep. And with a hard chase stop the filter is **not** protecting
against loss — a vanished edge simply never fills. It protects **order slots**, which are the
binding resource.

Same lesson the repo already recorded for signature terms: measure against the tail, not the
median.

### Which items stack: the items resource cannot tell you, the book can

Found in play 2026-08-11. With 8 order slots the basket asked for 500 Jungle Hearts and 500 Clipped
Wings. Both are unstackable, so the bazaar takes 256 of each in one order, and neither line could be
placed as written.

The cause is that `/v2/resources/skyblock/items` does not carry the answer. Measured against the
live resource on 2026-08-11:

- 515 of 5,646 items carry `unstackable`.
- **0 of the 107 reforge stones carry it**, and `CLIPPED_WINGS`, `BEADY_EYES` and `MANTID_CLAW` are
  reforge stones. `JUNGLE_HEART` does not carry it either.
- Nothing else in the resource separates the cases. Comparing 107 items measured unstackable against
  108 measured stackable, `material`, `tier`, `category` and `museum` all overlap; 52 of the
  unstackable group (the `ENCHANTMENT_*` books) are not in the resource at all.

The order book answers it exactly. A price level reports units and the number of orders holding
them, so `amount / orders` is a lower bound on the largest order there, and an order over 256 units
cannot exist on an item that does not stack. Across the whole bazaar the observed maxima land in two
clusters with nothing between them:

| observed largest order | products |
| --- | --- |
| exactly 256 | 107 |
| exactly 71,680 | 101 |
| everything else | thin books, no evidence either way |

**Unproven is read as unstackable**, and that costs almost nothing. Of the 117 products an NPC
basket could draw from that day, 108 proved stackable and the 9 that did not are all genuinely
unstackable in game — the reforge stones, the potato books, Jungle Heart, Overflowing Trash Can.

**One snapshot is enough; there is nothing to accumulate across polls.** The weakest of the 108
proofs was an 8,786-unit order, 34x the threshold, and none fell between 256 and 4,096. An item
cannot flicker between the two answers on a book that deep, so `Stacking` reads the current book and
holds no state.

This is the same failure shape the repo already records for shared item ids: the wrong answer is
silent. A 500-unit line is a perfectly plausible number right up to the moment you type it in.

### The cycle is one list, not two

Found in play 2026-08-11. `/flip npc plan` allocated against an empty account and
`/flip npc reprice` reviewed the resting orders, and neither knew the other existed. A player coming
back to a book with fourteen orders on it therefore got two answers that could not both be followed:
place twenty-one orders, and separately, here are the fourteen you have. Every trip after the first
one of a cycle was quoting an account the player did not have.

`NpcWorklist` is the join, and the arithmetic is the point rather than the presentation:

- resting orders come out of the order slots and out of the bankroll before any new line is sized;
- an item with an order already resting on it is dropped from the basket outright, because the
  resting order's real price lives in the orders menu and a second line at a second price is bidding
  against your own bid;
- what comes out is one ordered list of clicks.

**The order is claims, cancels, reprices, places**, and it is not the order the coins are in. A
claim is coins already made and it blocks the item from leaving the order at all. A cancel hands
back the order slot every line below it is short of. A place needs coins the cancels have just
returned.

### Two states the advice had nothing to say about

**Partial fills.** A buy order that fills part way announces nothing in chat - the notification only
fires on a complete fill - so the amount is read out of the orders menu, which `TradeTracker` was
already doing and nothing was reading. `NpcReprice.Order` now carries the filled and uncollected
counts, and an order that filled completely is kept rather than dropped for having nothing left on
the book, which was hiding exactly the orders that had worked.

**Orders that never fill.** An order can be correctly priced, on top of the book, and simply not
filling. Every axis the book knows about reported it healthy, forever. `npcRestingHours` already
meant "how long capital may be tied up here" and nothing enforced it, so it now does:
`Action.EXPIRED` past the window, whatever the book says. No new parameter, and no tuning constant -
the refund is the whole remaining stake, so the only question is whether the slot is worth more
elsewhere, and after a full window of nothing it is.

**A buy order's price is in its escrow line.** Chat announces a placement with the size and the
total and never the price per unit, so an order stayed unpriced until the orders menu was opened —
`restingBuyOrders()` dropped it, the basket charged neither a slot nor the coins for it, and the mod
could tell you to place the same order twice. A buy escrows exactly `units x price`, so the price is
`setupCoins / total`. **The escrow line is rounded to the coin**: the recorded
`311x Purple Candy for 6,554,823 coins` really rests at 21,076.6, and 311 x 21,076.6 is 6,554,822.6.
Hence "outbid" means the top bid beats you by more than half a price increment, not by any amount at
all. Sells are not priced this way — a sell quotes a taxed payout, so it stays unpriced.

**The age is a lower bound.** The tracker starts empty every launch, so `placedAt` is when the mod
first saw the order rather than when Hypixel accepted it. An order placed yesterday and first seen
in today's menu is dated today and gets another full window. Expiry therefore fires late, never
wrongly, which is the correct direction for a rule whose failure mode is cancelling a live order.

### Repricing is a round, not a stream

Found in play 2026-08-11, and decided in full in `docs/adr/0002-reprice-in-rounds.md` — that ADR is
the record, this is the measurement it turns on.

`NpcReprice` compared your resting price to the top bid and called anything above it a reprice. On a
contested book that is correct every time and useless every time. Live sample of
`TRANSMISSION_TUNER`: the top five bids were 28594.6, 28594.5, 28594.4, 28594.3, 28594.2, each one
order — five bots penny-jumping by the 0.1 increment. An order placed at the top is outbid within
seconds, so the mod asked for a reprice within seconds, forever.

**The curve above never described that.** 16 reprice rounds per 8-hour cycle is the 59.7M figure, and
1h → 30 min is worth 690k of it (~1%). Continuous chasing is not the top of that curve; it is off the
end of it, buying nothing and costing every click.

So advice is delivered in **rounds**: a frozen list of tasks with frozen prices, opened at most once
per `npcCheckInMinutes`, surviving menu closes and NPC trips, superseded when the interval elapses.
The cost is being a price step down for up to one interval — about 1% of a cycle by the table above,
against a chase that is not achievable by hand at any price.

Three things the round is measured or reasoned into, rather than chosen:

- **A reprice has to earn its row.** Expected fills at the top over the rest of the interval, minus
  the fills where the order sits now, times the margin — from the same `FillModel` displacement the
  placing side sizes with. At a 5k floor on the same book: `TRANSMISSION_TUNER`, displaced every
  ~9 min, a repost is worth 2,890 over 30 minutes and is dropped; at one displacement per 10 hours
  the same order is worth 9,744 and is kept. **The baseline is zero**, the reading most favourable to
  repricing, so the filter only ever drops a row that fails on the generous reading. **An unmeasured
  gain never suppresses a reprice** — a fresh install has no tape.
- **The dwell exempts adopted orders.** An order must be an interval old to enter a round, but
  `placedAt` is "when this session first saw the order", so an order read out of a menu snapshot
  looks newborn however long it has really rested. Dwelling on that would mute the list for a whole
  interval at the moment it matters most: logging in to a basket outbid overnight.
- **A row survives its own cancel.** The bazaar has no in-place edit, so a reprice is a cancel and
  then a re-post, and the cancel deletes the order the price came from. Rows are held until the item
  is resting at the frozen price again, and while pinned they reserve their slot and their capital so
  the basket cannot spend what the re-post needs.

Claims and dead-trade cancels bypass the round entirely: a claim is coins already earned and blocks
the item from leaving the order, and a `CANCEL` past the chase stop or an `EXPIRED` past the resting
window is a trade that is over with the capital stranded in it. Neither improves by waiting.

**Open:** `NpcSettingsSweepTest` for a max-orders-per-item cap (1, 2, unlimited). An unstackable line
costs four times the clicks of a stackable one; the profit cost of capping it is not yet measured.

### Rejected: any guard on book depth

The unguarded basket posts `OVERFLOWING_TRASH_CAN` at 141% of the whole resting buy side,
`BEADY_EYES` at 62%. Order-size flow impact is fine everywhere (no order exceeds 4.3% of weekly
volume).

| guard | profit/cycle | pool |
| --- | --- | --- |
| none | **86.5M** | 87 |
| cap order at 25% of resting depth | 75.8M | 87 |
| require ≥15 buy orders (what `BazaarSpreadStrategy` does) | 59.7M | 42 |

**All rejected.** Being outbid on an oversized order costs time, not coins — you reprice or
cancel — and the player checks in several times an hour anyway.
`BazaarSpreadStrategy.MIN_ORDERS_PER_SIDE = 15` exists to avoid undercut spirals, which cannot
happen when the exit price is fixed. Do not import it.

### Rejected: cancelling a live order to chase a better item

Every measurement above plans a cycle against one book snapshot, so within a cycle the item chosen
at the top of the window is trivially still the best one at the end. That hides the only argument
for displacement: the book moving under some other item until it outruns something already resting.

`NpcDisplacementSweepTest` replays the real book from the tape at the 30-minute check-in and runs
`NpcBasket.plan` at every step, under two policies. **Sticky** is what ships — an order keeps its
slot until the book kills it, and freed slots are refilled from the whole market. **Reshuffle** is
the proposal at its theoretical best — the entire basket re-chosen every 30 minutes, with no
cancelled queue position, no stranded partial fill and no click cost. Measured 2026-08-12 over 9
windows of the user's tape, at the live settings (21 slots, 0.20 floor, 800M bankroll):

| policy | profit | NPC payout | profit per coin of payout |
| --- | --- | --- | --- |
| sticky | 930.5M | 804.4M/day | **38.56%** |
| reshuffle, free of charge | 989.9M (+6.4%) | 863.0M/day | 38.23% (-0.9%) |

**Rejected.** The +6.4% is bought entirely by handing the NPC 7.3% more, on a day that already
wants to hand over 804M against a 500M cap — 161% of it. Under a binding cap the day is worth
`cap x margin on payout`, and on that number reshuffling is *worse*: 192.8M/day against 192.3M.
Perfect foresight and free cancels buy the right to hit the same ceiling sooner at a slightly worse
price.

This is the same finding as *Ranking key: profit per slot-load, not cap efficiency* seen from the
other end. Margin against the cap is what a capped day is won on, and the mod already spends it in
the only place it pays — `npcMinMarginRatio` as a floor, never as a ranking key.

The gap concentrates in 2 of 9 windows (+17.9%, +20.6%) and is under 4% in six of them, so a
threshold tuned to catch it would fire on noise the rest of the time. The test is kept as a
tripwire: it asserts both that the cap still binds and that reshuffling still loses on margin, so
the question re-opens by failing rather than by being remembered.

### What actually limits a day, measured on the live book

Re-measured 2026-08-11 against the live bazaar, the live items resource and four days of the user's
own tape (1,193,354 samples, 759 of 767 NPC-priced products with a measured edge), running
`NpcBasket.plan` under the user's real settings and then one lever at a time. **Every row came back
`SLOTS`.** Nothing else binds, at any setting tried.

Baseline is the config as it stood: `npcMaxOrderSlots` 14, `npcMinMarginRatio` 0.15, `bankroll` 800M,
Bazaar Flipper 1 (21 slots on the account).

| change | profit/cycle | capital | ROC | NPC payout |
| --- | --- | --- | --- | --- |
| baseline, 14 slots | 25.1M | 58.9M | 43% | 84M |
| `npcMaxOrderSlots` 0, so all 21 | **32.3M** | 69.5M | 47% | 102M |
| Bazaar Flipper 2, 28 slots | **45.1M** | 101.3M | 45% | 146M |
| 21 slots, floor 0.10 | **44.1M** | 179.4M | 25% | 223M |
| 28 slots, floor 0.10 | **54.4M** | 227.6M | 24% | 282M |

**The bankroll is not a lever here.** At the 0.10 floor with 21 slots, 400M produces the same basket
as 1.6B; even 200M loses only 10%. At the 0.15 floor the basket asks for 69.5M of 800M. Coins are
not what is short.

**The margin floor deserves a re-measure.** The 15% peak in the sweep above was measured *under the
500M daily cap*, and the cap is what made a fat floor pay: at a 5% floor one cycle collects 380.7M of
NPC payout, so two cycles a day overrun the cap and the extra slots buy nothing. At 10% one cycle
collects 223M and two fit. So the two measurements do not contradict each other - they are the same
trade-off at different bankrolls - but 15% is no longer obviously the peak for a player whose coins
are idle. **This is one snapshot against a day-long simulation, so it is a candidate and not a
setting change.**

The floor sweep in full, 21 slots, 800M:

| floor | profit/cycle | capital | ROC | payout |
| --- | --- | --- | --- | --- |
| 0.05 | 46.7M | 334.0M | 14% | 380.7M |
| 0.075 | 43.1M | 214.5M | 20% | 257.6M |
| 0.10 | 44.1M | 179.4M | 25% | 223.4M |
| 0.125 | 33.9M | 91.0M | 37% | 124.9M |
| 0.15 | 32.3M | 69.5M | 47% | 101.8M |
| 0.20 | 31.5M | 54.5M | 58% | 86.0M |
| 0.30 | 28.7M | 32.3M | 89% | 61.0M |

The check-in interval does not appear here because a static allocation cannot see it: a basket's size
comes from fill over the resting window, and the interval's effect is on how much of it actually
fills, which only a simulation over repeated reprices measures. That is the 59.7M-at-30-minutes
against 67.3M-at-15 in the table above, and it still stands.

### The margin floor, settled: it depends on the bankroll, and 0.15 is right at neither end

Measured 2026-08-11 by `NpcSettingsSweepTest` (`./gradlew test -PtapeBacktest`), which is the
day-long version the re-measure above asked for: three 8-hour cycles against the live book, each
basket's NPC payout charged against the 500M cap, floors and slot counts swept one at a time. 767
products with a measured edge, 2,124 on the book, Bazaar Flipper 1.

At the user's live settings — 14 slots, 800M bankroll:

| floor | profit/day | capital | ROC | payout | cap spent |
| --- | --- | --- | --- | --- | --- |
| 0.05 | 48.3M | 451.7M | 11% | 500.0M | yes |
| 0.075 | 73.5M | 426.5M | 17% | 500.0M | yes |
| 0.09–0.105 | 81.6M | 418.4M | 19% | 500.0M | yes |
| **0.11** | **86.1M** | 413.9M | 21% | 500.0M | yes |
| 0.115 | 62.4M | 188.0M | 33% | 250.4M | no |
| 0.125 | 66.6M | 178.2M | 37% | 244.8M | no |
| **0.15 (shipped)** | **66.1M** | 133.4M | 50% | 199.5M | no |
| 0.30 | 54.1M | 75.9M | 71% | 130.0M | no |

**The rule the shape of that curve gives is: the best floor is the highest one that still spends the
whole daily cap.** Everything at or below 0.11 exhausts the 500M; everything above it leaves 250M to
300M of cap unspent, and profit falls by exactly that unspent share. Below 0.09 the cap gets spent on
items whose margin is too thin to pay for it.

**The 0.115 cliff is one item.** `ENCHANTED_RAW_SALMON` sits at an 11.3% margin and carries 92.0M of
the first basket's 167.4M payout and 10.4M of its 27.7M profit. A floor of 0.11 is 0.003 from
dropping it; 0.10 is 0.013 from it and sits on a flat stretch worth 81.6M. **Prefer 0.10 to 0.11**:
the extra 4.5M/day is not worth a setting that one item's drift turns into a 24M/day loss.

Sweeping the bankroll with the floor, at 14 slots, shows why the earlier two measurements disagreed:

| bankroll | best floor | profit/day | vs 0.15 |
| --- | --- | --- | --- |
| 25M | 0.30 | 52.9M | +69% |
| 100M | 0.12 | 66.6M | +1% |
| 250M and above | 0.11 | 86.1M | +30% |

**A thin floor pays only while the daily cap is what you run out of.** With 25M in the purse the
capital runs out long before the cap, so the highest return per coin wins and 0.30 beats 0.15 by
69%. Past roughly 250M the cap binds instead and the fat floor leaves half of it unspent. The
shipped 0.15 is optimal at neither end — it is within 1% of the peak only in the narrow band around
100M — which is an argument for making the floor a function of the bankroll rather than for moving
the constant. **Not done: that is a design change, and it is the one open question here.**

The extra candidates a 0.10 floor admits are exactly as durable as the fat ones, so this costs
nothing in slot risk. Re-running the persistence holdout at the lower floor
(`-PnpcMinMarginRatio=0.10`) adds 530 candidate-windows to the ≥95% cohort and moves neither number:

| floor candidates drawn at | ≥95% windows | realized/quoted | gap closed |
| --- | --- | --- | --- |
| 0.15 | 4,056 | 1.00 | 0.3% |
| 0.10 | 4,586 | 1.00 | 0.3% |

Slots are still the other lever and still the bigger one: at floor 0.10, going from 14 slots to all
21 is 81.6M/day against 93.9M. That is the coop's slots, so it is not a free change.

### Expected return

~86M coins per 8-hour cycle at 30-minute repricing, cap-bound at roughly two cycles a day. The
user's friend reportedly makes 90M/day; the model predicted 92.7M/day at hourly repricing before
that figure was known, which is agreement rather than proof.

**Both simulated cycles ran against the same book snapshot**, so cycle 2 is optimistic — its
orders would in reality compete with cycle 1's for the same dump flow. Treat day-level figures as
an upper bound; per-cycle figures are sound.

## What the existing code gets wrong

1. `NpcFlipStrategy`'s javadoc calls the daily cap "the binding constraint on every high-value NPC
   flip". Measured: it does not bind inside a cycle.
2. The same javadoc sizes plans around `SLOTS_PER_TRIP`/`TRIPS_PER_HOUR` walking trips. `/trades`
   with a booster cookie means no walking.
3. `notes()` prints cap efficiency as the figure to optimise. Following it costs 16x.
4. `ORDER_FILL_SHARE = 0.25` is a guess where a measurement exists.
5. Every candidate is sized against the full `maxCapitalPerFlip()`, so the list double-counts the
   bankroll.
6. `risks()` says the edge "closes fast". 204 of 223 edges are permanent.

## Build plan

1. **`core/valuation/NpcEdgeHistory`** — per product, from `bazaar-tape`: fraction of samples with
   `bid + 0.1 < npc_sell_price`, median margin, and cumulative upward bid drift per hour. Needs
   ≥200 samples to report. Computed on `MarketPoller`'s maintenance thread.
2. **Config** (`FlipperConfig` + `ConfigSchema`, both required or `ConfigSchemaTest` fails):
   - `npcMinMarginRatio` default `0.15`, clamp `[0.02, 0.50]`
   - `npcCheckInMinutes` default `30`, clamp `[5, 480]`
   - `npcRestingHours` default `8.0`, clamp `[0.5, 24.0]`
   - `npcMaxOrderSlots` default `0` meaning "all of `Fees.bazaarOrderSlots()`", clamp
     `[0, Fees.MAX_BAZAAR_ORDER_SLOTS]` — 28, not the 56 the six-level formula implied
   - retire `npcSessionHours`
3. **Rewrite `NpcFlipStrategy`'s buy-order route** — measured fill at the check-in horizon, chase
   cost, 15% floor, delete the cap-efficiency note, drop the trips story from javadoc and risks.
4. **`core/strategy/NpcBasket`** — greedy allocator over profit per slot-load, subject to
   bankroll, order slots, hauling, and remaining daily cap. **Reads `bankroll` from
   `StrategyContext` at plan time; never caches it**, so raising the bankroll in config and
   running `/flip reload` re-sizes the basket.
5. **`/flip npc plan`** and **`/flip npc reprice`**, plus a Basket panel on `FlipScreen` (draw
   inside the `pose().scale(zoom, zoom)` block, hit-test on `mouse / zoom`).
6. **`core/text/Guide`** entries for every new player-facing term.
7. **Tests** — `NpcBasketTest`, `NpcEdgeHistoryTest`, and a tape backtest pinning the persistence
   cohort finding so it cannot silently rot.
8. **Fix the user's config**: `bazaarFlipperLevel` reads 2, they have level 1. At level 2 the mod
   plans 28 order slots against a real 21 (`Fees`: `14 + 7 × level`) and quotes 1.0% bazaar tax
   against a real 1.125%.

## Reproducing the measurements

Live pulls go to the scratchpad, never the repo, and are reduced with `python3 -c` — see the
`api-probe` skill. The tape is at
`~/Library/Application Support/minecraft/config/skyblock-flipper/bazaar-tape/`, one JSONL file per
UTC day, records `{"p": id, "t": epochMillis, "a": ask, "b": bid, "bv": …, "sv": …}`.

Persistence, drift and the holdout backtest are all computed from `b` alone plus
`npc_sell_price` from the items resource.

The two tape backtests are opt-in JUnit tests rather than scratch scripts, because both answer
questions a later change could silently invalidate:

```bash
./gradlew test -PtapeBacktest \
  -PbazaarTapeDir="$HOME/Library/Application Support/minecraft/config/skyblock-flipper/bazaar-tape" \
  --tests '*NpcSettingsSweepTest'      # floors, slots, bankrolls, check-in, order caps
./gradlew test -PtapeBacktest \
  -PbazaarTapeDir="$HOME/Library/Application Support/minecraft/config/skyblock-flipper/bazaar-tape" \
  --tests '*NpcDisplacementSweepTest'  # whether cancelling a live order to chase a better item pays
```

They differ in what they replay, which is the whole reason there are two. The settings sweep plans
every cycle against **one live book snapshot** and varies the settings; it is the right shape for
"what should this number be" and structurally cannot answer anything about the book changing. The
displacement backtest replays **the book itself** at 30-minute steps out of the tape, rebuilding a
one-level-a-side `BazaarProduct` per product per step. One level is faithful for the resting-order
route, which reads the top of the bid side and the weekly volumes and nothing else, and is *not*
faithful for the instant-buy route, which reads depth. Do not reuse that replay for anything that
walks the book.
