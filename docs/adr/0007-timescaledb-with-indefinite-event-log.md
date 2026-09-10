# ADR-0007: TimescaleDB with an indefinite event log and rollups

**Date**: 2026-09-09
**Status**: accepted
**Deciders**: project author (spec review)

## Context

The service has two jobs off one ingestion pipeline: alert within seconds, and retain history so
questions can be asked later that nobody thought to ask up front. The second job only works if raw
observations survive — roughly 100k events/day — and if retention is affordable enough that
"forever" is a real answer rather than an aspiration.

## Decision

Store facts in PostgreSQL with TimescaleDB. Fact tables are hypertables; the order event log is
retained **indefinitely** and compressed beyond a configurable age; quote snapshots are retained
raw for a bounded window with hourly and daily continuous aggregates retained indefinitely.

## Alternatives Considered

### Alternative 1: Native PostgreSQL declarative partitioning
- **Pros**: No extension; works on any Postgres image.
- **Cons**: Retention, compression and rollups all become hand-written jobs.
- **Why not**: Hypertables, continuous aggregates and compression do that work directly, and
  retention economics are the whole reason "keep it forever" is viable.

### Alternative 2: Drop raw events after a retention window, keep only aggregates
- **Pros**: Bounded storage with no compression story.
- **Cons**: Forecloses any future question that needs order-level detail — which is the stated
  purpose of the warehouse.
- **Why not**: Compression makes retaining the detail cheap enough that discarding it buys little.

## Consequences

### Positive
- Future questions can be asked of past data at order granularity, not just at rollup granularity.
- The event log **is** the replayable append-only log, which is what makes
  [ADR-0012](0012-kafka-stays-unwired.md) possible.

### Negative
- Every unique index on a hypertable must include the partition column — a Timescale requirement.
  This is why the fact tables carry composite natural keys rather than a surrogate `bigserial`.
- Migrations that cannot run inside a transaction (continuous aggregates, retention and
  compression policies) must be marked as such and apply cleanly via both Gradle and the `mflyway`
  CLI, which share one history table.

### Risks
- `compose.yaml` pins `postgres:18-alpine` and a TimescaleDB image for pg18 may not exist. If not,
  the pin drops to pg17 and the dev volume must be reset — the data directory is incompatible
  across major versions.
