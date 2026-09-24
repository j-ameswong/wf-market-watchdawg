# Implementation Plan: C2 — Time-series storage & test harness

> Source: `SPEC.md` §4 "C2 — Time-series storage & test harness" (R2.1–R2.7), with §3.2 (data
> model), §8 (testing strategy) and [ADR-0007](../docs/adr/0007-timescaledb-with-indefinite-event-log.md)
> / [ADR-0017](../docs/adr/0017-tests-never-reach-the-live-api.md). Drafted 2026-09-24.

## Overview

C2 gives the service somewhere to put time series and a test suite that can be trusted with them.
It has no dependencies and runs in parallel with C1 on the spine (`C1 ∥ C2 → C3 → C4 …`), so C3's
market dimension and C4's ingest both land on what it builds.

Two halves:

- **Storage.** TimescaleDB in every environment, the two fact tables whose storage behaviour the
  spec pins down — the order event log (compressed, kept forever) and quote snapshots (raw for a
  bounded window, rolled up hourly and daily forever) — and migrations that apply identically
  through Gradle and the `mflyway` CLI.
- **Harness.** Scheduling off and outbound HTTP refused in every Spring test context, and the
  shared database reset before every test so that order-independence is enforced rather than
  hoped for.

## Assumptions

1. **C2 creates `order_event` and `market_quote`.** R2.3 and R2.4 specify their storage and the
   acceptance bullet needs a real compressed chunk, so they cannot wait for C4. Columns follow the
   §3.2 ERD; C4 extends them (Decision 1).
2. **No foreign key to `market` yet.** `market` is C3's. Adding the constraint to a hypertable with
   compression already enabled was verified to work on TimescaleDB 2.30.1, so C3 can add it
   without a table rebuild.
3. **No new dependency.** Testcontainers' Postgres module runs the Timescale image as a compatible
   substitute; the Flyway Postgres module already handles the extension. `nix/deps.json` is
   untouched.
4. **The retention and compression numbers are ours to propose.** §9 lists "changing retention or
   compression policy" as ask-first; these are the *initial* values, flagged for review in
   Decision 4 rather than silently chosen.

## Architecture Decisions

- **Every migration runs in a transaction.** The Timescale DDL C2 needs — hypertables, compression
  settings, policies, continuous aggregates created `WITH NO DATA` — was verified to run inside a
  transaction on 2.30.1. Only `WITH DATA` aggregates and `refresh_continuous_aggregate` cannot, and
  no migration needs either (Decision 2).
- **Policy values live in one repeatable migration.** `R__storage_policies.sql` holds every
  compression, retention and refresh setting. Editing a value changes its checksum, so the next
  migrate re-applies it through either Flyway path (Decision 3).
- **The harness is registered globally, not per class.** `META-INF/spring.factories` in test
  resources registers a context customizer and two test execution listeners for *every* Spring
  test. A test class cannot opt out by forgetting an annotation (Decisions 6–8).
- **Background jobs are off in the test container.** `timescaledb.max_background_workers=0`, so a
  policy runs only when a test calls `run_job`. Otherwise a retention job could fire mid-test
  (Decision 10).

## Dependency Graph

```
T1 TimescaleDB everywhere (R2.1)
     ├──────────────────────┐
T2 scheduling off +        T3 reset between tests +
   outbound refused (R2.6)    random order (R2.7)
     └──────────┬───────────┘
              T4 event log hypertable + compression (R2.2, R2.3)
                │
              T5 quote snapshots + retention + rollups (R2.2, R2.4)
                │
              T6 both Flyway paths, one history (R2.5)
```

## Task List

### Phase 1 — A Timescale-backed harness
- [x] T1: TimescaleDB in dev, test and packaged deployment
- [x] T2: Scheduling off and outbound HTTP refused under test
- [ ] T3: Order-independent tests

**Checkpoint A** — the existing suite runs green on TimescaleDB, in random order, with scheduling
off and no outbound HTTP.

### Phase 2 — The fact tables
- [ ] T4: The order event log: a compressed hypertable, retained forever
- [ ] T5: Quote snapshots: raw for a bounded window, rolled up forever

**Checkpoint B** — R2.2–R2.4 each have a named passing test.

### Phase 3 — One history, two paths
- [ ] T6: Migrations apply identically via Gradle and the Flyway CLI

**Checkpoint C** — C2 acceptance met; C3 may begin.

Full task bodies with acceptance criteria live in `tasks/todo.md`.

## Risks and Mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| A continuous-aggregate refresh window reaching past raw retention silently **deletes** rolled-up history when the raw chunks are dropped | **High** — the aggregates are the only permanent quote record | T5 asserts every refresh window ends inside retention, and replays the drop to prove the aggregates survive it |
| An aggregate's shape cannot be altered, only dropped and recreated — which loses everything older than raw retention | **High**, once history exists | C4 settles the quote columns before the service accumulates history (Decision 1); after that a change means a new aggregate alongside |
| A background policy job fires mid-test and makes storage tests flaky | Med | Background workers off in the test container; tests run jobs explicitly (Decision 10) |
| The existing dev volume was initialised by plain `postgres:18-alpine`, whose `postgresql.conf` does not preload TimescaleDB | Med — `create extension` would fail on `mrun` | `shared_preload_libraries` is set on the command line in `compose.yaml`, so it does not depend on how the data directory was initialised (Decision 5) |
| Random test order surfaces a latent order dependence as an intermittent failure | Low — that is the point | The seed is printed on every run and replayable with `-PtestSeed=` (Decision 9) |
| The `mflyway` CLI's Flyway version (nixpkgs) differs from the library's (12.4.0) | Low | Script configuration via `.sql.conf` has been stable across Flyway majors; T6 verifies the real CLI entry point at 12.4.0. Nix is not available in the environment this plan was built in |

## Resolved Decisions

All delegated and decided 2026-09-24 while planning; each is open to review at Checkpoint C.

1. **C2 owns the fact tables' storage shape; C4 owns their remaining columns.** `order_event`
   carries the ERD's columns plus the previous *and* new price and quantity, which R4.2's "carrying
   previous values" needs to mean anything. `market_quote` carries the ERD's four measures only.
   R4.3's quantities, depth and online counts are C4's to define, because "depth" is not specified
   anywhere yet. C4 drops and recreates the aggregates when it adds them, which is free while no
   history exists.
2. **No migration is marked non-transactional, and the marker is proven anyway.** R2.5 assumed
   aggregates and policies cannot run in a transaction; on 2.30.1 they can, provided aggregates are
   created `WITH NO DATA`. Keeping every migration transactional means a failed one leaves nothing
   half-applied. The marker for the day one is needed — a sibling `V<n>__desc.sql.conf` holding
   `executeInTransaction=false` — is exercised by a fixture migration in T6, through both a
   classpath and a filesystem location.
3. **Policy values are declared once, in `R__storage_policies.sql`.** The alternative was Flyway
   placeholders, which would have to be supplied identically by `application.yaml` and by the
   `mflyway` shell function — two sources that can drift, on the one surface R2.5 says must agree.
   A repeatable migration is re-applied whenever its content changes, so "configurable" means
   "edit one reviewed file", consistent with §9 making policy changes ask-first.
4. **Initial values.** Event log compressed after **7 days**, no retention. Raw quotes dropped
   after **90 days**, matching the v1 statistics daily window. Hourly aggregate refreshed over the
   last 2 days, daily over the last 4, both every 30 minutes; neither aggregate has retention.
   Chunk interval is Timescale's default 7 days on both hypertables.
5. **Image `timescale/timescaledb:2.30.1-pg18`, pinned by version.** This answers SPEC §10 open
   question 3: a pg18 image exists, so nothing drops to pg17. Its `PGDATA` and volume path match
   `postgres:18-alpine`, so the existing dev volume is reused without `mdb-reset`. Telemetry is off
   in both dev and test: an unrequested outbound call does not belong in an observe-only service.
6. **Scheduling is a property, `watchdawg.scheduling.enabled`, on by default.** `@EnableScheduling`
   moves to a conditional configuration. The harness turns it off in every Spring test context,
   ranked below `@SpringBootTest(properties = …)` so a test can still opt back in. This replaces
   the Gradle `wfm.sync.initial-delay` pin: that only delayed the scheduler, applied only under
   Gradle, and leaves the context starting with scheduling *on*, which R2.6 rules out.
7. **Outbound HTTP is refused at the request factory.** Every Spring test context gets a
   `ClientHttpRequestFactoryBuilder` whose factory records the request and throws. A listener fails
   any test that reached it, even when production code swallowed the exception —
   `CollectionSyncScheduler` catches everything. `MockRestServiceServer` swaps the factory out, so
   mocked calls never touch it; a recording interceptor would have seen mocked calls too.
8. **The database is truncated before every test method.** Every table in `public` except
   `flyway_schema_history`, and every continuous aggregate, found by querying the catalog rather
   than listed by hand, so tables C3 onwards add are covered with no edit. Sequences restart too.
9. **JUnit runs classes and methods in random order.** R2.7 makes order-independence mandatory;
   random order is what makes it tested. The seed is printed at the start of `test` and pinned with
   `./gradlew test -PtestSeed=<n>`.
10. **Timescale background workers are off in the test container.** Verified that `run_job` still
    executes in the foreground with zero workers.

## Open Questions

1. **Are Decision 4's numbers right?** Every one of them is a single edit to
   `R__storage_policies.sql`, but §9 wants a human to say so.
2. **Nix verification.** `nix build .#market` and `mflyway` were not run: Nix is not available in
   the environment C2 was built in. No dependency changed, so `nix/deps.json` is unaffected, and T6
   verifies the Flyway CLI's own entry point instead. Worth one `mflyway info` on a dev machine.
