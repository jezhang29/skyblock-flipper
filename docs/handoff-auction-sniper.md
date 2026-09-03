# Handoff — auction sniper audit + cross-branch reconciliation (2026-09-03)

Written at the end of a session that ran low on usage. Everything below is on disk; the conversation
is gone. Read `docs/roadmap.md` first, then this.

## Where things stand

Branch **`auction-sniper-audit`**, tip after this session (nothing pushed):

| commit | what |
|---|---|
| `684f6f4` | `SnipeGateReconcileBacktestTest` — the band×trust reconciliation (evidence) |
| `6ef0877` | restamp the bazaar tape fixture (duplicate of codex's `afa5814` — see merge notes) |
| `b1cf9d6` | suspect guard **+** the `snipeMinDiscount` 0.15→0.25 raise (raise now reverted, guard kept) |
| `7141ee1` | the realized-P&L backtest `SnipeProfitBacktestTest` |
| `7ff6393`, `88f5418`, `205c8ca` | the gemstone unlocked-slot `slots` bit + un-blinded harm probes |

The **floor revert** (0.25 → 0.15) and the roadmap/handoff updates are committed on top of these (see
`git log`). Full offline `./gradlew test` is green.

## The reconciliation verdict (settled — do not re-open)

Two branches tuned the same knob opposite ways. This branch raised the global `snipeMinDiscount`
0.15→0.25; `auction-overlay` (codex) kept 0.15 and added a tighter `exactMinDiscount` 0.12 that fires
only for a **trusted EXACT** quote (confidence > 0.80, samples ≥ 15, dispersion < 0.20).

`SnipeGateReconcileBacktestTest`, one 24h holdout of the live tape, both gates resold at the same
realized out-of-sample median (the premise they differed on resale truth was wrong — both use it):

- The losing 0.15–0.25 band splits by **trust, not depth**: trusted EXACT 20.3% loss / 0.40M per flag,
  everything else 31.2% / 0.20M.
- The disputed set the 0.25 floor drops but codex's gate keeps — **trusted EXACT in [0.12, 0.25)** —
  resells at 19.8% loss, 0.37M per flag, **+2.23B realized**, ~80% profitable.
- Whole-gate: codex's trusted-exact gate books **more** realized profit than the 0.25 floor
  (4,332M vs 4,197M) with **fewer** flags (8,730 vs 10,458), at the same quote survival (57% vs 58%).

**Verdict: the blanket floor is the wrong instrument; codex's trust-gated 0.12 exact margin is right.**
So the floor is back at 0.15 here. The `suspect` guard (deep end, 0.60+) and the gemstone `slots` bit
**stay** — both additive, both validated. The audit's old open #3 (`ability_scroll` probe RED on
Hyperion) is **done on `auction-overlay`** (`docs/auction-sniper-scroll-safety.md`).

Run it yourself:
`./gradlew test -PtapeBacktest "-PtapeDir=/Users/jzhang/Library/Application Support/minecraft/config/skyblock-flipper/tape" --tests '*SnipeGateReconcile*'`

## The next task: merge `auction-sniper-audit` into `auction-overlay`

`auction-overlay` is the de-facto integration line — it descends through fusion → work-many-flips →
npc-respects-other-flips → bazaar-combine → craft, so it already holds most other branches, plus the
overlay rework, recovery alerts, the LGPLv3 license, and codex's `exactMinDiscount`/`SupplyCounter`/
`ability_scroll` work. This branch holds the gemstone bit, the suspect guard, and the two P&L
backtests. **Neither is on `main`.** Merge was deferred because it is a real conflict surface and not
worth rushing at a session's end.

Recommended direction: `git checkout auction-overlay` and merge/rebase `auction-sniper-audit` onto it.
Expected conflicts and how to resolve each:

- **`src/test/.../tape/BazaarTapeTest.java`** — both restamped `fixture()`. Keep **codex's** version
  (`afa5814`, it carries the LGPL header this branch's `6ef0877` lacks). This branch's fixture commit
  is then redundant.
- **`DecodedItem` / `DecodedItem.signature()`** — this branch adds `unlockedSlots` + a `slots` term;
  codex adds `abilityScrolls` + a term. Keep **both**; they are orthogonal attributes on the same
  method.
- **`docs/roadmap.md`** — both heavily edited. Take **codex's** as the base (it is more current:
  craft/combine/fusion/recovery), then fold in this branch's gemstone + suspect + reconciliation
  entries.
- **`signature-findings` skill** — fold both: keep the gemstone `slots` finding (this branch) and the
  `ability_scroll` finding (codex).
- **`AuctionValueStrategy.java`** — this branch's suspect guard vs codex's decoded-blob-sharing; likely
  small, keep both behaviours.
- **`FlipperConfig.java` / `ConfigSchema.java`** — minimal: this branch reverted `snipeMinDiscount` to
  0.15, codex's base is also 0.15, so no conflict there. Take codex's `exactMinDiscount` field.
- New test files coexist: `SnipeProfitBacktestTest`, `SnipeGateReconcileBacktestTest` (this branch),
  `ExactMarginBacktestTest` (codex).

Target end state after merge: floor 0.15 **and** `exactMinDiscount` 0.12 **and** the suspect guard
**and** the gemstone `slots` bit **and** the `ability_scroll` key, all present. Then `./gradlew build`
and re-run the opt-in tape backtests to confirm the recorded numbers still hold.

## Rules that bind the next chat

Never work on `main` (branch first). Commit at each checkpoint that builds + passes. `./gradlew build`
before a checkpoint commit. `core` must not import `net.minecraft`/`net.fabricmc`. Read config through
`config()`, never cache field values. Don't push unless asked. The tape lives at
`~/Library/Application Support/minecraft/config/skyblock-flipper/tape` (pass it with `-PtapeDir`).

## Resume prompt for the next chat

> Read `docs/roadmap.md` and `docs/handoff-auction-sniper.md`, then `git log --oneline -12` and
> `git branch -a`, and report the real state before touching anything. The task is next-item #1 in the
> roadmap: reconcile branch `auction-sniper-audit` into `auction-overlay`. The auction-sniper discount
> question is already settled (see the handoff): keep the 0.15 floor, keep codex's `exactMinDiscount`
> 0.12 trusted-exact gate, keep the `suspect` guard and the gemstone `slots` bit — do not re-open that.
> Do the merge on a new branch off `auction-overlay`, resolve the conflicts the handoff lists (take
> codex's `BazaarTapeTest` and roadmap as bases, fold in this branch's gemstone/suspect/reconciliation
> and both signature findings), keep both signature terms in `DecodedItem.signature()`, then
> `./gradlew build` and re-run the opt-in tape backtests
> (`-PtapeBacktest -PtapeDir="/Users/jzhang/Library/Application Support/minecraft/config/skyblock-flipper/tape"`)
> to confirm the recorded numbers hold. Commit at each checkpoint; never work on `main`; don't push
> unless asked.
