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
- [ ] `item` gains `name` and `icon` (from `i18n.en`), `subtypes`, `max_charges`,
      `max_amber_stars`, `max_cyan_stars`, `bulk_tradable`, `tradable` and `rarity`, alongside the
      existing `max_rank` and `vaulted`.
- [ ] A field absent from the payload stores null, not zero or false (Decision 2, R7.8).
- [ ] `updated_at` becomes `synced_at`, written by the upsert as the refresh transaction's time;
      nothing reads an upstream `updatedAt` any more.
- [ ] Migrating a database with epoch rows and a stored `items` hash leaves no epoch `synced_at`
      and no stored `items` hash.
- [ ] Multi-subtype items keep their subtypes, in order.

**Verification:**
- [ ] `mtest --tests '*ItemSyncTest' --tests '*ItemRepositoryTest' --tests '*CatalogUpgradeTest'`
- [ ] `mbuild` green

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
- [ ] A first tick fetches `/v2/items`, upserts the catalog and stores the hash.
- [ ] A second tick with the same hash fetches `/v2/versions` only.
- [ ] A changed hash refetches, and every row the new payload carries gets a later `synced_at`.
- [ ] A refresh that fails part-way leaves no rows and the old hash, so the next tick retries.
- [ ] `CollectionSync.kt` and `CollectionSyncScheduler.kt` are unchanged by C3.

**Verification:**
- [ ] `mtest --tests '*CatalogRefreshTest'`
- [ ] `git diff main -- market/src/main/kotlin/com/watchdawg/market/sync/CollectionSync*.kt` is empty
- [ ] `mbuild` green

**Dependencies:** T1
**Files likely touched:** `market/src/test/kotlin/.../sync/CatalogRefreshTest.kt`
**Estimated scope:** S

---

## Checkpoint A — the catalog
- [ ] `mbuild` green, under several seeds
- [ ] R3.1, R3.2, R3.5 each have a named passing test

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
