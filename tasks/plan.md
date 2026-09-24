# Implementation Plan: C3 — Catalog & market dimension

> Source: `SPEC.md` §4 "C3 — Catalog & market dimension" (R3.1–R3.5), with §2.3 (an item is many
> order books), §3.2 (data model) and [ADR-0003](../docs/adr/0003-market-is-a-mutually-tradable-pool.md).
> Drafted 2026-09-24.

## Overview

C3 turns the item catalog into something downstream capabilities can key on. It depends on C1
(the governed `/v2/items` call) and C2 (storage and the test harness), and C4's order ingest, C7's
statistics and C10's notifications all build on it.

Two halves:

- **Catalog.** `item` carries every field the later capabilities read: subtype dimensions and
  their maxima, tradability, rarity, and the English display name and icon that notifications
  need. It records when this service last synced a row, not an upstream timestamp that v2 does
  not have.
- **Market dimension.** A `market` row per `(item, platform, subtype, rank, charges, amberStars,
  cyanStars)` tuple, resolved idempotently and safely under concurrency. No-subtype dimensions
  compare equal. The fact tables C2 created reference it.

## Assumptions

1. **`/v2/items` could not be captured for this plan.** The environment's egress policy refuses
   `api.warframe.market`. The test fixture is built from the `Item` model in
   `docs/v2/data-models.mdx`, with dimension values taken from `docs/v1-statistics.md`'s
   observations, and is labelled as such. The spec's live acceptance bullet ("~3.8k rows") is a
   manual check for a machine that can reach the API (Decision 1).
2. **Every R3.1 field is optional in the payload.** The docs flag all of them
   "optional/contextual", so an absent field binds as null, never as a default that looks like
   data. `vaulted` keeps its existing `false` default.
3. **`CollectionSync` and `CollectionSyncScheduler` are not edited** (R3.5). Only `ItemSync`, its
   DTO and its record change.
4. **No new dependency.**

## Architecture Decisions

- **The market's platform comes from `WfmContext`, not from the caller.** `MarketKey` has no
  platform field; the resolver stamps `WfmContext.platform`. A caller therefore cannot key a book
  by the *seller's* platform, which is the mistake ADR-0003 rules out (Decision 5).
- **Resolution is select, then insert-if-absent in its own transaction, then select.** Nothing is
  cached. A market row is an idempotent fact that outlives a rolled-back ingest harmlessly, and
  committing it at once means two ingests meeting the same new tuple never wait on each other's
  locks (Decision 4).
- **Refresh is forced once after the schema change.** The migration that adds the catalog columns
  deletes the stored `items` hash, so the next tick refetches and fills them. Otherwise hash
  gating would leave existing rows without names or subtypes until upstream happened to change
  (Decision 3).

## Dependency Graph

```
T1 catalog columns + synced_at (R3.1, R3.2)
     │
T2 hash-gated refresh, end to end (R3.5)
     │
T3 market dimension + resolver (R3.3, R3.4)
     │
T4 fact tables reference markets

T5 detail fields from /v2/item/{slug} (R3.1)   ◄── added after the live run; needs only T1
```

## Task List

### Phase 1 — The catalog
- [x] T1: The catalog carries what downstream needs
- [x] T2: Catalog refresh stays hash-gated, end to end

**Checkpoint A** — a refresh fills every R3.1 column and stamps `synced_at`; an unchanged hash
fetches nothing. *Met: 80 tests.*

### Phase 2 — The market dimension
- [x] T3: Markets resolve idempotently and safely under concurrency
- [x] T4: The fact tables reference markets

**Checkpoint B** — C3 acceptance met, apart from the live catalog run; C4 may begin once that run is
recorded. *Met in the suite: 90 tests. The live run and human review are open.*

### Phase 3 — What the list leaves out
- [ ] T5: Fill `tradable`, `rarity` and `max_charges` from `/v2/item/{slug}`

**Checkpoint C** — the detail sweep fills every catalog item within its budget and never undoes a
list refresh.

Full task bodies with acceptance criteria live in `tasks/todo.md`.

## Risks and Mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| The real `/v2/items` omits some R3.1 fields (`tradable`, `rarity`, the star maxima) | Med — the columns stay null for every row | They bind as nullable, so nothing fails; the live run reports which are populated. Filling gaps from `/v2/item/{slug}` would be ~3.8k calls, which §9 makes ask-first (Open Question 1) |
| The hand-built fixture drifts from the real payload | Med | *Happened, then closed:* the capture showed `serration` has subtypes, `khra` has `maxRank` rather than `maxCharges`, and nothing carries `tradable` or `rarity`. The fixture is now a trimmed capture |
| Existing rows keep empty new columns because the items hash has not changed upstream | **High** — silent, and invisible until C10 renders a nameless notification | The migration deletes the stored hash; a migration test asserts it |
| `NULL` dimensions compare distinct and every no-subtype order creates a new market | **High** — R3.4's whole point | The unique constraint is `nulls not distinct`; tested with an all-null tuple resolved twice |
| An order arrives for an item the catalog does not have yet | Med | The foreign key rejects it with a typed exception; C4 decides whether to skip or trigger a resync |
| `TRUNCATE … CASCADE` from `market` does not empty compressed rows in `order_event` (verified on 2.30.1) | Low — only a bulk reset truncates | The test reset lists every table explicitly rather than relying on cascade; T4 pins that with a test |

## Resolved Decisions

All delegated and decided 2026-09-24 while planning; each is open to review at Checkpoint B.

1. **The fixture is documentation-shaped, and the live acceptance is manual.** *Superseded
   2026-09-24: the author ran the live check and supplied a capture, which replaced the fixture.* The environment
   that built C3 cannot reach the API. `v2-items.json` exercises every dimension with values
   consistent with `docs/v1-statistics.md` (serration ranks 0–10, requiem mods with charges,
   relics with refinements, ayatan stars). Its test KDoc says it is not a capture.
2. **Absent means null.** `tradable`, `bulk_tradable`, `rarity`, `max_rank`, `max_charges`,
   `max_amber_stars` and `max_cyan_stars` are nullable. Null says "the catalog did not say",
   which R7.8 needs for dimensions and which is honest for the flags.
3. **`synced_at` is stamped by the database.** The upsert writes `now()`, the refresh
   transaction's start time, so every row one refresh touched carries the same value. That also
   makes a row missing from the latest refresh visible: its `synced_at` is older. Rows left at the
   epoch by the old `updated_at` binding become null ("never synced by this schema"), and the
   stored `items` hash is deleted so the next tick refetches.
4. **No resolver cache.** Test resets truncate `market` with `restart identity`, and ingest
   transactions can roll back. Either would leave a cached id pointing at the wrong row. One
   indexed lookup per resolution is cheap at C4's volume; C4 can add a per-batch cache if a
   profile ever says so.
5. **`MarketKey` carries no platform.** See Architecture Decisions.
6. **Unknown items fail loudly.** `market.item_id` references `item`. Resolving a market for an
   item the catalog lacks throws `UnknownItemException` rather than inventing an item row.
7. **The fact tables get their foreign keys now.** Verified on 2.30.1: they can be added after
   compression is enabled, alongside the rollups, and they are enforced on inserts into
   compressed chunks.
8. **The detail fields come from `/v2/item/{slug}`, one item at a time.** *Approved by the project
   author, 2026-09-24 (Open Question 4, option 3):* the API is changing quickly, so the explicit
   fields are wanted rather than inferring rarity from `tags`, which could stop carrying it. A
   separate scheduled sweep fetches a batch per interval (30 a minute by default, 0.5 req/s, a
   full catalog in about two hours). It works through never-fetched items first, then any whose
   details are older than their last catalog refresh, so a catalog change triggers one re-sweep
   and nothing more. It does not run inside the catalog refresh, which would hold that transaction
   open for over half an hour. The list sync stops writing the three columns, so a refresh cannot
   undo the sweep.

## Open Questions

1. **Which R3.1 fields does the live `/v2/items` carry?** *Answered by the live run,
   2026-09-24: 3,888 items.* Every item carries `id`, `slug`, `gameRef`, `tags` and `i18n` (English
   only). Some also carry `maxRank` (1,521), `bulkTradable` (1,017, always `true`), `subtypes` (884),
   `vaulted` (799, both values), `ducats` (757) and the star maxima (10–11). **`tradable`, `rarity`
   and `maxCharges` never appear**, so those three columns are null on every row. Rarity is carried
   as a tag instead (`uncommon`, `rare`, `legendary`, …). What to do with the three columns is open
   question 4.
2. ~~Replace the fixture with a capture.~~ **Resolved:** the fixture is now eight entries trimmed
   unchanged from the author's capture.
3. **Nix verification** remains outstanding from C2.
4. ~~`tradable`, `rarity` and `max_charges` are never populated.~~ **Resolved:** filled from
   `/v2/item/{slug}` (Decision 8, T5). The option to drop `tradable` rested on the route summary
   "Get all tradable items" in `docs/v2/api/manifests.mdx`, which is documentation, not evidence.
   The `Item` model carrying a `tradable` flag at all suggests some items are not tradable. After
   the sweep, `count(*) filter (where tradable is false)` answers it.
5. **What does `/v2/item/{slug}` actually return?** T5 binds the documented `Item` model, and its
   test data is written from the docs, not captured: the live list turned out to differ from the
   docs, so this may too. A Bruno Get Item capture for `khra`, `serration` and `frost_prime_set`
   should replace it.
