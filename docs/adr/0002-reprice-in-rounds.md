# 2. Reprice in rounds, not on every book move

Date: 2026-08-11

## Status

Accepted, implemented. All seven steps of the plan at the end of this file have landed; each one
records what it did and what it decided, so the plan stays the account of how this was built.

## Context

`NpcReprice` compares your resting price to the top bid and calls anything above it a reprice. On a
contested book that is correct every time and useless every time. Live sample of
`TRANSMISSION_TUNER`, 2026-08-11: the top five bids are 28594.6, 28594.5, 28594.4, 28594.3, 28594.2,
each a single order. Five bots penny-jumping by the 0.1 increment. An order placed at the top is
outbid within seconds, so the mod asks for a reprice within seconds, forever.

The measurement the strategy is built on never described that. `docs/npc-flipping.md` measures
**16 reprice rounds per 8-hour cycle** at 59.7M, and going from 1h to 30 min is worth 690k of it
(~1%). Continuous chasing is not on the curve at all; it is off the end of it, buying nothing and
costing every click.

Four further faults found while diagnosing this, all of which make the same trip worse:

1. `NpcCheckInService.acknowledge()` is called only from `/flip npc plan` and `/flip npc reprice`
   (`FlipCommand.java:241,274`). The bazaar panel never calls it, so working the basket from the
   panel never restarts the check-in clock and the chime fires the moment you finish placing.
2. A buy order announced in chat has `unitPrice = 0` until the orders menu is opened, so
   `TrackerService.restingBuyOrders()` drops it, `NpcBasket.Held` never charges a slot or the coins
   for it, and the basket can tell you to place the same order twice. `TrackedOrder.setupCoins`
   has held the escrow from the chat line the whole time, and a buy escrows exactly `units x price`.
3. Cancelling to reprice deletes the row that told you the price. `CandidateFeed.worklist()`
   rebuilds on tracker state (`CandidateFeed.java:131`), the order is gone, the task is regenerated
   from resting orders only, and the number you were about to type goes with it. The bazaar has no
   in-place edit, so cancel-then-repost is the only workflow and the mod drops you mid-way through
   it.
4. `TRANSMISSION_TUNER` and the other unstackable products take 256 units per order
   (`Stacking.UNITS_PER_ORDER_UNSTACKABLE`), so a 1000-unit line is four orders, four rows and eight
   clicks to move.

`NpcReprice` also ignores the one thing that would tell it whether a reprice is worth doing.
`FillModel` measures real top-bid displacement per product from the tape, 759 products with >=200
samples, and `NpcFlipStrategy.java:387` already sizes every basket line with it over the check-in
horizon. The reprice side has no idea that a Transmission Tuner order will be outbid again in nine
minutes while a Clipped Wings order will hold for hours.

## Decision

**Chase on a clock, not on the book.** Reprice advice is delivered in rounds.

- A round is a frozen list of tasks with **frozen prices**, opened at most once per
  `npcCheckInMinutes`. Prices computed when the round opens do not move while you work it, so a
  number can be read, walked to a menu and typed.
- A round survives menu closes and NPC trips. **Closing the bazaar means nothing** — you have to
  leave it to sell to the NPC. It ends when the tracker sees every task done, or when the interval
  elapses and a fresh round supersedes it with recomputed prices.
- An order must itself be at least one interval old to enter a round. Orders adopted from a menu
  snapshot (`TradeTracker.java:200-203`) have unknown age and are **always eligible**, so logging in
  to a basket that was outbid overnight still gets a full list at once. Without that exception the
  dwell would mute the list for 30 minutes at the moment it matters most, because `placedAt` is
  "when this session first saw the order".
- **Claims and dead-trade cancels bypass the round.** A claim is coins already earned and blocks the
  item from leaving the order; a `CANCEL` because the book caught the NPC price, or an `EXPIRED`
  past the resting window, means the trade is over and the capital is stranded.

**A reprice has to earn its row.** Expected fills at the top of the book over the rest of the
interval, minus fills where the order sits now, times the margin — from `FillModel`, the same
measured displacement the placing side uses. Below `minProfitPerFlip` it does not make the round;
the rest sort by that gain. On a fast-displacement item the gain is small and the mod stops asking.

**One row per item, and it survives its own cancel.** Rows merge across an item's 256-unit orders:
"cancel 4, repost 4 x 256 at 28594.7". Where a free slot and the coins exist, the row says place
first then cancel, so you never leave the book. Otherwise it is pinned through the cancel with its
frozen price, **reserving its slot and capital** so `NpcBasket.plan` cannot spend them on something
else in the meantime.

**Between rounds**, the bazaar panel adds one header line — "3 outbid, next round in 12m" — and
keeps its rows for actual clicks; it is ~70px wide. `/flip npc reprice` lists them with due times.
One chime per round that opens with work in it, plus the exempt claims and cancels.

**A new buy order is priced from its escrow line**, `setupCoins / total`, so it counts against slots
and bankroll immediately; the orders menu overrules on the next read. "Outbid" requires the top bid
to beat you by more than a rounding error, in case that line is ever rounded.

`npcCheckInMinutes` stays at 30. The measured knee is at an hour and the whole 15min-1h band is
within +-11%.

### Rejected

- **Excluding contested books from the basket.** The clock solves the churn without giving up the
  margin, and the fill model already prices displacement into the sizing.
- **A learned/ML ranking.** The mod already learns this from the user's own tape, transparently, and
  a model would fit the same data with less inspectability and no more information.
- **A skip button.** Nothing depends on it: a round supersedes at the interval whether or not it was
  finished. Add it only if a row turns out to be ignored repeatedly.
- **Capping orders per item** — and now for a reason rather than for want of a measurement. The
  sweep prices it below: the effect is within noise and changes sign between slot counts.

## Consequences

The reprice list becomes a batched trip rather than a stream. The cost is being a price step down
for up to an interval, which the measured curve says is worth about 1% of the cycle against chasing
every move — and chasing every move is not achievable by hand anyway.

`NpcReprice.review` stops being a pure function of (orders, book) and gains a round: it needs to
know when the round opened and what it froze. The round is a record in `core` with `now` passed in,
same rule as everything else there; the client holds the open instance.

## The measurement that was open

**Capping orders per item is noise. Do not ship one.** Measured 2026-08-12 by `NpcSettingsSweepTest`
over 14 days of bazaar tape — 767 products with an edge, 1781 with measured displacement, 2124 on
the book — against the user's live settings: 800M bankroll, Bazaar Flipper 1, 8h resting, 30min
rounds, 500M daily cap, 0.10 floor, 50k min profit per flip, 0.25 max capital share.

| cap | profit/cycle, 14 slots | vs uncapped | profit/cycle, 21 slots | vs uncapped |
| --- | --- | --- | --- | --- |
| 1 | 36.9M | -2.5% | 59.7M | -0.2% |
| 2 | 37.8M | +0.0% | 57.1M | -4.5% |
| unlimited | 37.8M | — | 59.8M | — |

Across the floors the cap moves the answer between -2% and +13% with no consistent sign, and it
changes sign between the two slot counts at the same floor. That is what perturbing a greedy
allocator looks like, not an effect. The mechanism that would make a cap pay — freeing a slot from
an item's second order for a fresh item with its own dump flow — is real, but on this book it is
worth less than the ranking it disturbs, and it is not reliably positive.

**The first version of this measurement was wrong**, and is worth recording as a trap rather than
quietly replaced. It reported a cap of 1 as a firm +5.7%. The sweep was passing
`TrendSnapshot.empty()`, so no product had measured displacement, every line fell back to
`NpcFlipStrategy.UNMEASURED_FILL_SHARE`, and line sizes ignored the check-in horizon entirely. It
also left `minProfitPerFlip` at 0 and `maxCapitalShare` unlimited, which are two of the three things
that bound a line. The harness was measuring a market where nobody is ever outbid and every line can
be any size. `measureFromTape` now builds a `PriceHistory` in the same tape pass as the edges, and
the test asserts that displacement was measured at all, so this cannot silently recur.

**Not shipping a setting.** `NpcContext.maxOrdersPerItem` stays as a sweep dimension:
`UNLIMITED_ORDERS_PER_ITEM` is the shipped value and every caller in the mod uses the six-argument
constructor. The question is now answered rather than open, which is what step 7 was for.

## Plan

Each step compiles and passes tests on its own and is committed `wip:` as it lands.

1. ~~**Escrow-derived buy price and an outbid tolerance.**~~ **Done, `adc708c`.** `TradeTracker`
   sets `unitPrice = setupCoins / total` on a BUY placement (sells quote a taxed payout, so they
   stay unpriced); `TrackedOrder.applySnapshot` no longer lets a menu slot with no price line erase
   it; `NpcReprice` holds unless the top bid beats the resting price by more than half an increment.
   **The escrow line is rounded to the coin** — the recorded `311x Purple Candy for 6,554,823 coins`
   really rests at 21,076.6, and 311 x 21,076.6 is 6,554,822.6 — which is what the tolerance is for.
   Fixes the double-place leak and the "Open Manage Orders" dead end.
2. ~~**Reprice value.**~~ **Done, `10cc4ef`.** `NpcReprice.Advice` carries a `RepriceValue`:
   expected units over the rest of the interval from `FillModel`, capped at the units resting, times
   the margin at the repost price. Below `minProfitPerFlip` the advice is `HOLD` with the reason
   saying so, and `Advice.outbid()` is what still reports the book having moved. `review` gained a
   four-argument form taking the time left, which is where step 3's round plugs in.
   **The baseline is zero**, not a modelled fill for an order sitting below the top: nothing measures
   when the orders inside it clear, and zero is the baseline most favourable to repricing, so the
   filter only ever drops a row that fails on the generous reading. **An unmeasured gain never
   suppresses a reprice** — a fresh install has no tape, and filtering on the fallback share would
   mute the mod hardest on the accounts with no history. Measured on the same book at a 5k floor:
   at `TRANSMISSION_TUNER`'s displacement (every ~9 min) a repost is worth 2,890 over a 30-minute
   interval and is dropped; at one displacement per 10 hours the same order is worth 9,744 and is
   kept. `NpcFlipStrategy.UNMEASURED_FILL_SHARE` is now package-visible so both halves of the cycle
   assume the same throughput.
3. ~~**The round, in core.**~~ **Done, `da0bb1b`.** `NpcRound` is a record of opened-at, the interval
   it was opened for and the frozen rows, `open`ed from a review with `now` passed in. It freezes
   `REPRICE` and nothing else, so claims and dead-trade cancels bypass it by construction. `elapsed`
   is the supersede rule and `remaining` is the horizon the four-argument `review` from step 2 takes.
   **The interval is frozen with the prices**: a settings edit part way through changes the next round
   rather than moving the end of the one in hand, and a finished round does not let the next one open
   early, because the interval is the rate limit on opening at all.
   **The dwell rule needed an input that did not exist.** `placedAt` is the same number for an order
   watched being placed and one adopted from a menu, and means a different thing in each, so
   `TrackedOrder.adopted()` now records which and `NpcReprice.Order` carries it; the seven-argument
   constructor still means "announced". Unknown-age and adopted orders are always eligible.
   Rows merge per item, taking the **highest** of the merged post prices so no order is re-posted
   under the top of the book, and `OUTBID_TOLERANCE` is package-visible so the round calls the same
   two prices equal that the review does.
   **A row is outstanding while the item has nothing resting on it**, which is what holds the price
   through the cancel half of its own reprice - fault 3 above. The cost is a row that lingers when a
   re-post fills and is claimed inside the same interval, leaving no order behind as evidence it
   moved; the next round clears it. A stale row costs one wasted click, and dropping a row mid-reprice
   costs the number the player was walking to the menu to type.
4. ~~**Wire the round into `NpcWorklist`.**~~ **Done, `3841956`.** `NpcWorklist.of` gained a
   four-argument form taking the round; the three-argument one passes null and behaves exactly as
   before, which is the honest answer for a caller tracking no clock. Given a round: the review is
   valued over `round.remaining(now)`, the reprice tasks are the round's frozen rows rather than the
   live advice, and the holds, claims, cancels and places are unchanged.
   **A pinned row reserves at the larger of the two claims, never their sum.** The row and the orders
   behind it are one position, so an item with two of four orders already moved reserves four slots
   and not six; mid-reprice, with nothing resting, the row is the only claim there is, which is what
   stops `NpcBasket.plan` spending the slot and the coins the player is about to re-post with.
   **A dead trade overrules its own row** - a `CANCEL` or `EXPIRED` since the round opened drops the
   row, because the cancel is emitted in the same trip and re-posting into a book that caught the NPC
   price would buy at a price the strategy would refuse to open.
   Place-first needs one free slot and the coins for **one** order, not the whole row: the slot and
   coins a place-first borrows come back with the cancel that follows it, so one free slot serves
   every row in turn.
   **An order the round did not freeze is emitted as a hold, not dropped.** Leaving it out of the list
   entirely lost it from every count, and the no-work headline then read "all N orders are on top of
   the book" over a book that had walked past one of them; it now says what is waiting for the next
   round, and `Worklist.outbidWaiting()` is the count step 5's header line needs.
   `Stacking.orderSplit` is now the one copy of the split arithmetic, shared by the basket line to
   place and the round row to re-post - the same number typed into the same box.
5. ~~**Client.**~~ **Done, `f119867`.** `NpcRoundService` owns the one open round and the clock that
   supersedes it, `CandidateFeed.worklist()` passes it to the four-argument `NpcWorklist.of`, and the
   round is part of that cache's key — a round supersedes without the book revision or the tracker
   moving, so without it the panel would go on drawing prices frozen an interval ago until something
   else happened to change.
   **`NpcCheckInService` speaks once per opening, not once per interval.** The old wall-clock rate
   limit is gone; the round is the clock, and `NpcCheckIn.due` gained a three-argument form that
   counts the round's rows rather than the live review's reprices. Announcing the review would point
   the player at orders the list they are about to open does not contain. Claims and cancels still
   come off the live advice — they bypass the round in the list itself — so the cost is that one
   appearing part way through a round waits for the next opening, at most one interval.
   **The panel acknowledges.** `BazaarOverlay` calls `acknowledge()` whenever it actually draws rows,
   which is fault 1: working the basket from the panel was the one route that restarted nothing, so
   the chime arrived while the player was placing the orders it was about to ask for. `acknowledge`
   no longer moves any clock — it spends the one chime that round was entitled to.
   **A round is not opened over nothing.** No tracking, no book, or nothing resting yields no round
   at all rather than an empty one, because opening starts the interval, and an interval spent muted
   on a book the mod could not read is an interval of nothing said. An elapsed round is dropped
   rather than kept until a new one can open: its prices were frozen against a book an interval ago.
   `CandidateFeed.invalidate()` drops it too, so a settings edit is felt on the next round rather
   than at the end of one opened under the old interval.
   The between-rounds line is `Worklist.waitingNote` ("3 outbid, next round in 12m") and the chat
   form is `Worklist.roundNote` ("These prices are held for another 20m..."), both in `core` beside
   `headline()` for the same reason: the panel, the Basket tab and `/flip npc reprice` are three
   views of one round and must not be able to quote different due times. The panel now draws for the
   note alone when it has no rows — vanishing over a book that has walked past your orders is
   indistinguishable from having stopped working.
6. ~~**Config, `ConfigSchema`, `Guide`, and `docs/npc-flipping.md`**~~ **Done, `da57983`.** No new
   settings — the round reuses `npcCheckInMinutes`, which now documents its second job as the length
   of a round and the rate limit on opening one, and `npcRepriceReminder`/`npcRepriceSound` say "once
   per round" where they said "once per check-in interval". `/flip config` prints "30m reprice
   rounds".
   `Guide` gained seven terms in the basket section — why it is a round, when the next one opens, why
   an order can be missing from one, claims and dead trades not waiting, what a reprice has to be
   worth, one row per item, and place-first against pinned — plus "What it counts" under the
   reminder. That section is where the loop is explained; the NPC section says what the trade is.
   `docs/npc-flipping.md` gained "Repricing is a round, not a stream" holding the measurement this
   turns on (the five-bot `TRANSMISSION_TUNER` book, the 690k/1% the 1h→30m step is worth, and the
   2,890-against-9,744 value test), and the escrow-line finding from step 1 under the states the
   advice had nothing to say about, since `311x Purple Candy for 6,554,823 coins` is a measurement of
   Hypixel's behaviour rather than a decision.
7. ~~**The sweep**, then fill in the open measurement above.~~ **Done.** `NpcContext` gained
   `maxOrdersPerItem` with `UNLIMITED_ORDERS_PER_ITEM` (0) as the shipped value, applied in
   `NpcBasket.plan` through `ordersForItem`, which bounds **the line's slots and never the
   basket's** — what a capped item does not take goes to the next item down the ranking, which is
   the whole trade being measured. A six-argument `NpcContext` constructor means unlimited, so every
   caller in the mod is unchanged and the sweep is the only thing that names the seventh.
   `NpcSettingsSweepTest` sweeps the cap over slots, floors and bankrolls; `NpcBasketTest` covers
   both halves offline, since the sweep is opt-in and needs a tape.
   **Fixing the harness was most of the work.** The sweep planned every basket against
   `TrendSnapshot.empty()`, so no product had measured displacement and every line was sized off the
   unmeasured fallback — which meant the check-in horizon did nothing and the answer described a
   market where nobody is ever outbid. `minProfitPerFlip` and `maxCapitalShare` were left unlimited
   too. `measureFromTape` now builds a `PriceHistory` alongside the edges in the same pass and the
   test asserts displacement was measured, so the failure is loud rather than silent.
   The answer is in "The measurement that was open" above: the cap is noise and is not shipped.
