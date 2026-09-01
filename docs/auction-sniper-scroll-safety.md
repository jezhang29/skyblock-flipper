# Auction sniper scroll-safety plan

## Finding

The auction-value path currently does not decode `ExtraAttributes.ability_scroll`. That field carries
the Wither blade scroll set (`IMPLOSION_SCROLL`, `SHADOW_WARP_SCROLL`, and `WITHER_SHIELD_SCROLL`).
Because it is absent from `DecodedItem` and `DecodedItem.signature()`, otherwise-identical scrolled
and unscrolled Hyperions share one valuation pool.

This is separate from `power_ability_scroll`, the gemstone power-scroll field covered by
`PowerScrollBacktestTest`.

The current local tape reproduced the reported failure:

| Variant | 48-hour sales | Median |
|---|---:|---:|
| Hyperion, no Wither scrolls | 91 | 510M |
| Hyperion, all three Wither scrolls | 142 | 1.064B |

Several identical production signatures pooled the variants. One pool quoted 1.038B while its
unscrolled subgroup sold around 510M. A 12-hour holdout produced two unscrolled sales quoted at
1.082B when they fetched 504M (2.15x overvaluation). Adding `ability_scroll` as a key removed both
over-2x errors in that holdout and reduced p90 log error from 0.764 to 0.045, at the cost of pricing
two fewer sales (14 instead of 16).

The existing unread-attribute alarm also failed on the current tape: `ability_scroll` ranked first,
with 7.78B of upward historical mispricing, far above its 100M investigation threshold.

## Implementation order

### P0 — contain the loss

Temporarily suppress ordinary and recovery auction valuations for Wither-blade families until the
permanent keying fix is installed. At minimum, do not trust any Hyperion, Astraea, Scylla, Valkyrie,
or related blade snipe that was produced by the old model.

### P1 — decode and key the missing state

1. Add an immutable normalized `abilityScrolls` collection to `DecodedItem`.
2. Read `ExtraAttributes.ability_scroll` from both legacy and current blobs.
3. Canonicalize ordering, reject malformed entries safely, and distinguish no scrolls, each partial
   set, and the complete set.
4. Append a stable `abilityScrolls=...` term to the signature.
5. Mark scroll-capable blade families as ineligible for coarse fallback when scroll state is absent;
   otherwise a low-volume unscrolled blade could still inherit a coarse pool containing scrolled
   sales.
6. Preserve this state in recovery clean-host keys as well as ordinary auction keys.

### P1 — regression coverage

Add real NBT fixtures and tests for:

- no, one, two, and all three Wither scrolls;
- reordered list entries and duplicate entries;
- malformed or unknown list values;
- exact signature separation;
- no coarse fallback for scroll-capable blades;
- fewer-than-six comparable sales returning no valuation;
- `ItemDecoder → FairValueModel → UnderpricedScan → AuctionValueStrategy` end to end;
- recovery clean-host valuation retaining scroll state.

The critical regression is: high-value fully scrolled sales plus an unscrolled Hyperion listed near
its own 500M market value must not create a giant-profit candidate.

### P1 — evidence shown to the player

Auction candidates must state `no Wither scrolls`, the partial set, or `3/3 Wither scrolls` in the
verification text. Preserve the source auction UUID so a candidate can be audited or opened against
the exact listing rather than found by an ambiguous name search.

### P2 — backtest and release gates

1. Make the unread-attribute probe render list values canonically; it currently reduces list-shaped
   attributes to an unhelpful generic value.
2. Run rolling 6-, 12-, and 24-hour holdouts over every retained tape day using the shipped 48-hour
   training window.
3. Require zero unscrolled Wither-blade quotes at 2x or more, and report absolute coin exposure,
   p90 error, coverage, sample counts, and resale-rate changes.
4. Run the unread-field alarm against the newest tape before every valuation release.
5. Add a high-ticket quarantine experiment for multimodal or incomplete estimates. Ship a circuit
   breaker only if the whole-market holdout demonstrates a gain; do not invent an arbitrary 300M cap.

## Review

This plan fixes the comparable-sales boundary, which is the root cause. A haircut, lower confidence
setting, or permanent Hyperion blacklist would only hide the incorrect pooled estimate. The expected
coverage loss is an intentional fail-closed tradeoff: two sparse valuations disappear, while the
observed 500M-versus-1B false-profit path disappears as well.

The temporary block is only a containment step and should be removed after the signature, coarse
fallback, recovery, end-to-end, and rolling-holdout gates pass. The existing `power_ability_scroll`
finding must not be reopened or reused for this fix.

No exact auction UUID was recoverable from the prior incident because ordinary candidates discard it;
that evidence-loss is itself included in the repair.
