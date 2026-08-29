# 3. Enchanted-book combining is its own strategy, not a craft

Date: 2026-08-20

## Status

Accepted. Being implemented on branch `bazaar-combine`. The measured record is
`docs/combine-flipping.md`; this pins the two decisions that a later session would otherwise
re-derive and get wrong.

## Context

Buy low-tier enchanted books, combine `2^(T-k)` of them up to tier `T` at the anvil, sell the top
tier. Structurally this is a single-ingredient craft: one input book, one output book, a resting buy
order on the source and a sell offer on the output, both legs sized by `FillModel`. The obvious move
is to synthesise a `Recipe` (`output = ENCHANTMENT_E_T ×1`, `ingredient = ENCHANTMENT_E_k ×2^(T-k)`)
and price it through `CraftQuote`, reusing the route search, the capital sizing and the depth gate
already measured in `docs/craft-flipping.md`. Not duplicating the money math is a standing rule in
this repo.

Two facts, both confirmed against the live bazaar of 2026-08-20, rule that out.

## Decision

### Combine gets its own quote, because the exit book is one-sided

`CraftQuote.liquid()` requires `MIN_ORDERS_PER_SIDE` (15) resting orders on **both** sides of a book
and a two-sided spread under 25%. The best combine targets fail it: `ENCHANTMENT_FEATHER_FALLING_10`
rests 37 ask orders and **0** bid orders, because the people who want a high-tier book combine it
themselves rather than rest a buy order for it. Routing combine through `CraftQuote` would silently
reject every one-sided target — which is most of the strategy.

So `CombineQuote` is bespoke. Its exit gate is the target's resting **ask** order count ≥ 15, with
no bid-side requirement. That gate is what separates a real edge from a single-order fantasy price:
on the same snapshot it rejected `ENCHANTMENT_STRONG_MANA_10` (a claimed 25M/output resting on 10
ask orders) and kept `FEATHER_FALLING_10` (37). `CombineQuote` still reuses the shared primitives —
`FillModel`, `Fees`, `BazaarProduct`, `Stacking` — so the fill and fee math has one owner; only the
gate and the single-leg tier scan are its own.

**Do not re-route combine through `CraftQuote` to save code.** The reuse looks free and drops the
best targets on the floor without an error.

### Combinability is game data, curated, never inferred from price

A tier gap is not evidence of combinability in either direction. The bazaar lists a book only when
it cannot come off the enchant table, so a listed low tier can be drop-gated (non-anvil) and a
1000x gap can be pure tedium premium. There is no enchantments resource, and the items resource
does not carry books, so the API cannot answer which enchants combine or over what tier range. The
`CombineTable` allowlist is a hardcoded wiki/spec claim, validated against the live bazaar only for
existence. Enchants known to be drop-gated or non-anvil (experiment, champion, compact, cultivating,
expertise, hecatomb, Scavenger, Growth, Power, Protection, Vampirism) stay excluded by name.

## Consequences

- A new core quote path exists beside `CraftQuote`, sharing its primitives but not its gate. The one
  place they must not converge is the liquidity check.
- The strategy's honest ranking axis is net per anvil click, surfaced beside the shared
  profit-per-hour that lets the unified list compare a combine against other flips. See
  `docs/combine-flipping.md`.
- Adding an enchant is a factual claim about the game, testable only in play. The table carries the
  source-tier range so the solver never treats a drop-gated low tier as a combine input.
