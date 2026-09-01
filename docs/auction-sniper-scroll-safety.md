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

Completed offline 2026-09-01 against the eight retained UTC tape days 2026-08-25 through
2026-09-01. `Backtest.holdout` streamed each arm through the real `FairValueModel`; the corrected
arm was `Keying.PRODUCTION`, and the pre-fix arm was
`CounterfactualKeying.withoutTerm("abilityScrolls=")`. Each day was anchored at its end (the newest
record for the partial current day), with 6-, 12-, and 24-hour holdouts and the shipped 48-hour
training window. The first retained day necessarily has no earlier raw day to train from and fails
closed; it remains in every denominator rather than being silently discarded.

The dangerous-exposure figure below is absolute quoted excess (`estimate - realized`) on an
unscrolled blade only when the quote was at least 2x realized price. Counts are holdout observations
across the eight daily anchors for that horizon; they do not combine horizons, so a sale is not
triple-counted inside a row.

| Holdout | Arm | Priced / held | Coverage | p90 abs log error | Unscrolled >=2x exposure | Median resale rate |
|---|---|---:|---:|---:|---:|---:|
| 6h | ability-scroll unread | 81 / 296 | 27.36% | 0.709 | 2.842B | 0.479 sales/h |
| 6h | corrected | 70 / 296 | 23.65% | 0.033 | **0** | 0.417 sales/h (-13.0%) |
| 12h | ability-scroll unread | 122 / 565 | 21.59% | 0.703 | 3.462B | 0.313 sales/h |
| 12h | corrected | 109 / 565 | 19.29% | 0.039 | **0** | 0.292 sales/h (-6.7%) |
| 24h | ability-scroll unread | 190 / 924 | 20.56% | 0.703 | 3.466B | 0.458 sales/h |
| 24h | corrected | 162 / 924 | 17.53% | 0.037 | **0** | 0.375 sales/h (-18.2%) |

All 24 corrected day/horizon cells have zero unscrolled quotes at 2x or more. The pre-fix arm
reproduces the failure in every horizon on 2026-08-30, 2026-08-31, and the partial 2026-09-01 day.
The coverage and resale-rate reductions are expected: the old arm called the combined scroll pool
one fast-selling configuration, while the corrected arm requires six sales of the exact scroll set.

Per-variant holdout and model sample counts (`I` = Implosion, `S` = Shadow Warp, `W` = Wither
Shield, and `ISW` = all three):

| Holdout | Held out (none, I, S, W, ISW) | Priced pre-fix | Priced corrected | Median backing samples pre-fix -> corrected |
|---|---|---|---|---|
| 6h | 108, 7, 1, 2, 178 | 57, 0, 0, 0, 24 | 52, 0, 0, 0, 18 | none: 23 -> 22; ISW: 13 -> 13 |
| 12h | 222, 7, 3, 3, 330 | 90, 0, 0, 0, 32 | 84, 0, 0, 0, 25 | none: 25 -> 22; ISW: 13 -> 11 |
| 24h | 374, 11, 6, 6, 527 | 137, 1, 0, 0, 52 | 123, 0, 0, 0, 39 | none: 25 -> 25; I: 25 -> unpriced; ISW: 13 -> 11 |

The unread-value renderer now sorts and spells list contents instead of collapsing them to `-1`.
Before `ability_scroll` was added to the probe's decoded-field set, the retained tape named the
actual dominant configuration as
`[IMPLOSION_SCROLL,SHADOW_WARP_SCROLL,WITHER_SHIELD_SCROLL]` (477 of 499 scroll-bearing sales),
followed by `[IMPLOSION_SCROLL]` (11), `[SHADOW_WARP_SCROLL]` (6), and
`[WITHER_SHIELD_SCROLL]` (5). This is the canonical evidence behind the pre-P1 7.78B alarm, not a
generic list marker.

The final 100M release alarm ran only against the newest UTC tape day, 2026-09-01, and passed. Its
top upward entries, including their canonical leading values, were:

| Unread field | Upward coins | Leading canonical values |
|---|---:|---|
| `bossId` | 2,684,999 | `a5cd2d97-e256-4a00-88bd-1c750a16b0b2` (4), `114bc507-5ef7-4dd1-8259-de292fc9d263` (3), `40fd1a3a-be58-4445-91be-2f83ace67198` (3) |
| `spawnedFor` | 2,684,999 | `9c063d8b-557c-43e3-a0d0-ab2b487226c6` (13), `dd4d55d9-2b58-4c59-b581-d13d1ff512fa` (12), `77100e98-b546-495e-afaa-7717ff18f478` (10) |
| `color` | 1,590,000 | `0:0:0` (17), `204:85:0` (7), `41:240:233` (6) |
| `boss_tier` | 1,109,997 | `4` (289), `0` (161), `2` (143) |
| `new_years_cake` | 1,100,000 | `510` (9), `509` (5), `504` (3) |

The whole-market quarantine experiment used the newest 24-hour holdout under
`Keying.PRODUCTION`. "High ticket" was the holdout's top quote decile (13.5M on this tape), not a
fixed coin cap. Within it, the experiment quarantined estimates already carrying a shipped warning:
fewer than 12 samples, dispersion above 0.4, or a non-exact basis. It selected 1,876 of 134,099
priced sales (1.40%).

| Whole-market metric | Before | After quarantine | Delta |
|---|---:|---:|---:|
| p90 abs log error | 0.583 | 0.586 | **+0.002 (worse)** |
| Coverage by count | 87.95% | 86.72% | -1.23pp |
| Coverage by realized coins | 62.74% | 52.78% | -9.95pp |
| Quotes >=2x realized | 4,945 | 4,864 | -81 |
| >=2x quoted excess | 4.158B | 2.360B | -1.799B |

**Quarantine verdict: no-go.** It removes some high-coin errors, but does not improve whole-market
p90 and gives up nearly ten percentage points of coin coverage to do it. No circuit breaker or coin
cap ships from this experiment.

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
