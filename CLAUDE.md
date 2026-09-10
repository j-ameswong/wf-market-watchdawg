# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A Spring Boot 4 / Kotlin service that mirrors warframe.market data into Postgres. It syncs the item catalog today; `SPEC.md` specifies the intended growth into a price watcher and warehouse over `/v2/orders/*`, and `docs/adr/` records the decisions behind that design.

## Layout

The Gradle root is `market/`, **not** the repo root. `nix/` packages the jar; `bruno/` (API collection) and `docs/` (upstream API reference, plus `docs/adr/` for this project's decision records) are tracked in the repo.

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

**Docker must be running.** `bootRun` starts the Postgres in `market/compose.yaml` via `spring-boot-docker-compose` (lifecycle `start_and_stop`), and any test touching Spring, HTTP or storage is a `@SpringBootTest` that boots a Testcontainers Postgres. Classes with no Spring or JDBC dependency (`WfmRateLimiterTest`) are plain JUnit and need neither. The test task pins `wfm.sync.initial-delay` out of reach so `@EnableScheduling` cannot tick mid-run and call the live API — `MarketApplicationTests` fails if that override is removed.

Hermetic jar: `nix build .#market`. It builds with nixpkgs' `gradle_9` rather than `./gradlew` (the wrapper can't download inside the sandbox) and runs `bootJar` with `doCheck = false`, since tests need a Docker daemon. **After any dependency change in `build.gradle.kts`, regenerate the lock from the repo root:**

```
$(nix build --no-link --print-out-paths .#market.mitmCache.updateScript)
```

That jar excludes the `developmentOnly` deps, so it will not start its own Postgres — pass `SPRING_DATASOURCE_URL` / `_USERNAME` / `_PASSWORD`.

## Architecture

### The sync loop

`CollectionSyncScheduler` is the only scheduled component. Each tick (`wfm.sync.interval`, default 1h):

1. `GET /v2/versions` returns a content hash per collection (`items`, `rivens`, `liches`, …).
2. For each `CollectionSync` bean, compare its hash against the `collection_version` row.
3. On a change, run `sync.refresh()` **and** the version upsert inside one `TransactionTemplate` block.

The transaction boundary is the important invariant: if a refresh throws, the stored hash stays stale and the next tick retries. `CollectionSync` implementations only fetch and upsert — they never touch `collection_version` or open transactions. A `RateLimitedException` on `/versions` is logged and skipped, not retried in-tick.

**Adding a collection:** implement `CollectionSync` as a `@Component` with `collection` set to a key of `VersionCollections.asMap()` (add the field and the `asMap()` entry if the collection isn't modelled yet). The scheduler picks it up by list injection; nothing else needs editing.

### warframe.market client

`WfmClient` wraps a single `RestClient` bean built in `WfmConfig`. Every v2 response is an `Envelope<T>` with `data` xor `error`; the private `get()` unwraps it and throws on either an empty body or a populated `error`.

The public API allows **3 req/s** — Cloudflare answers `429` above it and `509` on too many concurrent connections. Any code that sweeps per-item endpoints (~4000 tradable items) has to budget against that limit.

Failures are typed, in `WfmErrors.kt`, all under one sealed `WfmException`: `RateLimitedException` (`429`) and `ConcurrencyLimitedException` (`509`) share a `ThrottledException` parent carrying the parsed `Retry-After`, and `WfmHttpException` covers every other `4xx`/`5xx` — v1 `/items/{slug}/orders` answers `403` in plain text and a `502` arrives as Cloudflare HTML, so an error body must never reach Jackson (R1.7). It carries the status, the content type and a **200-character** excerpt with whitespace collapsed; only the first 800 bytes of the body are read at all.

`baseUrl` is v2. `baseUrlLegacy` (v1) exists because three v1 routes have no v2 equivalent — auctions, `/items/{slug}/statistics` (price history), and `/items/{slug}/dropsources`. Everything else in `docs/v1.yml` is dead; `bruno/README.md` has the verified route-by-route table.

### The transport stack

`WfmConfig.wfmTransportCustomizer` is one `RestClientCustomizer` applied to **every** `RestClient.Builder` the context hands out, so a new client bean is governed the moment it is built rather than when someone remembers to wire it. It installs, in order:

1. `WfmRateLimitInterceptor` — pacing, plus the `429`/`509` retry.
2. `WfmContextInterceptor` — `Platform`, `Crossplay` and `User-Agent`, `set` rather than added, so a call site that names its own value is overridden ([ADR-0002](docs/adr/0002-crossplay-single-global-setting.md), R1.5). These were `defaultHeader`s, which is precisely what a call site *can* override.
3. The `defaultStatusHandler` for `4xx`/`5xx` other than `429`/`509`, which the interceptor above has already converted.

`RateLimitWiringTest` enumerates `RestClient` beans and fails if one lacks either interceptor, so it is the standing no-bypass guard for both R1.1 and R1.8 and covers clients added later without being edited.

`WfmContext` (`platform` + `crossplay`) is the single read point for the observer's context, and **C5's socket client is obliged to quote its `crossplay` explicitly** — the two channels take opposite upstream defaults, and mixing them fabricates a `vanished` on ~7% of ingested orders. `WfmProperties.platform` and `.crossplay` deliberately have no Kotlin defaults, so an unset value fails startup.

`WfmRateLimiter` holds two independent budgets keyed by **route**, not by API version and not by which bean called ([ADR-0005](docs/adr/0005-rate-buckets-keyed-by-route-class.md)): `contract-search` for auction-search routes, `public` for everything else — including v1 `statistics`. Both live under `wfm.limits` as `permits`/`per`, alongside `max-concurrency` and `max-retry-after`. Turns are **evenly spaced with no burst allowance**, and are taken *before* the concurrency permit so a thread waiting out pacing never holds a connection slot.

Call it as `limiter.acquire(bucket) { … }`. The block form is what guarantees the permit is released when a call throws, so a retry must call `acquire` **again, sequentially** — nesting a re-acquire inside an outstanding one deadlocks against `max-concurrency`.

### Retries

One initial call and **exactly one** retry, issued from `WfmRateLimitInterceptor`. It takes its own turn before reissuing, so a retry spends budget rather than bypassing it. `Retry-After` is parsed in both RFC 9110 forms (delta-seconds and HTTP-date); a value above `wfm.limits.max-retry-after` surfaces immediately instead of parking a worker thread, and one that is absent gets no extra cooloff at all, because the retry's own turn already spaces it. A `509` additionally narrows the connection cap by one, floored at one and never widened again within a run.

### Persistence

Spring Data JDBC, not JPA — no dirty checking, no lazy loading, and `save()` on an entity with an assigned id issues an `UPDATE`. Two consequences that shape `store/`:

- Upserts are hand-written `@Modifying @Query` methods with `on conflict … do update`, each paired with a record-taking extension function (`ItemRepository.upsert(item)`) so call sites stay readable. Add a new column in three places: the migration, the record, and both the SQL and the parameter list of the upsert.
- `CollectionVersionRecord` has a natural id (`name`), so it implements `Persistable` with a `@Transient val new` flag to tell Spring Data whether to insert or update. Prefer the `upsert` extension over `save()` for it.

Schema lives in `market/src/main/resources/db/migration` (Flyway, `V<n>__desc.sql`). The `mflyway` CLI and the app share one `flyway_schema_history` table on purpose — a migration applied by either is seen as applied by the other.

### Conventions

Kotlin sources indent with **4 spaces** (Kotlin official style), enforced by Spotless + ktlint — `./gradlew build` runs `spotlessCheck`, and `./gradlew spotlessApply` fixes violations. Style lives in `market/.editorconfig`, which must stay in the Gradle root: Spotless does not discover `.editorconfig` from parent directories. The Gradle daemon caches that file, so after editing it run `./gradlew --stop`. Compiler args are `-Xjsr305=strict` and `-Xannotation-default-target=param-property`. JSON binding is Jackson 3 (`tools.jackson.module:jackson-module-kotlin`) — the package is `tools.jackson`, not `com.fasterxml.jackson`. Kafka starters are on the classpath but nothing publishes yet.
