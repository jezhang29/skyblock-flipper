# Trade capture

Groundwork for tracking buys and sells automatically. Hypixel has no public endpoint for your own
orders, so the only things that know a trade happened are the chat lines the server sends and the
menus it fills in. This records both, verbatim, so the parser that will read them can be built
against measured text.

Nothing reads the file at runtime. It is off by default.

**That parser exists now** (`core/track`, driven by `/flip track`), so an ordinary session needs
nothing here. Run a capture when Hypixel's wording changes under it — a Skyblock update, a trade
that the ledger does not book, an order the tracker does not see — and the recorded session becomes
the fixture the parser is fixed against. `LedgerAutoTrackTest` replays exactly such a file.

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
ambiguous — 187 of 5549 item names Thereare a strict prefix of another, so "Enchanted Melon" alone cannot
tell `ENCHANTED_MELON_BLOCK` from `ENCHANTED_MELON`. The menu stack carries Hypixel's own id.
`customData` keeps the whole compound as text because it is not yet known which key matters, and the
only way to get a missing key back is another play session.

Menus are snapshotted once their contents have been unchanged for 5 ticks, and again whenever they
change, so a page of orders filling in over several ticks records once rather than a hundred times.

Capture stops at 32MB rather than rotating: the early part of a session is worth more than the late
part, and rotation would discard the wrong half. `/flip capture` says if it hit the cap.

## The bazaar trail

Menus are normally kept on their title (`bazaar`, `auction`, `order`, `bid`, `offer`). The three
screens an order is actually placed on — the product page and the amount and price pages behind it —
are titled with the item's own name, so that list cannot see them, and the 2026-08-09 session
recorded none of the 850 menus it wrote. They are exactly the screens a slot detector needs.

So while capturing, **every** menu opened within 30 seconds of a bazaar menu is recorded, whatever
it is called. The tracker is not fed those extra menus; only the file gets them.

### Reading one menu instead

For a single question — "what is that button actually called?" — a whole session is too much.
`/flip menu` prints the title, the size and every named slot of the **last menu you had open**. Chat
cannot be typed into while a menu is up, so open the screen, close it, then run the command.

That is how the wording of the place flow gets confirmed. `BazaarSlots` matches buttons by name, and
the names on the product page and the amount and price pages were read off screenshots rather than a
capture — each answers to more than one wording, and a screen whose buttons match none of them is
highlighted not at all rather than wrongly.

**A menu title is cut on rendered width, not on a character count.** Over the 850 captured menus and
two photographed product pages: `Bazaar ➜ "Enchanted Nether Wart"` survives whole at 32 characters,
while `Bazaar ➜ "Enchanted Cooked Mutt` and `Revenant Horror ➜ Revenant Cata` are both cut at 31 and
`Item Upgrades ➜ Transmission Tun` at 32. `core` holds no font and cannot measure the width, so
`BazaarMenu` matches a title exactly first and only falls back to a prefix from 30 characters up —
the shortest cut ever seen, less one. A rule that required 32 left the Revenant Catalyst page with no
box on it, photographed live 2026-08-14.

### A full session

That trail is what a slot-detection session is for:

1. `/flip capture`.
2. Open the bazaar, click **Search**, and search an item by name.
3. Open the item's page from the results, and go all the way through a **buy order** using
   **custom amount** and **custom price**, ending at Confirm.
4. Do the same for a **sell offer**.
5. Open **Manage Orders**, click one order, and look at its options page.
6. `/flip capture` to stop.

Five minutes of play. Without step 3 and step 4 the mod cannot know which slot on those pages is the
button, and it will highlight nothing there rather than guess.

## After a session

The file becomes a fixture under `src/test/resources` and the parser gets written against it. Trim
it first: the chat filter is deliberately loose (any line containing `coins`, `order`, `bid` and so
on), so it will have picked up unrelated lines, and it can pick up other players' chat in a public
lobby.

## Automatic tracking

`/flip track` (`autoTrackEnabled`) is what the capture was groundwork for. It reads the same live
chat lines and menu snapshots — it needs neither the capture flag nor the file — and writes the
ledger: a buy opens a position, a sale closes it, and a position can close in pieces because one
order often fills in pieces.

Chat is the event stream and the orders menu overrules it, because two things happen that chat never
mentions. A partial fill sends no notification at all, and an order that fills while the client is
closed sends nothing either. So **open your bazaar orders menu now and then**; that is the only way
those fills are ever seen.

Two limits worth knowing before you turn it on:

- A sale of stock you owned before tracking started settles against no position and is dropped. In
  the recorded session that is 2,340 Slimeballs — booking them against nothing would have reported
  about 87,000 coins of profit that was really just inventory.
- A trade the mod never quoted is recorded as `AUTO_UNQUOTED`. It counts toward the fill rate and
  stays out of the capture rate, because there was no quote for it to fall short of.

`/flip status` reports resting orders, trades seen this session, and how many are sitting on coins
you have not collected.
