# 1. Defer the signature term model

Date: 2026-08-04

## Status

Accepted.

## Context

`DecodedItem.signature()` builds a key by appending terms — `stars=`, `recomb`, `ench=`,
`ethermerge`, `dye=` and the rest. `FairValueModel.isBare()` then states the same domain fact a
second time, as thirteen boolean clauses, in another language: a term in the signature and a clause
in `isBare` are both answers to "does this property make the item non-generic". Nothing checks that
the two agree, and each has drifted independently before.

The obvious deepening is a term model: `Keying` yields a set of terms, and `signature()`,
`valuationKeys()` and `isBare()` are all derived from it. One statement, no possible disagreement.
It was proposed during the 2026-08-04 architecture review and rejected for now.

## Decision

`Keying` is extracted as a three-method interface — `keys`, `bidRatioKey`, `isBare` — over
`DecodedItem` as it stands. `DecodedItem` is not restructured, and `signature()` keeps building its
string by appending terms.

## Consequences

The signature string is byte-stable, which is the point. `daily.jsonl` is keyed by signature, is
never pruned, and carries no schema version. A term-model rewrite that shifts a signature by one
character does not fail — it silently splits every rollup key into a before and an after, and since
the raw days behind it are pruned at `valuationWindowDays`, the history under the old spelling is
not recoverable. That is a permanent, silent loss, and it is not one to accept as a side effect of
a test refactor.

The `signature()`/`isBare()` double statement survives, still unchecked. `Keying.PRODUCTION` at
least gathers both into one module, so a future rewrite has one file to change rather than two.

## Reopening this

Worth doing, with a migration decision attached rather than bundled into other work. What it needs:

- A schema version in `daily.jsonl`, or a key-rewrite pass over the existing file.
- A characterisation test pinning `signature()` byte-for-byte across the recorded tape before and
  after, not just across the offline fixtures.

Do not re-suggest the term model as a free refactor. The cost is not in the code.
