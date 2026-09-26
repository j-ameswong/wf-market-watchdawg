# Changelog

Project-level changes. Implementation detail lives in the capability task logs under `tasks/`, and
design rationale in [`docs/adr/`](docs/adr/README.md).

## [Unreleased]

### Added

- **C5 T4 — The socket stays up and fills its gaps.** A connection that drops, does not connect
  within 10 seconds, is not confirmed within 10 seconds of subscribing, or hears nothing for 90
  seconds once subscribed is abandoned, and the next is made after a delay with full jitter under
  a ceiling that doubles from 1 second to 5 minutes, reset once a connection has stayed subscribed
  for a minute. Each confirmed subscription gap-fills once from `/v2/orders/recent` as
  `source=recent`, through the rules, unless the last gap-fill was under a minute ago or a
  throttle's `Retry-After` has not passed; a failed one is logged and counted and the socket stays
  up. `wfm.socket.reconnects` (by why the connection ended), `wfm.socket.gapfills` (by outcome)
  and `wfm.throttles` (every `429` and `509` by bucket and status, retried or not) are new meters.
- **C5 T3 — Socket orders reach the rule.** A new order the socket adds is evaluated by the
  underpriced rule and admitted in the transaction that recorded it, seen when its message
  arrived. A known order is not evaluated again, and the next book poll does not signal the same
  listing at the same price. Admission now takes one database advisory lock, so a socket ingest
  and a book poll committing at once cannot both pass a watch's cooldown or the daily ceiling.
- **C5 T2 — The socket records new orders.** One connection to `wss://ws.warframe.market/socket`
  on the `wfm` subprotocol, with the project's `User-Agent`, subscribed to every new order with
  `platform` and `crossplay` sent explicitly from the one setting, which a test pins against the
  REST header. Each new order is recorded as `appeared` with `source=ws`, for every item, watched
  or not (ADR-0022). Malformed messages and unknown routes are skipped. `watchdawg.socket.enabled`
  turns it off; `wfm.socket.connected` and `wfm.socket.frames` (by outcome) measure it. A dropped
  connection stays down until restart for now.
- **C5 T1 — Live socket capture.** A dependency-free JDK capture script under `bruno/` and a
  scrubbed fixture with 74 real new orders, the subscription acknowledgement and an online report.
  All captured orders carry item, status and platform fields; 29 report `offline`, so socket
  rules will use the payload's status without an online fallback.
- **One alert end to end, by polling (C6, C9a, C10).**
  - `watches.yaml` declares the watches: an item, each of its dimensions as a value or `any`, a
    per-unit price threshold, an ntfy priority and a logical topic. A bad watch fails startup
    naming it; a missing slug first gets one catalog refresh.
  - The poll loop fetches every watched item's book once per `wfm.poll.interval` (2 minutes), on
    a thread of its own, never overlapping itself. A throttle ends the round and its `Retry-After`
    holds off the next. `wfm.poll.lateness` and `wfm.polls` (by outcome) measure it.
  - The underpriced-listing rule: a live sell order from an online owner at or under a watch's
    unit price is admitted to the `signal` outbox, in the transaction that reconciled its book.
  - A dispatcher posts pending signals to ntfy as JSON, the topic in the body, and marks one sent
    only on a 2xx. The push carries the item, its dimensions, the unit price and lot, when the
    listing was seen and a link to the item page. It runs only once a topic is mapped.
  - Suppression: one listing at one price is admitted once; within a watch's `cooldown` (1 hour
    by default) only a cheaper listing is admitted, counting pending signals as well as sent ones;
    past `watchdawg.alerts.daily-ceiling` (50) a UTC day, a candidate is recorded as `suppressed`
    and never sent. `watchdawg.signals` counts signals by watch and state.
  - Delivery retries a failure with doubling backoff (30 seconds first) up to 5 attempts, then
    marks the signal `failed`, logs it and counts it. `watchdawg.deliveries` counts attempts by
    outcome. A pending signal is sent after a restart, however late.
  - Real topics come from `WATCHDAWG_NOTIFY_TOPICS_<NAME>`. With any mapped, a watch naming an
    unmapped one fails startup. The topic appears in no URL, stored error or log line.
- **C4 — Order-book ingest.**
  - `wfm_order` holds every order's current state; `order_book` the latest book per item.
  - Reconciling a full book records `appeared`, `price_changed`, `quantity_changed` and
    `vanished`, with the order's side and lot size, and ignores a book older than the last one.
  - Socket and `/recent` observations can only add orders never seen before.
  - Each reconciled book writes a quote for every market of the item: per-unit best bid and ask
    over all orders and over online owners only, with order counts and online counts. Both rollups
    carry the new measures.
  - The `/v2/orders/item/{slug}` and `/v2/orders/recent` client calls. Nothing calls them on a
    schedule yet, so no request budget is spent.
  - `bruno/scrub-orders.mjs`, which strips trader identity from order captures before they become
    fixtures.
- **C3 — Catalog and market dimension.**
  - `item` carries the English name and icon, subtypes, the rank, charge and star maxima,
    `bulk_tradable`, `tradable` and `rarity`. A field the catalog omits is stored as null.
  - `market`, one row per order book, keyed by item, observer platform and every subtype
    dimension, with missing dimensions counted as equal.
  - `MarketResolver`, which maps a tuple to its market and creates it the first time, safely
    under concurrency.
  - `order_event` and `market_quote` reference `market`.
  - A paced background sweep fills `tradable`, `rarity` and `max_charges` from
    `/v2/item/{slug}`, because `/v2/items` omits them. It runs 30 items a minute by default.
- **C2 — Time-series storage and test harness.**
  - TimescaleDB 2.30.1 on Postgres 18 in dev (`compose.yaml`), in tests (Testcontainers) and as a
    requirement of the packaged jar.
  - `order_event`, a hypertable compressed after 7 days and kept forever.
  - `market_quote`, a hypertable kept raw for 90 days, rolled up into `market_quote_hourly` and
    `market_quote_daily`, which are kept forever.
  - Every storage policy is declared in the repeatable migration `R__storage_policies.sql`.
  - `watchdawg.scheduling.enabled` switches all `@Scheduled` components on or off.
  - A test harness applied to every Spring test context: scheduling off, outbound HTTP refused
    and recorded, and the database emptied before each test. Tests run in random order with a
    printed, replayable seed (`-PtestSeed`).
- **C1 — API access.** Every outbound call to warframe.market goes through one paced,
  observable transport:
  - A two-bucket rate limiter keyed by route class (`public` 2 req/s, `contract-search`
    12 req/min) with a global concurrency cap of 2, installed on every `RestClient`.
  - One retry on `429`/`509`, honouring `Retry-After` in both RFC 9110 forms up to
    `wfm.limits.max-retry-after`. A `509` permanently narrows the concurrency cap.
  - A typed `WfmException` hierarchy. Non-JSON and HTML error bodies never reach Jackson.
  - `Platform`, `Crossplay` and `User-Agent` stamped on every request from one setting.
  - A v1 client for `/items/{slug}/statistics`.
  - Actuator, exposing `health` and `metrics` only, with per-bucket rate-limit meters.
- Item catalog sync: an hourly, version-hash-gated refresh of `/v2/items`.
- Nix flake with a dev shell and a hermetic jar build.
- `SPEC.md`, ADRs, the API reference under `docs/`, and the Bruno collection.

### Changed

- Documentation has one home per kind: `SPEC.md` holds behaviour, constraints and boundaries;
  `CLAUDE.md` holds commands, layout, conventions, testing practice and traps. SPEC §5–§8 moved
  to `CLAUDE.md`, and their numbers are retired so §9–§11 keep theirs.
- The WFM transport (pacing, context headers, error handling) applies to the v2 and v1 clients
  only, not to every `RestClient.Builder`, so a client for another host does not inherit it.
- The item detail sweep is off unless `wfm.sync.item-details.enabled` is true.
- `market_quote.best_buy` and `best_sell` are per-unit `numeric`.
- `SPEC.md` builds one alert end to end by polling before the WebSocket, and C6 starts as a single
  instance on a fixed cadence ([ADR-0021](docs/adr/0021-limiter-owns-the-ceiling-poll-loop-owns-freshness.md)).
- `item.updated_at` is replaced by `synced_at`, stamped by each refresh. Upgrading clears the
  epoch values the old column held and forces one catalog refetch.
- The dev database image is `timescale/timescaledb:2.30.1-pg18`. An existing `postgres:18-alpine`
  volume is reused as is.
- `wfm.requests-per-second` is replaced by `wfm.limits.*`.
- `wfm.platform` and `wfm.crossplay` have no defaults; startup fails if either is unset.
- Kotlin sources use 4-space indentation, enforced by Spotless + ktlint as part of `build`.
- The dev Postgres listens on port 5432.

### Fixed

- Calls queued behind a busy connection could start together once it freed, bypassing the pacing,
  and were metered before they started. A call now takes its connection slot before its turn.
- The sync scheduler could tick inside a test run and call the live API.
