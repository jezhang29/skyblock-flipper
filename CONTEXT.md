# CONTEXT

The vocabulary this codebase prices items in. A term here means exactly one thing; if code needs a
second meaning, it needs a second word.

## Signature

The string describing one item configuration precisely enough that two items sharing it are worth
about the same. Built by `DecodedItem.signature()` from sorted parts, so the same configuration
always produces the same string whatever order the blob listed things in.

**Measure any candidate valuation input at the signature, never at the bare item id** — the item id
overstates every one of these by an order of magnitude or two, because whole markets share one id.

## Coarse key

Name and rarity, and nothing else. Readable off a live listing without decoding `item_bytes`, which
is the only reason it exists: it throws away the ~46,000 listings nowhere near mispriced before
anything expensive happens.

Not a cheap signature. A coarse key describes an item completely only when the item is **bare**.

## Bare

Carrying nothing a name-and-rarity match could have missed: no stars, no recombobulation, no
enchantments, gemstones, attribute rolls, runes, hot potato books, dye, ethermerge, quality roll or
Dark Auction bid, and not a pet or a potion. A bare item, and only a bare item, may be priced off the
coarse index.

**A reforge does not count**, even though it is in the signature. Hypixel writes it into the display
name — "Heroic Aspect of the End" — so the coarse key already separates a reforged item from a plain
one. Bareness is therefore *not* "the signature says nothing beyond id and rarity"; deriving it that
way denies every reforged item the coarse fallback it has in production, which is a bug this repo has
already shipped once into a backtest. `Keying.PRODUCTION.isBare` is the statement; `Bareness` in the
backtest package is the only copy, and a test pins it to production's answer.

## Keying

How an item is described for pricing: which keys it may be valued under, whether its Dark Auction
bid gives a ratio to scale, and whether it is bare. `Keying.PRODUCTION` is what ships.

The seam exists because a backtest's question is almost always counterfactual — *what would this
cost if the ethermerge were unread*, *what if a finer term shipped* — and production emits exactly
one keying. A backtest passes a different `Keying` and gets the real `FairValueModel` around it.

## Rung

One step of the ladder `valueOf` walks, most specific first, taking the first key with enough sales
behind it. Only pets have more than one rung today. Anything past the first describes the item less
completely and is labelled `BANDED` so the confidence it earns is discounted.

## Holdout

The newest slice of the tape, withheld from training and then priced from a model built on
everything older. How every valuation claim in this repo is justified. A finding measured any other
way is not a finding.

## Tape

The append-only record of what actually sold. `SalesTape` holds ended auctions, `BazaarTape` holds
book samples. A day of sales tape is ~265MB, so **nothing may hold one in memory** — both tapes
stream day files a line at a time.

Valuation trains on the tape and never on active listings, which are contaminated by exactly the
mispricings being hunted.

## Rollup

The per-day `daily.jsonl` summary, 67x smaller than the raw day and never pruned. What carries price
history past the retention window. Keyed by signature, and carrying no schema version — so a change
to how signatures are spelled silently splits every key into a before and an after, and that history
does not come back. See `docs/adr/0001-defer-the-signature-term-model.md`.

## Yardstick

The frozen post price a reprice row is judged worked against: where the book was when the round
opened, held still for the interval. `NpcRound.Row.postPrice`. It is what `NpcRound.outstanding`
calls a row done or outstanding against, and what `NpcWorklist.reserve` sizes the held slot and coins
on — both have to be the number the player actually acted on. It is **not** the number the panel
tells anyone to type; that is the live reprice. See `docs/adr/0002-reprice-in-rounds.md`.

## Live reprice

What to type into the price box now, re-read off the snapshot in hand every trip, because the player
is standing in front of Hypixel's own "+0.1 coins" button which reads the live book. The counterpart
to the yardstick: the round freezes *which* items to work, never *what to type* for them. Computed
once per row by `NpcReprice.repriceNow`, which also carries the chase stop and whether the book has
walked past it. The top bid moves inside a thirty-minute window on the majority of samples for a
contested item, so a price frozen with the row would visibly disagree with the button.
