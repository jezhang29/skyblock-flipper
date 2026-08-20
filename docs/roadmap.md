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

## Current state (2026-08-20)

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

`BazaarSpreadStrategy` came out of the same audit sound: ranking on profit per hour is right (a
greedy portfolio by that axis spends the whole 400M bankroll on 7 flips for 19.2M/h, against 5.5M/h
ranking by profit per coin), capital binds where slots do not (6 of 130 plans), and the only defect
was a quantity that no order box would take - now split through `Stacking.orderSplit` on both legs.

Everything the valuation side ships (pet levels, the fill model, the rune/potion/dungeon/dye/
ethermerge signature splits, the Midas ratio quote) is verified offline only. Getting it seen in a
running client is still the largest unverified-work risk in the project, and craft flipping has now
joined that queue.

## What is next

Two items stand, and both are the same shape: work finished offline that has never been seen in play.

1. **Play a craft flip.** Nothing in the craft strategy has been crafted and sold on Hypixel. What
   only play can answer: whether a recipe's materials really fill on a resting order inside the
   horizon, whether the sell offer sheds at the rate `FillModel` predicts, and how much of the
   ranked list the player cannot craft at all because of unlocks the mod does not read.
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

Nothing else is queued beyond those two. The signature-gap seam is closed and measured closed: the harm probe's top
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
  market on one key until split by their in-blob identity. The seam is now closed.
- **Six attributes measured out and must not be re-opened:** raw `color`, `power_ability_scroll`, the
  drill parts, `tuned_transmission`, `baseStatBoostPercentage` as a number (it is a `maxed` flag),
  and `dungeon_item`. Each is huge at the bare item id and flat at the production signature.
- **Three shipped:** `dye_item`, `ethermerge`, and `winning_bid` (as a price-to-bid ratio quote, not
  a key term). Pet level, dungeon `item_tier` and Kuudra `attributes` also ship.
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
