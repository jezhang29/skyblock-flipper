# Craft flipping

The measured record for the CRAFT strategy seat. Read this before writing craft code, and before
re-opening the "craft flips have no recipe source" line in `docs/roadmap.md`, which this supersedes.

## The recipe source question is settled: NEU, bundled

`/v2/resources/skyblock/items` carries no recipes (1 of 5549 entries has a `recipes` field), so the
API cannot answer this. The source is the **NotEnoughUpdates-REPO** item dump, which is **MIT
licensed**, so bundling a derived table is fine provided the copyright notice ships with it.

Measured against the live repo on 2026-08-17 (8,745 item files, 2,550 crafting recipes):

- **Two schema shapes coexist.** A singular `recipe` object (2,011 files) and a plural `recipes`
  array (1,380 files). Six files carry both. Both must be read.
- **Most `recipes` entries are not crafts.** Types present: `npc_shop` 1093, `crafting` 539,
  `drops` 429, `katgrade` 217, `forge` 120, `trade` 78. **Filter on `type == "crafting"`.** The
  singular `recipe` form has no `type` field and is always a craft.
- **`count` is not always an integer.** 582 entries write `1`, another 43 write `1.0`. Parse as a
  number and round, or Gson throws on the payload.
- **`count` is not exclusive to the plural form.** 159 singular `recipe` objects carry one. Absent
  means 1.
- **Quantities are per grid cell, not per recipe.** `ENCHANTED_DIAMOND:32` in five of the nine cells
  is 160 units. Sum across cells by ingredient id; never read one cell as the total.
- **41 cells carry a bare id with no colon**, e.g. `"YELLOW_FLOWER"`, meaning quantity 1. Skipping
  them silently drops an ingredient and understates the cost.
- **Legacy damage ids use a hyphen, Hypixel uses a colon.** NEU writes `INK_SACK-3`, `RAW_FISH-1`,
  `WOOD-3`; the bazaar trades them as `INK_SACK:3`, `RAW_FISH:1`.
  Measured across the 2,550 crafting recipes: 815 grid cells and 151 recipe outputs, over 79
  distinct ids. Every hyphenated id in the repo is of this shape, so the rewrite has nothing to
  false-positive on. The translation is verified live: `RAW_FISH:1` is a real bazaar product.
- **`overrideOutputId` is redundant.** Present 631 times, and it disagrees with `internalname`
  **zero** times. Honour it defensively; expect it to change nothing.

## The three exits, measured

One live bazaar snapshot, 2026-08-17. Bazaar Flipper 1 (1.125% tax). Input costs are **depth-walked
through the real ask book**, not quoted at top of book. 307 of 2,550 recipes have their output and
every ingredient on the bazaar with enough depth to fill one craft.

A crafted output can leave three ways, and the choice is worth roughly 10x:

| Exit | Positive recipes | Best profit/hour (5% flow share) |
|---|---|---|
| A: instant-sell into bids | 43 | 70k (`FISH_BAIT`) |
| B: post a sell offer at ask−0.1 | 42 | 810k (`WHALE_BAIT`) |
| C: sell the output to an NPC | 22 | 20k (`ENCHANTED_SEEDS`) |

Exit B is filtered to liquid books only (≥15 resting orders a side, spread <25%). Without those
filters the resting basis produces garbage — margins of 30,000% on books where the bid is near zero
and the ask is enormous, which is precisely what `BazaarSpreadStrategy`'s filters exist to reject.
**Any craft measurement quoting a resting price without those filters is wrong.**

**Exit A was the whole of the first measurement and it undersold the strategy by about 10x.** Do not
price a craft flip as a dump.

**Exit C is worse than it looks.** NPC sales draw down the 500M daily cap, which is already the
binding constraint on the NPC basket strategy. Coins routed through an NPC exit are coins the daily
driver cannot earn. Bazaar exits do not touch that cap at all.

## What the strategy is worth, and the assumption that decides it

Top candidates on exit B, click-limited, with collection requirements from NEU's `crafttext`:

| Output | Net/craft | Available crafts/h | Inventory loads/h | Unlock |
|---|---|---|---|---|
| `FLAWLESS_PERIDOT_GEM` | 183,999 | 51.7 | 1.8 | — |
| `FLYCATCHER_UPGRADE` | 1,555,511 | 3.3 | 0.1 | Spider Slayer 6 |
| `FLAWLESS_JASPER_GEM` | 334,737 | 13.3 | 0.5 | Gemstone IX |
| `VEILSHROOM_BUNCH` | 152,775 | 23.7 | 1.6 | Ruby Veilshroom |
| `ENCHANTED_COMPOST` | 84,888 | 33.3 | 2.3 | — |
| `BRAIDED_GRIFFIN_FEATHER` | 3,787,382 | 0.7 | 0.1 | — |

**These are volume-capped, not click-capped.** Raising the assumed click ceiling from 60 to 300
crafts an hour changes the top eight almost not at all, because none of them has more than ~52
crafts an hour of flow available. Clicking harder buys nothing. The click cost is genuinely small:
0.1 to 2.3 inventory loads an hour, against NPC flipping's constant hauling.

**The number that decides the strategy is what share of an item's flow one crafter can capture, and
this snapshot cannot answer it:**

- At **100% of flow** the top eight combined make **31M/hour** on 564M of the 800M bankroll.
- At the **5% share** `BazaarSpreadStrategy` already assumes, the same eight make **~2.3M/hour**.

That is a 13x range on one unmeasured parameter, and the flattering end assumes being the entire
market for peridot. **Do not quote the 31M figure.** The mod already owns the right tool for this:
`FillModel` learns displacement from the user's own tape, which is how `BazaarSpreadStrategy` sizes.
A craft strategy must size the sell leg through `FillModel`, never through a flat share.

Even the conservative end is worth having: ~2.3M/hour for one to two inventory loads of clicking,
against a strategy that does not consume the 500M NPC cap.

## Persistence: measured across 13 days of bazaar tape

The open question from the first pass. Answered against the tape at
`<.minecraft>/config/skyblock-flipper/bazaar-tape`, days 2026-08-04 to 2026-08-16 (295-436 snapshots
a day). Each product's day is reduced to the **median** top-of-book ask and bid over that day's
snapshots, so one bad poll cannot make a margin.

**The margins persist.** Between 35 and 47 recipes are profitable on exit B on every single day, and
the count never dips. The top-8 portfolio at the 5% flow share `BazaarSpreadStrategy` assumes:

| | coins/hour | capital deployed |
|---|---|---|
| median day | 5.39M | ~75M |
| worst day (08-04) | 4.00M | 71M |
| best day (08-05) | 8.47M | 88M |

That is a 2.1x spread across 13 days with no day near zero, on under a tenth of the 800M bankroll.

Ten recipes are profitable on **every** day of the tape:

| Output | Median net/craft | Worst day | Cost/craft | Crafts/h | Unlock |
|---|---|---|---|---|---|
| `TESSELLATED_ENDER_PEARL` | 340,418 | 212,133 | 4,035,008 | 10.5 | — |
| `CONDENSED_HELIANTHUS` | 263,634 | 85,115 | 3,771,206 | 59.0 | — |
| `ENCHANTED_COMPOST` | 110,665 | 68,971 | 2,341,824 | 123.4 | — |
| `ENCHANTED_HOPPER` | 44,883 | 21,258 | 280,909 | 23.1 | Iron Ingot IX |
| `SUPER_COMPACTOR_3000` | 18,810 | 11,467 | 132,575 | 25.4 | Cobblestone X |
| `ENCHANTED_LAVA_BUCKET` | 16,622 | 12,313 | 54,980 | 331.9 | Coal VIII |
| `REVENANT_VISCERA` | 4,533 | 3,171 | 47,421 | 2051.7 | — |
| `SILVER_FANG` | 2,100 | 1,557 | 9,100 | 1234.6 | Ghast Tear V |
| `WHALE_BAIT` | 1,740 | 542 | 9,718 | 39786.9 | Lily Pad VI |
| `ENCHANTED_SULPHUR` | 199 | 59 | 1,696 | 492.4 | Sulphur II |

**Membership of the daily top 8 rotates, so the strategy must re-rank every day rather than ship a
fixed list.** `ENCHANTED_COMPOST` holds a top-8 slot on 13 of 13 days and `CONDENSED_HELIANTHUS` on
11, but below those the turnover is real: `FLYCATCHER_UPGRADE` 9, `PRECURSOR_APPARATUS` 9,
`FIGSTONE` 9, `REHEATED_GUMMY_POLAR_BEAR` 8, `MANGCORE` 7, then a tail appearing 4-6 times. A
hardcoded basket would sit on the wrong items most days.

**What moves is the liquidity filter, not the margin.** Both of the headline rows from the first
snapshot dropped out a day later, and neither lost its margin: `FLAWLESS_PERIDOT_GEM` fell to 12
resting bids and `BRAIDED_GRIFFIN_FEATHER` to 8, under the 15-order floor. `CONDENSED_HELIANTHUS`
passes today at a 24.5% spread against a 25% ceiling. Several of the best rows sit right on a filter
boundary, so a candidate list is only as stable as the depth behind it - a reason to show the filter
margin in the UI rather than a bare pass/fail.

**Depth-walking the inputs costs almost nothing at this size.** Buying a whole hour of inputs through
the real ask book instead of quoting top of book moves the top-8 total from 5,662,203/h to
5,649,579/h, a 0.2% haircut, because 5% of an item's flow is small against resting depth. The
top-of-book basis the tape supports is therefore sound at this share. It would stop being sound at a
much larger share, which is another reason the sell leg must be sized by `FillModel`.

## What `CraftQuote` prices

`core/pricing/CraftQuote` is the shipped arithmetic. The exit is always a resting sell offer one
increment under the best ask. The **inputs have two routes, chosen per ingredient**, better profit
per hour quoted:

| | cost basis | rate ceiling | costs |
|---|---|---|---|
| `INSTANT_BUY` | the ask, depth-walked for the planned size | share of `buyMovingWeek` (asks lifted) | nothing rests |
| `BUY_ORDER` | best bid + 0.1 | `FillModel` on `sellMovingWeek` (players dumping) | an order slot, and time |

**The resting route wins, and by much more than the bill saving.** On the live book of 2026-08-18 it
was chosen for 39 of 57 profitable recipes, taking the best eight from 4.01M coins an hour to
**9.40M**. The saving on the bill is only 3-21%. The rest is fill rate, and it is largest exactly
where the strategy makes its money: farm materials are dumped into buy orders constantly and
instant-bought rarely, so `TARANTULA_SILK` runs about a thousand times faster on a resting order at a
5.2% cost saving. Several recipes — `CONDENSED_HELIANTHUS`, `FLAWLESS_JASPER_GEM` — are outright
losses instant-bought and solid flips on a resting order, so this is the difference between a flip
existing and not, rather than a refinement of the margin.

**A resting price needs the same liquidity gate as the output, per ingredient.** Without it, three
books quoting cost savings of 79% to 89% against bid sides nothing was resting on ranked in the top
eight, and the profitable count inflated from 31 to 108. An ingredient failing the gate is
instant-bought whatever the arithmetic says, so one recipe can mix them.

**The route is picked per ingredient, not for the whole bill.** A plan runs at the rate of its
slowest leg and pays the sum of its parts, so one material nobody dumps into can drag a whole recipe
to a trickle even where instant-buying that one material costs a few percent more and arrives a
thousand times faster. The search is exact rather than greedy: a plan's rate is always one of a
small set — the sell leg's, or one of the two each ingredient can hold to — so every achievable rate
is tried, each ingredient takes the cheaper route that still holds it, and the best plan wins. The
two whole-bill corners stay inside that search, so the answer is never worse than the old
best-of-two.

**Measured worth on the live book of 2026-08-20: nothing, and that is the honest number.** Over the
120 quotable recipes the sum of positive profit per hour came out identical to the corner-picking it
replaced, to the coin, with no recipe improved and none made worse. The reason is that the resting
route is normally both the cheaper and the faster one on a crafting material — farm materials are
dumped into buy orders constantly and instant-bought rarely — so there is rarely anything to trade
off. It ships as the correct model and as insurance for the recipe that does have a slow leg, not as
a measured gain. Do not re-measure it hoping for one; measure whether a slow-to-rest ingredient has
turned up instead.

**Slots are the real constraint, not coins.** The top eight together want **19 of the 21 order slots**
a Bazaar Flipper 1 account has, against 86.6M of capital out of an 800M bankroll. Those are the same
slots the NPC basket needs, and the NPC work already measured that slots bind where coins do not. A
craft strategy that ranks on profit per hour alone will quietly starve the daily driver, so
`CraftQuote` carries `orderSlots()` and the ranking layer has to spend that budget deliberately.

The rate ceiling correction also matters on its own: the snapshot measurements above sized input legs
on `sellMovingWeek` while quoting a depth-walked ask cost, which mismatches the route. Read the 13-day
persistence figures as the optimistic end of the instant route.

`CraftQuote` returns nothing rather than a partial answer when the output book is thin or wide, when
any ingredient is unpriceable or uncoverable, or when nothing clears.

## What ships as a strategy

`core/strategy/CraftFlipStrategy` walks the bundled `RecipeBook`, hands each recipe to `CraftQuote`,
lays the result out as a `CraftJob`, and emits the shared `FlipCandidate`. Three things belong to
this layer rather than to the quote, because all three are about the account rather than the recipe.

**The order-slot budget** (`craftMaxOrderSlots`, default 6). The budget is per plan and deliberately
not spent down the ranking — this is a menu the player picks one row from, and a shared budget would
hide row five because of four plans nobody placed.

**A plan over the budget is the right flip at the wrong size, and answering it with the route cost a
factor of hundreds.** Until 2026-08-20 an over-budget plan was re-quoted on `INSTANT_BUY`, which
gives every slot back at once and pays the ask on materials that were never the problem. On the live
book of that day the two plans over the shipped six-slot budget were the only two over it, and both
wanted their whole overrun for a **single** material:

| Plan | Wants | Old fallback | Sized to six slots |
|---|---|---|---|
| `ENCHANTED_MITHRIL` | 1,269,600 mithril ore = 18 orders | 1,123/h | **883,751/h** |
| `ENCHANTED_WHEAT` | 2,358,880 wheat = 33 orders | 4,774/h | **787,543/h** |

The fallback collapsed because nobody instant-sells ore or wheat: the ask side of a farmed material
supplies a trickle, so buying the bill there caps the plan at almost nothing. Sizing the same routes
down to what six slots can rest at once keeps the margin per craft exactly and takes the share of the
flow those slots hold — five orders of 71,680 ore plus the sell offer, 2,240 crafts.

So the budget is spent in this order:

1. **Cut the plan to size.** `CraftQuote` takes a hard craft ceiling, and the strategy
   binary-searches the largest plan whose real orders — `Stacking.orderSplit`, ceilings and all —
   fit the budget. The quote reports `Bound.SLOTS` when that is what held it down.
2. **Give up a resting leg**, most slot-hungry first, for the plan wanting more *distinct* resting
   materials than there are slots. A size cut cannot help there, because every leg costs one order
   however small it is.
3. Both are priced at every step and the better one ships, because neither wins everywhere: a small
   plan keeping a cheap order beats the ask on a farmed material, and the ask beats a plan cut to
   almost nothing on a material the bid side barely trades.

Measured over the whole recipe table on 2026-08-20, against the plan logic it replaced:

| Slot budget | Candidates | Profit/hour |
|---|---|---|
| 2 | 28 → 35 | 31.4M → 38.0M (×1.21) |
| 3 | 30 → 36 | 41.7M → 51.8M (×1.24) |
| 4 | 34 → 36 | 52.5M → 53.5M (×1.02) |
| **6 (shipped)** | **34 → 36** | **52.5M → 54.2M (×1.03)** |
| 12 | 34 → 36 | 52.5M → 56.2M (×1.07) |
| 21 | 35 → 36 | 55.6M → 58.8M (×1.06) |

No budget regresses. The headline is small because only two plans overran the shipped budget; what
it buys is that those two stop being noise.

**Slots are counted off the real orders, not off the ingredients.** `CraftQuote.orderSlots()` counts
one order per resting leg, which holds only while every leg fits in one order. A bazaar order takes
71,680 units of an item that stacks and **256** of one that does not, and a craft routinely wants
tens of thousands of a material: 58,624 units of an unstackable one is **229 orders**, against an
account maximum of 28. `CraftJob` splits every resting row through `Stacking.orderSplit` and counts
what that really costs, and the budget is spent against that number. The error the naive count makes
is small, which is the direction that gets a plan offered rather than refused.

**Where the margin is going, not only where it is.** A craft is two sides that move independently.
The recorded drift of the output less the cost-weighted drift of the materials is one number, and a
recipe whose margin is closing faster than `maxAdverseDrift` is refused. Weighted, because an
ingredient that is 3% of the bill moving 20% matters far less than the one that is 80% of it moving
5%. Judging either side alone is wrong in both directions: an output down 4% on materials down 20%
is a *widening* margin, and rejecting it on the output would throw away the better half of the
strategy. Books with no usable history contribute nothing, so an unrecorded recipe is neutral rather
than suspect — the same convention `BazaarSpreadStrategy` uses.

**One row per crafted item, not per recipe.** Several items are craftable more than one way, and the
overlay follows whichever way pays best, so a second row for the same item is a row the panel refuses
to follow. The ranking keeps the better recipe and drops the other.

The recipe table is a field on the strategy, not part of `StrategyContext`: it is shipped game data,
identical on every client, and it does not change between polls.

## What the player is actually shown

The panel used to carry four notes and up to five risks: the input route, the slot cost, which leg
bound the rate, the crafts available, that materials might not fill, that crafting takes clicks.
Every line was true and none of it changed what the player clicked next, which is the only thing
that earns a line in front of someone who is mid-trade. **All of it is cut.** What is left:

- **The steps**, first in the panel and rendered from `CraftJob.Row.describe()`.
- **The recipe unlock**, when NEU records one, as a single warning line under the item name. It is
  the one fact that can void the whole plan, because nothing here reads the player's collections and
  an unowned recipe means the materials get bought and then sit there.
- The figures, below the steps, unchanged.

The sparkline column is gone from the candidate table. A trend the ranking already acts on does not
also need to be read off a 14-pixel graph.

## The bazaar panel follows one job

Clicking a craft row calls `CandidateFeed.workCraft(outputId)`, and `BazaarOverlay` then draws that
job's rows — materials, craft, sell offer, each with the price and the split to type — beside
Hypixel's own menu, in place of the NPC basket. That is the point of the strategy being usable at
all: the alternative is closing the flip screen, placing an order, and reopening it for the next
line. `/flip craft stop`, or picking a row of any other kind, puts the basket back.

The job is **re-planned every poll**, not frozen at selection. The prices in it are what the player
is about to type, and a panel quoting a book from twenty minutes ago is worse than no panel because
it looks exactly as authoritative. When the flip stops clearing its gates mid-job the panel says so
and shows no prices, rather than vanishing — a panel that disappears is indistinguishable from one
that has broken, and the player may be halfway through buying the materials.

**No green box during a craft job.** `BazaarStep` works a slot out from an `NpcWorklist.Task` and has
nothing to say about a craft row, so the highlight stays off rather than pointing at a slot this list
is not asking for.

**Unverified in play.** Every figure in this document is offline: one live book snapshot and 13 days
of bazaar tape. Nothing here has been crafted and sold on Hypixel yet.

## Still unmeasured

- **Unlocks.** Collection requirements are read but not checked against the player. `Gemstone IX`
  and `Spider Slayer 6` gate several of the best rows.
- **Recursive inputs.** Every cost here prices the immediate ingredient at its own bazaar price.
  Whether crafting an intermediate beats buying it is not modelled.
