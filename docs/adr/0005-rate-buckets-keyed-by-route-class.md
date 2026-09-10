# ADR-0005: Rate-limit buckets are keyed by route class, not API version

**Date**: 2026-09-09
**Status**: accepted
**Deciders**: project author (approved during C1 task planning)

## Context

There are two upstream limits, not one: the general limit is 3 req/s, but contract search "is
expected to be limited to 10–20 req/minute" (`docs/v2/rules/overview.md`). The spec's requirement
text named the two buckets "v2 public" and "v1 contract search", which reads as a split by API
version — but the budget arithmetic puts v1 `/items/{slug}/statistics` (3,800 req/day, 0.044/s)
*inside* the v2 bucket total of ~112,500, and lists only v1 `/auctions/search` as a separate
bucket. The two readings disagree, and one of them silently misroutes 3,800 calls/day.

## Decision

Buckets are keyed by a **URI route predicate**: `contract-search` for auction-search routes,
`public` for everything else regardless of API version. The v1 statistics sweep runs on the
`public` bucket; only `/auctions/search` uses `contract-search`.

## Alternatives Considered

### Alternative 1: Bucket by API version (v1 vs v2)
- **Pros**: Matches the original requirement wording; trivially determined from the client bean.
- **Cons**: Puts the 3,800/day statistics sweep into a 12 req/min bucket, where it starves.
- **Why not**: Contradicts the budget arithmetic and the upstream rule, which limits *contract
  search* specifically — not v1 as a whole.

### Alternative 2: Bucket by which client bean issued the call
- **Pros**: No URI matching.
- **Cons**: Same failure as above in reverse — routing `/auctions/search` through a v1 client that
  also serves statistics puts it in the wrong bucket in whichever direction the bean is assigned.
- **Why not**: The bucket is a property of the endpoint's upstream limit, not of the code path
  that reached it.

## Consequences

### Positive
- Pacing matches the documented upstream limits rather than an internal code layout.
- Adding a route to either bucket is a predicate change, not a client refactor.

### Negative
- The requirement's original "v2 public / v1 contract search" phrasing was misleading and had to
  be reworded in `SPEC.md` R1.2 to match.

### Risks
- A new auction-search route that the predicate does not match would land in the `public` bucket
  and breach the 10–20 req/min limit. Mitigated by asserting both routing directions in tests.
