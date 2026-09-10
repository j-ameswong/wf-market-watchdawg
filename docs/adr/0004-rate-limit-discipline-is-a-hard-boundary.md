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

**Refined 2026-09-10 (C1 T2):** the limiter spaces turns evenly at `per / permits` rather than
letting a bucket's allowance be spent in a burst — see Alternative 3.

**Refined 2026-09-10 (C1 T4):** a refusal costs budget rather than skipping it — the single retry
takes its own turn from the limiter before reissuing. A `509` additionally gives up one connection
slot permanently, floored at one, and the cap never widens again within a run. The server has told
us the concurrency it will not accept; creeping back toward it is the kind of "traffic pattern" the
rules reserve the right to police, and the configured cap of 2 leaves exactly one step to give.

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

### Alternative 3: Spend a bucket's allowance in bursts, up to the window
- **Pros**: Lower latency for work that arrives in batches — C8's auction sweep could issue 12
  requests at once rather than spread over a minute.
- **Cons**: "12 req/min" is our own restatement of a limit the rules express as a *rate*
  ("expected to be limited to 10–20 req/minute"), not as a window the server promises to refill.
  Measured over a sliding window, a burst reads as 12 requests in the first second.
- **Why not**: The upside is latency on a sweep with no deadline; the downside is precisely the
  `429` this ADR defines as a bug. Revisit only if T8's live run shows the spaced rate leaving
  budget unused.

## Consequences

### Positive
- The budget becomes a design input rather than a runtime surprise: new endpoints are costed
  before they are called, which is why "calling any new upstream endpoint" is an ask-first item.
- Staying observational — no `POST`/`PATCH`/`DELETE` on orders or auctions — keeps the client
  clearly onside of rules that call trade bots a grey area with stricter limits coming.

### Negative
- Latency on any signal that requires a poll is bounded by the budget, not by need. See
  [ADR-0008](0008-vanished-only-from-full-book-polls.md).
- Even spacing means a bucket cannot absorb a spike: contract search issues one request every 5s
  rather than 12 and then idling. Any sweep sized against a burst — C8's is the one that would be —
  must be sized against the spaced rate instead.

### Risks
- A hand-rolled limiter can be subtly wrong under concurrency. Mitigated by deterministic tests
  with an injected clock, then a 1h live run asserting sustained req/s ≤ configured and **zero**
  `429`/`509`, with per-bucket metrics as the runtime proof.
