# Tasks: C3 — Catalog & market dimension

Plan: `tasks/plan.md`. Requirement ids refer to `SPEC.md` §4 C3.
Paths are relative to the repo root; the Gradle root is `market/`.

---

## Phase 1 — The catalog

### Task 1: The catalog carries what downstream needs

**Description:** Add R3.1's columns to `item`, bind them from `/v2/items`, and replace the
`updated_at` column — which v2 never populates, so every row reads as the epoch — with a
`synced_at` the database stamps on each refresh (R3.1, R3.2). The migration also forces one
refresh, so existing rows get the new columns filled (Decision 3).

**Acceptance criteria:**
- [x] `item` gains `name` and `icon` (from `i18n.en`), `subtypes`, `max_charges`,
      `max_amber_stars`, `max_cyan_stars`, `bulk_tradable`, `tradable` and `rarity`, alongside the
      existing `max_rank` and `vaulted`.
- [x] A field absent from the payload stores null, not zero or false (Decision 2, R7.8).
- [x] `updated_at` becomes `synced_at`, written by the upsert as the refresh transaction's time;
      nothing reads an upstream `updatedAt` any more.
- [x] Migrating a database with epoch rows and a stored `items` hash leaves no epoch `synced_at`
      and no stored `items` hash.
- [x] Multi-subtype items keep their subtypes, in order.

**Verification:**
- [x] `mtest --tests '*ItemSyncTest' --tests '*ItemRepositoryTest' --tests '*CatalogUpgradeTest'`
- [x] `mbuild` green

**Notes:** `Items` became `Item`, with an `english` accessor over `i18n`. `ItemI18n.name` is
nullable too, so one item without an English entry cannot fail the whole payload and leave the
hash stale forever.

The fixture is documentation-shaped (plan Decision 1): placeholder ids, and dimension values
consistent with `docs/v1-statistics.md`. Serration also carries a `de` entry, so the test shows
the name comes from `en` specifically.

`CatalogUpgradeTest` migrates a scratch database to V4, writes the pre-C3 state (an epoch row, a
dated row, stored `items` and `rivens` hashes), then migrates to the latest version. The
scratch-database helper moved out of `MigrationPathsTest` into `store/ScratchDatabases.kt`, which
both now use.

Mutation-checked:
- drop V5's hash deletion → the refetch test fails;
- drop V5's epoch-to-null update → the epoch test fails;
- stamp `clock_timestamp()` per row instead of `now()` → the one-refresh-one-time test fails;
- map an absent `maxRank` to 0 → the absent-means-null test fails.

**Addendum, after the live capture:** the hand-built fixture was wrong in the ways that
mattered. `serration` has subtypes (`regular`, `atragraph`). `khra` has `maxRank: 3`, and no item
carries `maxCharges`. `frost_prime_set` has no `vaulted` field. No item carries `tradable` or
`rarity`. The fixture is now eight entries copied unchanged from the capture, including
`axi_a2_relic` for an explicit `vaulted: false` and `ayatan_ayr_sculpture` for cyan stars without
amber. `ItemSyncTest` asserts the real values. The code needed no change: absent fields already
bound as null.

**Dependencies:** None
**Files likely touched:** `.../db/migration/V5__catalog.sql`, `.../wfm/WfmModels.kt`,
`.../store/StoreModels.kt`, `.../store/Repositories.kt`, `.../sync/ItemSync.kt`,
`market/src/test/resources/fixtures/v2-items.json`, `market/src/test/kotlin/.../sync/ItemSyncTest.kt`
**Estimated scope:** M

---

### Task 2: Catalog refresh stays hash-gated, end to end

**Description:** Nothing tests the scheduler today. Drive `CollectionSyncScheduler.tick()` against
mocked `/v2/versions` and `/v2/items` responses to prove the refresh is gated on the hash and runs in
one transaction (R3.5), without editing `CollectionSync` or the scheduler.

**Acceptance criteria:**
- [x] A first tick fetches `/v2/items`, upserts the catalog and stores the hash.
- [x] A second tick with the same hash fetches `/v2/versions` only.
- [x] A changed hash refetches, and every row the new payload carries gets a later `synced_at`.
- [x] A refresh that fails part-way leaves no rows and the old hash, so the next tick retries.
- [x] `CollectionSync.kt` and `CollectionSyncScheduler.kt` are unchanged by C3.

**Verification:**
- [x] `mtest --tests '*CatalogRefreshTest'`
- [x] `git diff main -- market/src/main/kotlin/com/watchdawg/market/sync/CollectionSync*.kt` is empty
- [x] `mbuild` green

**Notes:** the test builds the real scheduler and the real `ItemSync` on one mutated v2 client,
with every expected request declared up front and in order. A `/v2/items` call the hash should
have prevented therefore fails the test outright, rather than being counted afterwards. The
part-way failure comes from two items sharing a slug: the first upsert succeeds, the second
violates `item_slug_key`, and the whole refresh has to roll back.

Mutation-checked against the scheduler, then reverted, since R3.5 keeps it unchanged:
- ignore the hash → the unchanged-hash test fails;
- store the hash before the refresh → the part-way failure test fails;
- refresh outside the transaction → the part-way failure test fails.

**Dependencies:** T1
**Files likely touched:** `market/src/test/kotlin/.../sync/CatalogRefreshTest.kt`
**Estimated scope:** S

---

## Checkpoint A — the catalog
- [x] `mbuild` green, under several seeds — 80 tests
- [x] R3.1, R3.2, R3.5 each have a named passing test
      - R3.1 → `ItemSyncTest` (fills every column, absent means null)
      - R3.2 → `ItemSyncTest.every row one refresh writes carries that refresh's time`,
        `CatalogUpgradeTest` (both)
      - R3.5 → `CatalogRefreshTest` (all three)

---

## Phase 2 — The market dimension

### Task 3: Markets resolve idempotently and safely under concurrency

**Description:** A `market` table keyed by the §2.3 tuple, and a resolver that maps a tuple to its
id, creating the row the first time (R3.3, R3.4, and R4.6 for C4's use). The platform is the
observer's context, stamped by the resolver (ADR-0003, Decision 5).

**Acceptance criteria:**
- [x] `market` has a unique constraint over the tuple with `nulls not distinct` (R3.4).
- [x] Resolving one tuple twice returns one id — including a tuple with every dimension null.
- [x] Two concurrent resolutions of a new tuple create one row and return the same id — the
      spec's second acceptance bullet — both as a thread race and with one transaction holding the
      uncommitted row while the other waits.
- [x] Rank 0 and rank 10 on `serration` resolve to two distinct markets — the spec's third bullet.
- [x] A tuple for an item the catalog lacks throws `UnknownItemException` and creates nothing.
- [x] A market resolved inside a transaction that then rolls back still exists (Decision 4).

**Verification:**
- [x] `mtest --tests '*MarketResolverTest'`
- [x] `mbuild` green

**Notes:** the lookup uses `is not distinct from` with casts, because Postgres cannot type a
null parameter in that position on its own. `explain` shows the lookup still uses the
`market_tuple` index. A foreign-key violation is recognised by SQLSTATE 23503 and becomes
`UnknownItemException`; any other integrity error propagates unchanged.

The deterministic concurrency test holds the tuple in an uncommitted transaction on a raw
connection, checks that the resolution is still blocked after 500 ms, then commits and expects
the other transaction's id. The race test starts eight threads on one latch.

Mutation-checked:
- drop `nulls not distinct` → the all-null test fails, plus both concurrency tests. On the first
  run only the concurrency tests failed, because the resolver's null-aware lookup found the
  existing row before inserting. The all-null test now also shows the table itself refuses a
  second all-null market;
- drop the `on conflict` clause → both concurrency tests fail;
- insert in the caller's transaction → the rollback test fails;
- drop the second lookup → both concurrency tests fail;
- rethrow the foreign-key violation untranslated → the unknown-item test fails.

**Dependencies:** T1
**Files likely touched:** `.../db/migration/V6__market.sql`, `.../store/MarketResolver.kt`,
`market/src/test/kotlin/.../store/MarketResolverTest.kt`
**Estimated scope:** M

---

### Task 4: The fact tables reference markets

**Description:** Add the foreign keys C2 deferred from `order_event` and `market_quote` to `market`
(Decision 7), and move the storage tests onto real markets.

**Acceptance criteria:**
- [x] An event or quote for a market that does not exist is rejected, including an insert into a
      compressed chunk.
- [x] The storage tests create their market through the resolver rather than assuming id 1.
- [x] The test reset still empties every fact table, compressed chunks included, even though
      `truncate … cascade` from `market` would not.

**Verification:**
- [x] `mtest --tests '*FactTablesTest' --tests '*EventLogStorageTest' --tests '*QuoteStorageTest' --tests '*DatabaseResetTest'`
- [x] `mbuild` green

**Notes:** `store/TestMarkets.kt` gives tests `resolver.marketFor(items)`, which creates the item
and resolves a market for it. The storage tests had written facts for a market id of 1 that
never existed. The "whole chunk past a policy's age" query was duplicated in two tests and moved
into `Policies.beyondAge`.

`DatabaseResetTest` now also leaves a compressed event-log chunk behind. That pins the probe's
finding: `truncate market cascade` does not empty compressed rows, so the reset has to name the
hypertables, which it does.

Mutation-checked:
- drop both foreign keys → both refusal tests fail;
- make the reset skip hypertables and rely on the cascade from `market` → `DatabaseResetTest`
  fails in both orders, with a compressed event row left behind (expected 0, was 1).

**Dependencies:** T3
**Files likely touched:** `.../db/migration/V7__fact_market_keys.sql`,
`market/src/test/kotlin/.../store/*`, `market/src/test/kotlin/.../harness/DatabaseResetTest.kt`
**Estimated scope:** S

---

## Phase 3 — What the list leaves out

### Task 5: Fill `tradable`, `rarity` and `max_charges` from `/v2/item/{slug}`

**Description:** The live run showed `/v2/items` never carries these three R3.1 fields. The project
author chose to fetch them item by item from `/v2/item/{slug}` (plan Decision 8). A scheduled
sweep drains the items whose details are missing or stale, in paced batches, and the list sync
stops owning the three columns.

**Acceptance criteria:**
- [x] A sweep writes each item's `tradable`, `rarity` and `max_charges` and stamps
      `detail_synced_at`.
- [x] It fetches never-fetched items first, then items whose details are older than their last
      catalog refresh, and nothing else.
- [x] One sweep fetches at most `wfm.sync.item-details.batch-size` items; the defaults keep it at
      0.5 req/s.
- [x] A catalog refresh leaves the three detail columns as the sweep wrote them.
- [x] An item the API answers `404` for is marked checked, keeping its old values, and the sweep
      moves on. A throttle or any other failure ends the sweep and leaves the rest for the next
      one, without spending more budget.
- [x] The sweep is a scheduled component, so the test harness keeps it off; `SPEC.md` §2.1 counts
      it against the budget.

**Verification:**
- [x] `mtest --tests '*ItemDetailSyncTest' --tests '*ItemSyncTest'`
- [x] `mbuild` green

**Notes:** the list upsert no longer names `tradable`, `rarity` or `max_charges` at all, so a
catalog refresh cannot write them; `recordDetail` is the only writer. `needingDetail` orders by
`detail_synced_at nulls first, slug`. A 404 calls `markDetailChecked`, which stamps the time
without touching values, so an item that disappears upstream keeps what was last known.

The test data is synthetic (`item_a`, `c_never`, …) and says so. It tests the sweep's behaviour,
not the upstream shape, which still needs a capture (Checkpoint C).

Mutation-checked:
- the list upsert blanks `tradable` → the refresh-keeps-details test fails;
- staleness ignored → three tests fail;
- never-fetched priority dropped → the ordering test and the 404 test fail. On the first run only
  the 404 test failed, because the ordering test's slugs sorted the same way alphabetically; they
  were renamed so the two orders disagree;
- a 404 stops the sweep, or blanks the old details → the 404 test fails;
- the batch limit ignored → the batch test fails;
- a failure carries on instead of stopping → the throttle test (catch-all branch) or the 503 test
  (HTTP-error branch) fails.

**Dependencies:** T1
**Files likely touched:** `.../db/migration/V8__item_detail.sql`, `.../store/Repositories.kt`,
`.../store/StoreModels.kt`, `.../wfm/WfmClient.kt`, `.../sync/ItemDetailSync.kt`,
`market/src/main/resources/application.yaml`, `market/src/test/kotlin/.../sync/ItemDetailSyncTest.kt`
**Estimated scope:** M

---

## Checkpoint C — detail fields
- [x] `mbuild` green, under several seeds — 97 tests
- [x] A capture of `/v2/item/{slug}` replaces T5's documentation-shaped test data (plan Open
      Question 5) — the author's captures of `khra`, `serration` and `frost_prime_set` drive
      `ItemDetailSyncTest`'s first test
- [ ] After a live run, `count(tradable)`, `count(rarity)` and `count(max_charges)` are non-zero,
      and `count(*) filter (where tradable is false)` is recorded

---

## Checkpoint B — C3 complete
- [x] Three of the spec's four C3 acceptance bullets pass in the suite — 90 tests
      - "resolving one tuple twice returns one id; two concurrent resolutions of a new tuple create
        one row" — `MarketResolverTest.resolving one tuple twice returns one id`,
        `…concurrent resolutions of a new tuple create one market`, `…a resolution waits for an
        uncommitted insert of its tuple and returns that row`
      - "rank-0 and rank-10 orders on `serration` resolve to two distinct markets" —
        `MarketResolverTest.rank 0 and rank 10 of serration are two markets`
      - "no row has `synced_at` = epoch" — `CatalogUpgradeTest.an epoch timestamp is not carried
        over as a sync time`, and `ItemSyncTest` for rows a refresh writes
      - "the real `/v2/items` payload yields ~3.8k rows…" — needs the live run below
- [x] Live catalog run on a machine that can reach the API — run by the project author,
      2026-09-24: `mrun` upserted **3,888** items; **884** have subtypes; **0** epoch rows; all
      3,888 named. `tradable` and `rarity` are populated on 0 rows, `bulk_tradable` on 1,017, the
      star maxima on 10. The capture behind it shows `/v2/items` never carries `tradable`, `rarity`
      or `maxCharges` (plan Open Questions 1 and 4).
- [x] `SPEC.md` status note and `CHANGELOG.md` updated
- [ ] Review with human
- [ ] C4 may begin
