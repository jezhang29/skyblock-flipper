---
name: signature-findings
description: What has been measured about auction signature terms and why each attribute ships or does not — the probe methodology, the six rejected attributes, and the four that shipped (dye, ethermerge, the gemstone-slot bit, the Midas ratio quote). Read before proposing a new signature key term, adding a valuation input, or re-opening a rejected attribute.
---

# Signature findings

Every claim here is measured against `DecodedItem.signature()` on taped `auctions_ended` sales, with a
holdout. The backtests named at the end of each section reproduce the numbers.

**All of these were re-measured on 2026-08-05 against the model that ships, and the numbers below are
the re-measured ones.** Until then every backtest here rebuilt the model's training and lookup path by
hand, and those copies were wrong in four ways that all flattered the pooled arm: no 200-sample ring
where `FairValueModel.Builder` keeps the most recent 200 per key, no rung ladder, no bid-ratio index,
and `isBare` retyped locally with a clause or two missing. A finding taken against that graded a
program nobody runs. **Every conclusion survived the re-measurement; several of the numbers did not.**

The harness is `core/valuation/backtest`: `Backtest.holdout` trains the real `FairValueModel` under a
`Keying` and returns one row per priced held-out sale. Counterfactual arms are `Keying`s —
`CounterfactualKeying.withoutTerm("ethermerge")` unreads a shipped term, `UnreadTerms` carries an
attribute nothing decodes (the scroll, the parts, the raw colour) alongside the sales of one run.
**Do not hand-roll a model in a new backtest.** If the question needs one the harness cannot express,
extend the harness.

One trap it hit on the way, worth not repeating: bareness is **not** "the signature says nothing
beyond id and rarity". A reforge is in the key and not in `isBare`, deliberately, because Hypixel
writes it into the display name the coarse key is built from. Deriving bareness from the key string
denied every reforged item its coarse fallback and silently invented a 12-fake-snipe "improvement"
for the power scroll. `Bareness` in the backtest package is the only copy of the clause list, and
`BarenessTest` pins it to `Keying.PRODUCTION.isBare`.

**Shared item ids are the recurring shape of this bug**, and they fail silently: the sales are
decoded, the medians are computed, and a whole market prices off one key. `SignatureGapProbeTest`
(opt-in, `-PtapeBacktest`) ranks signatures by the p10–p90 spread of a day's realized sales and
prints the `ExtraAttributes` keys nothing reads. That found the shared ids and then went 0 for 3:
`color`, `power_ability_scroll` and the drill parts each topped it, each was worth 100x+ at the item
id, and each measured flat at the key production prices from.

**Rank a candidate by the coins its pooling misprices upward, not by its spread — this is how to
pick the next one.** `UnreadAttributeProbeTest` (opt-in, same flag) walks every unread attribute's
production signatures, keeps only the pools that reach `MIN_SAMPLES` (a smaller pool is quoted by
nothing, so its disagreement costs nothing), and counts the sales the pooled median values at 2x+ of
what their own configuration fetched. On six days of tape it put `ethermerge` first at 1.25B coins
over 207 sales, an order of magnitude clear, where the spread ranking had it eighth — and that one
survived its holdout and shipped. With it read, the list drops to `eman_kills` at 45.5M over 26
sales, a counter with 979 distinct values that would cost 988 valuations to fix 26; everything below
is the same shape. **There was one more gap, and this probe could not see it:** both probes walk only
top-level `ExtraAttributes` keys, and `unlocked_slots` is nested inside the `gems` compound — so the
~60M gemstone-slot gap surfaced in play 2026-09-02, not here (it ships now; see below). Both probes
now split that nested key out. With it fixed the top is again `eman_kills`, so **there is no further
shared-id-shaped gap on this tape**, and the probe's 100M threshold stands as the alarm for a new
top-level one arriving — but check nested compounds by hand the way `unlocked_slots` was missed.
`dungeon_item` reads 1 on 2,218 of 2,223 sales and separates nothing — ignore it.

**`winning_bid` was the exception to that whole frame, and it ships as a valuation input rather than
a key term.** On a Midas weapon the coins burned at the Dark Auction *are* the item, so the attribute
is continuous — 103 distinct values over 439 taped `MIDAS_STAFF` sales — and keying it makes a cell
per sale. What works instead is to pool the **ratio** of sale price to bid over the signature
production already uses and quote `medianRatio × thisItem'sBid`: same sales, same key, different
question, so **it costs no coverage at all** — the first thing measured here that is free. On a 24h
holdout over the six bid-carrying ids, sales valued at 2x+ of what they fetched went 142/512 → 11 and
median |log err| 0.588 → 0.242 at identical coverage; at 48h, 342 → 108 and 2.169 → 0.563. **These
reproduce exactly against the shipped model** — the only Midas figure that moved is banding, now 421
priced and 36 over 2x. Flooring the ratio quote at the pooled median still throws the finding away
(144 over 2x) because the pooled median is the wrong number. Almost all of it is `MIDAS_STAFF`, where 116 training sales quote 27.0M against
a held-out median of 14.0M; `HEGEMONY_ARTIFACT` and `PLASMA_NUCLEUS` sell at 1.05–1.09x their bid and
neither arm ever overvalues one. Aggregate at 48h unmoved: 88.3% / 64.0% / 0.098 / 4,717 configs. See
`MidasBidBacktestTest`, and `FairValueModel.valueOf` for where the ratio index is consulted.

**Not every pooling gap is a shared id, and not every attribute belongs in the key as a number.**
Dungeon quality was both traps at once. `item_tier` earns an exact term — `SKELETON_MASTER_CHESTPLATE`
runs 980k at tier 5 to 113M at tier 10. `baseStatBoostPercentage` runs 1–50 and is **flat below 50**
(medians 48k–74k across every value, near-uniform counts), so it is a `maxed` flag; splitting on the
raw value prices 42 held-out sales where the flag prices 2,588. See `DungeonQuality`.

**What the tier term actually buys changed under re-measurement, and it is the one finding here whose
mechanism was wrong.** On a 24h holdout of the 65 ids that ever carry a roll it prices 2,588 of 7,578
against pooling's 5,886 and takes fake snipes from 1,105 to 467 — but on the 2,588 sales *both* arms
price the two are indistinguishable (424 against 467 over 2x, 157 against 154 wrong by 5x either
way). Nearly all the headline gain is refusing to quote, not quoting better. The term earns its place
on the catastrophic cases instead, and those are visible only in the direction this repo usually
ignores: a tier-10 chestplate that fetched 115M is quoted at 1.8M pooled and 107M keyed, because the
pooled key holds the tier-7 sales. **An undervaluation costs no coins directly and still matters — it
is the model having nothing to say about the market where the coins are.** The old figures (137 → 99
fake snipes, p90 1.281 → 1.194) are the hand-built copy's and are superseded.

**A pooling gap can be real, expensive and still not worth keying — `color` is the case.** Dyed
leather is two attributes, not one, and they are near-disjoint (of 2,091 sales carrying either, 8
carry both). `dye_item` is a named dye and ships. Raw `color` is an `r:g:b` triple and does **not**,
for three measured reasons: it is near-unique per sale where it is dense (632 distinct colours over
660 `SATIN_TROUSERS` sales) so no key can reach `MIN_SAMPLES`; the coarse pool it falls into today
is *right* about it, because the items carrying it densely are fashion items whose whole pool is
coloured; and leaving it in poisons nothing, since a median ignores the two 60M exotics sitting in
`GOBLIN_BOOTS`' 466-sale pool at 12k. Against the shipped model, on a 24h holdout of the 144 ids that
ever carry a colour: **keying it costs 1,043 valuations (11,264 → 10,221) to fix 28 fake snipes (868
→ 840)**, and coloured sales themselves go from 1,023 priced — 800 of them within 1.5x of what they
fetched — to 5. Keying an attribute converts a wrong number into no number — check the wrong number is
actually wrong first. See `DyeSignatureBacktestTest`.

**`power_ability_scroll` is the same answer with none of `color`'s excuses, and it was top of the
list by coins** — 554 sales / 185.8B over six days, one enumerable string of six values, 59 item ids,
and up to 604x within an item id (`RAGNAROCK_AXE` 510M scrolled against 844k plain). At the
production signature it disappears: 30 keys pool a scroll with anything else at all, they agree to
1.6x everywhere but two, and **no mixed pool overvalues its plain sales**. Against the shipped model,
on a 24h holdout of the 59 scroll-carrying ids: keying it prices 3,953 sales against 3,968 unread,
leaves fake snipes at **39 either way**, and moves neither the median (0.062) nor the p90 (0.317) at
all. Scrolled sales priced go 17 → 2, and 17 of the 17 were within 1.5x of what they fetched. Not one
of the 39 overvaluations involves a scrolled sale in either arm. The mechanism
is that scrolled sales dominate their own key — nine of ten sales under one `HYPERION` signature
carry a Sapphire scroll — so the median they price against is already a scrolled median, and
splitting leaves a cell of nine and a cell of one that `MIN_SAMPLES` rejects. Unlike `dye_item` it is
not free: 24 of 554 scroll sales are otherwise bare, so the term costs coarse coverage too. See
`PowerScrollBacktestTest`.

**The drill parts are the third no-op and the cheapest one to have skipped.** 405 sales / 97.3B over
six days across `drill_part_*`, the `engine`/`fuel_tank`/`upgrade_module` compounds, `polarvoid` and
`divan_powder_coating`, and a built drill genuinely runs 1.2x–2.4x a bare one at the same key
(`MITHRIL_DRILL_2` 42.9M against 17.7M). It measures out because **the market is tiny and the parts
are nearly an identifier**: 405 sales over 69 configurations and 314 signatures, so a part term makes
cells of one. Against the shipped model, on a 24h holdout of the sixteen drill ids: unread prices 876
of 1,680 with 10 fake snipes, and both the full part term and a single "something was installed" bit
price 860 with **the same 10**, at an identical median (0.046) and p90 (0.212). The pooled key prices
5 parted sales and is within 1.5x on 4; both terms price zero. Of the 75 mixed signatures, 52 never
reach `MIN_SAMPLES` and are quoted by nothing; of the 23 that are, none overvalues an unparted drill.
Read both formats or the measurement is wrong: `drill_part_engine` is a lowercase id and the `engine`
compound holds the same id uppercase, 103 sales against 59, neither a superset. See
`DrillPartBacktestTest`.

**`ethermerge` ships, and it is the one that broke the streak.** 516 sales / 13.8B on
`ASPECT_OF_THE_VOID` and `ASPECT_OF_THE_END`; one signature holds 396 plain sales at 5.9M beside 288
merged at 24.9M and quotes 6.1M for both. Three things separate it from the scroll and the drill
parts: it is **one bit**, so a split halves a pool instead of shattering it; **plain sales dominate
the mixed pools**, the exact opposite of the scroll, so both cells still clear `MIN_SAMPLES`; and it
is nearly free. Against the shipped model, on a 24h holdout of the merging ids: fake snipes **175 →
20**, p90 |log err| 1.440 → 0.230, median 0.107 → 0.065, for 7 valuations in 1,053. The re-measurement
made the case *stronger* — the hand-built copy scored only 13 fake snipes unread, because keeping
every sample instead of the newest 200 hid the failure mode: on a key pooling two populations the
ring fills with whichever sold lately, so the pooled median swings to the dearer one and every cheap
sale reads as a snipe. Aggregate at 48h unmoved (88.3% / 64.0% / 0.098 / 4,717 configs).
`tuned_transmission` stays out — it only rides on a merged item, is worth 1.06x on top of it, and
costs seven more valuations for no fewer overvaluations. The `isBare` guard is load-bearing here: 315
of the 516 merged sales carry nothing else, so unguarded they join the coarse pool and every plain
Aspect of the Void gets quoted off sales worth 4x it. See `EthermergeBacktestTest`.

**The gemstone unlocked-slot bit ships, and it is `ethermerge`'s twin — the second bit, and the first
gap found in play rather than by the probe.** A gemstone slot paid open costs real coins and
materials, so a Divan's piece with its slots open is worth far more than the same piece shut — ~76M
against ~38M on the tape for a mythic jaded recomb Divan's Helmet — yet unread the two share one
signature and the pooled median quotes the locked one at the unlocked price. It hid from the harm
probe because `unlocked_slots` is nested inside the `gems` compound the probe marked read for its
placed gems, so it reached the tape as a ~60M fake snipe on locked Divan pieces (2026-09-02) instead
of a probe entry. Like `ethermerge` it is one bit, the plain (locked) sales can dominate a mixed pool,
and it is cheap. On a 24h holdout of the 283 ids that ever unlock a slot: fake snipes 632 → 622,
median |log err| 0.127 → 0.126, p90 0.547 → 0.545, for 102 valuations in 18,656. Small in aggregate
because the expensive cases are rare in a single day, and it works mostly by **refusing to quote** the
locked minority of a mixed pool — `item_tier`'s mechanism — not by quoting it better. **It ships as a
bit, not the exact count:** the count tied the bit on fake snipes (621 against 622) and error and
shattered more pools (18,529 priced against 18,554; 204 open-slot sales priced against 229), because
intermediate slot counts (1–4) are too sparse on the tape to form a pool. The slot *index* (which
hole) is dropped, like placed gems: it does not move price. The `isBare` guard is load-bearing as it
was for the merge: 167 of the unlocked-slot sales are otherwise bare, so unguarded a paid-open piece
reads as bare and prices off the coarse pool of locked and gemmed ones. See `GemstoneSlotBacktestTest`.

**Measure a signature split against the tail, not the median.** The median sale under a pooled key
is whatever dominates its count, and pooling is accidentally right about that item — splitting
`POTION` made the median error slightly *worse* on the tape the finding was first taken on, while
cutting p90 by 7x. What matters is how often the key values a sale at 2x or more of what it fetched,
because a valuation is only acted on when it sits far above the asking price. Against the shipped
model, on a 24h holdout of every potion sale: fake snipes **454 → 62**, p90 3.534 → 0.561, median
0.152 → 0.118, for 60 valuations in 1,570. See `PotionSignatureBacktestTest`.

**The rune and pet-level splits reproduce too, and the pet ladder is the only term here that costs
nothing.** Runes, on a 24h holdout of the 128 ids that ever carry one: fake snipes 857 → 496, p90
0.981 → 0.693, median 0.155 → 0.143, coverage 11,259 → 11,143. Pet levels, over every pet sale: median
0.129 → 0.105, p90 0.799 → 0.531, fake snipes 542 → 473, at **identical coverage** (12,021 both), which
is the fallback ladder doing exactly its job. The old note that the level is within noise above 10M is
superseded — on 3,470 held-out sales over 10M it runs 0.103 without against 0.071 with. See
`RuneSignatureBacktestTest` and `PetLevelBacktestTest`.

**A signature term measured to change nothing is not automatically dead weight.** The `maxed` flag
splits no key at all — of 716 keys holding a maxed sale, none holds an unmaxed one — because stars,
hot potatoes and enchantments already fingerprint an invested item. It ships anyway: it costs zero
coverage, and at a coarse key maxedness is worth 44x on `SKELETON_MASTER_CHESTPLATE` tier 10 (110M
against 2.5M), so the correlation covering that hole is one nothing enforces. Check what a redundant
term would cost before removing it. `dye=` ships on the same footing: 67 of 587 dyed keys hold an
undyed sale and they run only 0.9x–2.1x at the production key, against 833x at the bare item id
(`SKELETON_MASTER_CHESTPLATE`, 200M dyed against 240k plain) — **the gap between those two numbers
is the investment terms doing the separating, not the dye.** Measure a term against the key the
model actually uses; the item id overstates every one of these by an order of magnitude or two.

**Six for six on that last point now** (pet levels, the maxed flag, the dye, the scroll, the drill
parts, the merge). A gap that is huge at the item id and flat at the production key is the normal
case, not the surprising one — even `ethermerge`, the one that shipped, is 4x at the real key against
73x at the bare id. Any candidate gets measured at `DecodedItem.signature()` first, and the two
questions are whether mixed pools *overvalue* the plain side and what the term costs in coverage.
**When the answer to both is bad and the attribute is a number the price scales with, ask the ratio
question instead of the key question** — that is what turned `winning_bid` from untouchable into the
one free win on the list.
