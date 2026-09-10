# ADR-0010: `item_stat` is market-keyed, two-series, and crossplay-identified

**Date**: 2026-09-09
**Status**: accepted
**Deciders**: project author (spec review)

## Context

`/v1/items/{slug}/statistics` is undocumented upstream — absent from `docs/v1.yml`, with no v2
equivalent. It was captured on 2026-09-09 across six slugs chosen to exercise every subtype
dimension; the schema is recorded in `docs/v1-statistics.md`. Three properties of the payload
drive the storage design:

1. Statistics split by the **same subtype dimensions as orders** (`mod_rank`, `subtype`,
   `amber_stars`, `cyan_stars`).
2. Each window carries **two different series with different field sets** — `statistics_closed`
   (real trades, OHLC + Donchian, no side) and `statistics_live` (open book, has `order_type`, no
   OHLC). `volume` means trades in one and open-order count in the other.
3. Row `id`s are unstable across crossplay settings, and the two settings return *different
   populations* rather than a superset: flipping the header changed 76 of 88 historical
   closed-daily rows on `frost_prime_set`, and `volume` moved **down** (51 → 41 for the 2026-06-12
   bucket).

## Decision

`item_stat` rows key to a **`market`** (not an `item`), keep the two series separate under a
`section` discriminator, and carry `crossplay` as part of the row's logical identity. The upsert
key is the logical tuple `(section, granularity, bucket, dimensions[, order_type], crossplay)` —
verified unique across all 3,386 sampled rows. The upstream row `id` is stored alongside but never
used as a key.

## Alternatives Considered

### Alternative 1: Key on the upstream row `id`
- **Pros**: Obvious, single-column, supplied by the API.
- **Cons**: Refetching at an unchanged crossplay setting is byte-identical (0/88 rows differ), but
  flipping `Crossplay` changes **88/88 row ids** on historical buckets whose values did not move.
- **Why not**: Would have duplicated the entire history the first time that header changed.

### Alternative 2: Merge the two series into one row shape
- **Pros**: One table, one row per bucket, fewer nulls to reason about.
- **Cons**: `closed.volume` counts trades and `live.volume` counts open orders — orders of
  magnitude apart on a liquid item.
- **Why not**: Merging them would corrupt every volume metric in the warehouse, silently.

### Alternative 3: Omit `crossplay` from the key
- **Pros**: Narrower key; one row per bucket per market.
- **Cons**: Nothing in the row records which population produced it, so any later change to the
  setting silently overwrites history with differently-scoped numbers.
- **Why not**: Unrecoverable corruption with no error at the time it happens. One column keeps the
  decision reversible.

## Consequences

### Positive
- The `market` dimension ([ADR-0003](0003-market-is-a-mutually-tradable-pool.md)) serves both
  order books and statistics.
- Ingest is idempotent across refetches even though every row `id` would differ at the other
  crossplay setting.

### Negative
- A third population now exists in the warehouse: order books describe a PC+crossplay pool
  ([ADR-0001](0001-pc-observer-context-with-crossplay.md)) while statistics describe something
  else again. `seller_platform` and `item_stat.crossplay` exist to keep them separable, but they
  only help a query author who knows to use them.

### Risks
- **`mod_rank` is ambiguous.** No `charges` field appears anywhere in the payload, and requiem
  mods — which v2 models with `maxCharges` — report `mod_rank: 3`. Mapping `mod_rank` → `rank`
  unconditionally collapses them onto the wrong market, and the corruption stays invisible until
  someone queries those items specifically. Resolve using the item's own `maxRank`/`maxCharges`
  from the catalog. This is the one unresolved correctness question in the design.
- What `Crossplay` *means* to this v1 route is inferred, not documented — best reading is trades
  where both sides are crossplay-enabled. The inference is one slug deep and unconfirmed upstream,
  which is precisely why the flag is stored rather than assumed away.
