# Attribute-shard fusion flipping (FUSION strategy)

The measured record for a fifth flip strategy, `StrategyKind.FUSION`. **Built 2026-08-28, offline-only
— nothing has been fused and sold on Hypixel under this strategy yet.** The design was settled in a
grilling session on 2026-08-27; the build followed the plan below. Read `docs/combine-flipping.md`
first — fusion is combine's twin and reuses most of its machinery.

The economic figures below are offline: live bazaar snapshots (2026-08-27 design, 2026-08-28 shipped
re-measurement) plus the recipe graph from `Campionnn/SkyShards`, pinned at commit
`0f14286a8d44d730244e546f7a5c6ac4a4b0d4fb` (see `src/main/resources/data/skyblock-flipper/SKYSHARDS-LICENSE`).
The code: `FusionImporter`/`FusionTable` (graph), `FusionQuote` (min-cost tree solver + exit gate),
`FusionFlipStrategy` + `FusionJob`, `FusionContext` + config `fusionFlipsEnabled`/`fusionCrocodileLevel`.

## The trade

Attribute Shards are hunting-attribute items. The **Fusion Machine** (Fusion House in Tangleburg, or
Kysha's Abiphone contact remotely — no walking) fuses two shard stacks into an output shard. All 320
tradeable shards are bazaar products (`SHARD_*`), set by different crowds at each end, so the gap
between cheap inputs and a dear output is the edge.

The flip, per output:

1. **Source** the input shards on resting buy orders at the bid (or instant-buy at the ask where the
   input has no bid side) — combine's source rule exactly.
2. **Fuse** them at the machine. No coin cost (confirmed against the reference tool; the machine has
   no per-fusion coin cost — the 155k-2.33M figures in SkyShards' `calculationService.ts` are Kuudra
   *farming* rates, irrelevant to a buyer).
3. **Exit** with a sell offer one increment under the best ask on the output shard, taxed, and wait
   for an instant-buy. Never dump into the bid — shard spreads are enormous (`SHARD_ALLIGATOR` ask
   212k vs bid 92k, a 57% loss to dump), the same reason combine always exits on an offer.

This is **multi-step**: an input is itself sourced as `min(bazaar acquire cost, cheapest fusion to
make it)`, recursively, up to a depth cap. So a flip is a *tree* of buys and fusions, flattened for
display (below).

## Fusion mechanics (game rules, from the wiki + the reference tool)

- A fusion consumes **`fuse_amount` of each input shard**, where `fuse_amount` is a property of the
  **input** shard: **2** for Reptile / Amphibian / Elemental family, **1** for Chameleon, **5** for
  everything else. The two inputs can have different `fuse_amount`s.
- Output is **deterministic**, not random. An input pair yields up to 3 candidate outputs; you pick
  one. (Three fusion classes — ID fusion is algorithmic by category+rarity, Special fusion is a fixed
  thematic table, Chameleon fusion returns the next eligible IDs. The recipe data pre-expands all of
  this, so we never reimplement the algorithm.)
- Output **quantity** is 1 (ID/Chameleon fusion) or 2 (Special fusion), carried per recipe.
- **Reptile double-output** ("Pure Reptile" / crocodile perk): reptile-family fusions output
  `quantity × (1 + 2%×crocodileLevel)`. Player perk level, unread by the mod. Default 0 → ×1.0.

### The authoritative cost formula

From `Campionnn/SkyShards` `src/services/calculationService.ts` (~line 399-431), the cost of one
fusion producing output `O`:

```
cost(input1) = minCost(input1) × input1.fuse_amount
cost(input2) = minCost(input2) × input2.fuse_amount
totalCost    = cost(input1) + cost(input2)
effectiveOutputQty = isReptile ? outputQuantity × crocodileMultiplier : outputQuantity
costPerOutputUnit  = totalCost / effectiveOutputQty
```

`minCost(shard)` is the recursion: the cheaper of buying it on the bazaar or fusing it. This is a
min-cost relaxation over the recipe graph. Cost only rises going up a tree (each fusion adds input
cost), so cycles in the graph never lower cost and the relaxation converges — no special cycle
handling beyond a visited/queue guard and the depth cap.

## The data source (make-or-break — solved)

No Hypixel API recipe source (same as craft). Bundle the recipe graph, like the NEU dump for craft.

- **Source:** `Campionnn/SkyShards`, **MIT licensed**, `public/fusion-data.json` (~3.2MB), auto-kept
  current by the repo's `.github/workflows/update-fusions.yml`. Pin a specific commit SHA at bundle
  time (the workflow rewrites the file). Attribute MIT in-repo like the NEU import. There is also
  `public/fusion-properties.json` (~161KB) — check whether anything is needed from it; the two files
  below are enough for the flip.
- **Structure** (confirmed against a fetched copy):
  - `shards`: 321 entries keyed by internal code (`C1`,`U1`,…) →
    `{name, family, type, rarity, fuse_amount, internal_id}`. `internal_id` is the bazaar id
    (`SHARD_GROVE`). `type` is the hunting skill the shard drops under (Global/Combat/Fishing/…),
    **not** an unlock gate — a flipper buys every input regardless.
  - `recipes`: 316 outputs keyed by output code → `{ "1"|"2": [[inA, inB], …] }`. The `"1"`/`"2"` key
    is the output quantity; the list is every input pair that fuses to that output. 257k input-pair
    routes total (ID fusion is pre-expanded — e.g. `SHARD_GROVE` has 11,054 routes).
- **Coverage:** 320 of 321 shard ids are live bazaar products; only `SHARD_RAINBUG` is absent.
  Existence-validate the table against the live bazaar on load and drop ids with no product, exactly
  as `CombineTable` is validated for existence. A stale table after a Skyblock update is a game-rule
  claim gone wrong, the same silent-wrong-number risk combine and NEU carry.
- Raw fetch used during design (re-fetch from SkyShards master and re-confirm the schema before
  bundling): `https://raw.githubusercontent.com/Campionnn/SkyShards/master/public/fusion-data.json`.

## Measured economics, 2026-08-27 (offline, a ceiling not a promise)

Single-step solver against the live book, combine's frame: inputs sourced at the bid, output sold on
an offer at the ask taxed at Bazaar Flipper 1 (1.125%), cheapest input pair per output. **131 of 316
outputs clear positive**, all with output `buyMovingWeek` ≥ 2000. Per single fusion click:

| output | net/click | recipe |
|---|--:|---|
| QUEEN_SNAKE | 2,062,334 | Queen Ant ×5 + Queen Bee ×5 → 2 |
| GALAXY_FISH | 1,988,959 | Sun Fish ×5 + Sun Fish ×5 → 2 |
| MOLTHORN | 1,897,514 | Jormung ×5 + Etherdrake ×5 → 2 |
| STARBORN | 1,631,957 | Tempest ×2 + Galaxy Fish ×5 → 2 |
| ETHERDRAKE | 1,292,785 | Kraken ×5 + Apex Dragon ×5 → 2 |
| DAEMON | 1,054,895 | Kraken ×5 + Kraken ×5 → 2 |

Per-click return is 5-40× combine's non-whale rows. Multi-step trees can only lower cost further.
Caveats, all inherited from combine: this is the optimistic order/offer frame; the ≥15-ask gate is
not applied in this quick run (some rows may be thin); fills are unproven; `FillModel` saturation
applies. Re-measure against a fresh snapshot before quoting picks. **Note the input:output ratio —
10 input shards in, 2 out, per click** — hauling matters (see clicks-are-the-cost).

### Re-measured live by the shipped strategy, 2026-08-28

The built `FusionFlipStrategy` run against a fresh book (`LiveApiTest.printLiveFusionPicks`,
`-PliveApi`), Bazaar Flipper 1, 1h horizon, 5% flow, crocodile 0, ≥15-ask gate applied, min-cost tree
solver at depth cap 3. This is the **shipped** frame, not the design-day quick run: the gate is on,
the source is min(bid rest, ask instant) per shard, and the tree may go multi-step. **62 outputs
clear positive, 43 of them single-step.** Ordered by net per output (as `/flip fusion`'s total-profit
sort would show), the notable rows:

| output | leaves | clicks | net/click | net/output | ~profit/hr |
|---|--:|--:|--:|--:|--:|
| SHARD_QUEEN_SNAKE | 2 | 1 | 2,594,452 | 1,297,226 | 1.30M |
| SHARD_DAEMON | 1 | 2 | 976,741 | 488,370 | 1.47M |
| SHARD_ANANKE | 2 | 1 | 900,935 | 450,468 | 357k |
| SHARD_STALAGMIGHT | 1 | 2 | 192,183 | 336,319 | 216k |
| SHARD_WILD_HOG | 3 | 12 | 102,260 | 306,779 | 1.23M |
| SHARD_GHOST_CRAB | 3 | 2 | 150,875 | 264,032 | 264k |
| SHARD_SUN_FISH | 2 | 1 | 423,220 | 211,610 | 212k |

Three things to read the table with:

- **The picks moved from the design-day list, and that is the market.** Queen Snake still tops it, but
  the design-day headliners Galaxy Fish, Molthorn, Starborn and Etherdrake dropped out - either under
  the now-applied ≥15-ask gate or because their output stopped clearing after tax on this book. The
  design-day run applied no gate; several of its rows were thin.
- **Single-step rows are the safe first play-test.** 43 of the 62 clear in one fusion click (`leaves`
  1-2, `clicks` 1-2); the deep rows (`SHARD_WILD_HOG` at 12 clicks, `SHARD_ABYSSAL_MINER` at 20) hold
  intermediate inventory across sequential fill-waits and are much harder to complete. Queen Snake
  (Queen Ant + Queen Bee, one click, two out) or Ananke is the row to verify first.
- **Existence-validation held: the only shard the graph names that the bazaar does not list is
  `SHARD_RAINBUG`,** exactly as the spec predicted, so no id was silently priced off a missing product.

A live bug the measurement caught: the min-cost memo left its deeper cost cells at 0.0, so an
unbuyable base shard read as free and a route through it NPE'd on reconstruction. Fixed by seeding
every depth with the buy cost (infinity when the shard has no product); `FusionQuoteTest` pins it.

## Settled decisions (the grill)

1. **Build now; fusion is the next strategy verified in play**, ahead of combine — shared exit gate
   and job shape mean combine's play-lessons transfer.
2. **Multi-step trees.** Input cost = `min(bazaar source, cheapest fusion)`, min-cost relaxation.
3. **Cheapest-coins tree wins**, **depth cap 3**, fusion clicks counted and shown but not optimized
   against coins. (Within a few levels the click budget does not bind — combine's settled lesson.)
4. **Flatten the tree into `WorkedJob`** — no model change:
   - Leaf buys → `BUY_ORDER` steps, one per distinct base shard, **aggregated by id** (summed across
     branches, because `WorkedJob.progressOf` matches on item id + side and two steps for one id
     would double-count).
   - Fusion steps → ordered `TRANSFORM` steps, bottom-up, e.g. "Fuse Kraken ×5 + Apex ×5 →
     Etherdrake ×2". Blank badge, untracked, exactly like combine's anvil merges. Intermediates
     never appear as a buy or a sell — made and consumed inside the labels, like combine's pass-
     through middle tiers.
   - Final output → one `SELL_OFFER`.
   - New `FusionJob` + `WorkedJob.ofFusion`, twin of `CombineJob`/`ofCombine`.
5. **Exit** = sell offer at the ask, taxed. **Reuse combine's ≥15-ask-order fantasy gate as-is** (no
   extra demand floor — a real-but-slow shard just ranks low on the profit/hr column).
6. **`/flip fusion` list + Fusion tab are sortable**, default **profit per hour**, toggle to **total
   profit**. (User's workflow is bimodal: long set-and-forget vs 30-min active bursts.) The unified
   `/flip` list stays profit/hr — the only axis that compares across strategies. Show net-per-output
   and fusion-click count as columns either way.
7. **Reptile boost via config `fusionCrocodileLevel` (0-10, default 0)** feeding the reptile double-
   output. Default 0 = no bonus = conservative, per the NPC rule that a profit-flattering multiplier
   ships as a setting, never a default. The other reptile perks (tiamat/seaSerpent/python/kingCobra)
   affect hunting rates, not a flipper's fusion output — leave them out. Needs a `ConfigSchema`
   entry + a `validated()` clamp. **Unlock-checking deferred** — assume Fusion Machine / Kysha
   access; no per-recipe unlock exists in the data, and the reference tools model none.

## Architecture: reuse vs new

**Reuse (do not rebuild):**

- Combine's one-sided **≥15-ask-order exit gate** and "sell via offer, source via order" rule.
- Combine's **source rule**: resting order at the bid where the input has ≥15 bid orders, else
  instant-buy at the ask. The min-cost recursion compares fusion cost against this bazaar acquire
  cost per shard.
- **`FillModel`** for leg sizing; throughput = the slower of (leaf buy fill rates ÷ tree
  multiplicities) and (output instant-buy shed rate at the 5% flow share), as craft/combine size.
- **`WorkedJob`** / `BazaarOverlay` board / Jobs tab / `/flip jobs` / `FlipIntentsService` (registers
  each `BUY_ORDER` id so the NPC side leaves fusion orders alone). All render `FusionJob` with what
  is already there.

**New:**

- `core/pricing/FusionQuote` — its own quote (not `CraftQuote`/`CombineQuote`): a min-cost input-tree
  solver + combine's one-sided exit gate. Output-only exit; recursion on the input side; depth cap 3.
- `core/recipe/FusionImporter` + bundled `FusionTable` (the SkyShards graph). Twin of
  `NeuRecipeImporter`/`RecipeBook`. Existence-validate against the live bazaar on load.
- `core/strategy/FusionFlipStrategy` producing `FlipCandidate`s + a `FusionJob` (`WorkedJob.ofFusion`).
- `StrategyKind.FUSION`; `strategyFilter=FUSION`; `/flip fusion` subcommand (+ `/flip fusion stop`);
  a Fusion tab in `FlipScreen`.
- `FlipperConfig.fusionCrocodileLevel` + `ConfigSchema` entry + `validated()` clamp.

No new tape — the bazaar tape already records `SHARD_*` products, so `FillModel` and pricing get
history for free.

## Build plan (all six steps built 2026-08-28 on branch `fusion-flipping`)

Every step below shipped; the strategy is offline-only until a fusion is fused and sold in play. The
one deviation from plan: the min-cost list ranks by profit/hr in the strategy, and the total-profit
toggle is a `/flip fusion`/tab sort concern flagged with the per-strategy-tab task, not built here.

1. **Importer + table.** `FusionImporter` reads the bundled `fusion-data.json` into `FusionTable`
   (shards + recipes, keyed on bazaar ids). Existence-validate against a bazaar snapshot fixture.
   Unit-tested: id coverage, `fuse_amount` per family, output quantity.
2. **`FusionQuote`.** Min-cost input-tree solver (depth cap 3) + the ≥15-ask exit gate + BZ-Flipper
   tax. Unit-tested against a fixed book fixture reproducing a couple of rows in the table above.
3. **`FusionFlipStrategy`.** Rank as `FlipCandidate`; wire `StrategyKind.FUSION`, `strategyFilter`,
   `/flip fusion`. Sortable list (profit/hr default, total-profit toggle).
4. **`FusionJob` + `WorkedJob.ofFusion`.** Flatten the tree; overlay/Jobs tab/`/flip jobs` render it;
   `/flip fusion stop`. Fusion tab in `FlipScreen`.
5. **Config.** `fusionCrocodileLevel` + schema + clamp; feed the reptile multiplier.
6. **Re-measure live** (`-PliveApi`, opt-in), print the picks, and record them here — the twin of
   `LiveApiTest.printLiveCombinePicks`.

## Named gaps (verify in play; do not silently assume away)

- **`fuse_amount` / output-quantity semantics** are from the reference tool, not confirmed in play.
  Get them wrong and every cost is silently off — the repo's recurring failure shape. Verify against
  one real fusion before trusting the table.
- **Recipe drift.** A Skyblock update restales the bundled table silently. Existence-validation
  catches removed ids, not changed recipes. Re-import on updates.
- **Recursion holds intermediate inventory** across sequential fill-waits; a deep tree is much harder
  to complete than a single flip. **The first play-test should be a single-step flip** (e.g. Molthorn
  from bought Jormung + Etherdrake) before trusting depth.
- **Per-recipe unlocks unread; reptile perks off by default.** Same class as craft's deferred
  unlock-check.
- **`FillModel` displacement saturates** at the 5-minute tape cadence (see `docs/roadmap.md`); the
  profit/hr column is a ceiling on contested shards.
- **Later, out of scope:** per-strategy GUI tabs (the user flagged this as a separate task).
