# Bazaar overlay rework plan

Status: **planned, not started.** Design settled via grill 2026-08-28. Build in a fresh session.

## Goal

Rework `client/hud/BazaarOverlay` (the panel drawn over Hypixel's live bazaar menu) from one
jumbled scrolling list (Worked jobs + NPC basket, NPC-dominated) into a **per-type view**: pick a
flip type from a small always-visible list, see that type's instructions clearly separated from the
others.

Not the `/flip gui` `FlipScreen` — that already has per-type tabs and is fine. Target is the
in-bazaar overlay only.

## Confirmed design

**Type selector (panel header)**
- Thin list of every bazaar flip type, labels straight from `StrategyKind.label()`, wrapped to as
  many thin rows as needed. Active one highlighted. Click a label to switch.
- Labels auto-generated from one source of truth for "which `StrategyKind`s are bazaar flips" (see
  step 1). A type Codex adds later appears with no overlay edit.

**Body, per active type**
- **NPC**: the existing basket worklist (claims / cancels / reprices / places + between-rounds
  note). Unchanged from today. NPC has no "To start" list and no Work action (it is a basket, not a
  followable per-item job).
- **Craft / Combine / Fusion / Spread**:
  - *Working now*: committed jobs of that type, expanded with steps + progress badges (today's job
    rendering, filtered to the active kind).
  - *To start*: ranked candidate one-liners (name + total net profit). Collapsed by default. Click a
    row to expand its buy / transform / sell steps inline.
- Step rows keep click-to-copy (name / price / size), exactly as now.

**Work action**
- Expanding a *To start* candidate reveals a small `Work` control. Pressing it calls
  `CandidateFeed.work(kind, itemId, displayName)` — the same commit path `FlipScreen.workSelected`
  uses. The flip moves to *Working now*, gains progress badges, and becomes green-box guidable.
- This is the overlay's first state-changing control. It still sends no game clicks (commit is mod
  state, not an inventory packet).
- A committed job's header offers `Stop` → `CandidateFeed.stopWork(itemId)`.

**Green box (BazaarStep) follows the active type**
- Generalize `BazaarStep` off `NpcWorklist.Task` to an abstract bazaar action so it guides any
  type's next bazaar click. Off-bazaar transform steps (anvil / Fusion Machine) highlight nothing.

**Active type state**
- New persisted config field `bazaarOverlayType` (String, stores a `StrategyKind.name()`), default
  `NPC_FLIP`, remembers last pick. Independent of `strategyFilter` (which carries All / Snipe that
  do not map to a bazaar type).

## Minor decisions (settled)
- Candidate one-liner shows total net profit only (width).
- *To start* ranks top ~5 for the active type; panel scrolls for more.
- No per-type counts in the selector — that would rank every type each frame. Only the **active**
  type is ranked (cheap, revision-cached).
- Empty states reuse the existing tone ("Nothing clears the fees right now. That is a normal
  answer.").
- Left/right placement, scaling, follow-the-sign window: unchanged.

## Verified hooks (already exist, reuse verbatim)

`client/CandidateFeed`:
- `rank(StrategyKind kind, int limit)` → ranked candidates of a kind.
- `jobs()` → all `WorkedJob`s (filter by `kind()` in the overlay).
- `worklist()` → the NPC basket worklist (NPC tab body).
- `work(StrategyKind, itemId, displayName)` → commit; returns **false** for kinds with no bazaar
  steps (NPC / snipe), so guard the Work control to the four per-item types.
- `stopWork(itemId)`, `working(itemId)`, `workedAs(itemId)`, `workedIds()`, `invalidate()`.
- `jobs()` builds each job per kind via a switch (`WorkedJob.ofCraft/ofCombine/ofFusion/ofSpread`);
  `work()` already records `FlipIntentsService` so the NPC side leaves the committed order alone.

`core/config`: enum-as-String pattern to mirror — `FlipperConfig.bazaarOverlaySide` (field) +
`overlaySide()` (getter) + `ConfigSchema.Entry.Choice` with `readOverlaySide`/`writeOverlaySide`
helpers + `validated()` re-serializing from the parsed enum. `ConfigSchemaTest` fails if a field has
no schema entry, so the entry is mandatory.

`core/strategy/BazaarStep.next(NpcWorklist.Task, double restingPrice, CapturedMenu)` — the only green
box path today; called from `BazaarOverlay.Guidance.update(container, worklist)` over
`worklist.pending()`. `BazaarStepTest` covers it.

## Build sequence (wip: checkpoints, one plan item each)

1. **Bazaar membership + config field.** Add one source of truth to `StrategyKind` for bazaar
   membership (e.g. `public boolean atBazaar()` per constant, or a static `EnumSet` + accessor);
   true for `BAZAAR_SPREAD, NPC_FLIP, CRAFT, COMBINE, FUSION`, false for `AUCTION_VALUE`. Add
   `FlipperConfig.bazaarOverlayType` (default `NPC_FLIP`) + `bazaarOverlayType()` getter + a
   `bazaarOverlayTypeOptions()` from the bazaar kinds + `ConfigSchema.Entry.Choice` + `validated()`
   clamp. `./gradlew test` green (ConfigSchemaTest).

2. **Selector + per-type body layout.** In `BazaarOverlay`: draw the wrapped type-label strip (panel
   pixels; conceptually like `FlipScreen.tabRows`), highlight active, hit-test switches active type
   and persists to config. Body dispatch: NPC → existing worklist board; else → *Working now*
   (`jobs()` filtered by kind, expanded) + *To start* (`rank(kind, ~5)`, collapsed one-liners,
   click to expand steps). Add active-type + expanded-candidate id to the `Board` cache key. Keep
   copy-on-click. `./gradlew build` compiles.

3. **Work / Stop action.** Expanded candidate → `Work` hit target → `CandidateFeed.work(...)`;
   committed job header → `Stop` → `stopWork(...)`. Guard Work off NPC. `./gradlew build`.

4. **Green box follows active type.** Introduce an abstract bazaar action (itemId, side, price,
   verb/label, units) in `core/strategy`; adapt both `NpcWorklist.Task` and the priced
   `WorkedJob.Step` stages (`BUY_ORDER`, `INSTANT_BUY`, `SELL_OFFER`) to it; refit `BazaarStep.next`
   to take it. `TRANSFORM` → no step. Rework `Guidance.update` to iterate the active type's action
   list (committed jobs first, then the expanded candidate). Keep the NPC path behaviour identical.
   Update/extend `BazaarStepTest`. `./gradlew test` green.

5. **Squash** wip commits into real commits, one per concern (config/membership; overlay layout +
   Work; BazaarStep generalization). Check `git log --oneline`. `./gradlew build` before the final
   commit.

## Branch & coordination
- **Branch off `fusion-flipping`**, not `main`. `main` lacks FUSION, combine-in-overlay, and the
  multi-follow `WorkedJob` this rework needs. Name it e.g. `bazaar-overlay-rework`.
- Codex is adding new `StrategyKind`s in parallel. The only shared touch point is
  `StrategyKind.java` (the bazaar-membership mark). A new kind is one line there to appear in the
  overlay. Rebase onto Codex's additions if they land on the same base.
- Pre-existing uncommitted changes on `fusion-flipping` (`.claude/output-styles/crisp.md`,
  `src/test/.../BazaarTapeTest.java`) are unrelated — leave them alone.

## Guardrails (from CLAUDE.md)
- `core` must not import `net.minecraft`/`net.fabricmc` — the `BazaarStep` abstract action lives in
  `core/strategy` as plain data.
- Never cache config field values; read through `SkyblockFlipperClient.config()` at use time.
- Commit at each checkpoint (`wip:` subject, no body); never commit a non-building state.
- 26.2 mapping names: copy imports from existing GUI files; `GuiGraphics` does not exist — see the
  `mc-26-2-gui-api` memory.
