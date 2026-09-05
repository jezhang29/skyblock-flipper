# Phase 1 bid-strategy — review fixes

**Branch:** `auction-bidding-investigation`
**On top of:** `ef90b81` (build) + `a5b207d` (change record). Lands as follow-up commits, not an amend,
so the reviewed baseline and the fixes stay a legible before/after.
**Status:** planned. Stays **off by default** (`auctionBidEnabled = false`); no bid advice ships until
the Phase 0b reachability number clears its gate. These fixes only make the code *correct while it
waits*.

## Why this exists

A review of `ef90b81` (verified against the live Hypixel auctions API) found one confirmed
price bug and three smaller capital/liveness gaps. The bug matters because the day the 0b gate lets
the switch turn on, the strategy must not quote a losing bid. Every decision below was settled in a
grill; the rationale is recorded so it is not re-litigated.

### The bug, in one line

The live API confirms: `highest_bid_amount == 0` means **no bids**; `highest_bid_amount ==
starting_bid` means **exactly one rival has already bid at the floor** (any second bid pushes it
above). On page 0, 16 of 99 timed auctions were in that one-bidder-at-floor state. The build read
both as "uncontested" and told the player to bid the starting bid — a **tying bid Hypixel rejects**.

## Decisions (from the review grill, 2026-09-05)

| # | Issue | Decision | Why |
|---|---|---|---|
| 1 | One bidder at floor (`hb==start`) | Surface it, at `ceil(start × 1.025)` | Provably one bidder; still winnable one step up. Fix is in `Bids.nextBid`; `contested()` stays `hb>start` |
| 1b | `hb>start` bucket | Drop as contested, **at the scan** | Can't tell a lone above-floor bidder from a war; measured surplus lives only in un-bid/under-known auctions. Dropping at the scan keeps the 200-result cap holding only winnable rows |
| 2 | Inflated profit/hour | **Document only** — keep `net/hoursToSell` | Within the Bid view it still sorts by resale liquidity; folding in the wait would float risky about-to-expire auctions to the top. State the optimism on the candidate |
| 3 | "Bid up to X" ceiling | Cap at `maxCapitalPerFlip` | Safety: the mod must never advise bidding past the player's own per-flip limit if a war starts |
| 4 | End-of-life auctions | Drop already-ended; **keep** final-2-min with the live-check warning | Ended = un-biddable noise. The anti-snipe timer makes the final 2 min human-playable; the warning is the live check the plan actually asks for (trap #9) |
| 6 | Double-decode when tape + scan both on | Leave, document the assumption | Never both-on in a real deployment (VM = tape only, client = scan only); sharing the decode adds coupling for zero real cost |

## Goal

Correct the bid-price bug and the capital/liveness gaps, so the strategy is safe the day the 0b gate
turns it on. It stays off by default; no behaviour ships early.

## Relevant code

- `core/valuation/Bids.java` — `nextBid` price bug (the core fix).
- `core/valuation/PricedBid.java` — `contested()` unchanged; `bidToWin()`/`discount()` inherit the
  `Bids` fix, so they need no edit.
- `core/valuation/UnderpricedTimedScan.java` — add an already-ended lower bound and a contested drop,
  so the ending-soon window and the `MAX_RESULTS` cap hold only winnable rows.
- `core/strategy/AuctionBidStrategy.java` — cap the ceiling; extend the escrow risk line; drop the
  dead `Math.max(hours, 0.05)`.
- `core/api/MarketPoller.java` — one comment on the both-on double-decode assumption.
- Tests: `UnderpricedTimedScanTest`, `AuctionBidStrategyTest`.
- Docs: `auction-bidding-phase1-changes.md`, `auction-bidding-plan.md`, `roadmap.md`.

## Implementation plan (checkpoints)

Each step compiles and passes tests on its own; commit `wip:` per step, squash to one code commit +
one doc commit at the end.

1. **`Bids.nextBid`** — `hb <= 0 → startingBid`; else `max(startingBid, ceil(hb × 1.025))`. Fixes the
   tying bid for `hb == start` and stays defensive against a malformed `hb < start`. Update the
   method javadoc (it currently states the buggy "or still equal to the opening bid" rule) and the
   one `UnderpricedTimedScanTest` assertion that pins `nextBid(6M,6M) == 6M`.
2. **`UnderpricedTimedScan.offer`** — store `nowMillis`; drop `end <= now` (already ended) and drop
   contested (`highestBidAmount > startingBid`) before adding to `found`. The scan now decides
   winnability + discount; the strategy's `contested()` becomes a defensive double-check.
3. **`AuctionBidStrategy`** — `ceiling = min(binNetProceeds(resale) − minProfitPerFlip,
   maxCapitalPerFlip)`; extend the escrow risk to state the per-hour figure ignores the wait until
   `end`; use `net / hours` (drop the dead `Math.max`, since `hoursToSell()` is already `>= 0.25`).
4. **Tests** — replace the `nextBid(6M,6M)==6M` assertion with `== 6_150_000`; add: one-bidder-at-floor
   surfaces at `ceil(start×1.025)`; `hb>start` dropped by the scan; already-ended dropped; ceiling
   capped at `maxCapitalPerFlip`; ceiling with a **non-zero** profit floor equals `binNetProceeds −
   floor`.
5. **Docs** — change record gains a "Review fixes applied" section correcting the "drops contested"
   claim; the plan corrects the mislabeled `sb=hb=6750` sample and the `hb==start` edge case; the
   roadmap notes the fixes landed.
6. **Commits** — `wip:` per step, squashed to one code commit + one doc commit on top of `ef90b81`.

## Invariants

- `auctionBidEnabled` stays **false**; no bid advice ships until 0b clears the gate.
- Resale truth stays the BIN median; valuation training untouched.
- `bidToWin` is always a legal winning bid — strictly above any standing bid.
- The advised ceiling never exceeds `maxCapitalPerFlip` and never drops below the opening bid.
- `core` stays Minecraft-free (`collectorJar` builds).

## Edge cases

- `hb == 0` → bid `startingBid`; `hb == startingBid` → bid `ceil × 1.025` (one rival at floor);
  `hb > startingBid` → dropped.
- `end <= now` (stale/ended) → dropped; final-2-min → surfaced with the live-check warning.
- Non-zero `minProfitPerFlip` → the ceiling subtracts it exactly.

## Verification

- `./gradlew build` and `./gradlew collectorJar` — pass.
- Updated `AuctionBidStrategyTest` and `UnderpricedTimedScanTest` — pass.
- `ConfigSchemaTest` — pass (no schema change, but it gates the build).

## Not in scope (deferred, unchanged)

Multi-lead escrow cap; `AuctionOverlay` bid rows; a `/flip bid` subcommand or a Bid tab;
`bidWindowHours` fractional window (stays `int`). All follow-ups, gated behind the 0b number like the
rest.
