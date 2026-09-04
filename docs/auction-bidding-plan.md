# Auction-house bidding (timed auctions)

Status: **Phase 0a measured (2026-09-03). Surplus is real and robust out-of-sample. Reachability is
the one open question, and it gates the build.** This is the "wait for the auction to end" play — real
bids on timed listings, not buy-it-now.

## Verdict

The shipped auction strategy (`AuctionValueStrategy`) snipes **BIN** listings: an instant buy below the
item's measured value. This asks a different question — can the mod advise the player to **bid** on
timed auctions and win them below value?

Two earlier fears are now settled, one by research and one by measurement:

1. **Mechanism (research).** Hypixel has a **2-minute anti-snipe timer**: a bid under 2 minutes left
   resets the clock to 2 minutes. So a timed auction is not a sub-second reflex race — it is a bidding
   round that re-opens for 2 minutes after every late bid. A human on stale data can play it, and the
   mod never needs to click. The idea is a good fit for an advisory mod. (Sources at the end.)
2. **Surplus (measurement).** `AuctionBidProfitBacktestTest`, run on 8 days of the user's tape, shows
   timed auctions **do** systematically clear below BIN value, and — the surprise — they resell
   **closer to quote than BIN snipes do**. The edge is real on the tape.

**What is NOT settled, and what the whole build now hinges on: reachability.** The tape records the
*final* price of each timed auction, not its bid history. So it can say "703 timed auctions a day
ended at snipe-worthy prices," but not "you could have won them." You would be competing for those
same auctions with whoever actually won them — often a bot, because auction sniping is bot-heavy for
exactly this surplus. Reachability needs new collection (Phase 0b) and is almost certainly where most
of the paper edge evaporates.

**So: the edge is real, its reachability is unproven. Build Phase 0b (bid-dynamics collection) next;
build the strategy only if 0b shows the surplus is actually winnable.** Temper the coin figures with
the project's rule that **clicks and session count are the real budget** — 164 trustworthy flips a day
is not clickable, so real capture is a fraction of the paper number, and each flip is far more
session-time than a BIN snipe.

## Phase 0a results (the measurement)

`src/test/java/jeff/skyblockflipper/core/valuation/AuctionBidProfitBacktestTest.java`, opt-in:

```
./gradlew test -PtapeBacktest -PtapeDir="<.minecraft>/config/skyblock-flipper/tape" \
    --tests '*AuctionBidProfitBacktestTest'
```

Method mirrors `SnipeProfitBacktestTest`, including its central rule — **resell at a price real BIN
sellers got, never at the model's own quote.** Train the shipped `FairValueModel` (`Keying.PRODUCTION`,
2-day window) on BIN sales before a 48h cutoff. For each held-out **non-BIN** sale (a timed auction
that ended at its winning bid): the *gate* sees only the model quote (discount, quoted profit — all the
strategy could know at bid time); the *truth* resells at the median of the **held-out BIN sales** of
the same signature (out of sample). Run on 2026-08-27 → 09-03 (8 days, ~1.25M sales):

| Cut | Flags/day | Quote survives | Loss-rate | Median realized/flip |
|---|---|---|---|---|
| Shipped sniper gate (≥15%, conf≥0.6, ≥50k) | 703 | 88% | 0.9% | 187k |
| **Trustworthy** (EXACT, conf>0.80, 15–40% off) | 164 | 90% | 3.7% | 243k |

Supporting facts:

- **Market size:** timed auctions are **1.89%** of AH sales (23,675 of 1,254,039 over 8 days,
  ~2,960/day). Small but not negligible.
- **Surplus:** of priceable timed sales, **74.9% ended below BIN value, 68% at ≥15% under, median 60%
  under.** Most surplus (2,796 of 3,911) is in **liquid** signatures (>1 BIN sale/hour), not the
  illiquid tail.
- **Coverage:** 3,911 of 5,119 held-out timed sales were priceable; **1,208 (23.6%) were unpriceable —
  no liquid BIN market at all.** That fraction is the item-level adverse-selection floor: those items
  are auctioned *because* they do not BIN.
- **Timed resells better than BIN snipes.** 88–90% of the quote survives to the out-of-sample resale,
  against the BIN sniper's ~58% (roadmap, `SnipeProfitBacktestTest`) and its 15% loss-rate. Reason:
  a timed underprice is a genuine "nobody bid," not a listing that is cheap because something is wrong.
- **The deep tail is miss-prone.** 66% of snipe-worthy flags are ≥60% under value — the band where a
  signature miss mints a wrong-high quote (the gemstone-slot and Hyperion-scroll precedents). The
  trustworthy row above deliberately excludes it; the suspect deep-discount guard in
  `AuctionValueStrategy` would demote it in production.
- **Value spread (snipe-worthy, realized):** <100k → 59k (n=231); 100k–1M → 182k (n=880); 1M–10M → 572k
  (n=273); >10M → 4.0M (n=22). Not all dust; the meat is 100k–1M, with a real 1M–10M tail.
- **Derpy:** 85% of the trustworthy subset still clears the floor under Derpy's ×4 fees; disable the
  rest while Derpy holds office.

Two things the measurement is blind to, by construction:

- **Reachability.** Ended data has no bid history. → Phase 0b.
- **Signature misses.** The out-of-sample median pools the same blind sales the quote did, so a term the
  model does not read fools both. The trustworthy subset minimises this; it does not remove it.

## Mechanics (researched)

- **Anti-snipe timer.** A bid with under 2 minutes left sets the clock back to 2 minutes. Auctions end
  only after 2 full minutes with no bid. Reflex sniping does not work; wars are human-playable.
- **Minimum bid increment: 2.5%.** Next bid = `starting_bid`, or `ceil(highest_bid_amount * 1.025)`.
  **This is computable, so the mod can state an exact "bid up to X, no higher" ceiling** — the single
  most useful thing it can do here.
- **Durations: 1 hour to 2 days preset, custom to 336 hours (14 days).** `end` is exposed per listing.
  Only ending-soon auctions are flips; a 14-day auction is not.
- **Escrow.** A bid is taken from the purse immediately and refunded if you are outbid. Capital is
  locked while you lead.
- **Fees.** The flipper's round trip is a won bid (you pay exactly the bid) then a BIN relist, already
  `Fees.binRoundTripProfit(winningBid, resale)`. Derpy ×4.
- **Documented method.** The wiki's flipping tutorial: "Sort by Ending Soon, bid below the lowest BIN,"
  win a fraction, relist; underbid by no more than 5–10%. A known, human-run play.

## How the anti-snipe timer cuts both ways

- **For the player (good):** no reflex race; the mod surfaces the auction and the player bids on live
  in-game state with 2-minute reaction rounds. Stale data is survivable.
- **For the margin (bad):** the same timer bids contested items **up to fair value** — an English
  auction with reaction time lands near the second-highest bidder's max. So surplus survives only in
  **uncontested** auctions (nobody else bids; win at the starting bid) or **under-known** ones (others
  stop below the true value). Both skew toward items others ignore — which is where liquidity is worst,
  and where the 23.6%-unpriceable floor comes from.

## Everything to consider

Grouped by the question each factor answers. Each notes whether the mod can see it.

### A. Can the mod see the auction's state?

- `end`, `starting_bid`, `highest_bid_amount`: exposed (view up to 60s stale; the 2-min timer makes
  that survivable).
- Contested-or-not: cheap proxy — `highest_bid_amount == 0` or `== starting_bid`.
- Bid count (`bids[]`): exposed but expensive (the 1.5 MB-per-page bulk `AuctionsDto` drops). The proxy
  avoids needing it.
- The final seconds are not the problem they are without an anti-snipe timer; the final *minute band*
  should still not be surfaced without a live in-game check.

### B. Is this bid a good deal? (valuation — reuse the sniper)

- `FairValueModel` + `DecodedItem.signature()` unchanged. Resale truth is the **BIN** median, never the
  timed-sale price.
- Margin vs. the bid to win (`starting_bid`, or `highest_bid × 1.025`).
- The sniper's gates: `minConfidence`, the `exactMinDiscount` trust gate, and the suspect deep-discount
  guard — all apply unchanged. The measurement shows why the last matters: 66% of raw flags are in the
  miss-prone deep band.

### C. Will I win it? (competition and timing)

- Bidding war closes the margin (the anti-snipe guarantees it); target uncontested/under-known.
- **The bid ceiling** (from the 2.5% increment): the mod computes the highest bid that still clears the
  profit floor and says "bid up to X, then walk away." Enforcing this is the mod's core value here.
- Bots: the clean discounts are the most bot-contested; this is the reachability risk in one line.
- Presence through the final rounds; escrow across several simultaneous bids.

### D. If I win, can I get out? (resale and liquidity)

- Rank on profit/hour ÷ liquidity. Most measured surplus is in liquid items — good — but 23.6% of timed
  items have no BIN market, and those must be dropped.
- `Fees.binRoundTripProfit` models the round trip; Derpy ×4.

### E. What does it cost beyond coins? (the real budget)

- **Clicks and session-time — the main structural cost.** 164 trustworthy flips a day is not clickable;
  each is search + several bids + outbids + 2-minute rounds + claim + relist. Real capture is a small
  fraction of the paper 164/day, at high click cost. Price hauling and trip/click count beside any coin
  figure.
- Escrowed-capital opportunity cost; the wasted-wait risk (outbid at the end → nothing but refunds).

### F. Whole-strategy economics

- Ranking: profit/hour ÷ liquidity. Position sizing: `maxCapitalPerFlip`, plus a cap on total escrow
  across simultaneous leading bids. Derpy guard: disable while Derpy holds office.

## Traps (read before building)

1. **Reachability illusion — the headline trap.** "703 ended cheap" is not "703 winnable." You compete
   for the same auctions with the actual winner, usually a bot. Phase 0b measures this; do not size a
   build off the 0a paper number.
2. **Signature-miss inflation.** 66% of the raw signal is suspect-deep, where misses hide even at EXACT
   basis (gemstone-slot was EXACT). Trust only the miss-controlled subset (0.15–0.40, EXACT, conf>0.80),
   and keep the suspect guard.
3. **Resell-at-quote flattery — the methodology trap.** The realized leg MUST resell at an out-of-sample
   BIN median, never the model's own quote. `AuctionBidProfitBacktestTest` does this; any refinement must
   keep it, or the profit figure is circular.
4. **Click-budget blindness.** Coins-positive can be net-negative in clicks. Always quote clicks/session
   beside coins for this strategy.
5. **Item-level adverse selection.** 23.6% of timed items have no BIN market — do not flip what you
   cannot resell.
6. **Escrow lock-up.** Bidding on several ties up capital on each you lead; cap total escrow.
7. **Derpy.** Fees ×4 drops 15% of the trustworthy subset below floor; disable, do not just widen margin.
8. **Over-fit to 48h/2-day.** Re-run 0a on the VM's 60-day tape and across mayors before trusting the
   rate; a single 48h window can catch an unusual market.
9. **Stale-data over-reach.** Never advise a bid inside the final-minute band on stale data without a
   live in-game check; the 2-min timer helps but is not a licence to bid blind.

## The plan

### Phase 0b — measure reachability (build next; needs new collection)

Active auctions are **not taped** today (only bazaar and ended-auctions are). Add a *lightweight* tape
of active **non-BIN** listings, sampled on the existing 60s auction sweep, and put it in the headless
collector so the VM gathers it 24/7.

- **Collect** per active non-BIN listing, once per sweep it appears in: `uuid`, `end`,
  `starting_bid`, `highest_bid_amount`, and the decoded `signature` (decode only non-BIN, a fraction of
  the house). No bid array, no lore. Append-only JSONL, its own file, its own retention.
- **Measure**, joining this trajectory tape to the ended-auctions tape already kept:
  1. Of timed auctions the model flags as undervalued (discount ≥ 0.15 vs the BIN median), what fraction
     **stay uncontested** (`highest_bid_amount` at or near `starting_bid`) to the last sample before
     `end`? Those are reachable by presence.
  2. For contested ones, how many 2.5% steps, and does the final price land above or below fair value?
  3. What is the **reachable** flags/day — flagged-undervalued AND ending uncontested (or under-known) —
     versus the 0a paper 164–703/day?
- **Decision gate:** build Phase 1 only if a meaningful share (target ≥ 20–30%) of flagged-undervalued
  timed auctions end reachably cheap, at a per-day rate and per-flip value worth the clicks. If the
  surplus is always bid away before `end`, stop here — with a number, like the drift premium was.

### Phase 1 — the strategy (build only if 0b passes)

# Goal
Advise the player which timed auctions to bid on, up to an exact ceiling, and win them below BIN value.

# Relevant code
- `AuctionsDto.Auction` — add `end` (long) and `highest_bid_amount` (long); add `auctionListings()`
  returning active non-BIN listings. **Do not change `binListings()`.**
- `AuctionValueStrategy` — the twin to copy: gates, suspect guard, risk/step text, `FlipCandidate`
  shape.
- `FairValueModel`, `ValueEstimate`, `Fees.binRoundTripProfit` — reused unchanged.
- `StrategyEngine`, `StrategyKind` — add `AUCTION_BID`.
- `AuctionOverlay`, HUD, `FlipScreen`, `FlipCommand` — render it as an ordinary `FlipCandidate`, no new
  surface (see `docs/auction-overlay-plan.md`).

# Implementation plan
1. Extend the DTO and add `auctionListings()`; the sweep already downloads the page, so this decodes
   only non-BIN blobs.
2. `AuctionBidStrategy`: for each active non-BIN listing ending within a band (e.g. 2–120 min), decode,
   `FairValueModel.valueOf`, compute the bid to win (`starting_bid`, or `highest_bid × 1.025`), discount
   and profit against the BIN median. Apply the sniper's confidence / `exactMinDiscount` / suspect
   gates. Prefer uncontested. Drop dust, illiquid signatures, and anything ending inside the final
   minute.
3. **The bid ceiling.** Compute the highest bid still clearing `minProfitPerFlip` after fees; put it on
   the candidate as "bid up to X, no higher." Steps: search → verify the item → bid up to X → wait →
   claim → relist at the BIN median.
4. Risks on the candidate (as the sniper states "gone within seconds"): "you may be outbid at the end",
   "coins are held while you lead", "a war ratchets +2.5% per bid — stop at the ceiling", "the shown
   price may be up to a minute old".
5. Rank on profit/hour ÷ liquidity; Derpy guard; cap total escrow across leading bids.
6. Wire into the overlay/HUD/screen/command as `AUCTION_BID`.

# Invariants
- Never automate the bid. Never advise above the ceiling. Never resell at the model's own quote in any
  measurement. Keep `binListings()` and the BIN-only valuation training untouched.

# Edge cases
- Uncontested = `highest_bid_amount` 0 or == `starting_bid` → bid to win is `starting_bid`.
- Ending beyond the band, or a 14-day auction → suppress.
- Stack sales → per-unit.
- Derpy → disable.
- Signature miss → suspect guard demotes.

# Verification
- `AuctionBidProfitBacktestTest` stays green as a regression, re-run on the 60-day tape.
- `AuctionBidStrategyTest` mirrors `AuctionValueStrategyTest`.
- `./gradlew build` and the offline suite; `collectorJar` still builds (no `net.minecraft` in `core`).

# Stretch (not first cut)
Track which auctions the player leads / is outbid on, from chat + the auction menu, the way
`TrackedOrder` tracks bazaar orders. Useful, but not needed to prove the strategy.

## Things not to change / rejected shapes

- **Do not remove the BIN-only filter from valuation training** (`FairValueModel:130`). Timed-sale
  prices are what is being measured, never a training input.
- **Do not automate the bid.** The 2-minute anti-snipe timer is exactly why the mod does not need to.
- **Do not build Phase 1 before Phase 0b produces a reachability number.** The 0a surplus is real but
  its reachability is unproven, and every strategy here was gated on a measurement.
- **Do not size anything off the 0a paper flags/day.** That is what ended cheap, not what you can win.

## Sources

- Hypixel Skyblock wiki — [Auction House](https://hypixelskyblock.minecraft.wiki/w/Auction_House)
  (anti-snipe 2-minute reset; 2.5% increment; durations to 336h; escrow and refund; fees).
- Hypixel Skyblock wiki — [Tutorial:AH Flipping](https://hypixelskyblock.minecraft.wiki/w/Tutorial:AH_Flipping)
  (ending-soon bid-below-BIN method; 5–10% underbid guidance).
- Hypixel Forums, e.g. [Bidding last second](https://hypixel.net/threads/bidding-last-second.2435496/) —
  corroborate the 2-minute anti-snipe reset in practice.
- The measurement: `AuctionBidProfitBacktestTest`, run 2026-09-03 on the local 8-day tape.
