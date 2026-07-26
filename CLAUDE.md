# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
./gradlew build          # compile + remap + jar into build/libs/ (what CI runs)
./gradlew runClient      # launch a dev Minecraft client; game files land in run/
./gradlew clean build    # after changing anything in gradle.properties
```

There is no `src/test` yet, so `./gradlew build` runs an empty `test` task. When adding tests,
put them under `src/test/java` — see the `core` rule below for what is testable. Single test:
`./gradlew test --tests 'jeff.skyblockflipper.core.*'`.

Requires JDK 25. The Gradle toolchain pins this, so the build does not use whatever `java` is
on PATH, but `runClient` still needs a JDK 25 available for Gradle to find.

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
   without parsing lore.

`/v2/resources/skyblock/items` carries `upgrade_costs` (exact per-star essence costs) and
`recipes`, so star pricing and craft flips should be computed deterministically from that data
rather than fitted from observed prices.

Bazaar sales tax is 1.25%, reduced 0.125% per Bazaar Flipper perk level to a floor of 1%.
`FlipperConfig.bazaarFlipperLevel` (0-6) feeds this; a wrong value silently biases every
bazaar margin.

## Scope constraint

The mod is advisory: it surfaces numbers and rankings, it does not click, buy, sell, or
otherwise act on the player's behalf. Do not add auto-purchase, auto-relist, inventory
manipulation, or packet-level automation.
