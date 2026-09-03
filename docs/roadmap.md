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

The whole NPC basket strategy is merged to `main` (2026-08-17) — the basket, the reprice rounds, the
check-in reminder, the Basket tab, the bazaar slot highlighting and the drift premium. The squash
folded 40+ `wip:` commits into a clean history and the branch is deleted. It has run in real play
across several sessions. `main` is ahead of `origin/main`; nothing is pushed until the user asks.

Everything the valuation side ships (pet levels, the fill model, the rune/potion/dungeon/dye/
ethermerge signature splits, the Midas ratio quote) is verified offline only. Getting it seen in a
running client is still the largest unverified-work risk in the project.

## What is next

1. **Reconcile this branch with `auction-overlay`.** The two forked at 2026-08-17 and have disjoint
   work. This branch (`auction-sniper-audit`) has the gemstone `slots` bit, the suspect guard, and the
   P&L + reconciliation backtests; `auction-overlay` (codex) has craft/combine/fusion, the overlay
   rework, recovery alerts, the LGPLv3 license, and the validated `exactMinDiscount` 0.12. Neither is on
   `main`. The merge is **deferred, not skipped** — it is a real conflict surface (this roadmap, the
   `signature-findings` skill, `DecodedItem.signature()`, `BazaarTapeTest`, `AuctionValueStrategy`) and
   was not worth rushing at a session's end. Full plan and a resume prompt in
   `docs/handoff-auction-sniper.md`.
2. **Get the shipped valuation work seen in a running client.** Pet levels, the fill model, the four
   signature splits and the Midas ratio quote are all offline-only. This is a long queue of
   unverified-in-game work, and it needs the built jar played on live Hypixel — the user's job, no
   dev client exists.

Done and closed since the last write:

- **Confirm the bazaar place-flow button names** (was next-2). Done 2026-08-16: `Create Buy Order`,
  `Create Sell Offer`, `Custom Amount`, `Custom Price` and `Top Order +0.1` all read off a live
  `/flip menu` and pinned in `BazaarSlotsTest`. Every screenshot guess was exact. Open sub-finding: a
  sub-category grid titled `<category> ➜ <sub>` still reads as `UNKNOWN`; the shipped worklist routes
  through search, so nothing live breaks, and fixing it needs a lore-carrying capture.
- **Squash and merge `npc-basket`** (was next-3). Done 2026-08-17 by fast-forward.
- **Re-run the unattended overnight experiment** (was next-1). Measured shut, not deferred: the
  premium holds the top ~10 minutes of a session (3% of samples) and catch-dumps ~1.4M/day;
  unattended bazaar-to-NPC makes almost nothing. Do not rebuild an away-mode. See
  `npc-unattended-verdict` in memory and `docs/npc-flipping.md`.
- **Gemstone unlocked-slot valuation** (found in play 2026-09-02). Locked-slot items priced off
  unlocked-slot sales and were flagged as ~60M snipes, because `unlocked_slots` (nested in `gems`)
  reached neither the signature nor the harm probe. Now decoded into `DecodedItem.unlockedSlots` and
  keyed as a one-bit `slots` term, with a paid-open slot made non-bare so the coarse fallback cannot
  undo it. Verdict on the user's tape: the bit beats no-op (fake snipes 632 → 622 over the 283 ids
  that ever unlock a slot, error flat, 102 valuations) and ties the exact count while keeping more
  coverage, so it ships as a bit. Both probes un-blinded to the nested key. See
  `docs/gemstone-slot-valuation.md`, `GemstoneSlotBacktestTest`, and the `signature-findings` skill.
- **Harden the auction sniper, and reconcile the discount gate across branches** (audit + reconcile
  2026-09-03). A realized-P&L backtest (`SnipeProfitBacktestTest`) closed the loop the accuracy tests
  never did: it resells each held-out snipe at the concurrent market median, not the model's own quote.
  The within-signature edge is real but the quote runs ~2x optimistic — ~58% survives, ~15% of flags
  resell at a loss, and the losing tail is the shallow 0.15–0.25 discount band (25.6% loss-rate). Two
  things shipped and **stay**: a runtime **hidden-upgrade guard** in `AuctionValueStrategy` (a discount
  past 0.60 on a 25M+ item is flagged `FlipCandidate.suspect`, sorted below every trusted flip, stamped
  with a verify-every-attribute risk, so a signature miss cannot rank as the top snipe — the general
  backstop the per-attribute probes are not), and the gemstone `slots` bit above.

  A first mitigation — raising `snipeMinDiscount` 0.15 → 0.25 to drop that band — was **built then
  reverted**, because a parallel branch (`auction-overlay`, codex) had tuned the same knob the *other*
  way: keep the 0.15 coarse floor and add a tighter `exactMinDiscount` (0.12) firing only for a trusted
  EXACT quote (confidence > 0.80, samples ≥ 15, dispersion < 0.20). `SnipeGateReconcileBacktestTest`
  settled it on one 24h holdout — both gates resold at the same realized median, so the premise that
  they used different resale truths was wrong: the 25.6% band splits by **trust, not depth**. Trusted
  EXACT flags in [0.12, 0.25) resell at 19.8% loss / 0.37M per flag / +2.23B realized, ~80% profitable;
  the untrusted half (33.1% loss) is junk either gate drops. Codex's trusted-exact gate books MORE
  realized profit than the 0.25 floor (4,332M vs 4,197M) with fewer flags. **Verdict: the blanket floor
  is the wrong instrument; the trust-gated exact margin is right.** So the floor is back at 0.15 here,
  and codex's 0.12 is the validated winner to carry forward at merge. The audit's old open #3 —
  `UnreadAttributeProbeTest` RED on `ability_scroll` (Hyperion) — is **done on `auction-overlay`**
  (`ability_scroll` decoded and keyed; `docs/auction-sniper-scroll-safety.md`), so it is no longer open.
  See `SnipeGateReconcileBacktestTest`, `AuctionValueStrategyTest`, and `docs/handoff-auction-sniper.md`.

Beyond the branch merge above, nothing else is queued. The signature-gap seam is closed for top-level
attributes, and now for a nested one too: the harm probe ranked only top-level `ExtraAttributes` keys, so it was blind to
`unlocked_slots` hidden inside the `gems` compound — a real ~60M gemstone-slot gap that surfaced in
play 2026-09-02, not on the probe. Both probes now split that nested key out, and the gemstone slot
bit shipped (below). With it read the harm probe's top entry is again `eman_kills` at 45.5M coins, a
counter, and everything below it is a counter or a per-item identifier. `UnreadAttributeProbeTest`
asserts the top stays under 100M — the alarm for a Skyblock update adding a new invisible upgrade, not
a to-do list. Do not start another attribute branch without a fresh probe run above that line, and
check for a newly nested attribute the same way this one was missed.

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
  market on one key until split by their in-blob identity. The seam is now closed, including one
  nested case: `unlocked_slots` hid inside the `gems` compound and pooled locked with unlocked
  gemstone-slot items until it shipped as the `slots` bit (2026-09-02).
- **Six attributes measured out and must not be re-opened:** raw `color`, `power_ability_scroll`, the
  drill parts, `tuned_transmission`, `baseStatBoostPercentage` as a number (it is a `maxed` flag),
  and `dungeon_item`. Each is huge at the bare item id and flat at the production signature.
- **Four shipped:** `dye_item`, `ethermerge`, the gemstone unlocked-slot bit (`slots`), and
  `winning_bid` (as a price-to-bid ratio quote, not a key term). Pet level, dungeon `item_tier` and
  Kuudra `attributes` also ship.
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
