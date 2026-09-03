# Roadmap

The single on-disk record of what is done, what is next, and what is settled and must not be
re-opened. A lost conversation loses everything not on disk, so this file is where the plan lives.
Update it when a checkpoint lands or a decision is made.

## How future chats must use this file

**When you read this file to do work, always apply this discipline:**

> Before modifying code, inspect the relevant implementation and callers. Prefer minimal changes
> over rewrites. Do not guess Minecraft/Fabric API behavior; verify it from the existing
> codebase or available documentation/source when uncertain. After implementation, run the
> appropriate Gradle compile/build/tests. Then inspect git diff for unintended changes. Do not claim
> a fix is complete if compilation or relevant verification has not succeeded.

**When you write a new work item to this file, use this format** so the item is actionable by a
future chat that lacks this conversation's context:

```markdown
# Goal
Exact desired behavior.

# Current behavior
What the code currently does.

# Root cause / architecture
Why the bug occurs or how the relevant system works.

# Relevant code
- File A — why it matters
- File B — why it matters
- Method X — important behavior

# Invariants / constraints
Things implementation MUST preserve.

# Implementation plan
1. ...
2. ...
3. ...

# Edge cases
- ...
- ...

# Verification
Exact Gradle commands/tests/manual checks that should pass.

# Things NOT to change
...
```

Deep records live elsewhere and are linked from here:

- `docs/npc-flipping.md` — every measured NPC parameter and the game facts the API cannot give you.
- `docs/adr/0002-reprice-in-rounds.md` — the reprice-in-rounds decision, its seven-step build log,
  and what was rejected.
- `docs/adr/0001-defer-the-signature-term-model.md` — why signature terms are read individually.
- The `signature-findings` skill — which auction attributes ship, which measured out, and the probe
  method. Read it before proposing any new signature key term.
- `docs/worked-flips.md` — the one model behind a flip the player has actually started: the shared
  job shape, the progress badges, and the three views that render it.
- `docs/headless-collector.md`, `docs/trade-capture.md` — the collector and the capture protocol.
- `docs/recovery-value-alerts.md` — the shipped read-only recovery floor, alert safety gates,
  and the exact legacy/removal evidence still deferred.

## Current state (2026-09-02)

**Read-only recovery values are implemented offline** on `recovery-value-alerts` (2026-08-28).
The special Recovery tab and `/flip recovery` explain clean-host plus removable-component floors
from realized ended BIN sales and actual Bazaar bid depth. The active-AH network sweep is shared
with ordinary snipes, recovery has its own atomic revision/feed/UUID selection, and opt-in alerts
default entirely off with stale/current-snapshot checks and bounded UUID/fingerprint dedupe.
Gemstones can receive credit under the versioned removal schedule. Drill and fishing parts remain
zero-credit until current removal-menu costs are captured; legacy salvage remains `PREVIEW REQUIRED`
and zero-credit until an exact manual preview fixture exists. All JDK 25 tests, `build`, and
`collectorJar` pass; no recovery flow has been verified in play yet.

The whole NPC basket strategy is merged to `main` (2026-08-17) — the basket, the reprice rounds, the
check-in reminder, the Basket tab and the bazaar slot highlighting. The squash
folded 40+ `wip:` commits into a clean history and the branch is deleted. It has run in real play
across several sessions. `main` is ahead of `origin/main`; nothing is pushed until the user asks.

The **CRAFT seat is now filled**, on branch `drop-drift-premium` beside the drift-premium removal:
the NEU recipe table is imported and bundled, `core/pricing/CraftQuote` prices a recipe by routing
each ingredient on its own, and `core/strategy/CraftFlipStrategy` ranks it as an ordinary
`FlipCandidate` reachable from `/flip craft`, the Craft tab and `strategyFilter=CRAFT`. The bazaar
overlay follows one chosen craft job the way it follows the NPC basket, so the steps are beside
Hypixel's menu while the orders are typed. Every number behind it is offline - one live book snapshot
plus 13 days of bazaar tape. Read `docs/craft-flipping.md` before touching any of it.

**Craft and bazaar were re-measured on 2026-08-20** against the live book plus two days of tape, and
two things changed (full record in `docs/craft-flipping.md`):

- **A craft plan over its slot budget is cut to size, not moved onto the ask.** The old fallback
  re-quoted the whole bill at `INSTANT_BUY`, which on a farmed material caps the plan at the trickle
  the ask side supplies: `ENCHANTED_MITHRIL` and `ENCHANTED_WHEAT` - the only two plans over the
  shipped six-slot budget - were quoted at 1,123 and 4,774 coins an hour against 883,751 and 787,543
  for the same routes sized to six slots. No slot budget from 1 to 21 regresses; the shipped 6 gains
  3%, and tight budgets 21-24%.
- **Ingredients are routed one at a time** rather than the whole bill taking one route. Worth
  **nothing** on this book, to the coin, and shipped as the correct model rather than as a gain - the
  resting route is normally both cheaper and faster on a crafting material, so there is rarely a
  trade to make. Do not re-measure it hoping for one.

**A fourth strategy, enchanted-book combining, is now built** on branch `bazaar-combine` off this
one (2026-08-20). Buy cheap low-tier books, combine `2^(T-k)` of them up to tier `T` at the anvil,
sell the top tier on an offer; off the NPC cap, and ranked on net per anvil combine rather than per
hour because its whole point is coins per click for a player who cannot grind. It is its own quote
(`CombineQuote`, not `CraftQuote`) because the best targets are one-sided ask books that
`CraftQuote.liquid()` rejects — the exit gate is target ask orders ≥15, no bid side. The combine
table is curated game data, not price-inferred. Read `docs/combine-flipping.md` and
`docs/adr/0003-combine-is-its-own-quote.md` before touching it. Every figure is offline: one live
book snapshot, nothing combined and sold in play yet, so it joins the same unverified-in-play queue
as craft.

**Combine now follows in the overlay the way craft does** (2026-08-20): picking a combine row in the
flip screen makes the bazaar panel follow that job — source buy, anvil merges, sell offer, each with
the price and amount to type beside Hypixel's menu — through a `CombineJob` that is the single source
for both the panel and the `/flip combine` step text. `/flip combine stop` leaves it. This closes the
parity gap where a combine was text-only in the GUI while a craft was guided in-menu, and it is what
makes the combine play-verification below workable.

`BazaarSpreadStrategy` came out of the same audit sound: ranking on profit per hour is right (a
greedy portfolio by that axis spends the whole 400M bankroll on 7 flips for 19.2M/h, against 5.5M/h
ranking by profit per coin), capital binds where slots do not (6 of 130 plans), and the only defect
was a quantity that no order box would take - now split through `Stacking.orderSplit` on both legs.

Everything the valuation side ships (pet levels, the fill model, the rune/potion/dungeon/dye/
ethermerge signature splits, the Midas ratio quote) is verified offline only. Getting it seen in a
running client is still the largest unverified-work risk in the project, and craft flipping has now
joined that queue.

**Several flips are now worked at once** (2026-08-21). Reported from play: picking any row in the
flip screen handed the bazaar panel that one job and took away whatever it was showing, and
selecting a *bazaar* row merely to read it called `stopCraft()` and `stopCombine()` — so comparing
two rows silently ended a craft whose materials were still resting. Nothing anywhere listed what was
open.

- `core/strategy/WorkedJob` is the shared shape for a spread, a craft and a combine, with a `Stage`
  that names the order side each step would show up on. `CraftJob` and `CombineJob` are unchanged
  and still what the strategies produce; `BazaarSpreadStrategy.job(productId, context)` is new, the
  twin of the craft and combine `job` methods.
- **Progress comes from the order tracker, never from a guess.** `[ ]` nothing placed, `[~]`
  resting, `[x]` filled and collected, blank for a step nothing can see — an anvil merge, or
  `autoTrackEnabled` off. Matched on item id and side and summed, because a step is several orders
  by the time `Stacking.orderSplit` is through with it. Tested against the recorded capture session
  rather than against invented orders.
- **Selection is not commitment.** Clicking a row selects it; the Work button starts or stops a job.
- The bazaar panel draws a section per job in the order picked, then the basket under them, in one
  scrollable board. A new **Jobs tab** and `/flip jobs` render the same rows, and the tab totals the
  capital every worked flip has tied up — which nothing else adds up.

Full record in `docs/worked-flips.md`. **Unverified in play**, like craft and combine.

**A fifth strategy, attribute-shard fusion flipping, is now built** (`StrategyKind.FUSION`, branch
`fusion-flipping`, 2026-08-28). Buy `SHARD_*` inputs on the bazaar, fuse them at the Fusion Machine
(multi-step trees, depth cap 3, min-cost recursion over the recipe graph), sell the output on an
offer — off the NPC cap, combine's twin. Recipe data is the MIT `Campionnn/SkyShards` graph
(`fusion-data.json`, pinned at commit `0f14286`, 320/321 shards live; only `SHARD_RAINBUG` absent).
Its own quote (`FusionQuote`, min-cost tree solver + combine's one-sided ≥15-ask exit gate), its own
`FusionFlipStrategy`/`FusionJob`, `/flip fusion` (+ `stop`), a Fusion tab, and config
`fusionFlipsEnabled`/`fusionCrocodileLevel`. Re-measured live by the shipped strategy: **62 outputs
clear, 43 single-step**, top ~1.30M net per output (Queen Snake). Read `docs/fusion-flipping.md`
before touching it. Every figure is offline; nothing fused and sold in play yet, so it joins the
play-verification queue below.

**The auction-sniper audit is reconciled onto the overlay line** (branch `auction-sniper-merge`,
2026-09-03). It merges `auction-sniper-audit` (the gemstone `slots` bit, the runtime suspect guard,
the realized-P&L and gate-reconciliation backtests) into `auction-overlay`, so one line now carries
craft/combine/fusion, the overlay rework, recovery alerts, the LGPLv3 license, `exactMinDiscount`
0.12, the Wither-blade `ability_scroll` key, **and** the gemstone bit + suspect guard. Both signature
terms are in `DecodedItem.signature()`; `UnreadAttributeProbeTest` is GREEN with both read. `build`
and the opt-in tape backtests pass offline. **Neither `auction-sniper-merge` nor its sources are on
`main`** — the merge to `main` is the user's call and nothing is pushed. The old
`auction-sniper-audit` and `auction-overlay` branches are kept until that lands.

## What is next

**Auction/recovery discovery audit (2026-09-02).** A six-hour holdout from the current local sales
tape exposed a false-negative in the auction sniper's cheap gate. Discounting every priceable exact
configuration by 20%, the display-family median admitted 28,115 of 30,670 (91.7%); 2,555 synthetic
exact-value bargains never reached the decoder because upgraded configurations can be worth far
more than their family median. Recovery was already decoding most of those families. The composed
sweep now runs ordinary exact valuation whenever recovery has already earned the decode, and sends
ordinary-decoded blobs to recovery even when recovery's family gate missed them. This changes no
signature, value, confidence, fee, profit, or liquidity rule and starts no second sweep.

The same holdout contained 699 sales with recovery metadata. The rarity-specific prefilter admitted
620 (88.7%); admitting a known display name across rarity changes raises that to 670 (95.9%). Exact
clean-host and component gates still fail closed after decoding. Only 112 of 699 (16.0%) had an
exact clean-host comparison. That is the remaining large coverage limit, but a coarse host fallback
would violate the recovery floor's evidence rule and was not added. The existing capture contains
no drill/rod removal menu, so those parts remain zero-credit. Focused compile checks, 18 targeted
JUnit tests, and all 735 non-socket core tests pass in the sandbox. The full Gradle build could not
start because the sandbox denies Gradle's cache locks and lock-coordination socket; 13 sync tests
were excluded because their local HTTP servers cannot bind in the same sandbox.

The urgent valuation-safety item — decode and key Wither-blade `ability_scroll` state — is done and
moved to the closed list below.

**Auction sniper profit improvements (2026-09-02 plan).** Three steps, each independent, each
measurable against the holdout before shipping. Steps 3-4 shipped; step 5 stands. The analysis, fee
math, validator corrections and rejected alternatives are in the conversation that produced this
plan (session `01FUETCQupia61S987thhXAV`); only the decisions survive here.

Step 3 — **lower the exact-gate discount threshold** — is **done**, but the tape corrected the
number. `snipeMinDiscount` (15%) had applied to both gates. The coarse gate needs that width because
name-and-rarity mixes configurations; the exact gate matches a full decoded signature, so it does
not. `exactMinDiscount` (default **0.12**) is a second margin that fires only after the exact gate
passes and only when the estimate is trusted: confidence > 0.80, samples >= 15, and the discount
larger than the market's own spread (`dispersion < exactMinDiscount`). The coarse gate is unchanged
and the cheap prune still never decodes a listing that fails it.

The plan proposed 0.05; the holdout said no. `ExactMarginBacktestTest` on 36,106 held-out BIN sales
(48h train, 6h holdout, each flag scored by whether buying at the listing and reselling at the
signature's post-split median clears `Fees.binRoundTripProfit`): the 15% baseline flagged 6,216
listings at a 26% false-positive rate. A 5% exact margin added 2,961 flags (+48%) but they lost to
fees 57% of the time and earned ~34k coins each against the baseline's ~253k. Sweeping margin against
dispersion, false-positive rate tracked the margin, not the dispersion cap: 5% ≈ 55%, 7% ≈ 50%,
10% ≈ 37%, and 12% ≈ 30%, matching the baseline. The reason is the model's own ~10% median error
(`ValuationWindowBacktestTest`, 0.106 median absolute log error) — a discount smaller than that is
inside the noise. 12% clears it; 5% does not. Robust at resale truth >= 8 sales. So the exact gate
can loosen from 15% to 12%, no further.

Step 4 — **supply counting from the existing sweep** — is **done**. `SupplyCounter`
(`core/valuation`) accumulates listing prices per coarse key (`itemName|rarity`) as each listing
passes `ActiveAuctionScan.offer()`, reading only the name, rarity and price already present, so it
decodes nothing and adds no request. After a sweep, `MarketPoller.logSupplySignals` logs each coarse
key that clears all three conditions: two or more listings below the name-and-rarity median,
`salesPerHour >= 0.2`, and a positive `Fees.binRoundTripProfit(cheapClusterMax, nextTier)` where
`nextTier` is the cheapest listing above the cheap cluster, or the median when the whole live supply
sits below it. Data-gathering only — nothing acts on a `SupplySignal`; it says which key is worth
decoding, not that an opportunity is settled. The counts stay coarse-keyed, so they mix
configurations (bare vs. 5-star); that approximation is accepted for logging, per the validator, and
the top 20 signals per sweep are logged with the rest as a count. Study the distribution in the logs
before step 5.

5. **Floor-sweep strategy (build only after step 4 data justifies it).** A new
   `AuctionFloorStrategy` beside `AuctionValueStrategy`. For items where step 4 shows 2-3 listings
   clustered below a gap to fair value, with fast turnover (`salesPerHour >= 0.2`, inventory days
   < 3) and tight market (dispersion < 0.20): present them as "buy these N listings, relist at X"
   rather than one-at-a-time snipes. Requires: multi-item position sizing against the capital cap,
   fee math for N relists, and a relist price set just under the next listing above the cheap
   cluster (not at the median). Guard: Derpy quadruples fees to ~12% round-trip, so floor sweeping
   is disabled while Derpy holds office. Guard: the player selects BIN listing duration (1h-48h) and
   the API does not expose it, so assume 24h for sell-probability estimates. Risk: new cheap listings
   appear while capital is parked; the listing fee is non-refundable.

   The "buy everything and relist higher" (true market cornering) was rejected: new supply appears
   constantly, manual clicking is too slow to corner, capital gets trapped, and the economics only
   work on items so illiquid that the capital is parked for days. Floor sweeping on 2-3 items is the
   viable subset.

Two older items stand, both the same shape: work finished offline that has never been seen in play.

1. **Play a craft flip, a combine flip, and a fusion flip.** Nothing in the three transformation
   strategies has been made and sold on Hypixel. What only play can answer for craft: whether a
   recipe's materials really fill on a resting order inside the horizon, whether the sell offer sheds
   at the rate `FillModel` predicts, and how much of the ranked list the player cannot craft because
   of unlocks the mod does not read. For combine: whether a source buy order at the bid fills at the
   predicted rate, whether a competitor parks under the sell offer the way the killed NPC drift
   premium was, whether the anvil is really coin-free, and whether the big net-per-combine whale rows
   (`VICIOUS_5` and the like) that the ≥15-ask gate admits are worth setting and forgetting or are
   manipulated. For fusion: whether `fuse_amount` and output-quantity from the reference tool match
   the game, whether a deep tree's intermediate inventory is bearable — **start with a single-step
   row** (Queen Snake, one click) before trusting depth — and whether the reptile double-output is
   right before anyone sets `fusionCrocodileLevel`.
2. **Get the shipped valuation work seen in a running client.** Pet levels, the fill model, the four
   signature splits and the Midas ratio quote are all offline-only. This is a long queue of
   unverified-in-game work, and it needs the built jar played on live Hypixel — the user's job, no
   dev client exists.

Not started, and deliberately: **checking unlocks against the player**, and **pricing an ingredient
as a craft of its own** (recursive inputs). Both are named in `docs/craft-flipping.md`; neither is
worth building before a craft flip has been run in play.

### Measured, and left alone on purpose: the displacement rate saturates

`FillStats` counts *intervals that contained a displacement*, never displacements, because it
compares consecutive samples - so at the ~5-minute bazaar tape cadence the number it can report is
capped at 12 an hour. On the tape of 2026-08-20 the busiest books sit on that ceiling: `SOULFLOW`
measures 11.01/h, `WHALE_BAIT` 10.38/h, and halving the sampling cadence roughly halves every one of
them (ratio 1.8-2.2 across the twelve busiest) while the per-interval hit fraction stays at ~0.91.
The measurement is saturated, and the estimator the model's own Poisson assumption implies -
`-ln(1-p)/t` rather than `p/t` - puts `SOULFLOW` at **28.6/h or more**. `FillModel` therefore credits
contested books with at least 2.6x the fill rate they really get, and those are exactly the books
that rank highest.

The fix that recovers the number is counting displacement at the 20-second poll cadence rather than
at the 5-minute tape cadence, which needs a small per-product counter rather than the trend ring (a
24-hour ring at 20s would be hundreds of megabytes). **It was measured and not done**, because
`NpcFlipStrategy` and `NpcReprice` size the daily driver off the same `FillModel`, and this would
change the basket and the reprice rounds - settled ground. It is the largest known correction to both
bazaar and craft ranking, and it lowers quoted profit rather than raising it.

Done and closed since the last write:

- **Rework the bazaar overlay into a per-type view.** Built on this branch: pick a flip type from a
  small auto-generated list, see that type's committed jobs + top candidates + instructions one type
  at a time, with the green box following the active type. `BazaarStep` generalized off
  `NpcWorklist.Task` via `BazaarAction`; fixed geometry and constant font. Plan and hooks in
  `docs/bazaar-overlay-rework.md`. Design settled by grill 2026-08-28.
- **Decode and key Wither-blade `ability_scroll`** (was the urgent valuation-safety item). Built on
  this branch: scrolled and unscrolled Hyperions no longer pool under one signature, containing the
  reproduced 2.15x unscrolled overvaluation. Release gates added, and the rolling gate is skipped on
  the pre-incident tape. Keep the `UnreadAttributeProbeTest` 100M alarm as the release check for the
  next invisible upgrade. Full record in `docs/auction-sniper-scroll-safety.md`.
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
  is the wrong instrument; the trust-gated exact margin is right.** So the floor stays at 0.15 and
  codex's 0.12 exact gate ships beside it (reconciled onto the overlay line 2026-09-03). The audit's
  old open #3 — `UnreadAttributeProbeTest` RED on `ability_scroll` (Hyperion) — is done: `ability_scroll`
  is decoded and keyed on the overlay line (`docs/auction-sniper-scroll-safety.md`), so it is no longer
  open. See `SnipeGateReconcileBacktestTest`, `AuctionValueStrategyTest`, and
  `docs/handoff-auction-sniper.md`.

The signature-gap seam is closed for top-level attributes — including Wither-blade `ability_scroll`,
decoded and keyed on the overlay line — and now for a nested one too: the harm probe ranked only
top-level `ExtraAttributes` keys, so it was blind to `unlocked_slots` hidden inside the `gems`
compound — a real ~60M gemstone-slot gap that surfaced in play 2026-09-02, not on the probe. Both
probes now split that nested key out, and the gemstone slot bit shipped (above). With both read the
harm probe's top entry is again `eman_kills` at 45.5M coins, a counter, and everything below it is a
counter or a per-item identifier. `UnreadAttributeProbeTest` asserts the top stays under 100M — the
alarm for a Skyblock update adding a new invisible upgrade, not a to-do list. Do not start another
attribute branch without a fresh probe run above that line, and check for a newly nested attribute the
same way this one was missed.

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
- **A buy order is posted at the plain "+0.1" price and never above it.** Paying the chase into the
  posted price (`npcDriftPremium`) shipped 2026-08-14 and was removed 2026-08-19. Tape said it held
  the top of the book 96.7% of a window; in play it held 3%, because a competitor parks a coin or two
  above your specific order whatever you paid — and every tape sample came from a book with none of
  your orders in it. It also charged the wrong number: `chaseCostRatio` sums every upward tick, and a
  price posted once only has to beat the window's running maximum (202 median against 3,704 charged
  on `ENCHANTED_ANCIENT_CLAW`). `/flip npc probe` stays as the experiment that would have to produce
  new evidence first. See `docs/npc-flipping.md`, "Removed: paying the chase up front".

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
- **AH → BZ arbitrage is dead by game rule.** The two venues trade disjoint item sets.
- **Craft flips have no recipe source in the API, and that is answered.** The source is the NEU item
  dump, MIT licensed, imported offline by `core/recipe/NeuRecipeImporter` and shipped as a table
  `RecipeBook` reads. Measured worth: 35-47 profitable recipes on every one of 13 tape days, a
  median 5.4M/hour on ~75M of capital at a 5% flow share, without touching the 500M NPC cap. The
  exit is always a **resting sell offer** — never a dump (worth ~10x less) and never an NPC sale,
  which spends the cap the daily driver needs. **Order slots are the constraint, not coins**: the
  best eight plans wanted 19 of 21, so one plan is capped at `craftMaxOrderSlots` and re-quoted on
  the instant route rather than dropped, and counted off the real orders (`Stacking.orderSplit`)
  rather than one per ingredient — 58,624 units of an unstackable material is 229 orders, not one.
  A recipe is offered only while its **margin drift** (output drift less the cost-weighted drift of
  its materials) is holding. Do not re-open the "no recipe source" line — read
  `docs/craft-flipping.md`.
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
