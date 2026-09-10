# ADR-0018: Contracts and auctions are sequenced after the item spine

**Date**: 2026-09-09
**Status**: accepted
**Deciders**: project author (spec review)

## Context

Riven, lich and sister auctions are a wanted capability, but they are reachable only through v1 —
v2 has a `contracts` concept (`Group.kind`, an OAuth scope) but ships no public routes. v1 uses a
`payload`/`include` envelope and snake_case, `/auctions/search` cannot be enumerated (it requires a
weapon or an attribute), and it sits under a separate, much tighter rate limit of 10–20 req/min.

Riven valuation is also the harder modelling problem: value lives in the *combination* of
attributes rather than in a single price, so there is no equivalent of "best ask" to alert on.

## Decision

Build the item spine — API access, storage, catalog, order ingest, realtime feed, threshold rules,
notifications — to a working push notification first. Contracts and auctions come after that,
using the contract-search rate bucket exclusively.

## Alternatives Considered

### Alternative 1: Build contracts alongside the item spine
- **Pros**: Both halves of the market land together.
- **Cons**: Contracts share only the API-access capability with the spine, and add the hardest
  modelling problem in the project to the critical path of the first useful output.
- **Why not**: Delays proof that the end-to-end pipeline works at all, for a capability nothing
  else depends on.

### Alternative 2: Drop auctions from scope
- **Pros**: Removes the v1 envelope, the enumeration problem and the second rate bucket.
- **Cons**: Rivens are a large part of the market the service is meant to watch.
- **Why not**: The capability is wanted; only its ordering is in question.

## Consequences

### Positive
- The dependency graph stays acyclic and the shortest path to a real push notification is
  unobstructed.
- By the time auctions are built, the rate limiter, storage and event-log patterns are proven.

### Negative
- No riven coverage until the spine is complete.

### Risks
- Sweeps must iterate weapon slugs from the v2 riven/lich/sister manifests, because
  `/auctions/search` cannot be enumerated. A slug missing from the manifest is a market the
  service never sees.
