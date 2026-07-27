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
   S=/private/tmp/claude-501/-Users-jzhang-Documents-IntelliJ-skyblock-flipper-26-2/*/scratchpad
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

- **Bazaar sides are inverted from their names.** `buy_summary` is the
  sell-offer/ask side — what you pay to instant-buy, matching
  `quick_status.buyPrice`. `sell_summary` is the buy-order/bid side, matching
  `quick_status.sellPrice`. Re-read this every time; it reads backwards.
- **Size plans from flows, never resting book depth.** `buyMovingWeek` = units
  instantly bought, `sellMovingWeek` = units instantly sold.
- **`item_bytes` is a hybrid NBT blob** — legacy `{i:[{id,Count,tag,Damage}]}`
  plus a modern `components` compound. Parse as a generic NBT tree; never build
  an `ItemStack`. Rarity comes from `components["minecraft:tooltip_style"]`, with
  a last-lore-line fallback (~4 of 154 live sales need it).
- **Item names are not unique enough to search on.** 187 of 5549 names are a
  strict prefix of another (`ENCHANTED_MELON_BLOCK` is "Enchanted Melon";
  `ENCHANTED_MELON` is "Enchanted Melon Slice"). Match on id, and never
  synthesise a name from an id.
- **Valuation trains on `auctions_ended` only** — active listings are
  contaminated by the mispricings being hunted. BIN sales only, medians not means.

## Refreshing fixtures

Fixtures in `src/test/resources` are hand-trimmed samples (2.6K-15K), not raw
dumps. To refresh one, reduce a live pull down to the same shape and size with
python3 and write that — never copy a raw response in. Keep `LiveApiTest` opt-in
(`./gradlew test -PliveApi`); an outage must not fail an ordinary build.
