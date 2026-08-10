# Collecting tape without the game

The mod's numbers are only as good as the history behind them, and two of its three data sources
cannot be backfilled:

- **`auctions_ended`** — the window is about 60 seconds wide and Hypixel keeps nothing behind it.
  Every minute nothing is polling is a minute of realized sales that stops existing. This is the
  only data valuations train on.
- **The bazaar tape** — same property, coarser grain. Gaps become holes in the trend series.

Everything else (the live book, the item catalog, the mayor) is current state and returns within one
interval of a restart. So the cost of downtime is history, not freshness.

The client collects only while Minecraft is open — the poller starts at `onInitializeClient`, so the
title screen is enough, but a closed game collects nothing. `collectorJar` builds the same pipeline
as a plain process you can leave running.

## Build

```bash
./gradlew collectorJar          # -> build/libs/skyblock-flipper-<version>-collector.jar
```

The jar contains `core` and Gson, and nothing else. It is not remapped and never loads Minecraft.
Needs JDK 25, same as the mod.

```bash
java -jar skyblock-flipper-1.0.0-collector.jar --data-dir /var/lib/skyblock-flipper
```

`--data-dir` mirrors `<.minecraft>/config/skyblock-flipper`, so what it writes can be copied into a
client install unchanged. It writes a default `config.json` there on first run.

## Configure it for collection, not for play

Two settings matter far more here than they do in game:

```json
{
  "scanAuctions": false,
  "bazaarPollSeconds": 300
}
```

`scanAuctions` is the expensive one — roughly 70MB per sweep, every 60 seconds, about **3TB a
month**. It finds live listings to snipe, which is only actionable while you are sitting at the
auction house. A collector has no use for it.

`bazaarPollSeconds` is the next largest. The book is ~434KB; the default 20 seconds is about 56GB a
month, and the bazaar tape only records every 5 minutes. Matching the two means the fetches you pay
for are the ones that reach disk. The tape dedupes on Hypixel's own stamp, so nothing is lost.

Measured against the live endpoints (gzipped, 2026-07-27):

| Endpoint | Per fetch | Default cadence | Per month |
|---|---|---|---|
| `bazaar` | 434 KB | 20s | ~56 GB |
| `bazaar` | 434 KB | 300s | ~4 GB |
| `auctions_ended` | 87 KB | 45s | ~5 GB |
| `auctions` | ~70 MB | 60s | ~3 TB |

So a tape-only collector costs about **9GB a month** of download. That is all *ingress* — cloud
providers bill egress, and this process uploads request headers. The collector prints its own
estimate at startup from whatever settings it actually loaded.

Disk follows `tapeRetentionDays` (7d, ~265MB a day measured) and `bazaarTapeRetentionDays` (14d,
~40MB a day) — budget ~3GB steady-state at the defaults, ~6.5GB at the 21d/30d a server can afford.

Retention past `valuationWindowDays` buys nothing for pricing — measured, see
`ValuationWindowBacktestTest`: coverage of held-out sales goes 88.9% at a 48h window to 89.3% at
120h, with the error flat. It is kept for measuring model changes against, and because a day of
`auctions_ended` that was never recorded cannot be bought back.

What survives retention is the rollup. Both tapes summarise each completed UTC day into a
`daily.jsonl` beside the raw files and never prune it: for sales that is one line per item
signature per day, measured at **67x smaller** than the days it replaces (366MB of raw tape over
four days became a 5.5MB index), so long-horizon price history costs a few megabytes a year. The
sales rollup runs on its own thread and does one day per pass — it decodes every blob in the day,
which must never be allowed to delay the 60-second `auctions_ended` window.

## Run it under systemd

`/etc/systemd/system/skyblock-flipper.service`:

```ini
[Unit]
Description=Skyblock Flipper market collector
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=skyblock
ExecStart=/usr/bin/java -jar /opt/skyblock-flipper/collector.jar --data-dir /var/lib/skyblock-flipper
Restart=always
RestartSec=30

# A gap in the tape is permanent, so a crash should cost seconds, not a night.
StartLimitIntervalSec=0

# The heap only ever holds one bazaar book and the trend ring.
Environment=JAVA_TOOL_OPTIONS=-Xmx512m

StateDirectory=skyblock-flipper
ProtectSystem=strict
ProtectHome=true
PrivateTmp=true
NoNewPrivileges=true

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl enable --now skyblock-flipper
journalctl -u skyblock-flipper -f
```

It logs a heartbeat every 10 minutes with sales taped, data age, and any poll failures, so
`journalctl` alone will tell you whether it is actually working.

## Getting the tape back

The mod pulls it itself. Serve the two tape directories over HTTP and point the client at them;
`TapeSync` fetches whatever the collector appended since last time and merges it into the local
tape. Nothing has to be run by hand and nothing has to be remembered before launching the game.

### Serve the tape

Read-only, one token, and nothing but the two tape directories. `/etc/nginx/sites-available/tape`:

```nginx
server {
    listen 8080 default_server;
    listen [::]:8080 default_server;

    root /var/lib/skyblock-flipper;

    # config.json holds settings and ledger.jsonl holds trades; neither is tape. Everything
    # outside the two directories below is not served at all.
    location / {
        return 404;
    }

    # A regex location wins over the prefix one above, so only these two are reachable.
    location ~ ^/(tape|bazaar-tape)/ {
        if ($http_x_tape_token != "REPLACE-WITH-A-LONG-RANDOM-STRING") {
            return 403;
        }

        limit_except GET HEAD {
            deny all;
        }

        # The client discovers what to fetch from the directory listing, and parses it as JSON.
        autoindex on;
        autoindex_format json;

        # Must stay off. The sync resumes at a byte offset into the file, and a gzipped response
        # would number its bytes differently - every resumed fetch would start in the wrong place.
        gzip off;
    }
}
```

```bash
sudo ln -s /etc/nginx/sites-available/tape /etc/nginx/sites-enabled/tape
sudo nginx -t && sudo systemctl reload nginx
sudo ufw allow 8080/tcp
```

On Oracle Cloud the instance firewall is not the only one: add an ingress rule for TCP 8080 to the
subnet's security list too, or the port answers locally and nowhere else.

Check it from the Mac before touching the mod:

```bash
curl -s -H 'X-Tape-Token: REPLACE-WITH-A-LONG-RANDOM-STRING' http://<server>:8080/tape/
curl -s http://<server>:8080/tape/            # expect 403
curl -s http://<server>:8080/config.json      # expect 404
```

The first should return a JSON array of `{"name","type","size"}`. If it returns HTML instead, the
`autoindex_format json` line is not in effect and the sync will report exactly that.

The tape is public Hypixel data, so the token is not protecting a secret — it keeps an open
directory of gigabyte files from being crawled by whatever finds the port.

### Point the mod at it

In `config.json`, or the Collector sync group in the settings screen:

```json
{
  "tapeSyncEnabled": true,
  "tapeSyncUrl": "http://<server>:8080",
  "tapeSyncToken": "REPLACE-WITH-A-LONG-RANDOM-STRING",
  "tapeSyncIntervalMinutes": 0
}
```

The sync starts about five seconds after the poller, on its own daemon thread, and the game does
not wait for it. When it finishes it asks the poller to re-read the tape, so the recovered hours
reach the price history in the same session. `/flip sync` runs one on demand.

What crosses the wire is only what the server appended: the offsets already merged are kept in
`sync-state.json` beside each tape and every request after the first is a `Range` fetch. The first
sync is the whole retention window, so keep `tapeRetentionDays` on the client at what the client
actually needs — the default 2-day valuation window means 2 or 3 days is enough, and the server can
keep 21 for backtesting without the client ever downloading them.

Both machines record the same endpoints, so the merge is keyed rather than a copy: sales are folded
on `auction_id`, bazaar samples on the snapshot instant plus the product, rollup lines on their
signature and day. Nothing is overwritten, and a day both machines taped comes out as the union.

### The manual fallback

```bash
rsync -az --delete collector:/var/lib/skyblock-flipper/tape/ \
  ~/Library/Application\ Support/minecraft/config/skyblock-flipper/tape/
```

Same for `bazaar-tape/`. This is a mirror and not a merge, so it discards anything the client taped
that the server did not — use it only to seed an empty client, and only with the game closed. Leave
`config.json` out of it either way: the collector's is tuned for collection, and copying it over
your client's would turn off auction scanning in game.

## Where to run it

The shape of the workload — a long-running process appending to local disk, with a sub-minute
non-recoverable window — rules out the serverless options:

- **GitHub Actions**: cron minimum is 5 minutes and it is best-effort, routinely 5–15 minutes late.
  Against a 60-second window you would miss most ended sales.
- **Lambda / Cloud Functions**: 1-minute minimum granularity is exactly the window width, leaving no
  overlap margin — the poller uses 45 seconds specifically to guarantee overlap. And the tapes are
  append-to-local-disk with a startup replay, so a stateless runtime means rewriting both onto
  object storage.
- **Free container tiers** (Render, Koyeb): ephemeral disk. A redeploy wipes the tape.

What works is a persistent VM with a disk. Oracle Cloud's Always Free Ampere A1 (2 OCPU / 12GB /
200GB) is far more than this needs and is the only free tier that could also carry the auction
sweep. A Raspberry Pi or an old laptop at home is just as good and has no capacity roulette — at
~9GB a month no ISP will notice. GCP's `e2-micro` free tier works for tape-only; its single shared
vCPU is not worth pointing the sweep at.
