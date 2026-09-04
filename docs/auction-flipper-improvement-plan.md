# Auction flipper: from price finder to profitable player workflow

Status: **proposal and measured audit, 2026-09-04**. This document describes the current BIN
sniper, the evidence for and against it, the work required to turn it into a dependable auction
flipping workflow, and the measurements that must gate each expansion. It does not authorize
automated buying or bidding.

The short verdict is:

> The auction valuation has a real edge, but the mod has not yet demonstrated that a player can
> capture that edge at a high rate. The highest-priority work is exact-listing handoff, live
> acquisition telemetry, and realized player-hour measurement—not a looser price gate.

This distinction matters. The current backtests answer “did qualifying auctions later look
profitable at a concurrent resale median?” They do not answer “did this client show the listing in
time, did the player buy it, how long did the whole interaction take, and what did the relist
actually return?” A large paper market and a large playable income are different claims.

## 1. Desired outcome and success measures

The goal is not to maximize the number of candidates. It is to maximize **safe realized auction
profit per minute of player attention**, subject to capital, resale time, and Hypixel's rules.

The finished auction path should be able to report, over a chosen period:

- candidates detected;
- candidates shown to the player;
- age of each candidate when shown;
- candidates still live when revalidated;
- purchase attempts and successful purchases;
- relists, expirations, and completed sales;
- quoted versus realized net profit;
- capital-hours consumed;
- active Auction House minutes and clicks/steps spent;
- realized coins per active Auction House hour; and
- loss rate, with results split by valuation basis, discount band, value band, liquidity, and age.

The primary success metric is:

```
realized auction coins / active player time spent working auction recommendations
```

Secondary metrics are:

```
still-live rate = candidates still available at revalidation / candidates revalidated
capture rate    = successful purchases / candidates the player attempted
quote capture   = realized net profit / quoted net profit on completed positions
capital return  = realized net profit / capital-hours committed
```

Candidate count and aggregate backtest profit are diagnostic metrics, not success metrics.

## 2. What exists now

### 2.1 Data and valuation

`MarketPoller` learns fair values from realized `auctions_ended` BIN sales. `FairValueModel` groups
sales by `DecodedItem.signature()`, using the full decoded configuration where sufficient evidence
exists. The shipped window is two days.

The model intentionally fails closed when there are too few comparable sales. It also tracks the
estimate's basis, sample count, dispersion, confidence, and observed sales rate. This is the right
foundation: active asking prices are not allowed to define fair value, and a shared item id is not
treated as a comparable configuration.

### 2.2 Active BIN discovery

About once per minute, the client walks the public active-auction endpoint page by page. Each BIN
first passes a cheap name-and-rarity comparison. Only a small subset earns an `item_bytes` decode.
After decoding, the listing must clear the exact value gate.

Every ordinary listing must first clear the 15% coarse gate unless the composed recovery scan has
already earned a decode for that blob. Once decoded, production accepts either:

- discount at least `snipeMinDiscount`, default 15%; or
- an estimate discounted by at least `exactMinDiscount`, default 12%, where confidence is greater
  than 0.80, samples are at least 15, and production requires dispersion below the exact margin—also
  0.12 by default. Although documentation calls this the trusted **EXACT** path, the current runtime
  helper does not explicitly require `ValueEstimate.Basis.EXACT`; that parity issue is covered in
  Problem I;
- configured confidence at least 0.60;
- quoted net profit at least 50k;
- purchase price within `bankroll * maxCapitalShare`, default 25%; and
- a valid fee-adjusted resale at the recent median.

A discount of at least 60% on an item valued at 25M or more is marked suspect and sorted below every
trusted candidate. That guard is a backstop for a hidden upgrade the signature may not yet read.

### 2.3 Player handoff

The current auction overlay shows up to five ranked candidates beside an auction menu. Clicking a
row copies the display name. The player must open search, paste the name, identify the correct
configuration and price, and buy it before somebody else does.

`ActiveListing` already contains the exact auction UUID, and `PricedListing` preserves it. The UUID
is lost when `AuctionValueStrategy` converts the listing to the generic `FlipCandidate`. Expansion
state is also keyed by item id, not listing UUID, so two live listings of the same item are not
distinct UI targets.

The overlay is built and offline-tested but is still recorded as unverified in real play.

### 2.4 Tracking

The ledger can represent an `AUCTION_VALUE` position, but the current local ledger contains no such
entries: 171 NPC entries, one craft entry, and zero auction-value entries. This is evidence that the
auction workflow has not produced an observed sample in this installation, not proof that it cannot.

There is no persistent active-BIN candidate tape. Consequently the project cannot reconstruct which
recommendations were shown, which remained available, or which disappeared before a human could act.

### 2.5 Adjacent auction work

Two expansions are partly investigated:

- **Timed bidding.** The value study is positive. Collection and a reachability backtest are built,
  but collection is off and no real trajectory tape exists locally yet. Phase 1 is correctly gated
  on reachability.
- **Floor sweeping.** The live sweep logs coarse name-and-rarity clusters, but no strategy consumes
  them. The logged clusters mix configurations and are evidence for further decoding only, not a
  buy signal.

## 3. Evidence from the current tape

The following was reproduced on 2026-09-04 against the current local sales tape with:

```
./gradlew test -PtapeBacktest \
  "-PtapeDir=/Users/jzhang/Library/Application Support/minecraft/config/skyblock-flipper/tape" \
  --tests "*SnipeProfitBacktestTest" \
  --tests "*SnipeGateReconcileBacktestTest"
```

Both tests passed.

### 3.1 Reconciliation policy as the test currently defines it

The reconciliation test's 12%-trusted-exact plus 15%-general policy produced this result over the
newest 24-hour holdout:

| Metric | Result |
|---|---:|
| Hypothetical flags | 17,748 |
| Resale-verifiable flags | 17,215 |
| Loss rate | 18.5% |
| Mean realized net per verifiable flag | 340k |
| Quoted profit that survived | 47% |
| Aggregate realized profit | 5.872B |

The trusted exact 12–25% discount subset remained profitable in aggregate, but the newest run was a
little weaker than the roadmap's earlier measurement:

| Metric | Earlier roadmap run | Current run |
|---|---:|---:|
| Loss rate | 19.8% | 21.4% |
| Mean realized net per verifiable flag | 370k | 380k |
| Aggregate realized profit | 2.23B | 1.802B |

This still supports the trust-gated 12% policy over a blanket 25% floor. It also demonstrates that
calibration moves from day to day and must not be treated as a constant.

It is important not to label this table an exact production simulation. The test defines trusted
dispersion as below 0.20, while `UnderpricedScan` production code compares dispersion with
`exactMinDiscount`, currently 0.12. The test also applies its final exact/general gate directly to
priceable held-out sales; it does not reproduce the earlier coarse family admission boundary that
can prevent an exact decode. This is a test-to-production parity defect and is addressed explicitly
in Problem I.

A separate six-hour `ExactMarginBacktestTest` run helps isolate the dispersion difference. It scores
only candidates added below the 15% baseline:

| Added arm | Flags | Measurable | False-positive rate | Net | Net/flag |
|---|---:|---:|---:|---:|---:|
| 12% margin, dispersion < 0.20 | 464 | 453 | 35.1% | 74.43M | 160k |
| 12% margin, dispersion < 0.12 | 258 | 255 | 25.9% | 58.54M | 227k |

This favors the stricter production dispersion rule on quality and profit per flag, while the 0.20
arm earns more aggregate net by admitting more volume. It still does not settle production policy:
the study calls `valueOf` directly, does not reproduce coarse discovery, and does not explicitly
split `EXACT` from `BANDED` basis. Those are required in the corrected replay.

### 3.2 The simpler 15% gate

The directly reported 15% gate produced:

| Metric | Result |
|---|---:|
| Held-out BIN sales | 137,503 |
| Priceable sales | 119,585 (87.0% by count, 62.3% by coins) |
| Median absolute log error | 0.109 |
| Estimates at least 2x realized | 4,418 (3.69% of priceable sales) |
| Hypothetical flags | 16,049 |
| Resale-verifiable flags | 15,521 |
| Loss rate | 17.5% |
| Median realized net per flag | 110k |
| p90 realized net per flag | 710k |
| Quoted profit that survived | 47% |

At the user's 400M bankroll and default 25% position cap, listings above 100M are suppressed. The
backtest's closest printed comparison, a 62.5M cap, retained nearly all flags but cut away much of
the high-value quoted tail.

### 3.3 Correct interpretation

The evidence supports all of these statements at once:

1. There is real within-signature auction edge.
2. Under the tested policies, the model's raw profit quote is about twice what concurrent resale
   evidence realizes.
3. Roughly one in six to one in five resale-verifiable recommendations loses money.
4. High-value configurations are disproportionately hard to price: count coverage is 87%, but coin
   coverage is only 62.3%.
5. Aggregate billions are not a player income estimate. The simulation treats every qualifying
   held-out sale as a candidate the player could have captured.
6. The backtest cannot detect a signature term missing from both its quote and resale grouping.
7. No current measurement includes acquisition latency, attention, failed searches, or competitors.

For scale, earning 20M/hour from the observed distribution would require about 59 mean-profit
captures per hour, or about 28 p90-profit captures per hour. A player can selectively pursue a
better-than-random tail, so this is not a forecast. It demonstrates why the acquisition funnel and
the ranking of obtainable candidates determine the strategy's real income.

## 4. Main problems, proposed corrections, and impact

### Problem A — no measurement of the playable acquisition funnel

#### Why it matters

This is the largest evidence gap. A candidate can be correct and still be useless because it was
bought before it reached the player. A candidate can also look like a successful recommendation in
an ended-sale backtest even if no one client could have seen all such listings.

Without candidate identity and event timestamps, the ledger cannot distinguish:

- a bad valuation;
- a valid opportunity lost to latency;
- a valid opportunity the player ignored;
- a failed name search;
- a purchase that was never recorded; or
- a bought item still waiting to resell.

#### Proposed correction

Add an append-only **auction opportunity journal** that records small, structured events. Do not
store NBT blobs, seller identities, lore, or page payloads.

Suggested event model:

```
AuctionOpportunityEvent(
  eventId,
  auctionUuid,
  eventType,
  at,
  snapshotLastUpdated,
  signatureSchema,
  itemId,
  displayName,
  signatureHash,
  buyPrice,
  quotedResale,
  quotedNet,
  profitPerHour,
  basis,
  confidence,
  samples,
  dispersion,
  hoursToSell,
  discount,
  suspect,
  rank,
  detail
)
```

Initial event types:

- `DETECTED` — survived valuation in a completed sweep;
- `SURFACED` — entered a player-visible top list;
- `SELECTED` — player deliberately clicked the row;
- `REVALIDATION_LIVE` / `REVALIDATION_GONE` / `REVALIDATION_UNKNOWN`;
- `PURCHASE_CONFIRMED`;
- `RELIST_CONFIRMED`;
- `SALE_CONFIRMED`;
- `ABANDONED`; and
- `EXPIRED_UNSOLD` where the UI provides reliable evidence.

The journal should derive reports; it should not become a second ledger. The ledger remains the
money book, while the opportunity journal explains conversion before a position opens.

#### Parts affected

- `core/model` — event record and stable auction target identity;
- `core/tape` or a new `core/opportunity` package — append/read/retention;
- `client/hud/AuctionOverlay` and `client/gui/FlipScreen` — `SURFACED` and `SELECTED` events;
- `client/track/CaptureService` / `TradeTracker` — purchase, relist, and sale correlation;
- `core/ledger` — source UUID on a quoted auction position, without changing non-auction entries;
- `FlipperConfig` and `ConfigSchema` — explicit journal enablement and retention;
- `/flip status` or a new auction report — funnel and player-hour summary; and
- tests for serialization, dedupe, restart recovery, and event correlation.

#### Detailed implementation plan

1. Define a versioned event schema. Use short JSON field names only if a documented adapter keeps the
   file readable. Store a standard cryptographic `signatureHash`, the signature-schema version, and
   item id rather than the potentially long raw signature. Keep raw signatures only in bounded debug
   output when an audit specifically needs them.
2. Add an append-only daily JSONL store with a conservative size cap and configurable retention.
   Reuse the tape write discipline: validate filenames, tolerate a malformed row, never hold a day in
   memory.
3. Give one sweep a stable `observedAt` and `snapshotLastUpdated`. Record `DETECTED` once per UUID per
   sweep, with bounded in-memory dedupe.
4. Record `SURFACED` only when the candidate is actually rendered in a visible auction surface, not
   every time ranking is computed off-screen.
5. Record `SELECTED` on an intentional row action. Merely hovering or scrolling must not count.
6. Carry the UUID into the planned quote/ledger entry when a purchase is confirmed. Never associate
   an arbitrary auction purchase by item name alone.
7. Extend capture fixtures with real purchase, relist, sold, expired, and “already purchased” menu
   evidence before writing parsers for those states.
8. Add an offline report that groups conversion by candidate age, rank, price band, discount band,
   basis, liquidity, and suspect status.
9. Add an active-session clock. Count time only while an auction surface is open or a recently
   selected auction target is being followed. Report the rule used; do not silently equate Minecraft
   uptime with auction work.
10. Validate in play for at least seven calendar days or until there are at least 200 surfaced
    candidates, 30 deliberate attempts, and 20 completed resales. If those counts take much longer,
    the low rate is itself the finding.

#### Release gate

The report must reconcile every `PURCHASE_CONFIRMED` event to at most one ledger position and must
survive restart/replay without duplicating it. Unknown events stay unknown; the parser must never
invent a success.

#### How this reasoning could be wrong

- **Flaw:** Zero auction ledger entries may only mean the user never tried the feature.
  **Correction:** Treat the ledger as “no evidence,” not “evidence of zero profitability.” The new
  funnel distinguishes not-used from attempted-and-failed.
- **Flaw:** Logging every detected candidate could create a large file and distort client latency.
  **Correction:** Store compact rows without blobs, write asynchronously in batches, cap retention,
  and benchmark the hot sweep before enabling by default.
- **Flaw:** Player attempts are selected, so attempt success is not the availability of all
  candidates.
  **Correction:** Report `SURFACED` and `SELECTED` separately. Use revalidation sampling independent
  of player selection when rate budget permits.
- **Flaw:** Detailed market records may contain unnecessary user data.
  **Correction:** Never store seller or buyer identifiers. The auction UUID is sufficient and is
  already public market identity.

### Problem B — the exact UUID is discarded before the UI

#### Why it matters

The public sweep has already done the expensive work and knows the exact listing. Reducing that
identity to a display name throws away the best tool for defeating ambiguity and measuring latency.
The player then repeats a fuzzy search while the listing is disappearing.

This also prevents auditing a dangerous recommendation after the fact. The scroll incident had no
recoverable UUID even though the active DTO originally supplied one.

#### Proposed correction

Carry an auction target through strategy and UI, then revalidate it before presenting a positive
“still live” state. Keep every game action manual.

A minimal compatible shape is a trailing optional target on `FlipCandidate`:

```
CandidateTarget(type, id)
type = AUCTION_UUID initially
```

Existing convenience constructors default to no target. `AuctionValueStrategy` supplies the UUID.
The same target can later represent timed auctions without adding another auction-only field.

#### Parts affected

- `FlipCandidate` and its constructors;
- `AuctionValueStrategy`;
- `CandidateFeed` caches and any equality/selection assumptions;
- `AuctionOverlay`, `FlipScreen`, HUD, and command rendering;
- `PlannedQuotes`, `Quote`, and auction ledger correlation;
- the API client for targeted UUID revalidation; and
- tests that currently key expansion or selection only by item id.

#### Detailed implementation plan

1. Add `CandidateTarget` in `core/strategy`, with `NONE` represented by absence rather than a fake
   empty UUID. Preserve source compatibility through existing constructors.
2. Populate it from `priced.listing().uuid()` in `AuctionValueStrategy`.
3. Change auction-row identity, expansion, and copied-state keys from item id to auction UUID. Two
   listings for one item must render as two targets.
4. Expose `HypixelApi.fetchAuction(uuid)` as a narrow method if the DTO can reuse the existing
   auction shape. Confirm the endpoint's response for live, sold, missing, and API-error cases in an
   opt-in contract test.
5. Add a small asynchronous revalidation service. It must never block the render thread, must dedupe
   concurrent requests for one UUID, and must cache results for a few seconds.
6. Show candidate age and one of `checking`, `live`, `gone`, or `unknown`. “Unknown” on timeout,
   rate-limit, or parse failure must not be rendered as live.
7. Verify in game whether a manual `/viewauction <uuid>` flow exists and is appropriate. If it does,
   a row may copy that command for the player to paste. Do not send it automatically. If it does not,
   retain copied-name search and use UUID only for validation and exact menu matching.
8. On an opened listing view, compare UUID when available; otherwise compare display name, exact
   price, and decoded item fingerprint. Never place a green purchase box on name alone.
9. Before confirmation, show the original price and warn if the live view differs. A changed target
   is not the recommendation.
10. Journal selection and revalidation results from Problem A.

#### Release gate

In a recorded play fixture, two same-name auctions must remain distinct, a sold UUID must be marked
gone, and an API failure must remain unknown. No code may click the purchase or confirmation slot.

#### How this reasoning could be wrong

- **Flaw:** UUID handoff may not make the in-game UI faster if the game offers no exact-open command.
  **Correction:** Revalidation and audit identity still have value. Make exact navigation conditional
  on a measured game flow, with name search as the fallback.
- **Flaw:** A revalidation that says live can race with another buyer immediately afterward.
  **Correction:** Treat live as a timestamped observation, never a guarantee. Continue displaying
  “may be gone,” and measure the gap from revalidation to confirmation.
- **Flaw:** Per-click API checks may exhaust a key's budget.
  **Correction:** Dedupe, cache briefly, cap concurrency, honor rate-limit headers, and prefer one
  revalidation for an intentional selection rather than background-checking every row.
- **Flaw:** A UI shortcut could drift into prohibited automation.
  **Correction:** Clipboard or visual guidance only. Do not send chat commands, click slots, or bypass
  ordinary confirmation. Hypixel's policy makes automation outside this project's scope.

### Problem C — temporary Wither-blade containment is still active

#### Why it matters

`ability_scroll` is now decoded and included in the signature. The documented release backtest found
zero corrected unscrolled quotes at 2x or more over all tested day/horizon cells. The plan explicitly
says the temporary family suppression should be removed once signature, fallback, recovery,
end-to-end, and rolling gates pass.

Despite that, `WitherBladeValuationContainment.ACTIVE` is still `true`. Both ordinary and recovery
valuation suppress every scroll-capable blade. This removes a major high-value family after the root
cause has been fixed.

#### Proposed correction

Remove the containment only after rerunning the full release suite against the newest available tape.
Retain the permanent scroll signature, no-coarse-fallback rule, unread-attribute alarm, and generic
deep-discount suspect guard.

#### Parts affected

- `WitherBladeValuationContainment`;
- `UnderpricedScan`;
- `AuctionValueStrategy`;
- `RecoveryValueModel` and `RecoveryListingScan`;
- containment-specific tests; and
- release/backtest documentation.

#### Detailed implementation plan

1. Run `WitherScrollReleaseBacktestTest` against all retained days, not only the checked-in pre-release
   fixture.
2. Run decoder, signature, bareness, recovery clean-host, underpriced-scan, strategy, and unread-field
   tests together.
3. Inspect results by no-scroll, partial-scroll, and full-scroll set. Require zero corrected 2x
   unscrolled exposure and at least six exact comparable sales for any emitted value.
4. Remove the containment checks and containment-only expectations rather than leaving a permanent
   `ACTIVE = false` switch that can rot. Keep a focused regression proving the former false snipe is
   rejected by correct keying, not by a family blacklist.
5. Build and run one play session with Wither candidates visible. Audit the first several candidates
   manually against scroll state before treating the family as fully released.
6. Record the removal and measurements in the roadmap and scroll-safety document.

#### Release gate

All corrected rolling cells must remain free of the reproduced 2x failure, the unread-field alarm
must remain below 100M, and the first play audit must show the scroll state that the signature used.

#### How this reasoning could be wrong

- **Flaw:** Passing historical tests cannot prove a new hidden blade attribute does not exist.
  **Correction:** Keep the generic suspect guard and unread/nested-attribute probes. Display scroll
  state and UUID so the player can audit the exact listing.
- **Flaw:** Removing containment increases coin exposure sharply.
  **Correction:** The 25% capital cap remains, and high-value deep discounts remain suspect. A short
  canary period may surface blades only below the top trusted rows until live observations exist.
- **Flaw:** A permanent blacklist feels safer.
  **Correction:** The safety study showed that whole-market/high-ticket quarantine sacrificed nearly
  ten percentage points of coin coverage without improving p90 error. Fixing the comparison key and
  keeping general alarms is safer than silently abandoning a corrected market.

### Problem D — the cheap discovery gate misses valuable upgraded configurations

#### Why it matters

The six-hour discovery audit constructed a 20%-under-value listing for every priceable exact
configuration. The name-and-rarity gate admitted 28,115 of 30,670 and missed 2,555, or 8.3%.
Upgraded items can be worth far more than their display-family median, so a real bargain relative to
the exact configuration can look expensive to the coarse gate and never be decoded.

Recovery-sharing regains some decodes when the recovery scan already needs the same blob. It does not
make the ordinary discovery boundary complete.

A second issue appears under a flood: `UnderpricedScan` stops accepting work once `found.size()`
reaches 200. Because the full endpoint is paginated, this is an early page-order cap, not necessarily
the best 200 opportunities.

#### Proposed correction

Build a measured, bounded **family decode admission index**. Do not immediately decode all 46,000
BIN blobs.

For each coarse name-and-rarity family, derive the highest well-backed exact-signature median that
could plausibly justify a decode. Admit a listing when it is discounted against either:

- the ordinary coarse median; or
- a trusted exact-family ceiling built only from sufficiently sampled, low-dispersion signatures.

Then use a per-sweep decode budget and telemetry to learn the cost and recall. Replace the early
200-result stop with a bounded best-candidate heap so later pages can displace weaker results.

#### Parts affected

- `FairValueModel` indexes and builder;
- `UnderpricedScan` admission and result retention;
- `ActiveAuctionScan` decode accounting;
- `MarketPoller` status text;
- scan settings and config schema if the decode budget is configurable; and
- synthetic-recall, CPU, memory, and end-to-end scan tests.

#### Detailed implementation plan

1. Turn the existing synthetic discovery test into a permanent baseline reporting recall by item
   family, value band, basis, and upgrade shape.
2. During model build, compute per-coarse-key trusted exact summaries: maximum median, the signature
   producing it, samples, confidence, and dispersion.
3. Reject sparse or noisy signatures from the family ceiling. One manipulated rare sale must not
   cause every listing in a large family to be decoded.
4. Backtest several admission policies without changing production: coarse only; trusted maximum;
   trusted p90 of exact medians; and a capped multiple of the coarse median.
5. For each policy, report recovered synthetic bargains, extra decodes, decoded-to-candidate yield,
   and concentration by family. Pick a policy by recall per thousand additional decodes, not recall
   alone.
6. Add a hard per-sweep decode budget. Reserve part of it for the existing coarse gate so an
   expensive family cannot starve ordinary candidates. When exhausted, record `budget_exhausted`
   rather than pretending the sweep was complete.
7. Replace `found.size() >= 200` rejection with a priority queue retaining the best 200 by the same
   trusted/suspect and profit ordering the player ultimately sees. Continue evaluating the rest of
   the sweep within the decode budget.
8. Benchmark decompression/decoding and total sweep publication delay on a recorded active-page
   fixture. Network download is already large, but extra CPU can still make the result staler.
9. Shadow-log newly admitted candidates for several days without showing them. Score them with the
   opportunity journal and subsequent resale evidence.
10. Promote the policy only if it raises the count of still-live, profitable candidates per player
    hour—not merely synthetic recall.

#### Release gate

The selected policy must materially improve recall while keeping sweep completion inside a defined
latency budget and must not worsen realized loss rate in shadow evaluation. The status command must
make partial/budget-limited sweeps visible.

#### How this reasoning could be wrong

- **Flaw:** The maximum exact value can be an outlier and admit thousands of irrelevant bare items.
  **Correction:** Trust-gate the ceiling, compare maximum versus p90/capped variants, and impose a
  per-family plus global decode budget.
- **Flaw:** More discovery may reduce profit because the player already cannot capture current rows.
  **Correction:** Do this after funnel measurement and UUID handoff. Release on profitable live
  captures, not candidate count.
- **Flaw:** Decoding all blobs might actually be cheap enough and simpler.
  **Correction:** Benchmark it as one counterfactual. Prefer it if measured publication latency and
  memory are acceptable; do not preserve complexity based on an old assumption.
- **Flaw:** A top-200 heap can rank with fields not known until strategy evaluation.
  **Correction:** Either evaluate the complete `FlipCandidate` before heap admission or define one
  shared auction ranking key. Do not approximate it twice.

### Problem E — ranking optimizes resale velocity but ignores acquisition probability and attention

#### Why it matters

`AuctionValueStrategy` currently ranks `quoted net / estimated hours to sell`. That correctly
penalizes an illiquid exit, but it assumes the entry is acquired and player attention is free.

For BIN snipes, entry probability may dominate the result. A 5M quote that is gone 99.9% of the time
can rank above a 300k opportunity that remains buyable. Repeated dead clicks can make the first
candidate worth less per player-hour even when its successful purchase would be excellent.

The current quote also runs high: only about 47% survived on the newest holdout.

#### Proposed correction

Do not guess an acquisition model. First collect enough events from Problems A and B. Then calibrate
an empirical expected-value score:

```
expected realized net
  = empirical resale calibration(bucket) * quoted net

expected captured net per attention minute
  = still-live probability(age, bucket)
    * purchase success probability(bucket)
    * expected realized net
    / expected attention minutes(bucket)
```

Keep hard safety gates before this score: exact/coarse valuation rules, minimum confidence, minimum
expected net, capital cap, suspect demotion, and liquidity. The empirical score orders safe
candidates; it does not make an unsafe candidate safe.

#### Parts affected

- the opportunity report/calibrator;
- `AuctionValueStrategy` or a dedicated auction ranker;
- `FlipCandidate` fields shown to users;
- `CandidateFeed` sorting and caches;
- config only if the user chooses an objective such as coins/click versus coins/hour; and
- backtests using chronological train/calibration/test splits.

#### Detailed implementation plan

1. Keep the current ordering as the control arm while collecting at least the minimum funnel sample.
2. Define coarse buckets before looking at their outcome: basis; discount band; value band; candidate
   age; sales-rate band; sample-count band; and suspect status.
3. Estimate resale calibration and still-live rate with confidence intervals. Merge sparse buckets
   toward a conservative global prior rather than assigning them 0% or 100% from anecdotes.
4. Use chronological calibration: train the value model on the past, calibrate on a following period,
   and score on a later untouched period. Never let an eventual sale teach the score used earlier.
5. Shadow-rank current versus empirical order on the same live candidate sets. Compare top-five
   still-live rate, expected realized net, and attention cost.
6. In a manual A/B play trial, alternate ranking policy by session or fixed time block. Do not let the
   player silently choose the more attractive arm.
7. Show a conservative expected net alongside the raw fair-value quote during the trial. Keep the
   raw ingredients available for audit.
8. Promote only if the empirical order improves realized coins per active auction hour without a
   meaningfully worse loss rate or capital-hours profile.

#### Release gate

The new score must beat the current ranking out of sample and in live conversion. A backtest-only
gain is insufficient because acquisition probability is the feature being added.

#### How this reasoning could be wrong

- **Flaw:** Player selection creates a feedback loop; only attractive rows get attempts.
  **Correction:** Revalidate a small fixed top-K sample independent of clicks, and keep surfaced and
  selected populations separate. Use randomized session ordering only if the user accepts it.
- **Flaw:** Availability changes with time of day, events, mayor, and other flippers.
  **Correction:** Timestamp every observation, decay old calibration, report sample counts, and fall
  back conservatively when a bucket is stale.
- **Flaw:** Multiplying noisy probabilities produces false precision.
  **Correction:** use broad buckets and confidence bounds. Render rounded expected values and evidence
  counts, not six-decimal probabilities.
- **Flaw:** Ranking by ease of acquisition may promote unwanted, illiquid items.
  **Correction:** Preserve exit liquidity and expected-profit floors before ordering by capture.

### Problem F — auction positions are not yet a worked portfolio

#### Why it matters

A dedicated auction flipper does not evaluate one purchase in isolation. They manage limited purse,
Auction House listing capacity, relist expirations, concentration, and several items waiting to sell.
The current auction candidate is one indivisible position, but the mod has no auction work board
equivalent to the bazaar jobs view.

Without a portfolio view, quoted profit per hour can suggest multiple individually affordable items
whose combined capital or exit risk is unacceptable.

#### Proposed correction

Add an auction-position view after UUID correlation works. It should show:

- bought but not relisted;
- listed and waiting;
- sold but unclaimed;
- expired and needing repricing;
- capital committed;
- current age versus quoted hours to sell;
- quoted and realized net; and
- concentration by item family and valuation risk.

The ledger remains authoritative for money. The view derives state from correlated capture/menu
evidence and refuses to guess when tracking is off.

#### Parts affected

- `WorkedJob` only if it can represent auction lifecycle without damaging its bazaar semantics;
  otherwise add a small auction-specific position view;
- `Ledger`, `LedgerEntry`, `PlannedQuotes`, and tracker correlation;
- `AuctionOverlay`, `FlipScreen`, `/flip jobs` or `/flip auction positions`; and
- config for total auction capital and concurrent position limits.

#### Detailed implementation plan

1. Write down the real menu/chat lifecycle from capture: purchase, claim item, create BIN, sale,
   claim coins, expiration, and cancellation.
2. Decide whether `WorkedJob` can express it. Do not force UUID-specific auction semantics into
   bazaar order stages merely for reuse.
3. Link the initial quote and purchase by auction UUID. Link the relist by the purchased item's own
   item UUID where capture exposes it. Do not join only by display name.
4. Add total-auction-capital and concurrent-position warnings. The existing 25% per-position cap
   stays; the portfolio cap addresses four concurrent 25% positions.
5. Render age versus expected sell time and make expired positions explicit.
6. Add close/abandon/forget semantics consistent with the ledger. A lost or consumed item is not a
   profitable completed flip.
7. Test restart and partial evidence. A purchase with no observed relist remains open, not silently
   closed.

#### Release gate

A recorded multi-auction fixture must reconstruct every position without merging same-name items or
double-booking a sale. Totals must equal ledger totals.

#### How this reasoning could be wrong

- **Flaw:** More portfolio UI does not find more flips.
  **Correction:** Build it only after acquisition works. Its value is preventing capital lock and
  measuring completed economics, not increasing signal count.
- **Flaw:** Auction menus may not expose enough identity to correlate relists.
  **Correction:** Capture before designing. Leave ambiguous positions unlinked and require a manual
  association rather than guessing.
- **Flaw:** A global position cap can suppress a rare exceptional opportunity.
  **Correction:** Warn by default and make any override explicit; do not silently exceed the user's
  configured purse exposure.

### Problem G — timed-auction profit is measured, but reachability is not

#### Why it matters

The timed-auction study is the strongest plausible expansion because it reduces the sub-second BIN
race. On the retained tape, the trustworthy subset produced about 161 opportunities/day, roughly 91%
quote survival, about 6.2% loss, and 245k median profit on the newest reproduction.

But those are auctions that *ended* cheaply. The player would have competed with the actual winner.
An English auction with a two-minute late-bid extension can bid a visible bargain toward fair value.

The Phase 0b collector and analysis exist, but `timedAuctionTapeEnabled` is absent from the local
config and no local timed-auction tape directory exists. Therefore there is still no reachability
number.

#### Proposed correction

Start collection on the 24/7 collector now, validate its join, then strengthen the decision gate so
“no bid” is not mistaken for proven profit.

Use three reported bounds:

- **lower bound:** trustworthy flags actually sold at or near the starting price, with a last active
  sample close enough to the end to show no meaningful bidding war;
- **middle estimate:** lower bound plus contested auctions whose observed final price remained below
  the bid ceiling; and
- **upper bound:** includes no-bid auctions that remained at start, recognizing that they may be
  unwanted and have no realized winning price.

Build Phase 1 only if the conservative lower/middle estimate is worthwhile after click and waiting
costs.

#### Parts affected

- collector VM config and deployment;
- `TimedAuctionCollector`, `TimedAuctionTape`, and retention monitoring;
- `AuctionReachabilityBacktestTest` reporting and gates;
- later, and only after a pass, `AuctionBidStrategy`, `StrategyKind`, auction surfaces, and tracking;
- portfolio escrow limits; and
- Derpy behavior.

#### Detailed implementation plan

1. Merge/deploy the current investigation branch through the project's normal integration process.
2. On the collector VM, enable `scanAuctions` and `timedAuctionTapeEnabled`, retain the default
   ending-soon window initially, and restart the collector.
3. After the first day, validate sample cadence, file growth, UUID join sanity, timestamp skew, and
   the share of trajectories whose last sample is close to `end`.
4. Fix collection defects before accumulating a mayor-long dataset. A broken UUID join or a
   several-minute terminal gap invalidates the conclusion.
5. Collect at least seven days; a full mayor term is preferable. Preserve mayor/Derpy context in the
   report even if it is joined from timestamps rather than stored per row.
6. Report sold-at-start, no-bid, and contested trajectories separately. Never count a no-bid return
   as realized profit.
7. Compute the bid-to-win and maximum profitable ceiling at each observation. For contested auctions,
   report whether the final known price crosses the ceiling and how many 2.5% steps occurred.
8. Apply the trustworthy exact restriction and generic suspect policy before calculating a build
   rate.
9. Estimate manual attention: searches, bid rounds, outbid refunds, claim, and relist. Give both
   coins/day and coins per attended minute.
10. Run the deep 0a study on the VM's longer sales tape across mayor periods.
11. Build `AuctionBidStrategy` only if at least 20–30% are reachable under the conservative
    definition and the absolute rate/value is worth the interactions.
12. Keep bids manual and give an exact “bid up to X, then stop” ceiling.

#### Release gate

The join sanity check must pass; reachability must clear the threshold on a multi-day tape; the lower
or middle estimate—not the no-bid upper bound—must justify the work; and a manual play pilot must
confirm the API's stale state is usable under the two-minute extension.

#### How this reasoning could be wrong

- **Flaw:** “Uncontested at the last sample” may miss a bid during the final unsampled seconds.
  **Correction:** Require a bounded terminal sample gap, use ended-sale final price where present,
  and report uncertainty rather than a binary claim.
- **Flaw:** No-bid listings look maximally reachable but may be worthless or impossible to resell.
  **Correction:** Keep them out of the conservative result. Require independent BIN comps and treat
  them only as an upper bound until actually bought and resold.
- **Flaw:** The anti-snipe extension makes the auction human-playable but may make it time-expensive.
  **Correction:** Include attended minutes and failed/outbid sessions in the denominator.
- **Flaw:** A one-week period may be unusual.
  **Correction:** Repeat across mayor/event regimes before relying on the rate for a permanent
  strategy.

### Problem H — coarse floor signals are not actionable floor-sweep evidence

#### Why it matters

Recent live logs report roughly 337–351 coarse keys with two or more listings under a family median
per sweep. Several leading examples expose the approximation:

- 5-star Heroic Hyperions from 500M to 785M compared with a 1.035B next tier;
- Soul Esowards around 4.3M–4.5M compared with a 120M next listing; and
- armor families whose “cheap cluster” spans tens of millions.

These may combine real gaps with different scrolls, attributes, quality, gemstones, enchants, or
other investment. The count proves there is a distribution worth studying. It does not prove there
are hundreds of safe floor sweeps.

#### Proposed correction

Collect **exact-signature live supply trajectories** for a small, justified set of families. Model
replenishment and exits before presenting a multi-listing purchase.

A safe floor-sweep candidate requires:

- two or three exact-comparable listings below an actual gap;
- a trusted signature value and tight dispersion;
- enough realized sales and acceptable inventory days;
- a relist price just below the next exact-comparable live listing, not blindly at the median;
- profitability after every listing/claim fee and a price buffer;
- a measured low enough rate of new undercutting supply; and
- total position sizing against purse and auction-slot budgets.

#### Parts affected

- `SupplyCounter` and `SupplySignal`, likely with a separate exact research path;
- selective decode policy from Problem D;
- a new research tape for exact supply snapshots;
- later `AuctionFloorStrategy` and a multi-listing candidate/job shape;
- fee, capital, AH-slot, and price-impact calculations; and
- auction overlay and portfolio tracking.

#### Detailed implementation plan

1. Aggregate several days of current coarse signal logs by family, frequency, apparent gap, and
   sales rate. Select a bounded top research set; do not decode every family forever.
2. For selected families, decode all live listings in the family and group them by production
   signature. Drop blobs after extracting compact UUID, signature hash, price, and snapshot time.
3. Measure how often an exact cluster persists, disappears through sales, or is replenished by new
   cheap listings.
4. Join observed removals to `auctions_ended` where possible. Distinguish sold from expired/removed;
   disappearance alone is not demand.
5. Backtest buying the cheapest `N`, paying all fees, relisting below the next exact listing, and
   allowing new undercuts to arrive. Include relist expiration and non-refundable listing fees.
6. Sweep `N` from one to three first. A single listing belongs to the ordinary sniper; larger corners
   require much stronger evidence.
7. Stress price by one new cheap listing arriving immediately after purchase. Reject a strategy whose
   profit depends on a perfectly static floor.
8. Apply inventory-days and total-capital limits. Disable under Derpy unless the complete fee stack
   still clears the buffered floor.
9. Shadow-render/log candidates before exposing them to the player.
10. Run a tiny live pilot—one exact family, one or two listings—with explicit tracking before adding
    it to global ranking.

#### Release gate

The exact-supply backtest must stay profitable after replenishment and fee stress, and the live pilot
must complete at a positive realized return without exceeding its expected holding time badly.

#### How this reasoning could be wrong

- **Flaw:** A visible gap may exist only because the next listing is absurd, not because buyers will
  pay it.
  **Correction:** Cap the exit with realized exact-signature medians and require actual turnover near
  the proposed relist price.
- **Flaw:** Removing several listings invites immediate new supply.
  **Correction:** Measure arrival/replenishment rate and stress an immediate undercut.
- **Flaw:** Exact signatures can still omit a new attribute.
  **Correction:** Keep unread/nested-attribute alarms and the high-value suspect policy; start with
  lower-exposure families.
- **Flaw:** A strategy can be coin-positive but attention- and slot-negative.
  **Correction:** Report listings hauled, clicks, AH slots, capital-hours, and expirations beside
  profit.

### Problem I — the gate reconciliation backtest has drifted from production

#### Why it matters

`SnipeGateReconcileBacktestTest` says its trust constants are held in step with `UnderpricedScan`,
but they are not currently identical:

- the test uses `TRUST_DISPERSION = 0.20`;
- production uses `exact.dispersion() < exactMinDiscount`; and
- the default `exactMinDiscount` is 0.12;
- the reconciliation test explicitly requires `ValueEstimate.Basis.EXACT`; but
- production's `exactEstimateTrusted` helper does not check the estimate basis, so a sufficiently
  confident `BANDED` estimate can potentially use the smaller 12% margin despite the documented
  EXACT-only intent.

The test also evaluates the final gate on every priceable held-out sale. Production normally reaches
that gate only after the coarse name-and-rarity family admission allows a decode, except when the
recovery scan independently earns that decode.

Therefore the printed “codex + coarse 0.15” row is useful policy evidence but is not a literal replay
of the shipped scan. Calling its 17,748 flags, 18.5% loss rate, or 5.872B aggregate an exact current
production result would be false precision.

#### Proposed correction

Make one production gate policy the shared source for runtime and backtests. Separately report:

1. **valuation eligibility** — what would pass if decoded;
2. **discovery eligibility** — what production would actually choose to decode; and
3. **strategy eligibility** — confidence, capital, fees, minimum profit, containment, and suspect
   ordering after discovery.

This preserves the useful counterfactual question (“is 12% trusted exact better than a blanket 25%?”)
while adding a production-replay arm that answers what the client truly ships.

#### Parts affected

- `UnderpricedScan` gate helpers and visibility;
- `SnipeGateReconcileBacktestTest`;
- `ExactMarginBacktestTest`;
- `SnipeProfitBacktestTest`;
- a new composed-scan production replay fixture, if the existing harness cannot model recovery-earned
  decodes; and
- roadmap claims and recorded baseline results.

#### Detailed implementation plan

1. Extract an immutable `AuctionGatePolicy` in `core/valuation` containing coarse margin, exact
   margin, exact confidence floor, exact sample floor, and the dispersion rule.
2. Decide the intended production dispersion rule by measurement, not by reconciling comments:
   fixed 0.20, margin-relative 0.12, or another tested value. The existing exact-margin study
   suggests dispersion and margin serve different purposes, so both alternatives must be printed.
3. Decide and test whether the smaller margin is strictly `Basis.EXACT`, as the roadmap says. Print
   the admitted and realized deltas for `EXACT` and `BANDED` separately before changing runtime.
4. Make `UnderpricedScan.clearsExactGate` delegate to the shared policy.
5. Make backtests instantiate the same policy. Counterfactual policies may differ, but their labels
   must print every differing parameter.
6. Add a production replay arm that runs the actual `UnderpricedScan` admission sequence, including
   the coarse family prune. Add a composed arm only where recovery actually would request the decode.
7. Report the delta between priceable-policy flags and production-discovered flags. This turns the
   current hidden discovery loss into an explicit number.
8. Keep resale truth leave-one-out and out of sample. Do not “fix” parity by simplifying production
   behavior inside a copied test model.
9. Rerun the newest 24-hour baseline and update every roadmap number whose label implies shipped
   behavior.
10. Add a parity test that feeds the same listing/value tuple to production policy and the backtest
   adapter and asserts the same decision at boundary values.

#### Release gate

There must be no duplicated trust constant in a backtest claiming to model production. Printed
reports must distinguish policy-only opportunity, discovered opportunity, ranked candidate, and
realized resale populations.

#### How this reasoning could be wrong

- **Flaw:** The 0.20 constant may be the intended rule and production's 0.12 comparison may be the
  bug.
  **Correction:** Do not blindly change the test to 0.12. The fresh exact-margin run shows the 0.12
  cap has better quality/net per flag while 0.20 has more aggregate net; sweep both definitions on
  untouched data, then choose and share one policy.
- **Flaw:** Adding an EXACT-basis check may discard useful, well-calibrated pet-band candidates.
  **Correction:** Score EXACT and BANDED separately. If BANDED deserves a smaller margin, give it an
  explicit measured policy rather than letting it enter through a helper whose name and docs say
  exact.
- **Flaw:** Sharing production helpers can make counterfactual tests harder to express.
  **Correction:** Represent a policy as data. Production uses one instance; counterfactual tests use
  explicitly labeled instances through the same evaluator.
- **Flaw:** Exact production replay can be slower than a policy-only backtest.
  **Correction:** Keep both. Use the fast arm for exploratory sweeps and require the real scan arm as
  the release result.
- **Flaw:** Fixing test parity alone improves no player outcome.
  **Correction:** Treat it as evidence hygiene and a prerequisite for threshold decisions, not a
  profitability feature.

## 5. Cross-cutting design rules

### 5.1 Never automate gameplay

The mod may observe public data, explain a candidate, copy text, and highlight a verified target. It
must not send commands, click purchase/bid/confirmation slots, or continue gameplay without the
player. Every new convenience remains at-use-risk under Hypixel's modification policy; automation is
explicitly outside the design.

### 5.2 Preserve exact identity end to end

Use auction UUID for the source listing and item UUID where a purchased item/relist exposes one.
Display name is presentation, not identity. Price plus name is a fallback observation, not a durable
join key.

### 5.3 Keep quoted, expected, and realized values distinct

- **Quoted** comes from the valuation and fee model at detection time.
- **Expected** is an empirically calibrated forecast made before the outcome.
- **Realized** is money actually booked by the ledger.

Never overwrite the frozen quote with a later model value. Never call the model's own median a
realized resale.

### 5.4 Fail closed without hiding why

An API failure is unknown, not live. A missing menu event is untracked, not complete. A decode budget
exhaustion is a partial sweep, not “no opportunities.” Every refusal should appear in status/reporting
so safety does not masquerade as an empty market.

### 5.5 Separate measurement from action

New discovery policies, rankers, timed-auction gates, and floor candidates first run in shadow mode.
The project should be able to compare what an alternative would have shown without encouraging the
player to risk coins on it.

### 5.6 Use chronological evaluation

All market calibration must use past data to predict later untouched data. Random splits leak market
regimes. Resale truth must come from out-of-sample realized sales or the actual ledger, never the
quote being tested.

## 6. Impact matrix

| Area | Expected changes | Main risk |
|---|---|---|
| Auction DTO/API | targeted UUID lookup, timestamps | rate limits and stale observations |
| Core candidate model | optional stable target | constructor/caller churn |
| Valuation | family admission index, containment removal | coverage increasing risk |
| Active scan | decode budgets, best-200 retention | publication latency |
| Ranking | calibrated captured-value score | feedback bias and overfitting |
| Overlay/screen/HUD | UUID-keyed rows, age/live state, expected value | implying guarantees |
| Capture/tracking | exact purchase/relist/sale lifecycle | ambiguous menu evidence |
| Ledger | source UUID and auction portfolio state | double booking |
| Persistence | compact opportunity and exact-supply journals | disk growth/privacy |
| Headless collector | timed trajectory deployment | operational configuration drift |
| Config/schema | journal, retention, budgets, portfolio limits | too many unsafe knobs |
| Tests | live fixtures, chronological backtests, replay/dedupe | flattering synthetic proxies |

## 7. Recommended execution order

Some work is sequential; one data collection task should start immediately because elapsed days are
its main cost.

### Phase 0 — reconcile and freeze the baseline

1. Confirm which integration branch is becoming the playable line. At the time of this document,
   the checked-out `auction-bidding-investigation` branch contains the auction audit and overlay work,
   while `main` does not.
2. Resolve Problem I's runtime/backtest gate-policy mismatch before describing a new run as the
   shipped baseline. Preserve the current output as a dated historical policy comparison.
3. Run `./gradlew build`, the production-replay P&L/reconciliation tests, the Wither release test,
   and the unread attribute probes on the same tape.
4. Save the printed baseline metrics in this document or a dated result artifact, with distinct
   counts for priceable, discovered, strategy-eligible, and ranked candidates.
5. Do not tune thresholds during the instrumentation build; otherwise conversion changes cannot be
   attributed.

### Phase 1 — remove obsolete containment

Follow Problem C. This is independent, small, and restores a corrected high-value family. Ship it
only with the release gates and a short manual audit.

### Phase 2 — preserve target identity and journal the funnel

Implement `CandidateTarget`, UUID-keyed UI state, the opportunity journal, and exact ledger source
correlation. At the end of this phase, the current name-search workflow may remain, but the project
can finally measure it.

### Phase 3 — targeted revalidation and measured handoff

Add asynchronous by-UUID revalidation and verify the safest manual in-game navigation flow. Record
candidate age and every outcome. Do not add automated interaction.

### Phase 4 — live baseline trial

Run the unchanged valuation/ranking policy until the minimum funnel sample is reached. Publish:

- age-bucket availability;
- shown-to-attempt and attempt-to-purchase rates;
- completed resale results;
- loss rate and quote capture;
- capital-hours; and
- coins per active auction hour.

This is the decision point for the original question. If income is already satisfactory after better
handoff, the model was stronger than it felt. If candidates are mostly gone, acquisition is the
bottleneck. If purchases lose, valuation/calibration is the bottleneck. If purchases win but capital
sits, exit liquidity/portfolio management is the bottleneck.

### Phase 5 — calibrate ranking

Only after Phase 4 has evidence, follow Problem E. Shadow compare first; promote only on live and
chronological gains.

### Phase 6 — improve discovery

Follow Problem D after knowing that another candidate is valuable. Fix the best-200 page-order issue
and measure selective decoding policies. Do not celebrate recall that produces more expired rows.

### Parallel clock — collect timed auctions now

Deploy and enable Phase 0b on the headless collector while Phases 1–4 proceed. Inspect the first day
for data defects, then wait for a meaningful span. Analysis and any Phase 1 timed strategy remain
gated as described in Problem G.

### Phase 7 — build timed bidding only if reachable

If the conservative reachability gate passes, implement the already documented bid strategy with a
hard bid ceiling, escrow portfolio limits, Derpy behavior, manual actions, and the same opportunity
journal.

### Phase 8 — exact floor-sweep research

Treat coarse supply logs as family-selection data only. Follow Problem H and build a player-facing
strategy only after exact supply/replenishment evidence and a small live pilot.

## 8. Verification plan by checkpoint

### Identity and persistence

- Two same-name/same-id live auctions stay distinct by UUID.
- Journal replay is idempotent.
- A malformed row does not discard the rest of a day.
- Retention cannot delete unrelated paths.
- No NBT blob or seller/player identifier is persisted.

### Revalidation

- live, sold, missing, 429, timeout, and malformed responses have distinct outcomes;
- renderer never blocks on a request;
- cached responses expire;
- `unknown` never becomes `live`; and
- no game action is generated.

### Tracking and ledger

- one successful UUID opens at most one position;
- a same-name manual trade cannot steal the quote;
- partial evidence remains open/untracked;
- sale fees match the frozen quote basis; and
- report totals reconcile to ledger totals.

### Valuation safety

Run at minimum:

```
./gradlew test --tests '*AuctionValueStrategyTest' \
  --tests '*UnderpricedScanTest' \
  --tests '*WitherScrollValuationTest' \
  --tests '*RecoveryValueModelTest' \
  --tests '*RecoveryListingScanTest'

./gradlew test -PtapeBacktest -PtapeDir=<sales-tape> \
  --tests '*SnipeProfitBacktestTest' \
  --tests '*SnipeGateReconcileBacktestTest' \
  --tests '*ExactMarginBacktestTest' \
  --tests '*WitherScrollReleaseBacktestTest' \
  --tests '*UnreadAttributeProbeTest'
```

### Timed auctions

```
./gradlew test -PtapeBacktest \
  -PtapeDir=<sales-tape> -PtimedTapeDir=<timed-auction-tape> \
  --tests '*AuctionReachabilityBacktestTest'

./gradlew test -PtapeBacktest -PtapeDir=<deep-sales-tape> \
  --tests '*AuctionBidProfitBacktestTest'
```

### Every code checkpoint

Run `./gradlew build`, inspect `git diff --check`, inspect the full diff, and verify
`./gradlew collectorJar` whenever core/API/tape changes could affect the headless collector.

## 9. Claims this plan deliberately does not make

- It does not claim auction flipping is weak. The measured edge is substantial.
- It does not claim the 17,748 daily backtest flags are obtainable.
- It does not claim the zero-entry auction ledger proves zero profit.
- It does not claim UUID revalidation guarantees a purchase.
- It does not claim more decoded listings improve player income.
- It does not claim a no-bid timed auction is profitable.
- It does not claim a coarse live supply gap is an exact floor.
- It does not claim dedicated flippers earn their income from the same narrow strategy. Their results
  may combine specialization, timing, upgrades, bidding, floor management, and far more attention.

## 10. Rejected shortcuts

- **Lower the exact margin below 12%.** Measured 5% recommendations lost to fees more than half the
  time; the model's normal error is already about 10%.
- **Raise every discount floor to 25%.** The existing comparison indicates this discards a large,
  profitable trusted-exact subset. Reconfirm the exact magnitude after fixing the production-policy
  parity defect; trust still needs to be tested as the discriminator rather than assuming depth is.
- **Rank raw quoted profit more aggressively.** The quote currently realizes at about 47%; amplifying
  it does not solve acquisition or calibration.
- **Decode everything without measurement.** It may be viable, but benchmark it first against
  publication staleness and memory.
- **Navigate or purchase automatically.** Prohibited by project scope and unsafe under Hypixel's
  rules.
- **Build timed bidding from ended-sale surplus alone.** Ended cheap is not proven winnable.
- **Turn coarse supply signals directly into buys.** They visibly mix configurations.
- **Corner whole markets.** New supply, manual speed, capital lock, and non-refundable fees make the
  broad strategy structurally worse than a measured two- or three-listing exact sweep.

## 11. Final decision framework

After the Phase 4 live baseline, classify the result using evidence:

| Observed result | Diagnosis | Next action |
|---|---|---|
| Most rows gone before selection | acquisition/latency bound | improve handoff; prioritize timed bids |
| Rows live but player rarely attempts | presentation/trust or attention bound | improve evidence display and candidate density |
| Purchases succeed but loss rate is high | valuation/calibration bound | tighten by measured bucket; inspect missing terms |
| Purchases profit but sell much slower | liquidity model bound | recalibrate hours-to-sell and portfolio limits |
| Good conversion and resale, low candidate count | discovery bound | selective family admission and exact floor research |
| Good coins but poor coins per attended minute | workflow bound | simplify manual steps or prefer higher-value targets |
| Strong realized coins per active hour | skepticism disproved for this workflow | preserve gates; scale cautiously with portfolio controls |

Until this table can be filled with real observations, the honest product description is:

> A carefully backtested auction valuation and candidate system with promising market edge, but
> without a measured human acquisition rate or demonstrated realized auction income yet.
