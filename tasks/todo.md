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
- [ ] `market` has a unique constraint over the tuple with `nulls not distinct` (R3.4).
- [ ] Resolving one tuple twice returns one id — including a tuple with every dimension null.
- [ ] Two concurrent resolutions of a new tuple create one row and return the same id — the
      spec's second acceptance bullet — both as a thread race and with one transaction holding the
      uncommitted row while the other waits.
- [ ] Rank 0 and rank 10 on `serration` resolve to two distinct markets — the spec's third bullet.
- [ ] A tuple for an item the catalog lacks throws `UnknownItemException` and creates nothing.
- [ ] A market resolved inside a transaction that then rolls back still exists (Decision 4).

**Verification:**
- [ ] `mtest --tests '*MarketResolverTest'`
- [ ] `mbuild` green

**Dependencies:** T1
**Files likely touched:** `.../db/migration/V6__market.sql`, `.../store/MarketResolver.kt`,
`market/src/test/kotlin/.../store/MarketResolverTest.kt`
**Estimated scope:** M

---

### Task 4: The fact tables reference markets

**Description:** Add the foreign keys C2 deferred from `order_event` and `market_quote` to `market`
(Decision 7), and move the storage tests onto real markets.

**Acceptance criteria:**
- [ ] An event or quote for a market that does not exist is rejected, including an insert into a
      compressed chunk.
- [ ] The storage tests create their market through the resolver rather than assuming id 1.
- [ ] The test reset still empties every fact table, compressed chunks included, even though
      `truncate … cascade` from `market` would not.

**Verification:**
- [ ] `mtest --tests '*FactTablesTest' --tests '*EventLogStorageTest' --tests '*QuoteStorageTest' --tests '*DatabaseResetTest'`
- [ ] `mbuild` green

**Dependencies:** T3
**Files likely touched:** `.../db/migration/V7__fact_market_keys.sql`,
`market/src/test/kotlin/.../store/*`, `market/src/test/kotlin/.../harness/DatabaseResetTest.kt`
**Estimated scope:** S

---

## Checkpoint B — C3 complete
- [ ] Three of the spec's four C3 acceptance bullets pass in the suite
- [ ] Live catalog run on a machine that can reach the API: `mrun`, wait for the first sync, then
      `mpsql -c "select count(*), count(*) filter (where cardinality(subtypes) > 0),
      count(*) filter (where synced_at = 'epoch') from item"` — ~3.8k rows, relics with subtypes,
      zero epoch rows. Note which R3.1 columns are never populated (Open Question 1).
- [ ] `SPEC.md` status note and `CHANGELOG.md` updated
- [ ] Review with human
- [ ] C4 may begin
