# Phase 1 bid strategy — change record for review

**Branch:** `auction-bidding-investigation`
**Commits:** `ef90b81` (build) and `9bc905e` (roadmap note), on top of `9c65013`.
**Status:** built, **ships off by default** (`auctionBidEnabled = false`). Unverified in play; the
reachability verdict (Phase 0b) that should gate this build has **not** returned yet.

> Context the reviewer must weigh: this was built *ahead of* its own decision gate. The plan
> (`docs/auction-bidding-plan.md`) says build Phase 1 only if Phase 0b shows ≥20–30% of flagged timed
> auctions end reachably cheap. That number does not exist yet — the collection has run ~1 day. So
> the central question is not only "is the code correct" but "should this exist at all yet, and is
> off-by-default a sufficient safeguard."

## What it does

Adds a sixth-and-a-half strategy: advise **bidding** on timed (non-BIN) auctions ending soon that
price below their item's **BIN** value, and state an exact "bid up to X, no higher" ceiling from
Hypixel's 2.5% minimum-increment rule. It is `AuctionValueStrategy`'s twin — same valuation edge,
same resale truth (BIN median), same gates — differing only in that the item sits behind an auction
the player must win rather than a buy-it-now they take instantly.

Two behaviours are the whole point of the class:
- **Bid ceiling.** `binNetProceeds(resale) − minProfitPerFlip`. Exact (not fitted) because fees fall
  only on the resale leg and the bid is paid exactly.
- **Drops contested auctions.** Once a rival has bid, the anti-snipe timer ratchets the price toward
  fair value, so the margin is already gone. Only auctions still at their opening price survive.

## Files changed

### New — core valuation
- **`core/valuation/Bids.java`** (new) — the 2.5% increment arithmetic. `nextBid(startingBid,
  highestBid)` = `startingBid` when uncontested, else `ceil(highestBid × 1.025)`.
- **`core/valuation/PricedBid.java`** (new) — the timed twin of `PricedListing`. Wraps a
  `TimedListing` + `DecodedItem` + `ValueEstimate`. Derived: `bidToWin()`, `contested()`,
  `hoursLeft(now)`, `discount()` (vs BIN median, using `bidToWin`).
- **`core/valuation/UnderpricedTimedScan.java`** (new) — the timed twin of `UnderpricedScan`,
  implements `TimedListingSink`. For each timed listing within the window of `end`, decodes, prices
  the **bid-to-win** against the BIN median, keeps it if it clears the same coarse/exact discount
  gates the BIN sniper uses (coarse floor + a tighter margin for a trusted exact estimate). Unlike
  the BIN scan it decodes every ending-soon listing (a timed listing exposes no name to prune on),
  affordable because the window narrows the population.

### New — core strategy
- **`core/strategy/AuctionBidStrategy.java`** (new) — the strategy. Gates: skip contested; skip over
  `maxCapitalPerFlip`; `minConfidence` floor; `minProfitPerFlip` floor on
  `binRoundTripProfit(bidToWin, resale)`. Computes the bid ceiling. Carries the suspect
  deep-discount guard verbatim (`≥60% discount on a ≥25M item` → quarantined via `asSuspect()`).
  Warns inside the final 2 minutes. Ranks on `net / hoursToSell` like the sniper. Takes an
  injectable `Supplier<Instant>` clock for tests (default `Instant::now`).

### Modified — enum + engine
- **`core/strategy/StrategyKind.java`** — added `AUCTION_BID("Bid", "valuation", false)`.
- **`core/strategy/StrategyEngine.java`** — registered `new AuctionBidStrategy()` in `withDefaults()`.

### Modified — context threading
- **`core/strategy/StrategyContext.java`** — added `List<PricedBid> pricedBids` as the last record
  component (null-defaulted in the compact constructor), a `withPricedBids(...)` wither, and a
  convenience constructor for the previous 15-arg canonical shape so existing callers still compile.
- **`client/CandidateFeed.java`** — appends `.withPricedBids(data.pricedBids())` to the context it
  builds.

### Modified — market data + poller wiring
- **`core/api/MarketData.java`** — added an `AtomicReference<List<PricedBid>>` with `pricedBids()`
  getter and `setPricedBids(...)` setter.
- **`core/api/MarketPoller.java`** — builds an `UnderpricedTimedScan` when `auctionBidEnabled`,
  composes it with the existing tape collector into one `TimedListingSink` (`composeTimed`, so the
  one sweep feeds both with no extra request), and calls `data.setPricedBids(...)` after the sweep.

### Modified — config
- **`core/config/FlipperConfig.java`** — new fields `auctionBidEnabled` (default false),
  `bidWindowHours` (default 3, int); clamp `1..48` in `validated()`; threaded into `scanSettings()`.
- **`core/config/ScanSettings.java`** — added `auctionBidEnabled` + `bidWindowHours` components;
  updated the compat constructor.
- **`core/config/ConfigSchema.java`** — two entries (a `Flag` and an `IntRange`) so
  `ConfigSchemaTest` passes.

### Modified — ledger
- **`core/ledger/Ledger.java`** — `netProceedsPerUnit` switch: `AUCTION_BID` resells on a BIN, so it
  shares the `AUCTION_VALUE` case (`binNetProceeds`). This was a *required* change — adding the enum
  made the exhaustive switch fail to compile.

### New — tests
- **`AuctionBidStrategyTest.java`** — 6 tests: surfaces an uncontested cheap auction with its exact
  ceiling; drops contested; warns in the final minutes; quarantines a deep discount on a dear item;
  skips a thin discount below the profit floor; applies the confidence floor.
- **`UnderpricedTimedScanTest.java`** — 3 tests: `Bids.nextBid` uncontested and contested; the
  ending-soon window filter (keeps a soon listing, drops a far one).

## What is NOT built (deliberately)
- The `AuctionOverlay` (in-menu panel beside Hypixel's auction house) does **not** list bid rows.
  Candidates reach `/flip`, the HUD and the flip screen only.
- No dedicated `/flip bid` subcommand; the `snipe`-style shortcut was not added.
- Escrow / simultaneous-bid capital is **not** modelled. Each bid is judged as one indivisible
  position, exactly as the BIN sniper judges one buy.

## Verification performed
- `./gradlew build` (compile + full offline JUnit suite) — pass on JDK 25.
- `./gradlew collectorJar` — pass (confirms `core` stays Minecraft-free).
- New tests pass. **No live/in-game verification** (no dev client; that is the user's job).

## Things a reviewer should specifically doubt
1. **Should this exist yet?** It front-runs its own reachability gate. Off-by-default is the only
   safeguard.
2. **The `PricedBid.discount()` and the scan's gate use `bidToWin`, not the current price.** Is that
   the right quantity to gate on, and is it consistent everywhere?
3. **Ranking on `hoursToSell` alone** ignores the wait until the auction ends and the escrow lock.
   Is the profit/hour number honest?
4. **`bidWindowHours` as an `int`** (1–48) vs the plan's stated 0.25–48. Was fidelity lost?
5. **Contested proxy** = `highestBid > startingBid`. A listing that opened with a first bid equal to
   the start reads as uncontested — is that exploitable or wrong?
6. **The `StrategyContext` wither + extra convenience constructor** — did this actually leave every
   existing caller and test semantically unchanged, or does some path now silently pass empty bids
   where it should not?
7. **`composeTimed` decodes each ending-soon listing twice** (once per sink) when both the tape and
   the bid scan are on. On the VM only the tape runs; on a client only the bid scan. Is the "no
   extra request" claim the same as "no extra decode," and does it matter?
8. **Suspect-guard thresholds** (0.60 / 25M) are copied from `AuctionValueStrategy` — are they right
   for a *bid* where the whole point is winning uncontested deep discounts?

## Review fixes applied (2026-09-05)

The review of the baseline above confirmed one price bug and three capital/liveness gaps. The fixes
below landed as follow-up commits on the same branch. The strategy stays **off by default**; these
make it correct while it waits for the Phase 0b gate.

- **The "drops contested" claim above is imprecise.** "Only auctions still at their opening price
  survive" reads `highest_bid_amount == starting_bid` as no bids. It is not: `hb == starting_bid`
  means **one rival has already bid at the floor** (any second bid pushes it above). The reviewed
  build read that state as uncontested and advised a **tying bid Hypixel rejects**. Now `Bids.nextBid`
  splits three cases: `hb == 0` → bid `startingBid`; `hb == startingBid` → bid `ceil(start × 1.025)`
  (surfaced, winnable one step up); `hb > startingBid` → dropped as contested **at the scan**
  (`UnderpricedTimedScan`), so the ending-soon window and the `MAX_RESULTS` cap hold only winnable
  rows. `AuctionBidStrategy.contested()` is now a defensive double-check, not the primary gate.
- **The bid ceiling is now capped at `maxCapitalPerFlip`.** It is
  `min(binNetProceeds(resale) − minProfitPerFlip, maxCapitalPerFlip)`, so the mod never advises
  bidding past the player's own per-flip limit if a war starts.
- **Already-ended listings are dropped at the scan.** `end <= now` is un-biddable noise. Final-2-min
  listings still surface, with the live-check warning (the anti-snipe timer makes that band
  human-playable).
- **The per-hour figure is documented as optimistic.** It keeps `net / hoursToSell` — the resale
  liquidity after the auction ends — and does not fold in the wait until `end`. Folding the wait in
  would float about-to-expire rows to the top of the Bid view; the optimism is stated on the
  candidate instead.
- **Test counts.** `AuctionBidStrategyTest` is now 9 tests (adds one-bidder-at-floor surfaces at
  `ceil(start × 1.025)`; ceiling capped at `maxCapitalPerFlip`; ceiling with a non-zero profit floor
  equals `binNetProceeds − floor`). `UnderpricedTimedScanTest` is now 6 tests (adds `hb > startingBid`
  dropped by the scan; already-ended dropped; the `nextBid(6M, 6M)` assertion now expects
  `6_150_000`, not `6M`).
