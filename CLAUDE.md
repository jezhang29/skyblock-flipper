# CLAUDE.md

## What this is

A client-only Fabric mod for Minecraft 26.2 that advises on Hypixel Skyblock flipping. It polls
Hypixel's public API, prices items from recorded sales, ranks flip candidates, and helps the player
place and track trades. It never automates the game — it tells the player what to click.

Three flip strategies exist today: bazaar spread, auction-house undervaluation, and NPC basket
flipping (the current active work). Everything is advisory and single-player.

**Read `docs/roadmap.md` first** for what is done, what is next, and what is settled and must not be
re-opened. This file is how the code works and the rules for changing it.

## Commands

```bash
./gradlew build                          # compile + remap + jar (what CI runs)
./gradlew test                           # JUnit 5, offline; fixtures in src/test/resources
./gradlew test -PliveApi                 # also runs LiveApiTest against the real Hypixel API
./gradlew test -PtapeBacktest            # opt-in valuation/sweep backtests; needs a tape
./gradlew test --tests '*SalesTapeTest'  # single test
./gradlew collectorJar                   # standalone tape collector, no Minecraft (docs/headless-collector.md)
```

Run `build` before each checkpoint commit. After editing `gradle.properties`, `clean build`.
Requires JDK 25, pinned by the Gradle toolchain.

**There is no dev client.** `./gradlew runClient` is not used — don't suggest it, and don't describe
live testing in terms of it. The mod is tested by dropping the built jar into the real mods folder
and playing Hypixel Skyblock, which is the user's job. Live state lives under
`~/Library/Application Support/minecraft/` (ledger, capture file, config, logs, tape), not under
`run/`.

Tests cover `core` only. **`LiveApiTest` is opt-in and must stay that way** — it asserts Hypixel's
behaviour, so an outage would fail an ordinary build. Run it after a Skyblock update or when the
numbers smell wrong. The `api-probe` skill answers a live-API question without loading the payload
into context.

## Working rules

An interruption loses the conversation; only disk survives. Leave recovery state in git and in
`docs/roadmap.md`.

- **Never work on `main`.** Branch first, named for the work; stay on it. Don't push unless asked.
- **Commit at each checkpoint without being asked** (this overrides the default). A checkpoint is a
  step that compiles and passes tests — roughly one plan item. Subject prefixed `wip:`, no body.
  Never commit a non-building state; if a step ends broken, say so and leave it uncommitted.
- **Squash `wip:` commits into one real commit when done** (one per concern if the work spans
  separate concerns). Check `git log --oneline` before declaring a task finished.
- **Resuming** (`--continue` / `/resume`): read `git log --oneline` and `git diff` and report the
  actual state before touching anything. Don't assume the plan was followed.
- **Commit messages.** Imperative, sentence case, no trailing period; comma-joined clauses are fine.
  The body is the point: what was wrong, why this approach works, what was rejected and why. Quote
  the measurements that justify a decision, and state what was verified live versus offline.

## Target versions

Versions are pinned in `gradle.properties`.

**26.2 mapping names differ from tutorials and older Fabric code** — e.g.
`net.minecraft.resources.Identifier`, `net.minecraft.ChatFormatting`,
`net.minecraft.network.chat.Component`. Copy imports from existing files; if one can't be confirmed
there, verify against decompiled sources (the `decomp-lookup` agent) before writing code around it.

## Architecture

### The core / client split

- **`core`** — pricing, market data, strategy, tape, tracking logic. **Must not import
  `net.minecraft` or `net.fabricmc`.** Inject Minecraft-shaped values as plain data (`FlipperConfig`
  takes a `Path` rather than reaching for `FabricLoader`). This is what makes the money math
  unit-testable and lets the headless collector run without a game.
- **`client`** — entrypoint, commands, HUD, GUI screens, mixins; owns the wiring `core` refuses to
  do. Adding a server or common entrypoint would be a design change, not a fill-in-the-blank
  (`"environment": "client"`, single `client` entrypoint).

### Config and wiring

- `SkyblockFlipperClient.config()` holds the single mutable `FlipperConfig`. `/flip reload` re-reads
  it, so **never cache config field values** — read through `config()` at use time.
- `FlipperConfig` is a mutable-field class, not a record, so Gson overwrites only keys present in the
  file and old `config.json` files stay valid. Keep that. New settings need a default and a clamp in
  `validated()` if a hand-edited value could break math. Path:
  `<.minecraft>/config/skyblock-flipper/config.json`.
- Every setting is described once in `core/config/ConfigSchema` (label, help, bounds, accessors);
  `ConfigSchemaTest` fails if a `FlipperConfig` field has no entry or an entry offers a value
  `validated()` would clamp. `FlipConfigScreen` is a loop over it — don't restate a setting there.
- `FlipCommand` registers `/flip` via `ClientCommandRegistrationCallback` (client-side only). New
  strategies graft subcommands onto that tree. Subcommands today: `bazaar`, `snipe`, `npc` (`plan`,
  `reprice`, `probe`), `craft` (`stop`), `combine` (`stop`), `jobs` (`stop`), `ledger`, `capture`,
  `track`, `unquoted`, `sync`, `config` (`edit`), `guide`, `hud`, `gui`, `menu`, `status`, `take`,
  `close`, `abandon`, `forget`, `clear`, `stop`, `reload`.
- **Cloth Config and Mod Menu are optional dependencies.** Loom 1.17 has no `mod*` configurations;
  they are plain `implementation` and Loom remaps them. `FlipConfigScreen` imports Cloth, so it must
  only ever be named inside a method body guarded by `Settings.available()` — `Settings` is the
  single door and imports nothing of Cloth's. Without Cloth the mod runs, minus that screen.
- Player-facing vocabulary lives once in `core/text/Guide`, rendered by `/flip guide` and the Guide
  tab. A UI term with no entry there is unlookupable.

### Market data and candidates

- `core/api/MarketPoller` fetches on its own schedule; `MarketData` is the snapshot; a maintenance
  thread does the expensive periodic work (sales rollup, one day per pass).
- `client/CandidateFeed` is the only place market state becomes ranked candidates. The HUD reads a
  cache rebuilt only when `MarketData.bazaarRevision()` moves; config edits that change a ranking
  without changing the book must call `CandidateFeed.invalidate()`.

### Valuation (auction pricing)

- `core/valuation/FairValueModel` prices an item from its **signature** — id, rarity, and the
  investment attributes that move the price. `DecodedItem.signature()` is the key; **measure any
  candidate attribute there, never at the bare item id**, which overstates every one by an order of
  magnitude or two.
- Valuation trains on `auctions_ended` only, never active listings (contaminated by the very
  mispricings being hunted). BIN sales only, medians not means.
- **Signature terms are a settled question — read the `signature-findings` skill before proposing a
  key term, adding a valuation input, or re-opening a rejected attribute.** There is no further
  shared-id-shaped gap on this tape; `UnreadAttributeProbeTest`'s 100M threshold is the alarm for a
  new one arriving.
- `core/valuation/backtest` is the harness. `Backtest.holdout` runs the real `FairValueModel` under
  a `Keying`; `CounterfactualKeying` unreads a shipped term; `UnreadTerms` carries an undecoded
  attribute through a run. **Do not hand-roll a model in a new backtest** — the copies drifted from
  what ships and every drift flattered the pooled arm. `Keying.PRODUCTION` is the only keying that
  ships; its `isBare` is the one copy of the bareness clause list, which `BarenessTest` pins.

### Strategies

`core/strategy` holds `FlipStrategy` implementations dispatched by `StrategyEngine`; `StrategyKind`
is `BAZAAR_SPREAD`, `AUCTION_VALUE`, `NPC_FLIP`, `CRAFT` and `COMBINE`.
`FlipCandidate` is the ranked unit. `strategyFilter` (ALL or one kind) selects what `/flip`, the HUD
and the screen's opening tab show.

**A flip the player has started is a `core/strategy/WorkedJob`** — one shape for a spread, a craft
and a combine, so the bazaar panel, the Jobs tab and `/flip jobs` render one thing. Several run at
once, in the order picked; **selecting a row commits nothing**, the Work button does. Progress
badges come from the order tracker and are blank rather than guessed. **Read `docs/worked-flips.md`
before touching the overlay's board, `CandidateFeed`'s follow list, or the flip screen's selection
handling.**

**NPC basket** is the active strategy and the user's daily driver. Its full measured design is in
`docs/npc-flipping.md` and `docs/adr/0002-reprice-in-rounds.md` — **read those before touching NPC
code; do not re-derive them from the bazaar.** The pieces:

- `NpcFlipStrategy` / `NpcBasket` — plan a basket of buy orders. `NpcBasket.plan` sizes each line
  with measured `FillModel` displacement over the check-in horizon, subtracts resting orders from
  the slots and bankroll first, and judges the profit floor over a whole position (a part-placed
  line is one position, not per-order).
- `NpcWorklist` — the join. One ordered list of clicks (claims, cancels, reprices, places) that
  chat, the Basket tab and the bazaar panel all render, so the three views cannot disagree.
- `NpcRound` / `NpcReprice` — reprice on a clock, not on the book. A round is a **frozen list** of
  reprice tasks opened at most once per `npcCheckInMinutes`; the **price** on each row is quoted
  live because the player is standing at Hypixel's own live "+0.1 coins" button. A reprice must earn
  its row: expected fills at the top over the rest of the interval times the margin, above
  `minProfitPerFlip`, so fast-displacement items stop asking. `NpcRound.Row.postPrice` stays frozen
  only for judging a row worked and sizing its reservation.
- `NpcEdge` / `NpcEdgeSnapshot` / `NpcEdgeHistory` — the per-item edge, from `data.npcEdges()`.
  **It is empty for the first ~20s of a session** until `MarketPoller` builds it; until then every
  `NpcEdge` is null and the chase is charged at 0, which only under-prices the cost. **Paying the
  drift into the posted price (`npcDriftPremium`) was removed 2026-08-19 on measurement** — read
  "Removed: paying the chase up front" in `docs/npc-flipping.md` before proposing it again.
- `NpcProbe` / `NpcProbeService` — `/flip npc probe <item>`, memory-only, settles the one thing the
  tape cannot: whether a competitor presses +0.1 at the user's order specifically.
- `NpcContext` — the parameters; `maxOrdersPerItem` is a sweep dimension only, shipped unlimited.
- `client/NpcRoundService`, `NpcCheckInService`, `NpcProbeService`, `NpcRenderer` — the client
  wiring. The check-in reminder chimes once per round that opens with work in it.

NPC facts the user confirmed in play, which the API cannot tell you (full record in
`docs/npc-flipping.md` and `docs/roadmap.md`): **slots bind, coins do not**; the **500M daily NPC
cap** is real, global and hard; **there is no walking** (a booster cookie's `/trades` reaches any
shop); and **clicks and session count are the user's real budget** — quote hauling and trip count
beside any NPC profit figure.

### Bazaar slot highlighting

`core/track/BazaarSlots`, `core/strategy/BazaarStep`, `BazaarMenu`, and `BazaarOverlay.Guidance`
work out which slot of an open bazaar menu to click next and draw a green box behind it
(`bazaarHighlightEnabled`). It refuses to guess — a box behind the wrong slot gets clicked, and
cancelling the wrong order costs coins. Menu titles are cut on **rendered width, not character
count**, so `BazaarMenu` matches exactly first and falls back to a prefix at 30 chars. The three
place-flow screens (`Create Buy Order`, amount, price) are recognised by their buttons, not their
titles. **Before extending the highlight to a new screen, confirm that screen exists in a capture
file** (`/flip menu` prints the last menu's buttons); do not invent a slot index.

### Tape, collector, and sync

- `core/tape/SalesTape` and `BazaarTape` record the API to append-only JSONL day files. **A day of
  sales tape is ~265MB, so nothing may hold one in memory** — both parse a line at a time
  (`SalesTape.readAll` is for tests only). Rolling a sales day up decodes every blob, so it runs on
  `MarketPoller`'s maintenance thread, one day per pass.
- Retention past `valuationWindowDays` (2d) buys no pricing accuracy (measured). Raw days are kept
  for backtesting; the per-day `daily.jsonl` rollup (67× smaller, never pruned) carries price
  history past retention.
- `core/headless/HeadlessCollector` is a `main` that runs the poller with no game, so tapes keep
  filling while Minecraft is closed — `auctions_ended` is a ~60s non-recoverable window, so downtime
  costs history permanently. `collectorJar` packs it with Gson and nothing else, which only works
  while `core` stays Minecraft-free. If that jar stops building, the layering broke.
- `core/sync/TapeSync` (`/flip sync`, `tapeSyncEnabled`, off by default) is the client half: it
  pulls what the collector taped while the game was closed, over HTTP, and **merges — never
  mirrors**. Both machines tape the same endpoints, so each holds records the other missed. Keyed on
  `auction_id` (sales), snapshot instant plus product (bazaar), signature plus day (rollup).
  Incremental by byte offset (`sync-state.json` beside each tape), which works only because tape
  files are append-only and the server does not gzip — **a gzipped response renumbers the bytes and
  every resumed fetch lands in the wrong place**. A remote index entry is written only if the tape's
  own `isTapeFile` accepts the name, which keeps a name off the network from being a path. It runs
  on its own daemon thread and calls `MarketPoller.rewarm()` when it merged something;
  `PriceHistory` deduplicates nothing, so a replay clears the ring first.

### Trade capture, auto-tracking, and the ledger

These three share one layering rule: `core/track` holds the filters, parsers and records
(Minecraft-free, unit-tested); `client/track` holds the parts that touch the game. `CaptureService`
owns the one pair of chat/menu hooks and feeds every consumer — a second set of listeners would
settle menus on its own schedule and disagree about what a menu said.

- **Trade capture** (`/flip capture`, `tradeCaptureEnabled`, off by default, `docs/trade-capture.md`)
  records the chat lines and menu contents a trade produces, so the fill parser can be written
  against measured text. Nothing consumes the file at runtime. `CaptureLog` stops at 32MB rather
  than rotating — a session's early records are worth more than its late ones.
- **Auto-tracking** (`/flip track`, `autoTrackEnabled`, off by default) reads the same live records
  and writes the ledger. `TradeTracker` is the reconciler: **chat is the event stream and the orders
  menu overrules it**, because a partial fill is announced in no chat line and a fill with the client
  closed is announced in none either. It learns an order's buy price from the escrow line
  (`setupCoins / total`, rounded to the coin) or the orders menu; a sell quotes a taxed payout and
  stays unpriced. An NPC sale does produce a chat line — `You sold Cobblestone x64 for 64 Coins!`,
  no `[Bazaar]` prefix — and `ChatParser` now reads it. It emits `Settlement`s (coins that actually
  moved) and `Ledger.record` books them.
- **Ledger** (`core/ledger/Ledger`) is the only feedback loop: capture rate (realized/quoted on
  filled units) and fill rate. **Quotes freeze at open time** — never re-derive from the current
  book; closing applies fees on the same basis the quote used. A **sale with no open position is
  dropped, never booked against nothing.** A **buy with no plan opens nothing unless
  `trackUnquotedTrades`** (off) — most bazaar buying is playing the game, not flipping.
  `LedgerEntry.Origin` (MANUAL / AUTO_QUOTED / AUTO_UNQUOTED) keeps unquoted trades out of the
  capture rate while leaving them in the fill rate. `forget`/`forgetAll` say an entry was never a
  flip; `abandon` keeps its units in the fill rate. `PlannedQuotes` + `Quote` let a basket line be a
  quote the ledger can recognise, which is how NPC flips reach the ledger and the daily cap counter.
- **A chat callback on a real modpack is not reliably on the client thread** — never touch rendering
  from one. `TrackerService` defers through `Minecraft.execute`.

### GUI and HUD

- `client/hud/FlipHud` renders via 26.2's `GuiGraphicsExtractor` (`HudElement.extractRenderState`),
  attached before `VanillaHudElements.CHAT` so F1 hides it.
- `FlipScreen` draws everything inside a `pose().scale(zoom, zoom)` block (GUI scale 6 leaves
  ~330 scaled px wide). New content must draw inside that block and hit-test against mouse coords
  divided by `zoom` — hence `TextButton` instead of vanilla `Button`. Scissors are transformed by
  the pose. Panels of unknown height draw at `-Scroller.offset()` inside a scissor and report their
  finished y.
- `GuiGraphicsExtractor.item(ItemStack, …)` is the one place an `ItemStack` is legitimate — for
  drawing an item icon, never for decoding NBT.
- See the `mc-26-2-gui-api` memory before writing GUI code: `GuiGraphics` does not exist in 26.2;
  input is event-record based; verify a symbol with `javap` (or `decomp-lookup`), not recall.

### Mixins

`skyblock-flipper.mixins.json` points at `jeff.skyblockflipper.client.mixin`;
`ContainerScreenLayout` is the one mixin so far. `requireAnnotations: true` — a new mixin must
satisfy it.

## Hypixel API

All endpoints used (`bazaar`, `auctions`, `auctions_ended`, `resources/skyblock/items`, `election`)
are public and unauthenticated. There is no API key setting — one existed, was read by nothing, and
was deleted 2026-08-19; don't add key-gated paths without a reason. See the `hypixel-api-quirks`
memory for the short version.

### Two traps that produce plausible wrong numbers, not errors

1. **Bazaar sides are inverted from their names.** `buy_summary` is the sell-offer/ask side (what
   you pay to instant-buy; matches `quick_status.buyPrice`); `sell_summary` is the buy-order/bid side
   (`quick_status.sellPrice`). Encoded in the type system.
2. **`item_bytes` is a hybrid NBT blob** — legacy `{i: [{id, Count, tag, Damage}]}` with
   `tag.ExtraAttributes` *plus* a modern `components` compound. Parse as a generic NBT tree;
   **never build an `ItemStack`** from it. `components["minecraft:tooltip_style"]` gives rarity,
   except when absent (4 of 154 live sales), where `ItemDecoder` falls back to the last lore line.

### Shared item ids are the recurring shape of the pricing bug

They fail silently: the sales decode, the medians compute, and a whole market prices off one key.
Every pet shares the id `PET` (identity under `ExtraAttributes.petInfo`; level is in the display
name `[Lvl 100] Mole`), every rune shares `RUNE` (`ExtraAttributes.runes`, `{MUSIC: 3}`), every
potion shares `POTION` (`potion` + `potion_level` + `splash`/`enhanced`/`extended`). Dungeon drops
keep their own ids but pool on `item_tier` (the floor they dropped at). Stars live under
`upgrade_level` or legacy `dungeon_item_level`. All are handled; the discipline is to check for the
next one at `signature()`.

### Other rules

- `/v2/skyblock/auctions` is ~51 pages / ~70MB per sweep, the most expensive operation. Three things
  keep it affordable and must survive any rework: `AuctionsDto` declares only the six needed fields;
  the sweep is skipped when `lastUpdated` is unchanged; listings are pruned on name and rarity
  (readable without `item_bytes`) before decoding. A coarse hit must be re-checked against the exact
  decoded signature, or a bare item gets priced off recombobulated five-star sales.
- `/v2/resources/skyblock/items` carries `upgrade_costs`, so star pricing is deterministic, not
  fitted: 544 items, 9 essence types, 43 ingredients, and every ingredient is a bazaar product.
  `UpgradePricing` quotes at the ask and returns empty rather than a partial total. **It carries no
  recipes** — one entry in 5549 has `recipes` — so craft flips have no deterministic source. Don't
  plan around one.
- **The auction house and the bazaar trade disjoint item sets**, so there is no AH→BZ arbitrage (of
  4351 live BINs sampled, 4 named a bazaar product and all four were `DIRT_BOTTLE` with an empty
  book). Enchanted books are bazaar-only now.
- **Item names are not unique enough to search on.** `ENCHANTED_MELON_BLOCK` is "Enchanted Melon",
  `ENCHANTED_MELON` is "Enchanted Melon Slice"; 187 of 5549 names are a strict prefix of another. Do
  not synthesise a name from an id (`DOUBLE_PLANT` is "Sunflower"), and **never ask the user for an
  id** — take the display name via `ItemCatalog.find`. `ItemCatalog.shadowedBy` reports the
  collision.
- Size every plan from `quick_status` flows, never resting book depth: `buyMovingWeek` = units
  instantly bought, `sellMovingWeek` = units instantly sold. Use `BazaarProduct.instantBuysPerHour()`
  / `instantSellsPerHour()`.
- Bazaar sales tax is 1.25%, less 0.125% per Bazaar Flipper level, floor 1%.
  `FlipperConfig.bazaarFlipperLevel` (0-6) feeds this and the order-slot count in `Fees`: 14 plus 7
  per level, capped at the real maximum of 28 (Bazaar Flipper 2) — the naive formula overstates past
  level 2, so the cap is load-bearing.
