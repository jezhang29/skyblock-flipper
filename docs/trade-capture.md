# Trade capture

Groundwork for tracking buys and sells automatically. Hypixel has no public endpoint for your own
orders, so the only things that know a trade happened are the chat lines the server sends and the
menus it fills in. This records both, verbatim, so the parser that will read them can be built
against measured text.

Nothing reads the file at runtime. It is off by default and it only ever reads: no clicks, no typed
commands, no packets sent.

## Running a session

1. `/flip capture` in game. It says where it is writing.
2. Trade normally, and make sure the session contains at least one of each:
   - a bazaar **buy order** placed, and later filled
   - a bazaar **sell offer** placed, and later filled
   - a **partial fill** if you can arrange one, on a slow item
   - an order **cancelled** before it filled
   - an **instant buy** and an **instant sell**
   - an auction **BIN purchase**, and an item of yours **sold**
   - collecting the proceeds of each of the above
3. Open **Manage Orders** and **your auctions** a few times along the way, including once while an
   order is part-filled. Menus are only recorded when you open them.
4. `/flip capture` again to stop. It reports how many records it wrote; if that number is zero, the
   filter missed everything and the keyword list needs widening before you spend another session.

Log out and back in with an order still resting if you can. An order that fills while the client is
closed produces no chat line at all, and the menu snapshot is the only way that fill is ever seen —
that case is the reason menus are captured and not just chat.

## What comes out

`<.minecraft>/config/skyblock-flipper/chat-capture.jsonl`, one JSON object per line.

```json
{"at":1754150400000,"text":"...","type":"chat"}
{"at":1754150402000,"title":"Your Bazaar Orders","slots":[
  {"index":11,"name":"Enchanted Melon","lore":["..."],"itemId":"ENCHANTED_MELON_BLOCK",
   "count":1,"customData":"{...}"}],"type":"menu"}
```

`itemId` is the point of the menu records. Chat gives a display name, and display names are
ambiguous — 187 of 5549 item names are a strict prefix of another, so "Enchanted Melon" alone cannot
tell `ENCHANTED_MELON_BLOCK` from `ENCHANTED_MELON`. The menu stack carries Hypixel's own id.
`customData` keeps the whole compound as text because it is not yet known which key matters, and the
only way to get a missing key back is another play session.

Menus are snapshotted once their contents have been unchanged for 5 ticks, and again whenever they
change, so a page of orders filling in over several ticks records once rather than a hundred times.

Capture stops at 32MB rather than rotating: the early part of a session is worth more than the late
part, and rotation would discard the wrong half. `/flip capture` says if it hit the cap.

## After a session

The file becomes a fixture under `src/test/resources` and the parser gets written against it. Trim
it first: the chat filter is deliberately loose (any line containing `coins`, `order`, `bid` and so
on), so it will have picked up unrelated lines, and it can pick up other players' chat in a public
lobby.
