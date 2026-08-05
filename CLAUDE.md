# CLAUDE.md

## Commands

```bash
./gradlew build                          # compile + remap + jar (what CI runs)
./gradlew test                           # JUnit 5, offline; fixtures in src/test/resources
./gradlew test -PliveApi                 # also runs LiveApiTest against the real Hypixel API
./gradlew test --tests '*SalesTapeTest'  # single test
./gradlew collectorJar                   # standalone tape collector, no Minecraft (docs/headless-collector.md)
```

Run `build` before each checkpoint commit. After editing `gradle.properties`, `clean build`.
The dev client is `./gradlew runClient` (the user's job — it's interactive); it writes game
files, logs, and `config.json` under `run/`.

Requires JDK 25 (pinned by the Gradle toolchain, but `runClient` needs one findable).

Tests cover `core` only. **`LiveApiTest` is opt-in and must stay that way** — it asserts
Hypixel's behaviour, so an outage would fail an ordinary build. Run it after a Skyblock update
or when the numbers smell wrong.

## Multi-step work

An interruption loses the conversation; only disk survives. Leave recovery state in git.

- **Commit at each checkpoint without being asked** (overrides the default). A checkpoint is a
  step that compiles and passes tests — roughly one plan item. Subject prefixed `wip:`, no body.
  Never commit a non-building state; if a step ends broken, say so and leave it uncommitted.
- **Squash `wip:` commits into one real commit when done** (one per concern if the work spans
  separate concerns). Check `git log --oneline` before declaring a task finished.
- **Never work on `main`.** Branch first, named for the work; stay on it. Don't push unless asked.
- **Resuming** (`--continue`/`/resume`): read `git log --oneline` and `git diff` and report the
  actual state before touching anything. Don't assume the plan was followed.
- **Commit messages.** Imperative, sentence case, no trailing period; comma-joined clauses are
  fine. The body is the point: what was wrong, why this approach works, what was rejected and
  why. Quote the measurements that justify a decision, and state what was verified live versus
  offline.

## Target versions

Versions are pinned in `gradle.properties`.

**26.2 mapping names differ from tutorials and older Fabric code** — e.g.
`net.minecraft.resources.Identifier`, `net.minecraft.ChatFormatting`,
`net.minecraft.network.chat.Component`. Copy imports from existing files; if one can't be
confirmed there, verify against decompiled sources before writing code around it.

## Architecture

Client-only mod (`"environment": "client"`, single `client` entrypoint). Adding a server or
common entrypoint would be a design change, not a fill-in-the-blank.

- **`core`** — pricing, market data, strategy. **Must not import `net.minecraft` or
  `net.fabricmc`.** Inject Minecraft-shaped values as plain data (`FlipperConfig` takes a `Path`
  rather than reaching for `FabricLoader`). This is what makes the money math unit-testable.
- **`client`** — entrypoint, commands, HUD, mixins; owns the wiring `core` refuses to do.

`core/headless/HeadlessCollector` is a `main` that runs the poller with no game, so the tapes keep
filling while Minecraft is closed — `auctions_ended` is a ~60s non-recoverable window, so downtime
costs history permanently. It is packed by `collectorJar` with Gson and nothing else, which only
works while `core` stays Minecraft-free: if that jar stops running, the layering broke.

Key invariants:

- `SkyblockFlipperClient.config()` holds the single mutable `FlipperConfig`. `/flip reload`
  re-reads it, so **never cache config field values** — read through `config()` at use time.
- `FlipperConfig` is a mutable-field class, not a record, so Gson overwrites only keys present in
  the file and old `config.json` files stay valid. Keep that. New settings need a default and a
  clamp in `validated()` if a hand-edited value could break math.
  Path: `<.minecraft>/config/skyblock-flipper/config.json`.
- `FlipCommand` registers `/flip` via `ClientCommandRegistrationCallback` (client-side only).
  New strategies graft subcommands onto that tree.
- `CandidateFeed` is the only place market state becomes ranked candidates. HUD reads a cache
  rebuilt only when `MarketData.bazaarRevision()` moves; config edits that change a ranking
  without changing the book must call `CandidateFeed.invalidate()`.
- `client/hud/FlipHud` renders via 26.2's `GuiGraphicsExtractor` (`HudElement.extractRenderState`),
  attached before `VanillaHudElements.CHAT` so F1 hides it.
- `FlipScreen` draws everything inside a `pose().scale(zoom, zoom)` block (GUI scale 6 leaves
  ~330 scaled px wide). New content must draw inside that block and hit-test against mouse
  coords divided by `zoom` — hence `TextButton` instead of vanilla `Button`. Scissors *are*
  transformed by the pose. Panels of unknown height draw at `-Scroller.offset()` inside a
  scissor and report their finished y.
- Every setting is described once in `core/config/ConfigSchema` (label, help, bounds, accessors);
  `ConfigSchemaTest` fails if a `FlipperConfig` field has no entry or an entry offers a value
  `validated()` would clamp. `FlipConfigScreen` is a loop over it — don't restate a setting there.
- **Cloth Config and Mod Menu are optional dependencies.** Loom 1.17 has no `mod*` configurations;
  they are plain `implementation` and Loom remaps them. `FlipConfigScreen` imports Cloth, so it
  must only ever be named inside a method body guarded by `Settings.available()` — `Settings` is
  the single door and imports nothing of Cloth's. Without Cloth the mod runs, minus that screen.
- Player-facing vocabulary lives once in `core/text/Guide`, rendered by `/flip guide` and the
  Guide tab. A UI term with no entry there is unlookupable.
- `Ledger` is the only feedback loop: capture rate (realized/quoted on filled units) and fill
  rate. **Quotes freeze at open time** — never re-derive from the current book. Closing applies
  fees on the same basis the quote used, dispatched by strategy.
- Trade capture (`/flip capture`, `docs/trade-capture.md`) records the chat lines and menu contents
  a trade produces, so the fill parser can be written against measured text. It splits on the same
  layering rule as everything else: `core/track` holds the filter, the JSONL log and the records
  (Minecraft-free, unit-tested); `client/track` holds `CaptureService` and `MenuReader`, which are
  the only parts that touch the game. Off by default (`tradeCaptureEnabled`), and nothing consumes the file at runtime. `CaptureLog` stops at
  32MB rather than rotating, because a session's early records are worth more than its late ones.
- Automatic tracking (`/flip track`, `autoTrackEnabled`, off by default) reads the same live records
  and writes the ledger. `TradeTracker` is the reconciler: **chat is the event stream and the orders
  menu overrules it**, because a partial fill is announced in no chat line and a fill that happens
  with the client closed is announced in none either. It emits `Settlement`s — coins that actually
  moved — and `Ledger.record` books those. **A sale with no open position is dropped, never booked
  against nothing**, and `LedgerEntry.Origin` keeps trades the mod never quoted out of the capture
  rate while leaving them in the fill rate. `CaptureService` owns the one pair of hooks and feeds
  both consumers; a second set of listeners would settle menus on its own schedule and disagree
  about what a menu said.
- Mixins: `skyblock-flipper.mixins.json` is wired but empty (no mixin package yet); a first mixin
  goes in `jeff.skyblockflipper.client.mixin` and must satisfy `requireAnnotations: true`.

## Hypixel API

All endpoints used (`bazaar`, `auctions`, `auctions_ended`, `resources/skyblock/items`,
`election`) are public and unauthenticated. `FlipperConfig.apiKey` is offered in the settings
screen but read by nothing — don't add key-gated paths without a reason.

Two verified traps that produce plausible wrong numbers, not errors:

1. **Bazaar sides are inverted from their names.** `buy_summary` is the sell-offer/ask side
   (what you pay to instant-buy; matches `quick_status.buyPrice`); `sell_summary` is the
   buy-order/bid side (`quick_status.sellPrice`). Encode this in the type system.
2. **`item_bytes` is a hybrid NBT blob.** Legacy `{i: [{id, Count, tag, Damage}]}` with
   `tag.ExtraAttributes` *plus* a modern `components` compound. Parse as a generic NBT tree —
   **never build an `ItemStack`** from it. `components["minecraft:tooltip_style"]` gives rarity,
   except when absent (4 of 154 live sales), where `ItemDecoder` falls back to the last lore
   line. Stars live under `upgrade_level` *or* legacy `dungeon_item_level`; every pet shares the
   id `PET` with its identity in JSON under `ExtraAttributes.petInfo`, every rune shares the id
   `RUNE` with its identity in `ExtraAttributes.runes` (`{MUSIC: 3}`, tier included), and every
   potion shares the id `POTION` with its identity in `potion` + `potion_level` + the
   `splash`/`enhanced`/`extended` flags. Dungeon drops keep their own ids but pool the same way,
   on `item_tier` (the floor they dropped at) and `baseStatBoostPercentage`.

**Shared item ids are the recurring shape of this bug**, and they fail silently: the sales are
decoded, the medians are computed, and a whole market prices off one key.

**Signature terms are a settled question — read the `signature-findings` skill before proposing a
key term, adding a valuation input, or re-opening a rejected attribute.** It holds which attributes
ship and which measured out, the evidence for each, the probe methodology, and how to rank the next
candidate. **There is no further shared-id-shaped gap on this tape**; `UnreadAttributeProbeTest`'s
100M threshold is the alarm for a new one arriving.
**Measure any candidate at `DecodedItem.signature()`, never at the bare item id**, which overstates
every one of these by an order of magnitude or two.

Other rules:

- `/v2/skyblock/auctions` is ~51 pages / ~70MB per sweep — the most expensive operation. Three
  things keep it affordable and must survive any rework: `AuctionsDto` declares only the six
  needed fields; the sweep is skipped when `lastUpdated` is unchanged; listings are pruned on
  name and rarity (readable without `item_bytes`) before decoding. A coarse hit must be
  re-checked against the exact decoded signature, or a bare item gets priced off five-star
  recombobulated sales.
- Valuation trains on `auctions_ended` only, never active listings (contaminated by the very
  mispricings being hunted). BIN sales only, medians not means.
- **A day of sales tape is ~265MB, so nothing may hold one in memory.** Both tapes parse day files
  a line at a time; `SalesTape.readAll` is for tests only. Retention past `valuationWindowDays`
  (2d) buys no pricing accuracy — measured, coverage 88.9% at 48h against 89.3% at 120h — so raw
  days are kept for backtesting, and the per-day `daily.jsonl` rollup (67x smaller, never pruned)
  is what carries price history past retention. Rolling a sales day up decodes every blob in it,
  so it runs on `MarketPoller`'s maintenance thread, one day per pass, never on the poller.
- `/v2/resources/skyblock/items` carries `upgrade_costs`, so star pricing is computed
  deterministically from that, not fitted: 544 items, 9 essence types and 43 item ingredients, and
  **every one of those ingredients is a bazaar product**, so any star tier prices off the book.
  `UpgradePricing` quotes at the ask and returns empty rather than a partial total.
  **It does not carry recipes** — one entry in 5549 (`PRECURSOR_APPARATUS`) has `recipes` and none
  has `recipe`, so craft flips have no deterministic source here. Don't plan around one.
- **The auction house and the bazaar trade disjoint sets of items**, so there is no AH→BZ
  arbitrage: of 4351 live BINs sampled across the house, 4 carried a bazaar product's name and all
  four were `DIRT_BOTTLE`, whose book is empty on both sides. Enchanted books are bazaar-only now.
- **Item names there are not unique enough to search on.** `ENCHANTED_MELON_BLOCK` is "Enchanted
  Melon", `ENCHANTED_MELON` is "Enchanted Melon Slice"; 187 of 5549 names are a strict prefix of
  another. Do not synthesise a name from the id — tried, and wrong more often than it helps
  (`DOUBLE_PLANT` is "Sunflower"). `ItemCatalog.shadowedBy` reports the collision as a note.
- Size every plan from `quick_status` flows, never resting book depth: `buyMovingWeek` = units
  instantly bought, `sellMovingWeek` = units instantly sold. Use
  `BazaarProduct.instantBuysPerHour()` / `instantSellsPerHour()`.
- Bazaar sales tax is 1.25%, less 0.125% per Bazaar Flipper level, floor 1%.
  `FlipperConfig.bazaarFlipperLevel` (0-6) feeds this.
