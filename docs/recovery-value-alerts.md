# Recovery-value alerts

Status: **implemented offline on `recovery-value-alerts` (2026-08-28), awaiting real-Hypixel
verification.** The release is strictly advisory. Gemstone recovery can receive evidence-backed
credit; drill and fishing parts are decoded and valued from realized standalone sales but remain
zero-credit while their current removal costs lack a captured fixture. Legacy salvage remains
`PREVIEW REQUIRED` and zero-credit because no exact manual-preview capture exists.

This is the recovery document for interrupted or fresh sessions. Read `CLAUDE.md`,
`docs/roadmap.md`, `CONTEXT.md`, and this file before changing code. On resume, inspect the actual
branch, log, status, and diff; do not assume the baseline named above is still current.

## Goal and safety boundary

Find active BIN auctions whose purchase price is conservatively below the value a player can
recover manually from the host item and attached components:

- removable gemstones;
- removable drill engines, fuel tanks, upgrade modules, and Goblin Omelettes;
- removable fishing-rod hooks, lines, and sinkers;
- verified legacy grey-attribute salvage outputs (shards and, when proven, Ananke Feathers).

The feature is analysis and notification only. It may poll public APIs, decode listing NBT, compute
floors, display explanations, copy text, and optionally chime. It must never click a menu, send an
inventory packet, buy an auction, remove a component, salvage an attribute, or infer that the user
completed an action. Do not put recovery opportunities into the current Take, Work, green-box,
order-tracking, or ledger flows in the first release.

## What shipped

- Immutable recovery legs, opportunities, warnings/confidence, and conservative floor math. The
  safety haircut is applied before venue fees and fixed removal costs are subtracted afterward.
- `ItemDecoder.decodeDetailed` derives the existing ordinary `DecodedItem` and recovery metadata
  from one parsed NBT tree. The ordinary decode API and signatures remain unchanged.
- One streaming realized-sales pass builds ordinary fair values, recovery clean-host values, and
  bare standalone AH-component values. Active listings never train either model.
- Bazaar gemstone exits walk the visible bid depth (`sell_summary` after the DTO inversion), require
  full quantity and moving flow, apply Bazaar tax, and use the explicit versioned gemstone removal
  schedule. A removal charge is applied per attached gemstone.
- Drill engines, tanks, modules, Goblin Omelettes, hooks, lines, and sinkers have explicit catalog
  identities. Their decoded identities and realized standalone-sale evidence are shown, but their
  contributions fail closed with `UNKNOWN_REMOVAL_COST` until a current removal-menu capture proves
  the cost.
- `ActiveAuctionScan` composes ordinary and recovery consumers over the existing active-AH network
  sweep. A memoized detailed decode is shared whenever both consumers inspect a listing; one
  listing failure is contained. `AuctionScanSnapshot` atomically publishes both result sets with a
  separate recovery revision.
- `RecoveryFeed`, `/flip recovery [auction UUID]`, and the special Recovery `FlipScreen` tab render
  the full evidence. GUI selection is keyed by auction UUID. The only Recovery-tab control copies
  the UUID; there is no Take, Work, overlay, tracking, or ledger path.
- Alerts are client-tick, opt-in and default-off for master/chat/toast/sound switches. They re-check
  current snapshot membership and age, apply validated profit/margin/family gates, deduplicate on
  auction UUID plus quote fingerprint, expire entries by TTL, and cap memory.

No second auction sweep, menu listener, gameplay click, inventory packet, auction purchase,
component removal, salvage operation, or automated sale was added. `core` remains free of
Minecraft and Fabric imports.

## Deliberately deferred

Checkpoint 8 did not run because the repository contains no captured legacy salvage preview that
binds an exact input item/context to exact shard or Ananke Feather outputs. NBT attribute presence
alone is not enough evidence. Until a user captures that menu manually through the existing
`CaptureService`, legacy output stays visibly `PREVIEW REQUIRED`, contributes zero coins, and cannot
trigger an alert as a credited family.

Current drill and fishing-part removal prices are deferred for the same evidence reason. Their
identities are retained and explainable, but no cost is guessed from lore or community prose. A
future checkpoint needs captured current removal menus and focused fixtures before enabling credit.

## Current architecture this must preserve

- `core` is Minecraft-free. API DTOs, decoding, recovery math, models, and tests belong there.
  Client wiring, screens, commands, sounds, and captured menus belong under `client`.
- `MarketPoller` owns the public API schedules. `MarketData` exposes snapshots. The active-AH sweep
  is already expensive (roughly 51 pages / 70 MB), skips unchanged `lastUpdated` values, declares a
  narrow DTO, and prunes by cheap name/rarity before decoding. Recovery must share that one sweep,
  not start a second one.
- Ordinary AH valuation trains only on realized ended BIN sales, never active listings. It uses
  exact `DecodedItem.signature()` keys and a coarse fallback only for truly bare items. Recovery
  must not weaken or rewrite those signatures.
- `CandidateFeed`, `FlipCandidate`, `StrategyEngine`, `/flip take`, worked jobs, and the ledger model
  a single buy followed by a single sell. Recovery is one host purchase with several possible AH
  and Bazaar exits, removal costs, and possibly a retained host. That is a different domain shape.
- `CaptureService` owns the one pair of chat/menu hooks. A future in-game attribute preview reader
  must consume that service; it must not register another listener and must not click anything.
- Config remains a mutable `FlipperConfig` with backward-compatible defaults. Every new setting
  needs a matching `ConfigSchema` entry and validation. Callers read current values through
  `SkyblockFlipperClient.config()` instead of caching fields.

## Bazaar GUI compatibility decision

The completed Bazaar overlay rework is for Bazaar work: `StrategyKind.bazaarKinds()`,
`bazaarOverlayType`, `BazaarAction`, `BazaarStep`, Work/Stop, and green-box guidance. Recovery may
value a component using Bazaar depth, but the opportunity begins as an AH host purchase and may
have several exits. It is not a Bazaar worked job.

Therefore the first recovery implementation should leave these files and concepts alone:

- `client/hud/BazaarOverlay.java`;
- `core/strategy/BazaarAction.java` and `BazaarStep.java`;
- `core/strategy/StrategyKind.java` and `StrategyKind.bazaarKinds()`;
- `FlipperConfig.bazaarOverlayType`;
- `CandidateFeed`'s follow/work/preview path;
- `core/ledger/**`.

Do **not** add `RECOVERY_VALUE` to `StrategyKind`, even with `atBazaar=false`, merely to obtain a
tab. Enum membership also reaches filters, the strategy engine, generic candidates, Take/Work, and
ledger switches. A cosmetic reuse would create semantic bugs.

Add Recovery to the main `/flip gui` as a special tab, analogous to Basket or Jobs rather than a
standard strategy tab. `FlipScreen` currently wraps tab rows, so one more tab fits without changing
the overlay layout. If the tab enum currently identifies standard candidate tabs by exclusions,
replace that with an explicit structural predicate (for example, a nullable standard
`StrategyKind`) so Recovery cannot accidentally enter `CandidateTable`.

Recovery UI state should be independent:

- `List<RecoveryOpportunity> recoveryRows`;
- selection keyed by auction UUID, never `itemId + kind` (several hosts of the same item coexist);
- its own table/scroller and `renderedRecoveryRevision`;
- a detail panel showing host value, each component and exit venue, removal costs, fees, buffer,
  total floor, expected profit, liquidity evidence, age, and warnings;
- Copy as the only initial action. No Take, Work, Stop, Abandon, or automatic tracking.

## Domain model

Use dedicated immutable recovery records under a package such as `core/recovery` rather than
stretching `FlipCandidate`:

```text
RecoveryOpportunity
  auctionUuid, itemId, displayName, purchasePrice, observedAt
  cleanHostQuote
  componentQuotes[]
  removalCosts[]
  conservativeFloor, expectedProfit, margin
  liquidity / confidence / warnings

RecoveryComponentQuote
  kind, stableComponentId, displayName, quantity
  exitVenue (AH or BAZAAR)
  grossQuickSale, bufferedGross, fee, removalCost, netContribution
  sampleCount / salesPerDay / sellingTime / quotedDepth / warnings
```

Names may change during implementation, but the shape must retain every leg and its evidence. The
UI must be able to explain the floor without reverse-engineering an aggregate number.

Do not add recovery fields directly to `DecodedItem`. Add a detailed decode result containing the
existing `DecodedItem` plus `RecoveryMetadata`, and have both derived from one parsed NBT tree. The
ordinary decoder API should continue returning the same values so existing signatures and tests do
not change. A malformed recovery field should reject or warn on that listing, not abort the active
AH sweep or ordinary snipe generation.

## Valuation rules

For each uncertain resale leg, haircut the gross value before applying percentage and fixed venue
fees. Subtract fixed removal costs after the haircut:

```text
bufferedGross = floor(grossQuickSale * (1 - safetyBuffer))
venueNet      = bufferedGross - feesComputedAt(bufferedGross)
totalFloor    = sum(venueNet) - sum(removalCosts)
profit        = totalFloor - hostPurchasePrice
margin        = profit / hostPurchasePrice
```

Default safety buffer: configurable in the 10-15% range, with 15% as the conservative starting
default. Do not implement `0.85 * (gross - removalCost)`; that incorrectly discounts a known fixed
cost and raises the floor relative to the intended ordering when fees are nonlinear.

### Clean host

The host leg must estimate the stripped configuration, not the bundled listing and not a bare item
unless stripping truly makes it bare. Build a recovery-specific clean-host key/model from realized
ended BIN sales.

- Visible gemstones and legacy attributes already affect the production signature, so stripping
  them requires a deliberate absence/transformation key.
- Drill and rod parts were intentionally excluded from production signatures after measured
  coverage loss. Do not reopen that decision. The recovery host model needs its own presence/absence
  masks so a clean drill is not pooled with a bundled drill just because production valuation is.
- Preserve all nonremoved value-moving terms (rarity, stars, recombobulation, enchantments, reforge,
  books, dye, quality, and other production signature inputs).
- No evidence means no clean-host credit. Never substitute LBIN.

### AH component exits

Value removable AH components from realized, bare standalone BIN sales only. Train by a stable
component identity, use conservative recent statistics, require minimum samples, and expose
liquidity (sales/day and/or median selling time). Active LBIN is context at most, not the floor.
Build the ordinary fair-value and recovery-specific models in one streaming pass over the sales
tape and decode each sale once; a raw day is too large to retain or scan repeatedly.

### Bazaar component exits

Quote the actual component quantity by walking the visible bid depth that an instant sell would
consume. Apply the configured Bazaar tax to the gross quote. Reject partial-depth quotes unless the
unquoted remainder is explicitly assigned zero value and the UI says so. Use moving flow and/or
depth capacity as a liquidity gate; do not size from a top price alone. Keep the existing Hypixel
side inversion straight (`sell_summary` is the bid side received by an instant seller).

### Removal costs and mappings

Keep component identities, removability, and removal costs in explicit, testable recovery data,
not GUI strings. Treat live game mechanics as versioned assumptions. Unknown component ids,
ambiguous NBT, unsupported slots, or an unknown current removal cost fail closed for that component
or listing. Never guess a cost from lore.

### Legacy attributes

Legacy salvage is the highest-risk leg because eligible outputs depend on item family, old
attributes and levels, combinations, and live game rules. NBT presence alone is insufficient.

- Credit shards or feathers only when a tested NBT-to-output rule is supported by captured live
  evidence for that exact case.
- Otherwise show `PREVIEW REQUIRED` and assign zero value to unverified outputs.
- A later manual preview reader may parse the result from `CaptureService` after the player opens
  the relevant menu. It must remain advisory, require no clicks, and bind the preview to the exact
  item/context rather than globally blessing a combination.
- Store evidence and mapping version in the quote/warnings so stale rules are visible.

The safe initial release may ship gemstone, drill, and rod analysis while leaving all legacy
proceeds at zero until preview capture fixtures exist.

## Snapshot and failure isolation

Publish an atomic `AuctionScanSnapshot` (name illustrative) containing both ordinary AH candidates
and recovery opportunities produced from the same completed active-AH sweep. Include separate
ordinary and recovery revisions, or otherwise guarantee that recovery consumers can refresh without
piggybacking on `bazaarRevision`.

The current GUI/HUD candidate cache watches `MarketData.bazaarRevision()`. Do not make recovery
depend on it. Add a small client `RecoveryFeed` that watches the recovery revision and holds the
latest immutable recovery list. Existing candidate invalidation and ranking behavior must remain
unchanged.

One listing's recovery decode or valuation failure must be contained. The sweep must still publish
ordinary auction snipes and all other valid recovery rows. Log diagnostics with bounded frequency;
do not spam once per sweep for the same unknown id.

## Alerts

Add a client-only `RecoveryAlertService` reading `RecoveryFeed` on the client tick. Defaults:
notifications off and sound off. Deduplicate by auction UUID plus the recovery snapshot/revision (or
the material quote fingerprint), with a bounded in-memory TTL cache. Before notifying, confirm the
opportunity is still in the current snapshot and not stale. Do not persist a queue that can replay
old auctions on restart.

Suggested gates, all configurable and validated:

- feature enabled;
- minimum absolute profit;
- minimum margin after buffer;
- safety-buffer percentage;
- minimum AH samples / sales per day and maximum selling time;
- maximum snapshot/listing age;
- optional per-family toggles (gemstone, drill, rod, legacy);
- desktop/chat notification and sound toggles.

Prefer a nested immutable recovery-settings value exposed through the existing mutable config only
if Gson compatibility and `ConfigSchema` coverage remain straightforward. Otherwise use flat fields
consistent with this codebase. Do not cache a derived settings object across `/flip reload`.

## Implementation sequence and checkpoints

Before implementation, inspect `git status`, `git diff`, and `git log`; preserve unrelated work.
Stay on a non-main feature branch. The inspected tree already had unrelated uncommitted changes in
`.claude/output-styles/crisp.md`, `docs/roadmap.md`, and `BazaarTapeTest.java`; verify rather than
assuming those exact changes still exist.

1. **Recovery contracts and math — shipped.** Add immutable quote/opportunity types, warning/confidence
   representation, the conservative floor calculator, and unit tests for buffer/fee/removal order,
   overflow/rounding, missing legs, and zero/invalid inputs. No client UI yet.
2. **Single-pass detailed decode — shipped.** Add `RecoveryMetadata` and a decode wrapper sharing one NBT
   parse with ordinary `DecodedItem`. Add captured fixtures/tests for known gemstone, drill,
   omelette, rod, empty-slot, malformed, and legacy forms. Prove ordinary signatures are unchanged.
3. **Recovery training models — shipped.** In one streamed ended-sales pass, build the existing fair-value
   data plus clean-host and bare-component recovery aggregates. Add absence-mask, sample/liquidity,
   and fail-closed tests. Do not change production signature keying.
4. **Bazaar exits and component catalog — shipped.** Add depth-walk quotes, tax, liquidity gates, explicit
   mappings/removal costs, unknown-id handling, and tests for insufficient depth and side inversion.
5. **Compose the existing active-AH sweep — shipped.** Produce ordinary and recovery results from the same
   fetch/decode path, isolate per-listing failures, and atomically publish `AuctionScanSnapshot`
   with a recovery revision. Regression-test that ordinary AH candidates are byte-for-byte or
   field-for-field unchanged for fixed fixtures.
6. **Client feed, command, and main-screen Recovery tab — shipped.** Add `RecoveryFeed`, a read-only `/flip
   recovery` summary/detail command, and the special tab/renderer. Key selection by auction UUID.
   Keep generic candidate, Take, Work, Bazaar overlay, and ledger paths untouched.
7. **Opt-in alerts and config — shipped.** Add validated schema-covered settings, tick-thread alerting,
   staleness checks, UUID/fingerprint dedupe, bounded TTL, and default-off notifications/sound.
8. **Legacy preview support — deferred; no exact fixture exists.** Parse existing
   `CaptureService` menu records into a preview result and bind it to the exact opportunity. Until
   verified, legacy contributions remain zero with `PREVIEW REQUIRED`.
9. **Verification and documentation — shipped.** Run offline tests with JDK 25, `./gradlew build`, and
   `./gradlew collectorJar`. Confirm no Minecraft imports entered `core`, ordinary fixture outputs
   did not change, old configs load, and the headless collector still packages. The user performs
   real Hypixel testing; there is no dev client.

Final offline verification on 2026-08-28 used Temurin JDK 25: `./gradlew test`,
`./gradlew build`, and `./gradlew collectorJar` all passed. The full suite covers the existing
Bazaar, NPC, craft, combine, fusion, auction-snipe, worked-job, tracking, and ledger paths; the
fixed active-AH fixture produces identical ordinary candidate fields through the composed sweep.
`ConfigSchemaTest` covers every new field, the old-config fixture retains default-off alert
channels, and an import audit found no Minecraft/Fabric dependency under `core`. The built client
jar was installed by the existing Gradle task. No real-Hypixel recovery trade or menu-removal flow
was exercised; that remains user verification.

Follow `CLAUDE.md` checkpoint rules: each coherent step must compile and pass proportional tests,
receive a `wip:` checkpoint commit, and finish squashed into real commits. Do not commit unrelated
working-tree changes.

## Acceptance criteria

- Existing Bazaar, NPC, craft, combine, fusion, snipe, worked-job, overlay, tracking, and ledger
  behavior is unchanged for the same inputs.
- The mod performs no gameplay action and sends no click/inventory packet for recovery features.
- There is still exactly one active-AH network sweep and one decode of each relevant blob per pass.
- Ordinary AH valuation signatures and results remain unchanged.
- Every displayed floor is decomposable into evidence-backed host/component legs, fees, fixed costs,
  and buffer; missing or ambiguous evidence reduces value rather than inventing it.
- Recovery rows and alerts are keyed by auction UUID, revision-aware, stale-checked, and deduplicated.
- Recovery does not enter `StrategyKind`, `CandidateFeed` follow/work, Bazaar overlay/guidance, Take,
  worked jobs, or the ledger in the initial release.
- Legacy outputs are zero unless an exact mapping or captured manual preview proves them.
- Core tests, build, and collector jar pass on JDK 25, and old config files remain valid.

## Deliberately deferred

- Any automated purchase, click, component removal, salvage, or sell action.
- Treating a recovery opportunity as a worked job or automatically opening a ledger position.
- Profit realization/capture-rate accounting for multi-exit recovery operations.
- Active-LBIN-only valuation.
- Reverse salvage beyond the four requested recovery families.
- A second AH poller, a second menu listener, or a production-signature redesign.
