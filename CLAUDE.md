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
- Mixins: `skyblock-flipper.mixins.json` wired but empty, package
  `jeff.skyblockflipper.client.mixin` (not created yet), `JAVA_25`, `requireAnnotations: true`.

## Hypixel API

All endpoints used (`bazaar`, `auctions`, `auctions_ended`, `resources/skyblock/items`,
`election`) are public and unauthenticated. `FlipperConfig.apiKey` is unused — don't add
key-gated paths without a reason.

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
decoded, the medians are computed, and a whole market prices off one key. `SignatureGapProbeTest`
(opt-in, `-PtapeBacktest`) ranks signatures by the p10–p90 spread of a day's realized sales and
prints the `ExtraAttributes` keys nothing reads. That found the shared ids and then went 0 for 3:
`color`, `power_ability_scroll` and the drill parts each topped it, each was worth 100x+ at the item
id, and each measured flat at the key production prices from.

**Rank a candidate by the coins its pooling misprices upward, not by its spread — this is how to
pick the next one.** `UnreadAttributeProbeTest` (opt-in, same flag) walks every unread attribute's
production signatures, keeps only the pools that reach `MIN_SAMPLES` (a smaller pool is quoted by
nothing, so its disagreement costs nothing), and counts the sales the pooled median values at 2x+ of
what their own configuration fetched. On six days of tape it put `ethermerge` first at 1.25B coins
over 207 sales, an order of magnitude clear, where the spread ranking had it eighth — and that one
survived its holdout and shipped. With it read, the list drops to `eman_kills` at 45.5M over 26
sales, a counter with 979 distinct values that would cost 988 valuations to fix 26; everything below
is the same shape. **There is no further shared-id-shaped gap on this tape**, and the probe's 100M
threshold now stands as the alarm for a new one arriving. `dungeon_item` reads 1 on 2,218 of 2,223
sales and separates nothing — ignore it.

**`winning_bid` was the exception to that whole frame, and it ships as a valuation input rather than
a key term.** On a Midas weapon the coins burned at the Dark Auction *are* the item, so the attribute
is continuous — 103 distinct values over 439 taped `MIDAS_STAFF` sales — and keying it makes a cell
per sale. What works instead is to pool the **ratio** of sale price to bid over the signature
production already uses and quote `medianRatio × thisItem'sBid`: same sales, same key, different
question, so **it costs no coverage at all** — the first thing measured here that is free. On a 24h
holdout over the six bid-carrying ids, sales valued at 2x+ of what they fetched went 142/512 → 11 and
median |log err| 0.588 → 0.242 at identical coverage; at 48h, 342 → 108 and 2.169 → 0.563. Banding
the bid into the key is the wrong answer measured twice over (499 priced, 69 over 2x), and flooring
the ratio quote at the pooled median throws the finding away (144 over 2x) because the pooled median
is the wrong number. Almost all of it is `MIDAS_STAFF`, where 116 training sales quote 27.0M against
a held-out median of 14.0M; `HEGEMONY_ARTIFACT` and `PLASMA_NUCLEUS` sell at 1.05–1.09x their bid and
neither arm ever overvalues one. Aggregate at 48h unmoved: 88.3% / 64.0% / 0.098 / 4,717 configs. See
`MidasBidBacktestTest`, and `FairValueModel.valueOf` for where the ratio index is consulted.

**Not every pooling gap is a shared id, and not every attribute belongs in the key as a number.**
Dungeon quality was both traps at once. `item_tier` earns an exact term — `SKELETON_MASTER_CHESTPLATE`
runs 980k at tier 5 to 113M at tier 10. `baseStatBoostPercentage` runs 1–50 and is **flat below 50**
(medians 48k–74k across every value, near-uniform counts), so it is a `maxed` flag; splitting on the
raw value prices 9 held-out sales where the flag prices 601. See `DungeonQuality`.

**A pooling gap can be real, expensive and still not worth keying — `color` is the case.** Dyed
leather is two attributes, not one, and they are near-disjoint (of 2,091 sales carrying either, 8
carry both). `dye_item` is a named dye and ships. Raw `color` is an `r:g:b` triple and does **not**,
for three measured reasons: it is near-unique per sale where it is dense (632 distinct colours over
660 `SATIN_TROUSERS` sales) so no key can reach `MIN_SAMPLES`; the coarse pool it falls into today
is *right* about it, because the items carrying it densely are fashion items whose whole pool is
coloured, so keying it out drops 191 held-out coloured sales to 5 to fix 2 overvaluations; and
leaving it in poisons nothing, since a median ignores the two 60M exotics sitting in `GOBLIN_BOOTS`'
466-sale pool at 12k (plain sales score identically either way, 702 overvaluations both). Keying an
attribute converts a wrong number into no number — check the wrong number is actually wrong first.
See `DyeSignatureBacktestTest`.

**`power_ability_scroll` is the same answer with none of `color`'s excuses, and it was top of the
list by coins** — 554 sales / 185.8B over six days, one enumerable string of six values, 59 item ids,
and up to 604x within an item id (`RAGNAROCK_AXE` 510M scrolled against 844k plain). At the
production signature it disappears: 30 keys pool a scroll with anything else at all, they agree to
1.6x everywhere but two, and **no mixed pool overvalues its plain sales**. On a 24h holdout the
pooled key prices 19 scrolled sales and is within 1.5x on 17 (four `HYPERION` within 2% at 1.2B);
keying it prices **2**, fixes one overvaluation, and moves median and p90 not at all. The mechanism
is that scrolled sales dominate their own key — nine of ten sales under one `HYPERION` signature
carry a Sapphire scroll — so the median they price against is already a scrolled median, and
splitting leaves a cell of nine and a cell of one that `MIN_SAMPLES` rejects. Unlike `dye_item` it is
not free: 24 of 554 scroll sales are otherwise bare, so the term costs coarse coverage too. See
`PowerScrollBacktestTest`.

**The drill parts are the third no-op and the cheapest one to have skipped.** 405 sales / 97.3B over
six days across `drill_part_*`, the `engine`/`fuel_tank`/`upgrade_module` compounds, `polarvoid` and
`divan_powder_coating`, and a built drill genuinely runs 1.2x–2.4x a bare one at the same key
(`MITHRIL_DRILL_2` 42.9M against 17.7M). It measures out because **the market is tiny and the parts
are nearly an identifier**: 405 sales over 69 configurations and 314 signatures, so a part term makes
cells of one. On a 24h holdout the pooled key prices 5 parted sales and is within 1.5x on 4; the full
term prices 0, and so does a bare "something was installed" bit. Of the 75 mixed signatures, 52 never
reach `MIN_SAMPLES` and are quoted by nothing; of the 23 that are, none overvalues an unparted drill.
Read both formats or the measurement is wrong: `drill_part_engine` is a lowercase id and the `engine`
compound holds the same id uppercase, 103 sales against 59, neither a superset. See
`DrillPartBacktestTest`.

**`ethermerge` ships, and it is the one that broke the streak.** 516 sales / 13.8B on
`ASPECT_OF_THE_VOID` and `ASPECT_OF_THE_END`; one signature holds 396 plain sales at 5.9M beside 288
merged at 24.9M and quotes 6.1M for both. Three things separate it from the scroll and the drill
parts: it is **one bit**, so a split halves a pool instead of shattering it; **plain sales dominate
the mixed pools**, the exact opposite of the scroll, so both cells still clear `MIN_SAMPLES`; and it
costs 4 valuations in 1,032. Holdout p90 |log err| 1.387 → 0.300, median 0.095 → 0.072,
overvaluations 13 → 11; aggregate at 48h unmoved (88.3% / 64.0% / 0.098 / 4,717 configs).
`tuned_transmission` stays out — it only rides on a merged item, is worth 1.06x on top of it, and
costs seven more valuations for no fewer overvaluations. The `isBare` guard is load-bearing here: 315
of the 516 merged sales carry nothing else, so unguarded they join the coarse pool and every plain
Aspect of the Void gets quoted off sales worth 4x it. See `EthermergeBacktestTest`.

**Measure a signature split against the tail, not the median.** The median sale under a pooled key
is whatever dominates its count, and pooling is accidentally right about that item — splitting
`POTION` made the median error slightly *worse* (0.091 → 0.104) while cutting p90 from 3.510 to
0.464. What matters is how often the key values a sale at 2x or more of what it fetched, because a
valuation is only acted on when it sits far above the asking price. See
`PotionSignatureBacktestTest`.

**A signature term measured to change nothing is not automatically dead weight.** The `maxed` flag
splits no key at all — of 716 keys holding a maxed sale, none holds an unmaxed one — because stars,
hot potatoes and enchantments already fingerprint an invested item. It ships anyway: it costs zero
coverage, and at a coarse key maxedness is worth 44x on `SKELETON_MASTER_CHESTPLATE` tier 10 (110M
against 2.5M), so the correlation covering that hole is one nothing enforces. Check what a redundant
term would cost before removing it. `dye=` ships on the same footing: 67 of 587 dyed keys hold an
undyed sale and they run only 0.9x–2.1x at the production key, against 833x at the bare item id
(`SKELETON_MASTER_CHESTPLATE`, 200M dyed against 240k plain) — **the gap between those two numbers
is the investment terms doing the separating, not the dye.** Measure a term against the key the
model actually uses; the item id overstates every one of these by an order of magnitude or two.

**Six for six on that last point now** (pet levels, the maxed flag, the dye, the scroll, the drill
parts, the merge). A gap that is huge at the item id and flat at the production key is the normal
case, not the surprising one — even `ethermerge`, the one that shipped, is 4x at the real key against
73x at the bare id. Any candidate gets measured at `DecodedItem.signature()` first, and the two
questions are whether mixed pools *overvalue* the plain side and what the term costs in coverage.
**When the answer to both is bad and the attribute is a number the price scales with, ask the ratio
question instead of the key question** — that is what turned `winning_bid` from untouchable into the
one free win on the list.

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
