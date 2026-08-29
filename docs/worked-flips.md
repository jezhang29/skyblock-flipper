# Working several flips at once

How a flip the player has actually started is tracked, drawn and stopped. One shared model for
bazaar spreads, crafts and combines, so the bazaar panel, the Jobs tab and `/flip jobs` cannot
disagree about what is left to click.

Read this before changing the bazaar overlay, `CandidateFeed`'s follow state, or the flip screen's
selection handling.

## What was wrong

The overlay followed **one** job. `CandidateFeed` held a single `craftOutputId` and a single
`combineTargetId`, each of which cleared the other, and `FlipScreen.mouseClicked` set them from the
row click itself — the same click that selects a row so its reasoning can be read on the right.

Three consequences, all reported from play:

1. Picking a combine ended a craft whose materials were still resting.
2. Clicking a **bazaar** row — to compare it, not to work it — called `stopCraft()` and
   `stopCombine()`, so reading the list silently took the panel away.
3. Nothing anywhere listed what was open. The mod knew about one job at a time, so a session with a
   craft, a combine and a spread running had no view that showed all three.

The trades themselves were never at risk: orders on the book are the player's, and nothing here
places or cancels anything. What was lost was the instructions.

## The model

`core/strategy/WorkedJob` is the shared shape. A craft, a combine and a spread read the same way at
the menu — rest an order, wait, do something to what arrives, offer the result — and every renderer
that wanted to show more than one of them had to know all three types.

```
WorkedJob(kind, itemId, displayName, steps, capital, netProfit, note)
  Step(stage, label, itemId, displayName, price, units, orderSplit)
  Stage = BUY_ORDER | INSTANT_BUY | TRANSFORM | SELL_OFFER
```

`Stage` carries the order side that would show the step happening: `BUY` for the two buys, `SELL`
for the offer, `NONE` for `TRANSFORM`. That one field is what makes progress possible without any
renderer knowing which strategy it is drawing.

Built by `WorkedJob.ofCraft`, `ofCombine` and `ofSpread`. The first two adapt `CraftJob` and
`CombineJob`, which stay as they are — they are what the strategies produce and what
`docs/craft-flipping.md` and `docs/combine-flipping.md` describe. `ofSpread` is built from the
`FlipCandidate` the ranking already produces, because a spread has no plan beyond its two prices;
`BazaarSpreadStrategy.job(productId, context)` re-quotes one product on the same gates the ranking
uses, the twin of the craft and combine `job` methods.

A **null plan is a real state**: the flip stopped clearing its own gates while it was being worked.
That produces a job with no steps and a `note` saying so, keeping the name it was picked under.
A job that vanishes is indistinguishable from one that broke, and the player may have coins resting
on it.

## Progress

`WorkedJob.progressOf(step, orders)` reads the tracked orders and returns
`TODO | RESTING | DONE | UNTRACKED`, with the filled and total units behind it.

- Matched on **item id and side**, not on the order itself. The mod never sees an order placed — it
  sees the orders menu afterwards — and a step sized at 111,507 units is several orders by the time
  it reaches the book, so the matching orders are summed. Anything narrower breaks on
  `Stacking.orderSplit`.
- **A resting order wins over a finished one** on the same item. A player flipping the same item
  twice in a day needs the live order, not this morning's.
- `UNTRACKED` covers two cases that look identical to the player: a `TRANSFORM` step, which nothing
  on the bazaar records, and auto-tracking being off. Both draw a blank badge rather than a guess. A
  step marked done that was never placed is a flip abandoned halfway.

Badges are three characters wide so a column of them lines up: `[ ]`, `[~]`, `[x]`, and blank.

`TrackerService.orders()` returns an empty list when `autoTrackEnabled` is off, so no view has to
test the setting.

**Tested against the recorded session**, not against invented orders — `WorkedJobTest` replays
`trade-capture-sample.jsonl`, which is the hour of real trading that carries a partial fill, a
cancel, three offers on one item at once, and an order that left the menu unannounced.

## Where the list lives

`CandidateFeed` holds an insertion-ordered `Map<String, StrategyKind>` of what is being worked, plus
the display name each id was picked under. Insertion order is the answer to "what am I in the middle
of", and re-sorting it every poll would move a row out from under the player.

- `work(kind, itemId, displayName)` — returns false for a strategy with no bazaar steps to follow.
  `AUCTION_VALUE` is a bid on another screen and `NPC_FLIP` is already the basket; both have their
  own view.
- `stopWork(itemId)` / `stopWork(kind)` / `stopWork()`.
- `jobs()` — every job re-quoted against the live book, cached on the book revision **and** on a
  generation counter the follow list bumps, so a pick shows up without waiting for the next poll.
  Client thread only, like `worklist()`.

Every `BUY_ORDER` step's item id is recorded through `FlipIntentsService` while the job is followed,
which is what stops the NPC side repricing or cancelling another strategy's order. That behaviour is
unchanged; it just now covers spreads as well as crafts and combines.

## The three views

**The bazaar panel** (`BazaarOverlay`) draws one board: a section per worked job, in the order
picked, then the NPC basket under them. Jobs first because they are what the player explicitly
committed to. A section heading is an ordinary row — one row height everywhere is what keeps the
scroll offset and the hit test a division rather than a search — and its second line carries the
done count instead of a size. `Guidance`'s green box counts within the basket, so the board records
`basketFirstRow` and offsets it.

When nothing is being worked the panel is exactly what it always was: the basket, headed "Do these".

**The Jobs tab** on the flip screen lists every job with all of its steps and badges, and adds up
what the whole worked set has committed and what it makes if it all fills. Nothing else in the mod
adds them up — the ranking quotes each flip as if it were the only one.

**`/flip jobs`** prints the same rows into chat, `/flip jobs stop <name>` drops one, `/flip jobs
stop` drops all. `/flip craft stop` and `/flip combine stop` now drop every job of that kind and
leave the others alone.

## Selection is not commitment

Row click selects. The **Work** button commits, and pressing it again on a flip already being worked
stops it. This is the fix for the reported bug, and it is why the button's label is the state it is
in rather than a fixed verb.

Stopping a job only stops the mod describing it. Orders already on the book are left exactly where
they are — the mod has never placed or cancelled anything, and a stop that looked like it might is
worse than no button.
