# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
./gradlew build          # compile + remap + jar into build/libs/ (what CI runs)
./gradlew runClient      # launch a dev Minecraft client; game files land in run/
./gradlew clean build    # after changing anything in gradle.properties
```

```bash
./gradlew test           # JUnit 5, offline only — fixtures under src/test/resources
./gradlew test -PliveApi # also runs LiveApiTest against the real Hypixel API
```

Tests live in `src/test/java` and cover `core` only — see the `core` rule below for why that
is the testable half. Single test: `./gradlew test --tests '*SalesTapeTest'`.

**`LiveApiTest` is opt-in and must stay that way.** It asserts things about *Hypixel's*
behaviour (field names, order-book sort order, `item_bytes` still being gzipped NBT), so a
network outage or an API hiccup would otherwise fail an ordinary build. Run it when something
smells wrong about the numbers, or after a Skyblock update. Everything else runs offline from
trimmed real captures in `src/test/resources`, so `./gradlew build` never touches the network.

Requires JDK 25. The Gradle toolchain pins this, so the build does not use whatever `java` is
on PATH, but `runClient` still needs a JDK 25 available for Gradle to find.

## Multi-step work

A long task can be cut short at any point — an `Esc`, a usage limit, a crashed terminal — and
what is on disk at that moment is all that survives. The in-session todo list does not; it is
context, not a file. So leave the recovery information in git rather than in the conversation.

**Commit at each checkpoint without being asked.** This overrides the default "only commit when
asked" behaviour, for this repo. A checkpoint is a step that compiles and whose tests pass —
roughly one item off the plan, not one file. Use a short subject prefixed `wip:` and no body;
these are scaffolding. Do not commit a state that does not build. If a step ends broken and
cannot be finished, say so and leave it uncommitted, so `git status` shows where the work stopped.

**Squash the `wip:` commits into one real commit when the task is done**, written in the style
below. The published history has no `wip:` in it — check `git log --oneline` before declaring a
task finished. If the work spans genuinely separate concerns, squash into one commit per concern
rather than forcing them together.

**Resuming after an interruption** (`claude --continue` or `/resume`): read `git log --oneline`
and `git diff` first and report the actual state — which plan items are committed, what is
half-written in the working tree — before touching anything. Do not assume the plan was followed
to the point it was last discussed.

**Branching.** Never work directly on `main`. Branch first, named for the work
(`price-history-and-flip-screen`), and stay on it for the whole task. Do not push unless asked.

**Commit messages.** Subject in the imperative, sentence case, no trailing period; comma-joined
clauses are fine when a change has two halves ("Record bazaar price history, and add a flip
screen on a keybind"). The body is the point: say what was wrong or missing, why the approach
chosen is the one that works, and which alternatives were rejected and for what reason. Quote the
measurements that justify a decision — page counts, timings, hit rates, sizes — and state what was
verified against the live API versus what runs offline. End with the co-author trailer.

## Target versions

Minecraft 26.2 / Fabric Loader 0.19.3 / Fabric API 0.155.2+26.2 / Loom 1.17-SNAPSHOT / Java 25.
All pinned in `gradle.properties`; `build.gradle` reads them from there, so change versions
in one place.

**Mapping names in 26.2 differ from most tutorials and from older Fabric code.** This repo uses
`net.minecraft.resources.Identifier`, `net.minecraft.ChatFormatting`, and
`net.minecraft.network.chat.Component`. Copy import paths from existing files rather than
recalling them — a name that was right in 1.21 is often wrong here, and `build.gradle`
declares no explicit `mappings` line to check against. If an import cannot be confirmed from
existing source, verify it against the decompiled sources before writing code around it.

## Architecture

Client-only mod (`"environment": "client"`, single `client` entrypoint). Nothing runs
server-side; there is no common or server entrypoint, and adding one would be a design change,
not a fill-in-the-blank.

Two packages with a hard boundary between them:

- **`core`** — pricing, market data, strategy. **Must not import `net.minecraft` or
  `net.fabricmc` anything.** `FlipperConfig` demonstrates the pattern: the caller passes in a
  `Path` so the class never reaches for `FabricLoader`. This keeps the money math runnable and
  unit-testable without Minecraft on the classpath, which is the only practical way to test it.
  Preserve this when adding to `core`; inject Minecraft-shaped values as plain data.
- **`client`** — everything that touches the game: the entrypoint, commands, HUD, mixins.
  Owns the wiring that `core` refuses to do.

Inside `core`: `api` (HTTP client, poller, shared `MarketData`), `model` + `model/dto` (wire
shapes and the domain types they translate into), `nbt` + `item` (blob parsing and the decoded
attributes that move a price), `tape` (realized sales on disk), `valuation` (what items are
worth, learned from the tape), `pricing` (the fee stack), `strategy` (candidates), `ledger`
(what the flips actually did), `config`, `text`.

`SkyblockFlipperClient` holds the single mutable `FlipperConfig` instance behind `config()`.
Config is re-read from disk by `/flip reload`, so **do not cache config field values** — read
through `SkyblockFlipperClient.config()` at use time or a reload silently won't take effect.

`FlipperConfig` is a class with mutable public fields and defaults rather than a record,
deliberately: Gson populates the no-arg instance and overwrites only keys present in the file,
so adding a setting later does not invalidate users' existing `config.json`. Keep that property.
New settings need a default here and a clamp in `validated()` if a hand-edited value could
break downstream math. Config lives at `<.minecraft>/config/skyblock-flipper/config.json`.

`FlipCommand` registers `/flip` through Fabric's `ClientCommandRegistrationCallback`, so it is
intercepted client-side and never reaches Hypixel. New strategies graft subcommands onto that
existing tree.

`CandidateFeed` is the only place market state becomes ranked candidates. Commands rank on
demand; the HUD reads a cache rebuilt only when `MarketData.bazaarRevision()` moves, because
ranking ~2000 books per frame to draw three lines is waste and a refresh timer would either
recompute identical results or show stale ones. Config edits that change a ranking without
changing the book call `CandidateFeed.invalidate()`.

The HUD (`client/hud/FlipHud`) renders through 26.2's `GuiGraphicsExtractor`, not `GuiGraphics`:
elements implement `HudElement.extractRenderState`. It is attached before
`VanillaHudElements.CHAT`, which also makes F1 hide it.

`FlipScreen` draws its whole contents inside a `pose().scale(zoom, zoom)` block and lays out in the
virtual size that produces, because GUI scale 6 leaves only ~330 scaled pixels of width. Anything
added to it must be drawn inside that block and hit-tested against mouse coordinates divided by
`zoom` — which is why the footer buttons are `TextButton` and not vanilla `Button` widgets, since a
widget renders and hit-tests outside the transform. Scissor rectangles *are* transformed by the
pose, so clipping works normally. Panels that render text of unknown height (`renderDetail`, the
guide) draw at `-Scroller.offset()` inside a scissor and report their finished y back, rather than
measuring the layout twice.

Player-facing vocabulary lives once, in `core/text/Guide`, and is rendered by both `/flip guide` and
the screen's Guide tab. A term that appears in the UI without an entry there is a term nobody can
look up.

`Ledger` is the mod's only feedback loop: flips taken by hand, closed by hand, reported as a
capture rate (realized over quoted, on filled units only) and a fill rate. Quotes freeze at open
time — never re-derive them from the current book, since the book has already moved in whatever
direction made the fill worse. Closing applies fees on the same basis the quote used, dispatched
by strategy, so the two sides stay comparable.

Mixins: `skyblock-flipper.mixins.json` is wired up but empty, package
`jeff.skyblockflipper.client.mixin` (not created yet), `compatibilityLevel: JAVA_25`,
`requireAnnotations: true` for overwrites.

## Hypixel API

Every endpoint the mod needs (`bazaar`, `auctions`, `auctions_ended`, `resources/skyblock/items`,
`election`) is public and unauthenticated. `FlipperConfig.apiKey` exists but is optional and
currently unused — do not add key-gated code paths without a reason.

Two verified traps, both of which produce plausible-looking wrong numbers rather than errors:

1. **Bazaar sides are inverted from their names.** In `/v2/skyblock/bazaar`, `buy_summary` is
   the sell-offer/ask side (what you pay to instant-buy; matches `quick_status.buyPrice`), and
   `sell_summary` is the buy-order/bid side (matches `quick_status.sellPrice`). Encode this
   mapping in the type system rather than trusting field names — swapping the sides inverts
   every computed spread while still looking reasonable.
2. **`item_bytes` is a hybrid NBT blob after the 26.2 migration.** Still legacy
   `{i: [{id, Count, tag, Damage}]}` with `tag.ExtraAttributes`, plus an added modern
   `components` compound. Parse it as a generic NBT tree — **never build an `ItemStack` from
   it**, since no vanilla codec reads a blob that is half legacy tag, half components.
   `components["minecraft:tooltip_style"]` is `hypixel_skyblock:<rarity>`, which gives rarity
   without parsing lore — **except when it is absent**, which measured at 4 of 154 live sales.
   Those items state their rarity only in the last lore line, so `ItemDecoder` falls back to
   parsing it. Two more decode traps: stars live under `upgrade_level` *or* the legacy
   `dungeon_item_level`, and every pet shares the item id `PET` with its identity in a JSON
   string under `ExtraAttributes.petInfo`.

`/v2/skyblock/auctions` is paged (~51 pages, ~70MB per full sweep) and is the mod's most
expensive operation by far. Three things keep it affordable, and all three should survive any
rework: `AuctionsDto` declares only the six fields needed so Gson discards the rest while
parsing; the sweep is skipped when `lastUpdated` is unchanged, which it is for a minute at a
time; and listings are pruned on name and rarity — both readable without touching `item_bytes` —
before anything is decoded. A coarse hit is never enough to recommend a purchase: it must be
re-checked against the exact decoded signature, or a bare item gets priced off sales of the
five-star recombobulated version.

Valuation trains on `auctions_ended` only, never on active listings: active listings are
contaminated by exactly the mispricings the model is hunting, so fitting to them teaches it to
agree with the mistake. Buy-it-now sales only, and medians rather than means — one fat-fingered
listing moves a mean enough to make the whole market look cheap.

`/v2/resources/skyblock/items` carries `upgrade_costs` (exact per-star essence costs) and
`recipes`, so star pricing and craft flips should be computed deterministically from that data
rather than fitted from observed prices.

**Item names in that resource are current but not unique enough to search on.** Hypixel tracks
modern Minecraft naming, so `ENCHANTED_MELON_BLOCK` is called "Enchanted Melon" and
`ENCHANTED_MELON` is "Enchanted Melon Slice" — 51200 and 320 coins respectively. Measured live,
187 of 5549 names are a strict prefix of another name, so typing one into the bazaar search returns
both. Do not synthesise a better name from the id: the words an id has that its name lacks are
mostly legacy Minecraft junk (`DOUBLE_PLANT` is "Sunflower", `INK_SACK:3` is "Cocoa Beans"), which
was tried and is wrong more often than it helps. `ItemCatalog.shadowedBy` reports the collision and
candidates carry it as a note instead.

Volume lives in `quick_status`: `buyMovingWeek` is units instantly bought (how fast sell offers get
lifted), `sellMovingWeek` is units instantly sold (how fast buy orders get filled). Every plan is
sized from those flows, never from resting book depth — depth is a snapshot and does not refill for
free. `BazaarProduct.instantBuysPerHour()` / `instantSellsPerHour()` are the accessors to use.

Bazaar sales tax is 1.25%, reduced 0.125% per Bazaar Flipper perk level to a floor of 1%.
`FlipperConfig.bazaarFlipperLevel` (0-6) feeds this; a wrong value silently biases every
bazaar margin.

## Scope constraint

The mod is advisory: it surfaces numbers and rankings, it does not click, buy, sell, or
otherwise act on the player's behalf. Do not add auto-purchase, auto-relist, inventory
manipulation, or packet-level automation.
