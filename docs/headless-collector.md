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

```bash
rsync -az --delete collector:/var/lib/skyblock-flipper/tape/ \
  ~/Library/Application\ Support/minecraft/config/skyblock-flipper/tape/
```

Same for `bazaar-tape/`. Do it with the game closed — the client prunes and rolls up these
directories on its own schedule, and two writers on one tape is not something either side checks
for. Leave `config.json` out of the sync: the collector's is tuned for collection, and copying it
over your client's would turn off auction scanning in game.

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
