# Skyblock Flipper

A client-side Fabric mod for Minecraft 26.2 that ranks Hypixel Skyblock trading opportunities by
expected profit per hour, after the full fee stack.

It is **advisory**. It surfaces numbers and rankings; it never clicks, buys, sells, or touches your
inventory. Everything it knows comes from Hypixel's public API.

## What it does

- **Bazaar spreads** — market making: post a buy order, sell into the ask side, collect the spread.
  Filtered hard on liquidity and order-book depth, because the arithmetic is the easy part.
- **NPC flips** — bazaar prices that have fallen below a fixed NPC buy price. Sized by how much you
  can actually sell by hand, not by how much you could buy.
- **Auction snipes** — live listings priced below what that exact item configuration has really been
  selling for, valued from realized sales rather than from what other people are asking.
- **A ledger** — record the flips you take and what they actually returned, and the mod will tell
  you how much of the profit it quoted you actually captured.

Ranking is always on profit per hour, never on margin percent. A 15% spread on something that
trades four units a day is not a business.

## Commands

| Command | What it does |
| --- | --- |
| `/flip` | Best candidates across every strategy |
| `/flip bazaar` / `/flip npc` / `/flip snipe` | One strategy at a time |
| `/flip take <rank>` | Record the flip on that line in the ledger |
| `/flip close <id> <units sold> <sell price>` | Record what it actually did |
| `/flip abandon <id>` | It never filled |
| `/flip ledger` | Open positions, capture rate, fill rate |
| `/flip status` | Poller, valuations, last auction sweep |
| `/flip config` / `/flip reload` | Show settings / re-read them from disk |
| `/flip hud` | Toggle the overlay |

Hover any candidate for the steps, the fee breakdown and the risks. Click to copy the item name.

## Configuration

`<.minecraft>/config/skyblock-flipper/config.json`, re-read by `/flip reload`. Worth setting:

- `bankroll` — coins you can deploy. Candidates needing more are hidden.
- `bazaarFlipperLevel` — your Bazaar Flipper perk level (0-6). A wrong value biases every bazaar
  margin, since it sets the sales tax.
- `scanAuctions` — sweeping the auction house costs roughly 70MB per sweep. Turn it off on a
  metered connection; the bazaar strategies do not need it.
- `tapeRetentionDays` — realized sales are kept on disk to value items from. Busy days are large.

## Building

Requires JDK 25.

```bash
./gradlew build      # jar into build/libs/
./gradlew runClient  # dev client
./gradlew test       # offline tests
```

## License

Available under the CC0 license.
