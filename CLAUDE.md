# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A Spring Boot 4 / Kotlin service that mirrors warframe.market data into Postgres. It syncs the item catalog; `SPEC.md` specifies the intended growth into a price watcher and warehouse over `/v2/orders/*`, and `docs/adr/` records the decisions behind that design.

## Layout

The Gradle root is `market/`, **not** the repo root. `nix/` packages the jar; `bruno/` (API collection) and `docs/` (upstream API reference, plus `docs/adr/` for this project's decision records) are tracked in the repo. `tasks/plan.md` and `tasks/todo.md` plan the capability in progress; finished ones move to `tasks/archive/`. `CHANGELOG.md` records project-level changes.

## Commands

`nix develop` provides the toolchain (JDK 25, docker, psql 18, flyway CLI, node) and shell functions that `cd` into `market/` for you. `mhelp` lists them.

| Task | In the dev shell | Directly |
| --- | --- | --- |
| Build + test | `mbuild` | `cd market && ./gradlew build` |
| Tests only | `mtest` | `cd market && ./gradlew test` |
| One test class | `mtest --tests '*ItemRepositoryTest'` | `./gradlew test --tests '*ItemRepositoryTest'` |
| One test method | `mtest --tests '*ItemRepositoryTest.upsert inserts then updates the same row'` | same |
| Run (Tomcat :8080) | `mrun` | `cd market && ./gradlew bootRun` |
| Dev psql | `mpsql` | `psql -h localhost -p 5432 -U watchdawg -d watchdawg` |
| Reset the DB | `mdb-reset` | `cd market && docker compose down -v` |
| Flyway without Gradle | `mflyway info` | see `flake.nix` for the full invocation |
| Replay the API collection | `bruno-run` | `cd bruno && npx @usebruno/cli run --env production --delay 400 -r` |

**Docker must be running.** `bootRun` starts the TimescaleDB (Postgres 18) in `market/compose.yaml` via `spring-boot-docker-compose` (lifecycle `start_and_stop`), and any test touching Spring, HTTP or storage is a `@SpringBootTest` that boots the same image through Testcontainers. `TimescaleTest` fails if `compose.yaml` and `TestcontainersConfiguration.IMAGE` name different tags. The image is not named `postgres`, so `compose.yaml` carries the `org.springframework.boot.service-connection: postgres` label; without it `bootRun` gets no datasource. Classes with no Spring or JDBC dependency (`WfmRateLimiterTest`) are plain JUnit and need neither. No test can reach the live API; see *Test harness* below.

Hermetic jar: `nix build .#market`. It builds with nixpkgs' `gradle_9` rather than `./gradlew` (the wrapper can't download inside the sandbox) and runs `bootJar` with `doCheck = false`, since tests need a Docker daemon. **After any dependency change in `build.gradle.kts`, regenerate the lock from the repo root:**

```
$(nix build --no-link --print-out-paths .#market.mitmCache.updateScript)
```

That jar excludes the `developmentOnly` deps, so it will not start its own Postgres — pass `SPRING_DATASOURCE_URL` / `_USERNAME` / `_PASSWORD`. The database must preload TimescaleDB (`shared_preload_libraries = 'timescaledb'`); migration V2 creates the extension if the role may, and startup fails otherwise.

## Architecture

### The sync loop

Two components are scheduled: `CollectionSyncScheduler`, below, and `ItemDetailSync`, which fills the fields `/v2/items` omits. `CollectionSyncScheduler` runs each tick (`wfm.sync.interval`, default 1h):

1. `GET /v2/versions` returns a content hash per collection (`items`, `rivens`, `liches`, …).
2. For each `CollectionSync` bean, compare its hash against the `collection_version` row.
3. On a change, run `sync.refresh()` **and** the version upsert inside one `TransactionTemplate` block.

The transaction boundary is the important invariant: if a refresh throws, the stored hash stays stale and the next tick retries. A migration that adds catalog columns has to delete that collection's stored hash (as V5 does for `items`), or existing rows keep the new columns empty until upstream happens to change. `CollectionSync` implementations only fetch and upsert — they never touch `collection_version` or open transactions. A `RateLimitedException` on `/versions` is logged and skipped, not retried in-tick.

`ItemSync` binds `/v2/items` into `item`. Every field the v2 model marks optional binds as null when absent, never as a zero or `false` that looks like data — rank 0 is a real market (R7.8). The live list carries no `tradable`, `rarity` or `maxCharges`. `ItemDetailSync` owns those three columns and fills them from `/v2/item/{slug}`, one item at a time. So the list upsert must never write them, or every catalog refresh would blank the sweep's work. `fixtures/v2-items.json` and `fixtures/v2-item/*.json` are live captures, so keep them that way: refresh them from Bruno's List Items and Get Item requests rather than editing values by hand. `synced_at` is not an upstream timestamp (v2 items have none): the upsert stamps `now()`, the refresh transaction's time, so one refresh marks every row it wrote alike and a row missing from the latest refresh shows an older value.

`ItemDetailSync` fetches one batch per run (`wfm.sync.item-details.batch-size` per `interval`; the defaults, 30 a minute, are 0.5 req/s). It takes never-fetched items first (`detail_synced_at` null), then any whose details are older than their last catalog refresh, so a catalog change costs one re-sweep of the whole catalog, about two hours, and nothing more. A `404` marks the item checked and keeps its old details. A throttle or any other failure ends the run, and the rest waits for the next one. It never runs inside the catalog refresh: a whole sweep is far too long to hold that transaction open.

**Adding a collection:** implement `CollectionSync` as a `@Component` with `collection` set to a key of `VersionCollections.asMap()` (add the field and the `asMap()` entry if the collection isn't modelled yet). The scheduler picks it up by list injection; nothing else needs editing.

### warframe.market client

`WfmClient` wraps a single `RestClient` bean built in `WfmConfig`. Every v2 response is an `Envelope<T>` with `data` xor `error`; the private `get()` unwraps it and throws on either an empty body or a populated `error`.

The public API allows **3 req/s** — Cloudflare answers `429` above it and `509` on too many concurrent connections. Any code that sweeps per-item endpoints (~4000 tradable items) has to budget against that limit.

Failures are typed, in `WfmErrors.kt`, all under one sealed `WfmException`: `RateLimitedException` (`429`) and `ConcurrencyLimitedException` (`509`) share a `ThrottledException` parent carrying the parsed `Retry-After`, and `WfmHttpException` covers every other `4xx`/`5xx` — v1 `/items/{slug}/orders` answers `403` in plain text and a `502` arrives as Cloudflare HTML, so an error body must never reach Jackson (R1.7). It carries the status, the content type and a **200-character** excerpt with whitespace collapsed; only the first 800 bytes of the body are read at all.

`baseUrl` is v2. `baseUrlLegacy` (v1) exists because three v1 routes have no v2 equivalent — auctions, `/items/{slug}/statistics` (price history), and `/items/{slug}/dropsources`. Everything else in `docs/v1.yml` is dead; `bruno/README.md` has the verified route-by-route table.

`WfmLegacyClient` is that second channel, on `wfmLegacyRestClient`. Both beans get the same transport, so the two differ only in base URL and envelope: v1 is `payload`/`include` in snake_case (`WfmLegacyModels.kt`), v2 is `apiVersion`/`data`/`error` in camelCase. The snake_case naming strategy is scoped to the v1 bean's own JSON converter — **never set one globally**, it would stop `updatedAt` and `gameRef` binding. Two `RestClient` beans also make injection by type ambiguous, so a client names its channel with `@Qualifier(WfmConfig.V2_CLIENT)` or `@Qualifier(WfmConfig.LEGACY_CLIENT)`. Bind every v1 price as `BigDecimal`: the upstream sends `150` and `80.0` for the same field, and an `Int` binding truncates silently rather than failing.

### The transport stack

`WfmTransport.applyTo(builder)` is applied to each client that talks to warframe.market, and to nothing else: a client for another host (C10's ntfy) must not inherit WFM pacing or headers. It installs, in order:

1. `WfmRateLimitInterceptor` — pacing, plus the `429`/`509` retry.
2. `WfmContextInterceptor` — `Platform`, `Crossplay` and `User-Agent`, `set` rather than added, so a call site that names its own value is overridden ([ADR-0002](docs/adr/0002-crossplay-single-global-setting.md), R1.5). Do not move them back to `defaultHeader`s: a call site *can* override those.
3. The `defaultStatusHandler` for `4xx`/`5xx` other than `429`/`509`, which the interceptor above has already converted.

`RateLimitWiringTest` enumerates `RestClient` beans: each must carry both interceptors or be named there as non-WFM, and a client from the context's plain builder must carry neither. It is the standing no-bypass guard for R1.1 and R1.8, and covers clients added later.

`WfmContext` (`platform` + `crossplay`) is the single read point for the observer's context, and **C5's socket client is obliged to quote its `crossplay` explicitly** — the two channels take opposite upstream defaults, and mixing them fabricates a `vanished` on ~7% of ingested orders. `WfmProperties.platform` and `.crossplay` deliberately have no Kotlin defaults, so an unset value fails startup.

`WfmRateLimiter` holds two independent budgets keyed by **route**, not by API version and not by which bean called ([ADR-0005](docs/adr/0005-rate-buckets-keyed-by-route-class.md)): `contract-search` for auction-search routes, `public` for everything else — including v1 `statistics`. Both live under `wfm.limits` as `permits`/`per`, alongside `max-concurrency` and `max-retry-after`. Turns are **evenly spaced with no burst allowance**, and are taken *after* the concurrency permit, so a request starts on its turn and is metered then. Taking the turn first lets callers finish pacing while every slot is busy and then start together; `WfmRateLimiterTest` pins that.

Call it as `limiter.acquire(bucket) { … }`. The block form is what guarantees the permit is released when a call throws, so a retry must call `acquire` **again, sequentially** — nesting a re-acquire inside an outstanding one deadlocks against `max-concurrency`.

### Retries

One initial call and **exactly one** retry, issued from `WfmRateLimitInterceptor`. It takes its own turn before reissuing, so a retry spends budget rather than bypassing it. `Retry-After` is parsed in both RFC 9110 forms (delta-seconds and HTTP-date); a value above `wfm.limits.max-retry-after` surfaces immediately instead of parking a worker thread ([ADR-0019](docs/adr/0019-long-retry-after-surfaces-to-the-caller.md)), and one that is absent gets no extra cooloff at all, because the retry's own turn already spaces it. A `509` additionally narrows the connection cap by one, floored at one and never widened again within a run.

### Metrics

`spring-boot-starter-actuator` is on the classpath for one reason: R12.1 wants the rate-limit boundary proven at runtime rather than assumed. `WfmMetrics` defines every meter name in one place — `wfm.requests`, `wfm.request.wait`, `wfm.retries` and the `wfm.concurrency.limit` gauge — because a dashboard or alert watching one is a contract a rename breaks silently. All of them are tagged by `bucket` (and retries by `status`), and **all are registered at startup rather than on first use**, so a quiet service reads as a flat zero instead of a missing series.

`management.endpoints.web.exposure.include` is `health,metrics` and nothing else. R12.5 makes actuator the service's *only* HTTP surface — Postgres is the read surface for the warehouse ([ADR-0011](docs/adr/0011-no-query-api-postgres-is-the-read-surface.md)) — and `HttpSurfaceTest` fails if a controller appears in `com.watchdawg` or if that exposure list grows.

### Persistence

Spring Data JDBC, not JPA — no dirty checking, no lazy loading, and `save()` on an entity with an assigned id issues an `UPDATE`. Two consequences that shape `store/`:

- Upserts are hand-written `@Modifying @Query` methods with `on conflict … do update`, each paired with a record-taking extension function (`ItemRepository.upsert(item)`) so call sites stay readable. Add a new column in three places: the migration, the record, and both the SQL and the parameter list of the upsert.
- `CollectionVersionRecord` has a natural id (`name`), so it implements `Persistable` with a `@Transient val new` flag to tell Spring Data whether to insert or update. Prefer the `upsert` extension over `save()` for it.

Schema lives in `market/src/main/resources/db/migration` (Flyway, `V<n>__desc.sql`, plus the repeatable `R__storage_policies.sql`). The `mflyway` CLI reads those files from the filesystem and the app from the classpath, into one shared `flyway_schema_history` table — a migration applied by either is seen as applied by the other, and `MigrationPathsTest` checks both directions. So a migration must not depend on Flyway placeholders or on anything else that only one of the two paths supplies.

Every migration runs in a transaction, so a failed one leaves nothing half-applied. TimescaleDB's hypertables, compression settings and policies all run inside one, and so does a continuous aggregate created `WITH NO DATA` — create them that way. A migration that genuinely cannot (a `WITH DATA` aggregate, `refresh_continuous_aggregate`, `create index concurrently`) gets a sibling `V<n>__desc.sql.conf` containing `executeInTransaction=false`. Both paths honour it; `src/test/resources/db/non-transactional/` is the worked example.

### Market dimension

A `market` row is one order book: `(item, platform, subtype, rank, charges, amberStars, cyanStars)` (SPEC §2.3). `platform` is the **observer's** context, never the seller's ([ADR-0003](docs/adr/0003-market-is-a-mutually-tradable-pool.md)), which is why `MarketKey` has no platform field and `MarketResolver` stamps `WfmContext.platform` itself. A dimension an item lacks is null. The unique constraint is `nulls not distinct`, so an all-null tuple is still one market (R3.4), and rank 0 is a different market from no rank.

Get a market id through `MarketResolver.resolve(key)`, never by inserting into `market` directly. It does a lookup, then an insert-if-absent committed in its **own** transaction, then the lookup again. A market therefore outlives an ingest that rolls back, and two ingests meeting the same new tuple never wait on each other's locks. The second lookup relies on read committed, so do not call it from a repeatable-read transaction. An item the catalog lacks throws `UnknownItemException`. There is deliberately no cache: test resets restart the id sequence.

### Time series

TimescaleDB holds the fact tables ([ADR-0007](docs/adr/0007-timescaledb-with-indefinite-event-log.md)). Each references `market` and is a hypertable partitioned on `observed_at`, so every unique index must include that column — TimescaleDB refuses one that does not, and `FactTablesTest` lists the fact tables and checks every hypertable's indexes.

| Relation | Kept | Policy |
| --- | --- | --- |
| `order_event` | forever | compressed after 7 days, segmented by `market_id` |
| `market_quote` | 90 days raw | dropped by retention |
| `market_quote_hourly`, `market_quote_daily` | forever | continuous aggregates of `market_quote`, refreshed over the last 2 / 4 days |

Every policy value lives in `db/migration/R__storage_policies.sql`, a repeatable migration that Flyway re-applies whenever the file changes. Changing one is an ask-first change (SPEC §9).

**Refreshing a rollup over a range whose raw chunks are gone deletes the rollup's rows for that range.** So a refresh window must start inside raw retention (`QuoteStorageTest` fails otherwise), and never run `refresh_continuous_aggregate(…, null, null)` by hand on a database old enough to have dropped raw quotes. A rollup's shape cannot be altered, only dropped and recreated, which loses everything older than the raw window. Once history has accrued, add a new rollup alongside instead.

In tests there are no TimescaleDB background workers: a policy runs only when the test calls `run_job` (see `store/Policies.kt`).

### Test harness

`market/src/test/kotlin/com/watchdawg/market/harness/` applies to **every** Spring test context, registered from `src/test/resources/META-INF/spring.factories` rather than imported, so no test class can opt out by forgetting an annotation (R2.6):

- `watchdawg.scheduling.enabled` is `false`, so `SchedulingConfig` (the only `@EnableScheduling`) stays off. A test's own `@SpringBootTest(properties = …)` still outranks that.
- Every `RestClient` built from the context sends through `LiveApiGuard`, a request factory that records and refuses. `LiveApiGuardListener` fails any test that reached it, even when the code under test swallowed the refusal. To exercise HTTP, bind `MockRestServiceServer` to `bean.mutate()`: that swaps the guard out.
- `DatabaseResetListener` truncates every table in `public` except `flyway_schema_history`, and every continuous aggregate, before each test method (R2.7). It finds them in the catalog, so a new table needs no edit. It names the hypertables explicitly: `truncate market cascade` does **not** empty compressed rows in `order_event`. Do not write a test that relies on rows another test left.
- A test that writes fact rows needs a real market, since the fact tables reference one: `resolver.marketFor(items)` (`store/TestMarkets.kt`) creates the item and resolves it.

Classes and methods also run in **random order** (`src/test/resources/junit-platform.properties`), so an order dependence fails a run rather than hiding. The test task prints its seed; replay an order with `./gradlew test -PtestSeed=<seed>`.

The defaults come from a context customizer because a `src/test/resources/application.yaml` would shadow the main file by classpath name rather than layer on it.

### Conventions

Kotlin sources indent with **4 spaces** (Kotlin official style), enforced by Spotless + ktlint — `./gradlew build` runs `spotlessCheck`, and `./gradlew spotlessApply` fixes violations. Style lives in `market/.editorconfig`, which must stay in the Gradle root: Spotless does not discover `.editorconfig` from parent directories. The Gradle daemon caches that file, so after editing it run `./gradlew --stop`. Compiler args are `-Xjsr305=strict` and `-Xannotation-default-target=param-property`. JSON binding is Jackson 3 (`tools.jackson.module:jackson-module-kotlin`) — the package is `tools.jackson`, not `com.fasterxml.jackson`. Kafka starters are on the classpath but nothing publishes yet.
