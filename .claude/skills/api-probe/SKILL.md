---
name: api-probe
description: Pull a live Hypixel Skyblock endpoint (bazaar, auctions, auctions_ended, resources/skyblock/items, election) and reduce it to an answer without loading the payload into context. Use before inspecting any live API response, and whenever a fixture under src/test/resources needs refreshing or a number "smells wrong".
---

# Probing the Hypixel API

All these endpoints are public and unauthenticated — no key, no `apiKey` config.

**The hard rule: never `Read`, `cat`, `head`, or `jq .` a Hypixel dump.** A single
auctions sweep is ~51 pages / ~70MB. Even one page of `auctions` or the full
`resources/skyblock/items` (5549 entries) will consume the context window and
cannot be recovered. Always land the response on disk, then reduce it with
`python3 -c` and print only the aggregate you actually need.

## Procedure

1. **Fetch to the scratchpad**, not the repo root:

   ```bash
   # The session id in that path varies, and an assignment does not expand a glob —
   # resolve the newest scratchpad instead of pasting a `*` into the path.
   S=$(ls -dt /private/tmp/claude-501/-Users-jzhang-Documents-IntelliJ-skyblock-flipper-26-2/*/scratchpad | head -1)
   curl -s "https://api.hypixel.net/v2/skyblock/bazaar" -o "$S/bazaar.json"
   ```

2. **Reduce with python3.** Print counts, medians, and at most 2-3 example
   records — never a whole object graph:

   ```bash
   python3 -c '
   import json; d=json.load(open("'"$S"'/bazaar.json"))
   p=d["products"]; print(len(p), "products")
   x=p["ENCHANTED_MELON_BLOCK"]["quick_status"]
   print({k:x[k] for k in ("buyPrice","sellPrice","buyMovingWeek","sellMovingWeek")})
   '
   ```

3. **Report the number, not the JSON.** Quote the figures that justify the
   conclusion; leave the file on disk in case a follow-up needs it.

## Traps that produce wrong numbers rather than errors

The Hypixel section of CLAUDE.md is already in context and carries these in full —
the hybrid `item_bytes` blob, name collisions, ends-only valuation, and sizing from
flows rather than book depth. One is worth re-reading at the moment you write the
query, because it reads backwards:

- **Bazaar sides are inverted from their names.** `buy_summary` is the
  sell-offer/ask side — what you pay to instant-buy, matching
  `quick_status.buyPrice`. `sell_summary` is the buy-order/bid side, matching
  `quick_status.sellPrice`.

## Refreshing fixtures

Fixtures in `src/test/resources` are hand-trimmed samples (2.6K-15K), not raw
dumps. To refresh one, reduce a live pull down to the same shape and size with
python3 and write that — never copy a raw response in. Keep `LiveApiTest` opt-in
(`./gradlew test -PliveApi`); an outage must not fail an ordinary build.
