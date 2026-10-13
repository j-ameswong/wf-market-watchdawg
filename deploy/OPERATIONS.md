# Operating watchdawg on homelab

Run one instance. The upstream limiter is per process; a second instance would spend a second
request budget. The service observes markets and never places or closes upstream orders.

## Build and install

From the repository root:

```sh
nix develop --command bash -c 'cd market && ./gradlew build'
cp market/build/libs/market-0.0.1-SNAPSHOT.jar deploy/market.jar
```

Copy `deploy/` to `/srv/src/wf-market-watchdawg/deploy` on homelab. Create `.env` with mode 0600:

```dotenv
DATABASE_PASSWORD=<random database password>
WATCHDAWG_DATABASE=watchdawg
NTFY_URL=http://100.86.149.18:8080
NTFY_TOPIC=<random high-entropy topic>
TAILNET_IP=100.86.149.18
```

The existing ntfy server is accessible only through loopback/tailnet bindings. Topic values stay
external and must not be included in issue reports, command output or logs. Publication sends
its topic in the JSON body. Use the protected `.env` when configuring your listener.

```sh
docker compose up -d --build
curl -fsS http://127.0.0.1:8081/actuator/health
```

The database lives in the named `watchdawg_database` volume. Do not use `down -v` on an established
warehouse. PostgreSQL is exposed on host loopback port 55432; use an SSH tunnel for remote queries.
Actuator is exposed on loopback and the explicit tailnet address, on port 8081.

## Watches and rules

Edit `watches.yaml`, then `docker compose restart market`. Settings are per operator. There is
no query API or web UI; PostgreSQL is the read surface.

Every item dimension must be named as a value or `any`. Existing watches default to `underpriced`.
Other `rule` values are:

- `price-crossing`: `side: sell` uses best online ask <= `max-unit-price`; `side: buy` uses best
  online bid >= that threshold. Initial qualifying books alert. Clearing the condition rearms it;
  cooldown-deferred reentries remain eligible. Changing its definition creates fresh condition state.
- `spread`: `min-spread` in platinum and/or `min-spread-percent` of ask. Both online sides must
  exist in the same market. Both configured thresholds must pass. Qualification uses unrounded
  arithmetic; displayed percentages are rounded.
- `volume-spike`: closed-trade daily volume relative to preceding 14 contiguous daily buckets.
- `vanish-fast`: observed appearance-to-disappearance lifetime, not proof of a completed sale.

Baseline watches use `min-samples` (default 35) and `target-rate` (default 0.1). The numerical
threshold is selected from earlier data and checked on a later holdout period. Sparse/insufficient
history, an unstable holdout or a combined replay that suppresses at the global ceiling disables
that evaluation. Inspect `baseline_calibration` for statistical evidence, `baseline_budget_epoch`
for shared readiness, and `baseline_shadow` / `baseline_coverage` for recorded candidate demand and
collection continuity. Shared validation needs at least one complete UTC day with successful
observations from every configured source, within twice its configured cadence. A restart,
collection gap, permanent delivery failure, changed threshold/configuration or newly selected market
starts fresh validation. Statistics refreshes must also remain current for volume watches.
Baseline candidates remain in shadow mode until this gate passes. Historical
statistics are a retrospective snapshot, not a trade tape or an availability-perfect simulation.
Old events without recorded crossplay cannot calibrate lifetime rules. A new install has no
lifetime calibration history; do not substitute synthetic fixtures for production evidence.

Contract watches select `kind: riven|lich|sister`, `weapon`, and optionally `max-price` for buyout
alerts. Omit the cap to collect history without alerts. Configure `priority`, `cooldown`, `topic`,
positive attributes as a name-to-minimum-benefit map (recoil uses the manifest's inverted sign), required negative attributes, `no-negative`,
`max-rerolls`, or nemesis `element`, `min-damage`, `ephemera`. Inapplicable/unknown selectors fail
configuration. Search sweeps prioritize configured watches. `all-weapons: true` enables manifest-wide
history collection. Missing search results get bounded, fair detail verification; search absence
alone never establishes closure. A detail 404 records disappearance, never a sale.

## Health and evidence

Actuator exposes only health and metrics. Useful meter names:

- `wfm.requests`, `wfm.throttles`: requests by bucket; any throttle needs investigation.
- `wfm.socket.connected`, `wfm.socket.reconnects`, `wfm.socket.frames`, `wfm.socket.gapfills`.
- `wfm.poll.lateness`, `wfm.polls`, `wfm.statistics.fetches`, `wfm.contracts.sweeps`.
- `watchdawg.signals`, `watchdawg.deliveries`: process counters for transitions and delivery attempts.
- `watchdawg.order.events`: committed event throughput, by source.
- `watchdawg.admissions.today`: durable UTC-day counts by signal state.
- `watchdawg.notifications.today`: durable finalized-success count for today; not every network attempt.

Counters reset with the process; measure rates from deltas and account for resets. The daily gauges
query committed database state and survive restart. Successful delivery means the outbox finalized
its ntfy acknowledgement; an accepted-but-unrecorded duplicate cannot be counted exactly.

A one-second dispatcher interval leaves room for healthy-path delivery under ten seconds.
Network outages and upstream throttles are recovery cases; pending notifications retain their
original seen time and retry with bounded backoff. A crash after ntfy accepts but before marking
sent can duplicate delivery. This is deliberate at-least-once behavior.

```sql
SELECT (seen_at AT TIME ZONE 'UTC')::date AS day, state, count(*)
FROM signal GROUP BY 1, 2 ORDER BY 1, 2;
SELECT watch, rule, samples, threshold, status, holdout_rate, budget_suppressed
FROM baseline_calibration;
SELECT started_at, checked_until, status, budget_suppressed FROM baseline_budget_epoch;
```

Use injected clocks and shortened dev timings to check midnight, cooldowns, reconnects and retries.
Never accelerate real upstream request limits. Listener receipt proves delivery without requiring
phone interaction; automated tests use local peers and never contact live services.

## Backup and restore

`WATCHDAWG_DATABASE` selects the application, healthcheck, and backup database, defaulting to
`watchdawg` when unset or empty. Set it in `.env`; an exported shell value takes precedence.
Changing it does not rename or create a database in an existing volume.
`./backup.sh` requires Python 3 and writes a restricted custom-format dump into `backups/`,
using Compose's resolved database setting. Apply configuration changes before taking backups.
`./verify-restore.sh backups/<file>.dump` restores into an isolated scratch database, uses the
Timescale pre/post-restore hooks, checks readable facts, then drops only its scratch database.
Copy backups to independent storage; keeping a backup on the same disk is not disaster recovery.
Run `python3 deploy/check-backup.py` from the repository root to check database selection and
backup failure cleanup locally; it renders Compose configuration but never contacts a Docker daemon.


## Recovering after database loss

Stop `market` before recovery and keep it stopped if any command fails. Preserve the existing
volume and take a final dump if it is readable. Restore into a **new** database, such as
`watchdawg_recovered`, rather than overwriting the damaged one:

```sh
docker compose stop market
docker compose exec -T postgres createdb -U watchdawg watchdawg_recovered
docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U watchdawg -d watchdawg_recovered \
  -c 'CREATE EXTENSION IF NOT EXISTS timescaledb; SELECT timescaledb_pre_restore();'
docker compose exec -T postgres pg_restore -U watchdawg -d watchdawg_recovered \
  --exit-on-error --no-owner < backups/CHOSEN.dump
docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U watchdawg -d watchdawg_recovered \
  -c 'SELECT timescaledb_post_restore(); SELECT count(*) FROM order_event; SELECT count(*) FROM signal;'
```

Inspect migration history, recent event dates, statistics coverage, pending signals, and Timescale
policies against the backup's recorded counts. If restoration failed, keep the application stopped;
remove only the newly created recovery database and retry from a verified backup. When validated,
set `WATCHDAWG_DATABASE=watchdawg_recovered` in `.env` and unset any shell override, then run
`docker compose up -d` to apply the database setting to both services. This recreates the PostgreSQL
container while retaining its named volume. Verify health, socket connection and pending delivery
progress, then run `./backup.sh` and verify that new backup before retiring the old
volume. Recoveries can redeliver a previously accepted notification; outbox delivery is at least once.

`check-delivery.py` checks ntfy publication/readback only. Application latency acceptance must also
correlate an actual signal's observation time with live listener receipt. A container restart policy
covers process exits; operational monitoring must detect a hung but running JVM separately.
