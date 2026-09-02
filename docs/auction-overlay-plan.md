# Auction sniper overlay plan

Status: **planned, not started.** Design settled 2026-09-02. Passive-list variant chosen; the
listing-box variant is deferred (see the last section).

## Goal

A sidebar panel drawn over Hypixel's live auction house menu, listing the AH snipe candidates worth
buying, mirroring what `client/hud/BazaarOverlay` does over the bazaar menu. Open the auction house,
see the handful of `AUCTION_VALUE` flips ranked, click one to copy its search name.

Not the `/flip gui` `FlipScreen` — that already shows snipes on its Auction tab. Target is the
in-menu overlay only.

## Why it is not a branch inside `BazaarOverlay`

The bazaar overlay is ~1600 lines built around three things AH does not have:

- a **type strip** — bazaar has five flip kinds (`StrategyKind.bazaarKinds()`); AH has one,
  `AUCTION_VALUE`.
- a **worked-job list with progress badges** — a bazaar flip is a multi-step buy / transform / sell
  job the tracker follows. A snipe is one bid; `FlipScreen` already states "a snipe is one bid on
  the auction house" (`FlipScreen.java:1521`).
- a **green box on the slot to click** — bazaar has a standing order book and `BazaarStep` slot
  math. AH has ephemeral BIN listings and no order book.

Grafting AH into that file couples two unlike flows and bloats an already large class. The plan is a
sibling `client/hud/AuctionOverlay` that **copies the proven scaffolding** and drops what does not
apply.

## What is different about AH, and why each choice follows

- **A candidate is one ephemeral listing.** A real underprice is "often gone within seconds"
  (`AuctionValueStrategy.java:41`). So: no attempt to guarantee the listing is live, and a standing
  risk line on every row.
- **`FlipCandidate` carries no listing UUID.** It is keyed on `skyblockId`
  (`FlipCandidate.java:55`), not the auction uuid. The panel therefore cannot name or point at a
  specific listing without new plumbing. This is the load-bearing reason the box is deferred.
- **No order book.** None of `BazaarStep` / `BazaarSlots` / the green box applies. A wrong-slot click
  on a BIN spends coins immediately, so a box here is more dangerous than the bazaar one.

## Confirmed design (passive list)

**Attachment** — reuse the `BazaarOverlay` pattern exactly:

- `ScreenEvents.AFTER_INIT`, attach once per screen (weak ref), draw `afterBackground`.
- Mouse handlers swallow only clicks/scroll inside the panel rectangle; everything else reaches
  Hypixel's menu untouched.
- Fixed 170px panel, `fit`/`draw` down-scaling, constant font, `OverlaySide` for which side,
  `ContainerScreenLayout` for the menu's real position.

**Menu recognition** — new `core/track/AuctionMenu`, mirroring `BazaarMenu`:

- Match on **rendered width**, exact first then a prefix fallback at 30 chars.
- Titles to pin (must be confirmed from a real `/flip menu` capture before shipping — do not invent
  them, same rule the bazaar highlighter follows):
  - the auctions browser grid (`Auctions Browser` / `Auctions`)
  - a per-item search-results grid
  - a single-listing view (`Auction View` / BIN view)
  - the buy-confirm screen (`Confirm Purchase`)
- Until a capture exists these are stubbed and the overlay recognizes nothing, so it draws nothing
  rather than mis-recognizing a menu.

**Body** — one list, no type strip, no worked-jobs section:

- Heading: `Auction snipes`.
- `To snipe`: `CandidateFeed.rank(AUCTION_VALUE, 5)` as one-liners — name, `+net`, discount%.
- Click a row copies the **display name** (via the candidate's `displayName`, itself from
  `ItemCatalog` — never an id) to the clipboard, to paste into the AH search sign.
- Expand a row (same `[+]`/`[-]` toggle as bazaar) to show: buy price, fair resale, confidence, and
  the one honest risk line — "not verified live, may be gone."
- No Work control (a snipe commits nothing to follow), no progress badges, no green box.

**Sign-follow** — the piece that earns the overlay even without a box:

- AH search is typed on a sign, like the bazaar search. Reuse the `FOLLOW_MILLIS` window: while the
  search sign is open, pin the panel to the edge and show the exact search string to type.

**Config**

- Add `auctionOverlayEnabled` to `FlipperConfig` (default `true`), plus a `ConfigSchema` entry
  (label, help, accessor) — `ConfigSchemaTest` fails otherwise. `FlipConfigScreen` picks it up
  automatically.
- Reuse `overlaySide`. No highlight toggle — there is no box.

**Tests**

- `AuctionMenuTest` pinning the recognized titles, once a capture provides them.
- Core stays Minecraft-free. The overlay is client-side and untested, like `BazaarOverlay`.

## Build order

1. `core/track/AuctionMenu` + `AuctionMenuTest` (titles stubbed until capture).
2. `auctionOverlayEnabled` in `FlipperConfig` + `ConfigSchema`.
3. `client/hud/AuctionOverlay` — panel, list, click-to-copy, sign-follow. Register in the client
   entrypoint beside `BazaarOverlay.register()`.
4. Fill real titles into `AuctionMenu` once the user captures them with `/flip menu`.

Steps 1-3 build and pass offline; step 4 needs a live capture and is the only thing between "panel
shows over recognized menus" and "panel shows over the real auction house."

## Deferred: boxing the listing slot

Rejected for now, kept here so it is not re-proposed without the cost attached. To draw a box on a
specific listing in the grid the plan would need:

- the listing **UUID and price** carried on `FlipCandidate` and through `CandidateFeed`;
- slot-matching the open grid by name+price in `AuctionMenu`.

Cost: a BIN mis-click spends coins (worse than the bazaar box, which mis-cancels an order), and
listings churn faster than the 250ms menu re-read, so the box would routinely point at a slot the
listing has already left. Revisit only with a measured need and the UUID plumbing.
