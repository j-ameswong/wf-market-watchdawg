# ADR-0003: A market is a mutually-tradable pool, not a seller-platform partition

**Date**: 2026-09-09
**Status**: accepted
**Deciders**: project author (spec review)

## Context

An `Order` carries `subtype`, `rank`, `charges`, `amberStars` and `cyanStars`, and `/top` exposes
all five as filters — so a price without its dimension tuple is meaningless. With crossplay
enabled ([ADR-0001](0001-pc-observer-context-with-crossplay.md)), each order additionally carries
the seller's own platform, which raises the question of whether `platform` belongs in the
dimension tuple as the seller's platform or as the observer's.

## Decision

A **market** is the tuple `(item, platform, subtype, rank, charges, amberStars, cyanStars)`, where
`platform` is the **observer's context** (`pc`), not the counterparty's. The counterparty's
platform is an attribute of the order — `wfm_order.seller_platform` — not a dimension of the book.

## Alternatives Considered

### Alternative 1: `platform` = seller platform
- **Pros**: Each row self-describes which platform's user posted it, without a separate column.
- **Cons**: Splits one tradable pool into four books.
- **Why not**: Best bid and best ask only mean something over a set of mutually-tradable orders.
  Partitioning by seller would force every threshold rule to aggregate back across four markets to
  stay correct — reconstructing, at query time, the thing the dimension had just destroyed.

### Alternative 2: Item-level books, dimensions ignored
- **Pros**: Far fewer rows; simpler ingest.
- **Cons**: Collapses rank-0 and rank-10 mods, intact and radiant relics, and every star
  combination onto one price series.
- **Why not**: The resulting prices are not comparable to each other, so no rule built on them can
  be correct.

## Consequences

### Positive
- Best bid / best ask / spread are well-defined over exactly the orders the operator can trade.
- The `market` dimension serves two fact families — order books and v1 statistics, which carry the
  same subtype dimensions ([ADR-0010](0010-item-stat-market-keyed-two-series.md)).

### Negative
- NULL subtype dimensions must compare **equal** for uniqueness. Postgres treats NULLs as distinct
  by default, which would silently defeat upserts for the common no-subtype case.

### Risks
- Market resolution happens on the ingest hot path and must be idempotent and concurrency-safe;
  two concurrent resolutions of a new tuple must create one row, not two.
