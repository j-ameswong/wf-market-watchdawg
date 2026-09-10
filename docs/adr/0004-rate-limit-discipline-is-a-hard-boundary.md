# ADR-0004: Rate-limit compliance is a hard boundary

**Date**: 2026-09-09
**Status**: accepted
**Deciders**: project author (spec review)

## Context

warframe.market's public API allows 3 req/s; Cloudflare answers `429` above it and `509` on too
many concurrent connections. `docs/v2/rules/overview.md` reserves the right to restrict clients by
"IP addresses, networks, cloud providers… or traffic patterns" and to block clients whose traffic
is "difficult to classify". This is a community service with limited infrastructure.

The full design — whole-market realtime coverage, tiered order-book polling, daily statistics —
sums to **~1.3 req/s against the 3 req/s ceiling**. Compliance costs nothing that the service
actually wants to do.

## Decision

Rate-limit compliance is an inviolable boundary, not a best effort. A single limiter governs every
outbound call, enforced at the transport layer so no call site can bypass it, and a `429`/`509` is
treated as a bug in our pacing. Raising effective throughput via proxy rotation, multiple egress
paths, or a browser-impersonating `User-Agent` is prohibited.

## Alternatives Considered

### Alternative 1: Pace at call sites, by convention
- **Pros**: No interceptor machinery.
- **Cons**: One forgotten call site breaches the limit, and the breach surfaces as a server-side
  block rather than a local failure.
- **Why not**: A boundary that depends on every future author remembering it is not a boundary.

### Alternative 2: Treat the limit as soft, back off on `429`
- **Pros**: Higher throughput when the server tolerates it.
- **Cons**: Evasion targets exactly what the operators police.
- **Why not**: No capability in the spec needs more than 1.3 req/s, so the entire upside is
  hypothetical while the downside is being blocked from a service with no alternative.

## Consequences

### Positive
- The budget becomes a design input rather than a runtime surprise: new endpoints are costed
  before they are called, which is why "calling any new upstream endpoint" is an ask-first item.
- Staying observational — no `POST`/`PATCH`/`DELETE` on orders or auctions — keeps the client
  clearly onside of rules that call trade bots a grey area with stricter limits coming.

### Negative
- Latency on any signal that requires a poll is bounded by the budget, not by need. See
  [ADR-0008](0008-vanished-only-from-full-book-polls.md).

### Risks
- A hand-rolled limiter can be subtly wrong under concurrency. Mitigated by deterministic tests
  with an injected clock, then a 1h live run asserting sustained req/s ≤ configured and **zero**
  `429`/`509`, with per-bucket metrics as the runtime proof.
