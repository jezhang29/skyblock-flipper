# Gemstone-slot valuation

> **Status: shipped 2026-09-02** as a one-bit `slots` signature term (not the exact count), on branch
> `gemstone-slots`. The holdout on the user's tape put fake snipes 632 → 622 over the 283 ids that
> ever unlock a slot at 102 valuations, tying the exact count while keeping more coverage. The count
> is decoded into `DecodedItem.unlockedSlots`; `Keying.isBare` treats a paid-open slot as non-bare.
> Both probes are un-blinded to the nested key. Verdict and numbers: the `signature-findings` skill
> and `GemstoneSlotBacktestTest`. Offline-verified only; unverified in a running client.

An auction-flipper bug found in play, 2026-09-02: the mod quoted a Divan's Helmet and Divan's Boots
at ~60M and flagged them as snipes. They were worth a fraction of that. The 60M sales it priced them
against had their **gemstone slots unlocked**; the flagged items did not. Unlocking a slot costs real
coins (and gemstone materials), so an item with locked slots is worth far less than one with them
open — and the model could not tell the two apart. This file is the work item; read it before
touching gemstone-slot valuation.

> Discipline for doing this work — inspect the implementation and callers before editing, prefer
> minimal changes, verify any Minecraft/Fabric symbol from the codebase or decompiled source, run the
> Gradle build and tests, inspect `git diff`, and never claim a fix complete if compilation or
> verification has not succeeded.

# Goal

Two Divan's pieces with identical id, rarity, reforge, recomb and enchants but **different
gemstone-slot-unlock state** must not price off each other. A locked-slot item must not be quoted at
the median of unlocked-slot sales, and the auction flipper must not flag it as a snipe.

# Current behavior

`ItemDecoder.gemstones()` reads only **placed** gems (`JASPER=FINE`) and steps over the
`unlocked_slots` array. An item with slots **unlocked but empty** therefore produces no `gems=` term.
`Keying.isBare` tests `item.gemstones().isEmpty()`, so that item reads as **bare** and prices off the
coarse name+rarity pool — which holds the gemmed, unlocked, recomb+Jaded sales. Result: a
locked/empty Divan's Helmet is quoted at the pool median (~60M) and shows as a large discount on
itself. Applies to every gemstone-slot item: Divan and Crimson armor, drills, the Gemstone Gauntlet.

# Root cause / architecture

Two defects, not one.

1. **Decode drops slot-unlock state.** `unlocked_slots` (the array naming which slots have been paid
   open) is a value driver worth millions, and nothing reads it into the signature. Placed gems are
   keyed; unlocked-but-empty slots are invisible.

2. **The alarm was blind to it.** `UnreadAttributeProbeTest.READ` marks the whole `gems` compound
   "read", and `unlocked_slots` lives inside `gems`. So the probe that ranks unread attributes by the
   coins their pooling misprices — the same probe that surfaced `ethermerge` — never ranked
   `unlocked_slots`. The settled claim "there is no further shared-id-shaped gap on this tape"
   (`signature-findings` skill) was never tested against this attribute. The safeguard meant to catch
   exactly this could not see it.

The fix is a signature key term. This is a settled-question area: read the `signature-findings` skill
first. Six of the last candidates measured out as pure no-ops (`color`, `power_ability_scroll`, the
drill parts, and more), so a term ships **only** after a holdout backtest against the model that
ships. But the mechanism here is the same low-cardinality investment split that let `ethermerge` and
`item_tier` through, and the play evidence is a concrete loss, so this one is expected to ship.

# Relevant code

- `src/main/java/jeff/skyblockflipper/core/item/ItemDecoder.java` — `gemstones()` (drops
  `unlocked_slots` at the `GEM_SLOT_INDEX` guard); the decoder builds `DecodedItem` around line 172.
  `GEM_SLOT_INDEX = "unlocked_slots"`.
- `src/main/java/jeff/skyblockflipper/core/item/DecodedItem.java` — the record and `signature()`;
  copy the `ethermerge` block as the shape for the new term. `valuationKeys()` and `isFullyDescribed`.
- `src/main/java/jeff/skyblockflipper/core/valuation/Keying.java` — `isBare` (line ~122); the new
  term joins the bareness clause list.
- `src/test/java/jeff/skyblockflipper/core/valuation/backtest/SignatureTerms.java` and `Bareness.java`
  — the backtest's copy of the term set and the bareness clause list; `BarenessTest` pins the latter
  to `Keying.PRODUCTION.isBare`.
- `src/test/java/jeff/skyblockflipper/core/valuation/backtest/CounterfactualKeying.java` — how an
  arm unreads one shipped term for the holdout.
- `src/test/java/jeff/skyblockflipper/core/valuation/EthermergeBacktestTest.java` — the template for
  the new backtest.
- `src/test/java/jeff/skyblockflipper/core/valuation/UnreadAttributeProbeTest.java` and
  `src/test/java/jeff/skyblockflipper/core/item/SignatureGapProbeTest.java` — the `READ` sets that hide
  `unlocked_slots` inside `gems`.
- `src/main/java/jeff/skyblockflipper/core/strategy/AuctionValueStrategy.java` — the exact-gate
  re-check that re-scores a coarse hit against `signature()`. It inherits the fix for free once the
  term is in the signature: a locked item's exact signature stops reading as bare.
- `src/test/java/jeff/skyblockflipper/core/item/ItemDecoderTest.java` — where the decode is pinned.

# Invariants / constraints

- **`core` stays Minecraft-free.** No `net.minecraft` / `net.fabricmc` imports in the decode or
  valuation change; the `collectorJar` build proves the layering held.
- **Signature strings stay deterministic** — sorted parts, one string per configuration. Match the
  existing term ordering in `signature()`.
- **Do not hand-roll a model in the backtest.** Measure through `Backtest.holdout` under a `Keying`,
  as `EthermergeBacktestTest` does. Hand-built copies drifted from what ships and every drift
  flattered the pooled arm.
- **`isBare` has one canonical copy.** `Keying.PRODUCTION.isBare` is authoritative; `Bareness` in the
  backtest package mirrors it and `BarenessTest` pins them equal. Change both together.
- **A gemmed slot is an unlocked slot.** The count must be the union of the `unlocked_slots` array and
  the slots holding a placed gem, or a fully-gemmed item reads as having zero unlocked slots.
- **Old `config.json` and old tapes stay valid.** No config schema change is needed; the term is
  derived from tape that already exists.

# Implementation plan

1. **Un-blind the probe first, and commit it on its own.** Split `unlocked_slots` out of the `gems`
   entry in `UnreadAttributeProbeTest.READ` and `SignatureGapProbeTest` so the probe ranks it as its
   own attribute. Run `./gradlew test -PtapeBacktest --tests '*UnreadAttributeProbeTest'` and record
   where `unlocked_slots` lands and the coins it misprices. This is the alarm fix and lands regardless
   of the term verdict.

2. **Decode the count.** Add `unlockedSlots` (int) to `DecodedItem`, computed as the count of the
   union of slots named in `unlocked_slots` and slots holding a placed gem. Drop the slot index the
   way placed gems do (which hole does not change the count). Keep the legacy constructor working.
   Pin it in `ItemDecoderTest` with a Divan-shaped blob in three states — slots locked, unlocked and
   empty, unlocked and gemmed — asserting the count in each.

3. **Backtest as a `Keying`.** Add `unlockedSlots` to `SignatureTerms` and a `CounterfactualKeying`
   arm that unreads it. Write `GemstoneSlotBacktestTest`, mirroring `EthermergeBacktestTest`, over the
   ids that ever carry a slot. Compare keyed vs unread on: sales valued at ≥2x what they fetched (the
   locked-side overvaluation — this bug), p90 and median |log err|, and the coverage cost. The
   backtest decides **count vs sorted slot-type multiset**: use whichever keeps pools above
   `MIN_SAMPLES`. Recommendation going in: **count** — the id already fixes which slots a piece has,
   so count is well-defined and low-cardinality (0–~6) and will not shatter pools the way `color`
   did.

4. **Ship the term only if it survives the holdout.** In `signature()`, after the `ethermerge` block:
   `if (unlockedSlots > 0) key.add("slots=" + unlockedSlots);`. Add `unlockedSlots == 0` to
   `Keying.PRODUCTION.isBare` and to `Bareness`; keep `BarenessTest` green. Extend
   `FairValueModelTest` with a case proving a locked item no longer pools with unlocked sales.

5. **Confirm the exact-gate defense.** With the term in the signature, verify in
   `AuctionValueStrategy` (and its test) that a coarse hit on a locked item is now refused, because
   its exact signature no longer matches the unlocked pool. No separate code change expected — assert
   it.

6. **Document the verdict.** Update the `signature-findings` skill with the measured result (ship or
   no-op) and the probe blind-spot fix, and mark this item done in `docs/roadmap.md`.

# Edge cases

- **Fully-gemmed item, `unlocked_slots` absent or partial.** Placed gems imply unlocked slots; count
  their slots too. Do not rely on `unlocked_slots` alone.
- **Newer gem format** — a gem is a compound of `quality` + applier uuid, not a bare string. The slot
  is still unlocked; count it whether or not the quality parses.
- **Items that ship with slots pre-unlocked** (some Divan pieces from crafting). Fine — key the
  observed unlocked count; do not special-case the source.
- **A count that shatters pools.** If the backtest shows a count value stranding sales below
  `MIN_SAMPLES`, fall back to a one-bit "any slot unlocked" term (the `ethermerge` shape) and
  re-measure.
- **Recovery decode.** `ItemDecoder.recoveryGemstones()` reads the same compound for a different
  purpose; do not change its behavior while adding the count.

# Verification

- `./gradlew test` — offline JUnit 5; new `ItemDecoderTest`, `GemstoneSlotBacktestTest` (only its
  non-tape assertions), `FairValueModelTest`, `BarenessTest`, `AuctionValueStrategy` test all green.
- `./gradlew test -PtapeBacktest --tests '*UnreadAttributeProbeTest'` — records where `unlocked_slots`
  ranks once un-blinded.
- `./gradlew test -PtapeBacktest --tests '*GemstoneSlotBacktestTest'` — the ship/no-op decision;
  needs a recorded tape (user runs it against theirs).
- `./gradlew build` and `./gradlew collectorJar` — compile, remap, and prove the `core`/Minecraft
  layering held.
- Inspect `git diff` for unintended changes before declaring done.

# Things NOT to change

- Do not add a config setting — the term is tape-derived and needs none.
- Do not price the unlock deterministically from a cost table. The market pays partial unlock cost
  like recomb, not full, and no items-endpoint fixture carries gemstone-slot costs (unlike
  `upgrade_costs` for stars), so there is no deterministic source. Let the medians speak.
- Do not key the slot **index** (`JASPER_0` vs `JASPER_1`) — which hole a slot is does not move price;
  drop it as placed gems already do.
- Do not touch NPC, bazaar, craft or combine valuation. This is the auction signature only.
