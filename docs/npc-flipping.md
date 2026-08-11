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
| check-in interval | 30 minutes default | User will check in several times an hour |
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
