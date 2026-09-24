# Tasks: C2 — Time-series storage & test harness

Plan: `tasks/plan.md`. Requirement ids refer to `SPEC.md` §4 C2.
Paths are relative to the repo root; the Gradle root is `market/`.

---

## Phase 1 — A Timescale-backed harness

### Task 1: TimescaleDB in dev, test and packaged deployment

**Description:** Swap `postgres:18-alpine` for `timescale/timescaledb:2.30.1-pg18` in
`compose.yaml` and in the Testcontainers configuration, and create the extension in a migration so
every environment that migrates has it (R2.1). No table uses it yet; this task proves the whole
existing suite still passes on the new image.

**Acceptance criteria:**
- [x] `compose.yaml` and `TestcontainersConfiguration` pin the same image tag.
- [x] `V2__timescaledb.sql` creates the extension `if not exists`, so a database where an
      administrator already created it migrates as an unprivileged user.
- [x] `shared_preload_libraries` and telemetry are set on the server command line, not left to
      the image's first-boot scripts, so a data directory initialised by plain Postgres still works
      (Decision 5).
- [x] A test asserts the migrated test database has TimescaleDB installed.
- [x] The packaged jar's database requirement is written down where the jar is built.

**Verification:**
- [x] `mtest --tests '*TimescaleTest'`
- [x] `mbuild` green — the existing 49 tests unchanged
- [x] Manual: `bootRun` starts on a dev volume initialised by `postgres:18-alpine`
- [x] Manual: `bootJar`, then `java -jar` against a Timescale container migrates; against plain
      Postgres it fails at startup naming the extension

**Notes:** `spring-boot-docker-compose` recognises Postgres by image *name*, so `compose.yaml`
carries the `org.springframework.boot.service-connection: postgres` label; without it `bootRun`
connects to nothing. Testcontainers needs the equivalent `asCompatibleSubstituteFor("postgres")`.

`TimescaleTest` pins two things at once: that the test and dev images are the same tag, and that
the extension migrations install is the version that tag names. Mutation-checked by pointing the
test container at `latest-pg18`: both tests fail.

Manual runs, 2026-09-24:
- **Dev volume upgrade.** A volume initialised by `postgres:18-alpine` with V1 applied by the app,
  then `bootRun` on the new `compose.yaml`: V2 applied, `timescaledb 2.30.1` installed, and the
  row written before the upgrade still there. No `mdb-reset`.
- **Packaged jar.** `bootJar`, then `java -jar` with `SPRING_DATASOURCE_*`: against a Timescale
  container it applies V1 and V2 and starts; against `postgres:18-alpine` it fails at startup with
  `extension "timescaledb" is not available`.

Both runs pushed `wfm.sync.initial-delay` out of reach, so neither called the live API.

**Dependencies:** None
**Files likely touched:** `market/compose.yaml`,
`market/src/test/kotlin/com/watchdawg/market/TestcontainersConfiguration.kt`,
`market/src/main/resources/db/migration/V2__timescaledb.sql`, `nix/market.nix`
**Estimated scope:** S

---

### Task 2: Scheduling off and outbound HTTP refused under test

**Description:** Make scheduling a property and switch it off in every Spring test context, then
make any real outbound HTTP from a test context fail loudly and be recorded (R2.6,
[ADR-0017](../docs/adr/0017-tests-never-reach-the-live-api.md)). Both are wired from
`META-INF/spring.factories`, so no test class can forget them (Decisions 6, 7).

**Acceptance criteria:**
- [x] `@EnableScheduling` lives on a configuration conditional on `watchdawg.scheduling.enabled`,
      matching when the property is missing.
- [x] Every Spring test context starts with the property `false`, and a
      `@SpringBootTest(properties = …)` value still overrides it.
- [x] A test asserts the context starts with scheduling off and records zero outbound HTTP — the
      spec's third acceptance bullet.
- [x] An unmocked call through a real `RestClient` bean is refused and recorded, never sent.
- [x] A test that reaches the guard fails even if the code under test swallows the exception.
- [x] The Gradle `wfm.sync.initial-delay` pin is gone, replaced rather than duplicated.

**Verification:**
- [x] `mtest --tests '*MarketApplicationTests' --tests '*SchedulingConfigTest' --tests '*LiveApiGuardTest' --tests '*TestHarnessTest'`
- [x] `mbuild` green
- [x] Mutation: drop the customizer's property → the scheduling test fails; drop the guard → the
      outbound test fails

**Notes:** `@EnableScheduling` moved off `MarketApplication` onto `SchedulingConfig`, which is
conditional on the property. The harness supplies `false` from a property source ranked directly
below a test's inlined properties, so it beats `application.yaml`, system properties and the
environment, and still loses to `@SpringBootTest(properties = …)`. `TestHarnessTest` pins both
halves of that without booting a context.

The guard replaces Boot's `ClientHttpRequestFactoryBuilder` bean, which the auto-configuration
backs off from, so every `RestClient.Builder` the context hands out builds on it. The refusal is an
unchecked exception rather than an `IOException`, so `RestClient` does not wrap it into a
`ResourceAccessException` that reads like a network fault.

`MarketApplicationTests`' check that nothing is scheduled is only meaningful if the probe can see a
scheduled method at all. `SchedulingConfigTest` shows it can, on a bare context with one
`@Scheduled` bean.

Mutation-checked:
- drop the harness's property → `MarketApplicationTests`' scheduling test fails;
- drop the guard's builder, with `wfm.base-url` pointed at a dead localhost port so the mutant
  cannot reach the real API → both `LiveApiGuardTest` tests fail;
- always rank the harness first → `TestHarnessTest`'s inlined-override test fails.

**Dependencies:** T1
**Files likely touched:** `.../MarketApplication.kt`, `.../SchedulingConfig.kt`,
`market/build.gradle.kts`, `market/src/test/kotlin/.../harness/*`,
`market/src/test/resources/META-INF/spring.factories`, `MarketApplicationTests.kt`
**Estimated scope:** M

---

### Task 3: Order-independent tests

**Description:** Truncate the shared database before every Spring test method, and run the suite
in random class and method order with a printed, replayable seed (R2.7, Decisions 8, 9).

**Acceptance criteria:**
- [ ] Every table in `public` except `flyway_schema_history`, and every continuous aggregate, is
      emptied before each test, discovered from the catalog rather than listed.
- [ ] `ItemRepositoryTest` passes alone *and* in any order alongside others — the spec's second
      acceptance bullet — including after another test has written items.
- [ ] Classes and methods run in random order; the seed is printed and `-PtestSeed=` replays it.

**Verification:**
- [ ] `mtest --tests '*ItemRepositoryTest'` alone, then `mtest` under three different seeds
- [ ] `mbuild` green
- [ ] Mutation: drop the reset listener → `DatabaseResetTest` fails under both orders

**Dependencies:** T1
**Files likely touched:** `market/src/test/kotlin/.../harness/DatabaseResetListener.kt`,
`market/src/test/resources/junit-platform.properties`, `market/build.gradle.kts`
**Estimated scope:** S

---

## Checkpoint A — a Timescale-backed harness
- [ ] `mbuild` green on TimescaleDB, under several seeds
- [ ] R2.1, R2.6, R2.7 each have a named passing test

---

## Phase 2 — The fact tables

### Task 4: The order event log: a compressed hypertable, retained forever

**Description:** Create `order_event` as a hypertable on `observed_at`, compressed beyond an age
declared in `R__storage_policies.sql`, with no retention policy (R2.2, R2.3,
[ADR-0007](../docs/adr/0007-timescaledb-with-indefinite-event-log.md)).

**Acceptance criteria:**
- [ ] `order_event` is a hypertable partitioned on `observed_at`; its primary key includes it.
- [ ] `event` and `source` are constrained to the values in §3.2 and R4.2.
- [ ] A row older than the compression threshold yields a compressed chunk after the policy runs,
      and a recent row's chunk does not — the spec's fourth acceptance bullet.
- [ ] The event log has no retention policy.
- [ ] A standing test fails if any hypertable carries a unique index without its partition column,
      covering hypertables added later without being edited.

**Verification:**
- [ ] `mtest --tests '*EventLogStorageTest' --tests '*FactTablesTest'`
- [ ] `mbuild` green

**Dependencies:** T2, T3
**Files likely touched:** `.../db/migration/V3__order_event.sql`,
`.../db/migration/R__storage_policies.sql`, `market/src/test/kotlin/.../store/*`
**Estimated scope:** M

---

### Task 5: Quote snapshots: raw for a bounded window, rolled up forever

**Description:** Create `market_quote` as a hypertable with raw retention, plus hourly and daily
continuous aggregates with no retention (R2.2, R2.4). The refresh windows must end inside raw
retention, or dropping raw chunks would delete rolled-up history.

**Acceptance criteria:**
- [ ] `market_quote` is a hypertable on `observed_at`, keyed `(market_id, observed_at)`.
- [ ] `market_quote_hourly` and `market_quote_daily` roll up best bid and ask (open, high, low,
      close, average) and order counts per market.
- [ ] A raw row past the retention window is dropped by the policy while both aggregates keep its
      bucket, including after their own refresh policies run again.
- [ ] Neither aggregate has a retention policy.
- [ ] A test fails if any refresh window reaches past raw retention.
- [ ] The T3 reset empties the aggregates, without being edited.

**Verification:**
- [ ] `mtest --tests '*QuoteStorageTest' --tests '*FactTablesTest'`
- [ ] `mbuild` green

**Dependencies:** T4
**Files likely touched:** `.../db/migration/V4__market_quote.sql`,
`.../db/migration/R__storage_policies.sql`, `market/src/test/kotlin/.../store/QuoteStorageTest.kt`
**Estimated scope:** M

---

## Checkpoint B — the fact tables
- [ ] `mbuild` green, under several seeds
- [ ] R2.2, R2.3, R2.4 each have a named passing test

---

## Phase 3 — One history, two paths

### Task 6: Migrations apply identically via Gradle and the Flyway CLI

**Description:** The app reads migrations from the classpath and `mflyway` reads the same files
from the filesystem, into one `flyway_schema_history` (R2.5). Prove the two agree, and prove the
non-transactional marker works for the day a migration needs it (Decision 2).

**Acceptance criteria:**
- [ ] A database migrated through the filesystem location validates with nothing pending through
      the classpath location, and the reverse.
- [ ] A fixture migration that cannot run in a transaction fails unmarked and applies once it has
      a sibling `.sql.conf` with `executeInTransaction=false`, through both locations.
- [ ] `mflyway info` is clean from scratch — run through the Flyway CLI's own entry point.
- [ ] CLAUDE.md documents the marker and where the policy values live.

**Verification:**
- [ ] `mtest --tests '*MigrationPathsTest'`
- [ ] `mbuild` green
- [ ] Manual: Flyway CLI `migrate` then `info` against a fresh container, then the app starts on
      it with nothing to apply; and the reverse

**Dependencies:** T5
**Files likely touched:** `market/src/test/kotlin/.../store/MigrationPathsTest.kt`,
`market/src/test/resources/db/non-transactional/*`, `CLAUDE.md`
**Estimated scope:** M

---

## Checkpoint C — C2 complete
- [ ] All four of the spec's C2 acceptance bullets pass
- [ ] R2.1–R2.7 each map to a named passing test or a recorded manual check
- [ ] `SPEC.md` status note and open question 3 updated; `CHANGELOG.md` updated
- [ ] Review with human
- [ ] C3 may begin
