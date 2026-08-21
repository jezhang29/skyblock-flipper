# Enchanted-book combine flipping

The measured record for the `COMBINE` strategy seat. Read this before writing combine code, and
before re-opening the "reuse `CraftQuote`" line, which the exit gate below rules out.

Every figure here is offline: one live bazaar snapshot of 2026-08-20 plus the id scheme confirmed
against `/v2/skyblock/bazaar` (773 `ENCHANTMENT_*` products of 2,124 total). Nothing has been
combined and sold on Hypixel under this strategy yet.

## The shape of the trade

An enchanted book of tier `T` is two books of tier `T-1` combined at the anvil, all the way down.
So a high-tier book is `2^(T-k)` low-tier books plus `2^(T-k) - 1` anvil combines. The bazaar lists
the low tiers cheap (they drop, or come off the enchant table) and the high tiers dear (nobody wants
the tedium), and the two prices are set by different crowds. That gap is the edge.

The flip, per output book:

1. **Source** `2^(T-k)` books of the cheapest liquid tier `k`, on a resting **buy order** one
   increment above the best bid (or instant-bought at the ask where the source has no bid side).
2. **Combine** them straight up to tier `T`. The middle tiers are traded through, never traded:
   they are nearly dead books (`ENCHANTMENT_FEATHER_FALLING_8` sold 160 units all week) and you
   only pass through them. `2^(T-k) - 1` anvil combines, assumed coin-free.
3. **Exit** with a **sell offer** one increment under the best ask on tier `T`, and wait for someone
   to instant-buy it.

This is a single-ingredient craft in every respect but two, which is why it is not routed through
`CraftQuote`:

- **The exit book is one-sided.** The best combine targets have a deep ask side and no bid side at
  all: `ENCHANTMENT_FEATHER_FALLING_10` rests 37 ask orders and 0 bid orders. `CraftQuote.liquid()`
  requires `MIN_ORDERS_PER_SIDE` on **both** sides and a two-sided spread, so it rejects exactly the
  books this strategy sells into. The combine exit gate is ask-orders-only (below).
- **The transform is an anvil merge, not a recipe.** The click cost is `2^(T-k) - 1` combines per
  output, and that count is the whole cost model for a player who cannot grind — see
  [[npc-flipping-clicks-are-the-cost]]. `CraftJob` renders one "Craft" row and hides it.

## Combinability is game data, never price-inferable

The single decision this strategy rests on. A large price gap between two tiers does **not** mean
they are combinable, and a small gap does not mean they are not. The bazaar lists a book only when
it cannot come straight off the enchant table, so a listed low tier can still be non-anvil (drop-
gated) and a huge gap can be pure tedium premium. There is no enchantments resource and the items
resource does not carry books, so the API cannot answer which enchants combine. The source of truth
is a hardcoded table from the wiki and the user's spec, validated only for **existence** against the
live bazaar. `CombineTable` is that table; adding an enchant to it is a game-rule claim, not a
price observation.

The table ships as an allowlist of `(enchant id, min bazaar source tier, max combinable tier)`. The
excluded enchants — experiment, champion, compact, cultivating, expertise, hecatomb (bit-levelled),
Scavenger, Growth, Power, Protection, Vampirism — are drop-gated or non-anvil and must stay out.

## The measured economics, 2026-08-20

**Selection rule changed 2026-08-20.** The table below was produced by an earlier rule that kept the
source tier with the best net **per anvil combine**. The shipped rule now keeps the tier with the
best total profit **per output book** after tax. At these volumes — a few tens of books a day — the
anvil grind never binds, so ranking the tier pick on clicks was trading real coins to skip merges
that are not scarce: on Rejuvenate the fewer-merges tier 3 cost ~65k more per output book than tier 2
to save four merges. So the `source → T` column below records the old pick; under the current rule
several enchants source from a lower, cheaper tier — Rejuvenate from tier 2 (8 books, 7 merges), not
tier 3. The net/output and profit/hr for a given tier do not change; only which tier is chosen moves.
Re-measure against a live snapshot before quoting the picks. Net per combine is still reported and
still orders the `/flip combine` list.

Run of the earlier `BazaarCombineStrategy` against the live book. For each enchant the solver scans
every bazaar-listed source tier below the max, sources it on the cheaper of a resting order (where
the source book has ≥15 bid orders) or an instant buy, exits on a tier-`T` sell offer taxed at
Bazaar Flipper 1 (1.125%), and kept the tier and route that paid the most **per anvil combine**.
Profit per hour is at the 5% flow share the mod already assumes, sized off `quick_status` volumes
with no displacement — treat it as a ceiling, not a promise. The list is ordered by net per combine,
which is how `/flip combine` orders it.

| enchant | source → T | merges/output | net/combine | net/output | ~profit/hr |
|---|---|---|---|---|---|
| Green Thumb | 1 → 5 | 15 | 437,625 | 6,564,379 | 1.09M |
| Turbo-Potato | 3 → 5 | 3 | 402,776 | 1,208,327 | 14k |
| Prosperity | 1 → 5 | 15 | 272,738 | 4,091,074 | 142k |
| Hardened Mana (Vitality) | 5 → 10 | 31 | 266,406 | 8,258,596 | 189k |
| Turbo-Cane | 1 → 5 | 15 | 87,920 | 1,318,797 | 255k |
| Charm | 1 → 5 | 15 | 68,014 | 1,020,205 | 549k |
| Rejuvenate | 3 → 5 | 3 | 63,739 | 191,217 | 191k |
| Feather Falling | 6 → 10 | 15 | 10,184 | 152,764 | 29k |

The strategy is a **net-per-combine** business, not a coins-per-hour one, and the solver optimises
that: it takes the cheapest way to make one top-tier book, not the fastest. Two consequences to
read the table with:

- **The chosen source tier is not always the bottom one.** It is the tier that makes one output book
  for the fewest coins after tax. On this snapshot Rejuvenate's cheapest source is tier 2, not tier 1,
  because tier 1 carries a fat bid from combiner demand; the solver sources from tier 2. It is not
  tier 3 either: tier 3 makes a tier-5 in three merges against tier 2's seven, but costs ~65k more per
  output book, and at a few tens of books a day those four saved merges are not worth 65k. An earlier
  rule ranked the tier pick on net per combine and picked tier 3 for its fewer clicks; it was dropped
  2026-08-20 because the click budget never binds at this volume, while capital and patience are slack.
- **Profit per hour is honest but is not the ranking.** It stays the shared `FlipCandidate` axis so
  the unified `/flip` list can compare a combine against an NPC flip, where a combine sits low on
  purpose. The cheapest source tier can be a book that fills slowly, so a row's profit-per-hour and
  fill time are the check on whether "cheapest per book" also means "ever fills".

**The ≥15-ask gate is a price check, not a liquidity one, so ordering by net per combine floats thin
whale flips to the top.** `VICIOUS_5` topped the live run at 67.8M a combine on a single merge, resting
on 19 ask orders at 71M with a demand of 125 books a week — a real price by the gate, but one output
every few weeks. Treat the biggest per-combine rows as set-and-forget bets that are unverified in
play, and read the profit-per-hour beside them.

### Re-measured live, 2026-08-21

The shipped `BazaarCombineStrategy` run against a fresh book
(`LiveApiTest.printLiveCombinePicks`, `-PliveApi`), Bazaar Flipper 1, 1h horizon, 5% flow, ordered by
net per combine as `/flip combine` is. This is the **current** rule — best total profit per output
book — so every source tier below is the shipped pick, not the 2026-08-20 one above.

| enchant | source → T | merges/output | net/combine | net/output | ~profit/hr |
|---|---|---|---|---|---|
| Vicious | 4 → 5 | 1 | 65,325,354 | 65,325,354 | 117k |
| Dedication | 2 → 3 | 1 | 1,789,162 | 1,789,162 | 12k |
| Green Thumb | 1 → 5 | 15 | 409,093 | 6,136,397 | 1.03M |
| Hardened Mana (Vitality) | 3 → 10 | 127 | 132,183 | 16,787,189 | 17k |
| Turbo-Cane | 1 → 5 | 15 | 88,905 | 1,333,575 | 261k |
| Charm | 1 → 5 | 15 | 63,081 | 946,222 | 510k |
| Rejuvenate | 2 → 5 | 7 | 45,841 | 320,885 | 321k |
| Turbo-Melon | 1 → 5 | 15 | 41,917 | 628,762 | 233k |

Seventeen enchants cleared; the rows above are the notable ones. Three things moved against the
2026-08-20 run, and each is the market, not a rule change:

- **`VICIOUS_5` still tops the list** — 65.3M a combine on one merge, resting on ≥15 ask orders so the
  gate admits it, and one output every few weeks. The set-and-forget caveat above stands.
- **Prosperity and Feather Falling 10 dropped out.** Prosperity 5 now rests 10 target ask orders,
  under the 15 gate; Feather Falling 10 passes the gate at 36 ask orders but no source tier still
  clears money after tax. Both headlined the old table; the book moved.
- **Hardened Mana 10 now sources from tier 3, 127 merges an output, not tier 5's 31.** Tier 3 became
  the cheapest liquid source per output book, so the profit-per-output rule reaches for it. 127 anvil
  merges for one book is deep in set-and-forget territory — read net per combine (132k), not the fat
  16.8M net per output, before working it.

Rejuvenate's shipped pick is confirmed tier 2 (7 merges), the lower, cheaper source the 2026-08-20
rule change was made to take.

### The ≥15-ask-order gate does the work

The gate is the target's resting **ask** order count, and it is what separates a real edge from a
single-order fantasy price. Rejected on this snapshot for fewer than 15 target ask orders, every one
of them a "flip" that would otherwise headline:

| enchant | target ask orders | net/output it falsely claims |
|---|---|---|
| Strong Mana | 10 | 25,135,179 |
| Overload | 5 | 3,552,650 |
| Smoldering | 5 | 4,982,773 |
| Mana Vampire | 12 | 2,901,962 |
| Turbo-Carrot | 5 | 1,991,764 |

A price a dozen sellers agree on is a price; a price one seller posts at 26M is a listing nobody has
tested. The gate reads ask orders rather than a bid side because the target of a combine is sold
into instant-buy demand and its bid side is naturally empty — the people who want the book combine
it themselves rather than rest a buy order. This is the same failure shape the repo already records
for shared item ids: the wrong number is silent and plausible right up to the click.

### The source tier is scanned, not assumed

The best source is not the bottom tier, so the solver quotes every listed tier and keeps the cheapest
per output book after tax. On this snapshot Rejuvenate tier 1 carries a fat bid (9,003, on 183k weekly
dumps of combiner demand) while tier 2's is 4,867, so tier 2 is cheaper per output despite being
higher, and tier 2 is the shipped pick. The scan also handles the Vitality enchants, whose lowest
liquid source floats: tier 5 on 2026-08-20, but tier 3 on 2026-08-21, and the scan takes whichever is
cheapest per output that day. It does not climb higher for the sake of fewer merges: tier 3
makes a Rejuvenate 5 in three merges against tier 2's seven but costs ~65k more per book. An earlier
rule ranked the tier pick on net per merge and so chose tier 3; it was dropped 2026-08-20 because at
these volumes the anvil grind never binds, while capital and patience are slack.

### Sell via offer, source via order

Measured 2026-08-16 and unchanged in shape here. Instant-dumping the output into the bid side is
negative almost everywhere, because high-tier spreads are enormous (Hardened 10 bid 9.0M against ask
26M). The exit is always a sell offer at the ask. The source is bought on a resting order at the bid
where the source book has a real bid side, which is 3-40% cheaper than the ask, and instant-bought
only where it has none (Feather Falling 6 rests 0 bid orders, so it is taken at its 30-coin ask).

## Following one in play

Selecting a combine row in the flip screen and pressing **Work** makes the bazaar overlay carry that
job, the same way it carries a chosen craft. `CombineJob` is the one source the panel and the `/flip combine` step text
both read from, so the two cannot disagree by a tenth of a coin — the reason craft routes through
`CraftJob`. The job is three stations, each counted in its own unit: the **source buy** (books, on a
resting order at the bid or an instant buy where the source has none), the **anvil** (merges, e.g. 15
for a Feather Falling 10, not one output), and the **sell offer** (output books, split into orders
the box will take). It is re-quoted from the live book every poll, so a flip that stops clearing
while it is worked shows "no longer clears" rather than a stale price. `/flip combine stop` drops
every combine; `/flip jobs stop <name>` drops one.

There is **no green box** on a combine row: the slot highlighter works a slot out from an NPC task
and has nothing to say about a combine, so the panel names the price and the player finds the button.
A combine is worked **beside** a craft or a spread rather than instead of one, and it keeps its
section on the panel until it is stopped — changed 2026-08-21, see `docs/worked-flips.md`. Selecting
a row no longer commits anything; the Work button does.

## Throughput and its cap

Every book is thin. The exit rate is the target's instant-buy demand at the 5% share; the source
rate is the dump flow into the source's bid at the same share, divided by `2^(T-k)`. The plan runs
at the slower of the two, sized by `FillModel` exactly as the craft and spread strategies size their
resting legs. The middle tiers never bind because they are never traded.

The absolute volumes are small — a few tens of output books a day on the busiest enchant — so the
combine list will sit low in the unified ranking and that is honest. Its reason to exist is the
per-click return, not the per-hour one.

## What it refuses

Empty, never a partial answer, when:

1. **The target rests fewer than 15 ask orders**, or has no ask price. The fantasy-listing gate.
2. **No source tier is both listed and priceable.** A bill that silently drops the source it could
   not find understates the cost, which always reads as a better deal than it is.
3. **Nothing clears**, or the best source tier still loses money after tax.

## Still unmeasured

- **The anvil is assumed coin-free.** Book-on-book combines carry no coin cost in game as far as the
  spec records, but this has not been checked in play; a per-combine coin cost would eat the
  per-click margin from the bottom.
- **Nothing here has been combined and sold on Hypixel.** The open questions play answers: whether a
  source buy order at the bid actually fills at the rate `FillModel` predicts, whether a competitor
  parks under your sell offer the way the NPC drift premium was killed for, and whether the anvil
  click count at scale is bearable — 15 combines per Feather Falling 10 is a lot of anvil.
- **Displacement on these specific books is not on the tape yet.** The profit-per-hour column is the
  unmeasured 5% fallback; once the tape covers these ids `FillModel` will size the legs from history,
  and the fill-rate saturation caveat in `docs/roadmap.md` applies here too.
