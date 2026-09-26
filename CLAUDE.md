# CLAUDE.md

Guidance for Claude Code (claude.ai/code) when working in this repository: commands, layout, and
the rules and traps that the code alone does not make obvious.

## What this is

A Spring Boot 4 / Kotlin service that mirrors warframe.market into Postgres + TimescaleDB. It syncs
the item catalog, and polls the books of the items `watches.yaml` names on a fixed cadence,
reconciling each into order state, an event log and quotes.

Where things are written down:

| Document | Holds |
| --- | --- |
| `SPEC.md` | Observable behaviour, domain constraints, non-goals, boundaries |
| `docs/adr/` | Consequential decisions and their trade-offs |
| `tasks/plan.md`, `tasks/todo.md` | The capability in progress, as acceptance checks; finished ones move to `tasks/archive/` |
| `CHANGELOG.md` | Project-level changes |
| This file | Commands, layout, rules and traps |
| Code and tests | Implementation behaviour and invariants |

## Layout

The Gradle root is `market/`, **not** the repo root. Also tracked: `nix/` (the jar build),
`bruno/` (the API collection), `docs/` (the upstream API reference in `docs/v2/`, `docs/v1.yml`
and `docs/v1-statistics.md`, plus this project's ADRs).

Packages under `com.watchdawg.market`:

| Package | Role |
| --- | --- |
| `wfm` | Rate limiter, transport, v2 and v1 clients and their models |
| `sync` | Catalog refresh (`CollectionSync`, `ItemSync`) and the item detail sweep |
| `ingest` | Book reconciliation, partial ingest, quotes, one-book polling |
| `store` | Repositories, `MarketResolver`, `OrderStore` |
| `watch` | Watches from `watches.yaml`, checked against the catalog at startup |
| `poll` | The poll loop (C6): every watched item's book, once per interval, on its own thread |
| planned | `wfm/ws` (socket), `notify` (C10) |

## Commands

`nix develop` provides the toolchain (JDK 25, docker, psql 18, flyway CLI, node) and shell
functions that `cd` into `market/` for you; `mhelp` lists them.

| Task | In the dev shell | Directly |
| --- | --- | --- |
| Build + test | `mbuild` | `cd market && ./gradlew build` |
| Tests only | `mtest` | `cd market && ./gradlew test` |
| One test class | `mtest --tests '*ItemRepositoryTest'` | `./gradlew test --tests '*ItemRepositoryTest'` |
| One test method | `mtest --tests '*ItemRepositoryTest.upsert inserts then updates the same row'` | same |
| Replay a test order | `mtest -PtestSeed=<seed>` | `./gradlew test -PtestSeed=<seed>` |
| Run (Tomcat :8080) | `mrun` | `cd market && ./gradlew bootRun` |
| Dev psql | `mpsql` | `psql -h localhost -p 5432 -U watchdawg -d watchdawg` |
| Reset the DB | `mdb-reset` | `cd market && docker compose down -v` |
| Flyway without Gradle | `mflyway info` | see `flake.nix` for the full invocation |
| Replay the API collection | `bruno-run` | `cd bruno && npx @usebruno/cli run --env production --delay 400 -r` |

**Docker must be running.** `bootRun` starts the TimescaleDB image in `market/compose.yaml`, and
Spring tests boot the same image through Testcontainers; `TimescaleTest` fails if the two tags
differ. The image is not named `postgres`, so `compose.yaml` carries the
`org.springframework.boot.service-connection: postgres` label; without it `bootRun` gets no
datasource.

**Hermetic jar:** `nix build .#market`, with nixpkgs' `gradle_9` and `doCheck = false`. After any
dependency change in `build.gradle.kts`, regenerate the lock from the repo root:

```
$(nix build --no-link --print-out-paths .#market.mitmCache.updateScript)
```

The jar excludes `developmentOnly` deps, so it starts no Postgres: pass `SPRING_DATASOURCE_URL`,
`_USERNAME` and `_PASSWORD`. The database must preload TimescaleDB
(`shared_preload_libraries = 'timescaledb'`); migration V2 creates the extension if the role may.

## Rules and traps

### Talking to warframe.market

- The upstream allows **3 req/s**; Cloudflare answers `429` above it and `509` on too many
  connections. `wfm.limits` configures 2 req/s `public` and 12/min `contract-search`, keyed by
  route, not API version ([ADR-0005](docs/adr/0005-rate-buckets-keyed-by-route-class.md)). Any
  sweep over per-item endpoints (~3.9k items) has to budget against that.
- Build every warframe.market client through `WfmTransport.applyTo(builder)`, and nothing else
  through it. `RateLimitWiringTest` fails on a `RestClient` bean that neither carries the transport
  nor is named there as non-WFM.
- The transport's order matters: rate limiting, then the context headers (so a retry is stamped
  again), then the status handler. `Platform`, `Crossplay` and `User-Agent` are `set` by
  `WfmContextInterceptor`; never move them to `defaultHeader`s, which a call site can override.
- Call the limiter as `limiter.acquire(bucket) { … }`, which releases the permit when the call
  throws. A retry acquires again **sequentially**; nesting an acquire deadlocks against
  `max-concurrency`.
- One retry on `429`/`509`, taking its own turn. A `Retry-After` above `wfm.limits.max-retry-after`
  surfaces at once ([ADR-0019](docs/adr/0019-long-retry-after-surfaces-to-the-caller.md)). A
  `509` also narrows the connection cap by one for the rest of the run.
- An error body never reaches Jackson: v1 answers `403` in plain text and a `502` arrives as HTML.
  `WfmHttpException` carries the status, content type and a 200-character excerpt.
- Two channels, two `RestClient` beans: name one with `@Qualifier(WfmConfig.V2_CLIENT)` or
  `@Qualifier(WfmConfig.LEGACY_CLIENT)`. The snake_case naming strategy lives on the v1 bean's own
  converter; **never set one globally**, or v2's `updatedAt` and `gameRef` stop binding. Bind
  every v1 price as `BigDecimal`: the upstream sends `150` and `80.0` for one field.
- Only three v1 routes are alive: auctions, `/items/{slug}/statistics` and
  `/items/{slug}/dropsources`. `bruno/README.md` has the route-by-route table.
- `WfmContext` is the one read point for platform and crossplay. **C5's socket client must send
  `crossplay` explicitly**: REST and the socket default to opposite values, and mixing them
  fabricates `vanished` on ~7% of orders ([ADR-0002](docs/adr/0002-crossplay-single-global-setting.md)).
  `wfm.platform` and `wfm.crossplay` have no defaults, so startup fails if either is unset.
- Meter names live in `WfmMetrics` and are registered at startup, so a quiet service reads as zero
  rather than a missing series. Renaming one breaks whatever dashboard watches it. Actuator exposes `health,metrics` and nothing else;
  `HttpSurfaceTest` fails on a controller or a wider exposure list.

### Catalog sync

- `CollectionSyncScheduler` runs `refresh()` and the `collection_version` upsert in **one
  transaction**, so a failed refresh leaves the hash stale and the next tick retries.
  `CollectionSync` implementations only fetch and upsert.
- A migration that adds catalog columns must delete that collection's stored hash (as V5 does),
  or existing rows keep the new columns empty until upstream changes.
- An optional v2 field binds as null when absent, never as `0` or `false`: rank 0 is a real market.
- The list upsert must never write `tradable`, `rarity` or `max_charges`. `ItemDetailSync` owns
  them, and runs only when `wfm.sync.item-details.enabled` is true.
- **Adding a collection:** a `CollectionSync` `@Component` whose `collection` is a key of
  `VersionCollections.asMap()`. The scheduler picks it up by list injection.

### Persistence

- Spring Data JDBC, not JPA: `save()` on an assigned id issues `UPDATE`. Upserts are hand-written
  `@Modifying @Query … on conflict … do update`, each with a record-taking extension function. A
  new column goes in the migration, the record, and the upsert's SQL and parameter list.
- Natural-id records implement `Persistable` with a `@Transient val new`; prefer `upsert` over
  `save()`.
- ktlint cannot format inside raw-string SQL; keep its indentation by hand.
- Migrations (`V<n>__desc.sql`, plus the repeatable `R__storage_policies.sql`) are applied by the
  app from the classpath and by `mflyway` from the filesystem, into one history table
  (`MigrationPathsTest`). Never depend on Flyway placeholders.
- Every migration runs in a transaction, and so can hypertables, compression settings, policies
  and a continuous aggregate created `WITH NO DATA`. One that cannot gets a sibling
  `V<n>__desc.sql.conf` with `executeInTransaction=false` (see
  `src/test/resources/db/non-transactional/`).

### Markets and order ingest

- Get a market id only through `MarketResolver.resolve(key)`. Its insert commits in its own
  transaction and its second lookup relies on read committed, so never call it inside a
  repeatable-read transaction. It has no cache: test resets restart the id sequence.
- `OrderIngest.reconcileBook` may record any change; `ingestPartial` (socket, `/recent`) may only
  add orders never seen before. Classification is the pure `reconcile()`; keep it free of Spring.
- A book is dated when it was **requested**, so it cannot vanish an order the socket reported
  after that.
- Claiming the item's `order_book` row both rejects a stale book and serialises reconciliations
  of one item. The partial path is insert-only and takes no lock.
- `platinum` prices a lot of `perTrade` units; quotes are per unit. Every reconciled book writes a
  quote for every market of the item, empty ones included.
- `wfm_order` rows are never deleted.

### Watches

- `watches.yaml` is imported by `spring.config.import` and bound as `watchdawg.watches`; there is no
  YAML dependency. `WATCHDAWG_WATCHES=file:/path` replaces it.
- Watches resolve against the catalog while the context starts, and a bad one fails startup. A
  missing slug gets one hash-gated catalog refresh first, so a fresh database can start.
- A watch names every dimension its item has, as a value or `any`, and none it lacks. Charges are
  checked only once the detail sweep has fetched the item, since `/v2/items` never carries them.
- A watch's `topic` is a logical name. The real ntfy topic is a credential and never goes in the
  file.

### Polling

- The poll loop and anything else that needs its own thread run through `OwnThreadSchedule`, a
  lifecycle bean declared only when `watchdawg.scheduling.enabled` allows. **Never declare a
  `TaskScheduler` bean**: it would replace Boot's and take over every `@Scheduled` method.
- `Cadence` keeps one round per interval: an overrun starts the next round when it ends, with no
  make-up burst, and a throttle's `Retry-After` holds off the next round (ADR-0019).

### Time series

| Relation | Kept | Policy |
| --- | --- | --- |
| `order_event` | forever | compressed after 7 days, segmented by `market_id` |
| `market_quote` | 90 days raw | dropped by retention |
| `market_quote_hourly`, `market_quote_daily` | forever | continuous aggregates, refreshed over the last 2 / 4 days |

- Fact tables are hypertables on `observed_at`, so every unique index includes it
  (`FactTablesTest`).
- Every policy value lives in `R__storage_policies.sql`; changing one is ask-first (SPEC §9).
  Dropping a table or rollup drops its policy, so a migration that recreates one must change that
  file in the same commit (`StoragePolicyUpgradeTest`).
- **Refreshing a rollup over a range whose raw chunks are gone deletes its rows for that range.**
  A refresh window must start inside raw retention (`QuoteStorageTest`); never run
  `refresh_continuous_aggregate(…, null, null)` on an old database. A rollup's shape cannot change
  once it holds history beyond the raw window: add a new rollup alongside.
- Tests have no TimescaleDB background workers; a policy runs when the test calls `run_job`
  (`store/Policies.kt`).

### Tests

- The harness in `src/test/kotlin/com/watchdawg/market/harness/` applies to **every** Spring test
  context, registered from `src/test/resources/META-INF/spring.factories`, so no test can opt out:
  - scheduling is off (`watchdawg.scheduling.enabled=false`); a test's own `properties` still
    outrank the harness;
  - every `RestClient` sends through `LiveApiGuard`, which refuses, and `LiveApiGuardListener`
    fails a test that reached it. To exercise HTTP, bind `MockRestServiceServer` to
    `bean.mutate()`;
  - `DatabaseResetListener` empties every table and continuous aggregate before each test. It
    names the hypertables, because `truncate market cascade` leaves compressed `order_event` rows.
- The defaults come from a context customizer: a test `application.yaml` would shadow the main one.
- `src/test/resources/watches.yaml` is empty and shadows the main one on purpose: a context
  resolves its watches at startup, and the test catalog is empty. Build `Watches` by hand in a
  test that needs one; `WatchesTest` checks the committed file.
- Classes and methods run in random order; the test task prints its seed. Never rely on another
  test's rows. A test writing fact rows needs a real market: `resolver.marketFor(items)`.
- Wiring and persistence are `@SpringBootTest`s. Parsing, classification and pacing are plain JUnit.
  `reconcile()` gets the densest coverage: everything downstream trusts its output.
- `WfmClient.get()` is `private inline`: test clients through the HTTP layer, not by mocking.
- Fixtures are live captures from the Bruno collection, taken **before** a field is committed to;
  synthetic payloads test behaviour only. Refresh them from Bruno, never by editing values. Order
  captures go through `bruno/scrub-orders.mjs` first. After a DTO change, run `bruno-run` by hand.

### Conventions

- Kotlin indents with **4 spaces**, enforced by Spotless + ktlint in `build`; `spotlessApply`
  fixes. Style lives in `market/.editorconfig`, which must stay in the Gradle root; run
  `./gradlew --stop` after editing it.
- Compiler args: `-Xjsr305=strict`, `-Xannotation-default-target=param-property`.
- JSON is Jackson 3: the package is `tools.jackson`, not `com.fasterxml.jackson` (annotations stay
  in `com.fasterxml.jackson.annotation`). DTOs declare only the fields the code reads.
- Kafka starters are on the classpath, but nothing publishes
  ([ADR-0012](docs/adr/0012-kafka-stays-unwired.md)).
