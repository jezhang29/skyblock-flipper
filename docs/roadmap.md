# Roadmap

The single on-disk record of what is done, what is next, and what is settled and must not be
re-opened. A lost conversation loses everything not on disk, so this file is where the plan lives.
Update it when a checkpoint lands or a decision is made.

Deep records live elsewhere and are linked from here:

- `docs/npc-flipping.md` — every measured NPC parameter and the game facts the API cannot give you.
- `docs/adr/0002-reprice-in-rounds.md` — the reprice-in-rounds decision, its seven-step build log,
  and what was rejected.
- `docs/adr/0001-defer-the-signature-term-model.md` — why signature terms are read individually.
- The `signature-findings` skill — which auction attributes ship, which measured out, and the probe
  method. Read it before proposing any new signature key term.
- `docs/headless-collector.md`, `docs/trade-capture.md` — the collector and the capture protocol.

## Current state (2026-08-15)

Active branch: `npc-basket`. The whole NPC basket strategy is built and committed there — the
basket, the reprice rounds, the check-in reminder, the Basket tab, the bazaar slot highlighting and
the drift premium. It has run in real play across several sessions. It is not merged and carries
40+ `wip:` commits that want squashing before it is called done.

Branch `docs-foundation-reset` holds this file, the CLAUDE.md rewrite, and the memory slim-down.

Everything the valuation side ships (pet levels, the fill model, the rune/potion/dungeon/dye/
ethermerge signature splits, the Midas ratio quote) is verified offline only. Getting it seen in a
running client is still the largest unverified-work risk in the project.

## What is next

Ranked. The top three are all NPC-branch work; the fourth is the standing valuation risk.

1. **Re-run the unattended overnight experiment with the premium actually live.** The first run
   (2026-08-15) returned 11.2% of capital because `npcDriftPremium = 1.0` contributed exactly zero:
   the basket was placed ~40s before `MarketPoller` built the NPC edge snapshot, so every `NpcEdge`
   was null and the chase cost was never charged. Fixed in `65f833d` — the plan now refuses rather
   than misprices when the edge is unmeasured, and the rebuild starts at 20s. The premium itself is
   still untested in play. Repeat the overnight run before drawing any conclusion about it.
2. **Confirm the bazaar place-flow button names in a capture, not from screenshots.** Slot
   highlighting ships, but `Create Buy Order`, `Custom Amount` and `Custom Price` were read off
   screenshots; a wording matching none highlights nothing. `/flip menu` prints the last menu's
   buttons — the cheap way to confirm one. Do not extend the highlight to a new screen without
   checking that screen exists in a capture file first.
3. **Squash the `npc-basket` branch and merge it.** One real commit per concern; check
   `git log --oneline` before calling it done.
4. **Get the shipped valuation work seen in a running client.** Pet levels, the fill model, the four
   signature splits and the Midas ratio quote are all offline-only. This is a long queue of
   unverified-in-game work.

Nothing else is queued. The signature-gap seam is closed and measured closed: the harm probe's top
entry is `eman_kills` at 45.5M coins, and everything below it is a counter or a per-item identifier.
`UnreadAttributeProbeTest` asserts the top stays under 100M — it is the alarm for a Skyblock update
adding a new invisible upgrade, not a to-do list. Do not start another attribute branch without a
fresh probe run showing something above that line.

## Settled — do not re-open

Each of these was measured, not guessed. The measurement is named so a re-opening fails a test
rather than a memory.

### NPC flipping

- **Slots bind, coins do not.** At every setting measured on the live book, order slots set the
  basket; 400M produces the same basket as 1.6B. The `npcMinMarginRatio` floor is the other lever.
- **The 500M daily NPC coin cap is real, global and hard.** It is what binds, not playtime. Above
  ~3h/day more presence buys nothing. Do not plan work around "the user cannot play 24h."
- **There is no walking.** With a booster cookie, `/trades` reaches any shop from anywhere. Any NPC
  plan phrased in trips models a cost that does not exist.
- **Clicks and session count are the real budget, not coins.** Every model here prices coins and
  none prices clicks, so a coins-positive change that adds hauling can be a net loss to the user.
  Quote hauling (an inventory load is 36 slots) and trip count beside any NPC profit figure. A
  ranking key that multiplies clicking ships as a setting, never as a silent default.
- **Cancelling a live order to chase a better item is rejected.** Under a binding cap, throughput is
  worthless and only margin against the cap counts. `NpcDisplacementSweepTest` asserts both halves.
- **Capping orders per item is noise.** Within-noise, changes sign between slot counts.
  `NpcContext.maxOrdersPerItem` stays a sweep dimension only; the shipped value is unlimited. See
  ADR 0002.
- **Reprice on a clock, not on the book.** A contested book (five bots penny-jumping by 0.1) asks
  for a reprice every few seconds, which is off the end of the measured curve. Reprice in rounds;
  the frozen *list* is what the measurement was about, and the price on each row is quoted live
  because the player is standing at Hypixel's own live "+0.1 coins" button. See ADR 0002 and
  `npc-live-bugs-2026-08-12`.
- **The drift premium pays the chase into the posted price instead of into 16 reprice rounds.** Same
  coins, no trips. Trap: the market peaks at 0.25x the drift and this mod's fill model peaks at 1.0x,
  because `FillModel` never returns a displaced order to the front of the book. The `/flip npc probe`
  command settles the one thing the tape cannot: a competitor pressing +0.1 at the user's order
  specifically.

### Valuation (see the `signature-findings` skill for the full record)

- **Shared item ids are the recurring silent bug.** `PET`, `RUNE`, `POTION` each pooled a whole
  market on one key until split by their in-blob identity. The seam is now closed.
- **Six attributes measured out and must not be re-opened:** raw `color`, `power_ability_scroll`, the
  drill parts, `tuned_transmission`, `baseStatBoostPercentage` as a number (it is a `maxed` flag),
  and `dungeon_item`. Each is huge at the bare item id and flat at the production signature.
- **Three shipped:** `dye_item`, `ethermerge`, and `winning_bid` (as a price-to-bid ratio quote, not
  a key term). Pet level, dungeon `item_tier` and Kuudra `attributes` also ship.
- **More tape does not buy pricing accuracy.** Coverage is 88.9% at 48h against 89.3% at 120h;
  `valuationWindowDays` stays 2.
- **AH → BZ arbitrage is dead by game rule.** The two venues trade disjoint item sets. Craft flips
  have no deterministic recipe source in the API, so that seat stays empty too.
- **Hedonic / component valuation measured flat** — no improvement over the base median. The hole is
  real (22.9% of sales unpriceable, 61.7% of coins) but component multipliers do not fill it.

## Explicitly rejected designs

- A backend server that polls and pushes to clients — turns an advisory single-player mod into a
  hosted service with running costs and a Hypixel redistribution question.
- Manual entry of bought/sold amounts — the user wants tracking fully automatic, and it works.
- A learned/ML reprice ranking — the mod already learns displacement from the user's own tape,
  transparently.

## History

The full turn-by-turn history of settled valuation work (July–August 2026) is condensed above. If a
finding's exact holdout numbers are needed, they are in the git log bodies of the branch that landed
it and in the `signature-findings` skill.
