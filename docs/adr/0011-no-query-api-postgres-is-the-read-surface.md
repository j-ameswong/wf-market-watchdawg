# ADR-0011: No query API; Postgres is the read surface

**Date**: 2026-09-09
**Status**: accepted
**Deciders**: project author (spec review)

## Context

The service has exactly one user: the operator who runs it. A query API over the warehouse was
scoped as a capability (C11) and would have been the largest module in the design. Nothing else
depends on it, and the queries it would serve are not yet known — the warehouse's whole premise is
that useful questions will emerge from data that has accrued.

## Decision

No query API and no UI. The warehouse is read directly from Postgres — `mpsql`, or any BI tool
pointed at it. The service only writes. Metrics are its only HTTP surface: actuator, no data
endpoints.

## Alternatives Considered

### Alternative 1: Build the query API (C11)
- **Pros**: A stable interface over the schema; remote access without database credentials.
- **Cons**: Largest module in the design, for one user who already has psql.
- **Why not**: Least clear payoff per unit of work. Designing an API before the queries are known
  means guessing at the access patterns it should optimise for.

### Alternative 2: Defer the decision, leave C11 in the plan
- **Pros**: Keeps the option formally open.
- **Cons**: A capability nobody is building still shapes the schema and the sequencing around it.
- **Why not**: Better to drop it and revisit as its own spec round once real queries exist.

## Consequences

### Positive
- Removes the largest module from the build order entirely.
- Schema evolution has no external API contract to preserve.

### Negative
- Reading the warehouse requires database access and SQL, which suits the single-operator
  audience and nothing else.

### Risks
- Capability ids are deliberately **not** renumbered after dropping C11, so requirement references
  stay stable. The gap in the sequence is intentional, not an omission.
