# ADR-0019: One retry, and a long `Retry-After` surfaces to the caller

**Date**: 2026-09-09
**Status**: accepted
**Deciders**: delegated to the implementer during C1 task planning (recorded as Decision 4 in
`tasks/archive/c1-plan.md`)

## Context

R1.3 requires a *bounded* retry on `429`/`509` that honours `Retry-After`, without naming the
bound. C1's own acceptance bullet asks for "exactly one retry". The retry runs inside
`WfmRateLimitInterceptor`, on the calling thread, so any cooloff it waits out is a worker thread
held for that long. `Retry-After` can name minutes.

## Decision

One initial call and **exactly one** retry. The retry takes its own turn from the limiter, so it
spends budget rather than bypassing it.

A `Retry-After` above `wfm.limits.max-retry-after` (60s) is not waited out: the refusal surfaces
immediately as a typed `ThrottledException` carrying the parsed duration. Deciding what to do with
a long cooloff belongs to the caller — for sweeps, the poll scheduler (C6) — not to the
interceptor. A refusal with no `Retry-After` gets no extra cooloff; the retry's own turn already
spaces it by the bucket rate.

## Alternatives Considered

### Alternative 1: Honour any `Retry-After` inside the interceptor
- **Pros**: Callers never see a throttle they could have waited out.
- **Cons**: Parks a worker thread for as long as the server asks, possibly minutes.
- **Why not**: The poll scheduler is the component that should decide what a long cooloff means
  for its queue.

### Alternative 2: More than one retry
- **Pros**: Rides out a longer burst of refusals.
- **Cons**: A second refusal in a row says more than "busy", and each attempt spends budget.
- **Why not**: The spec's acceptance bullet fixes the number at one.

## Consequences

### Positive
- A throttled call never holds a thread longer than the configured ceiling.
- `ThrottledException.retryAfter` gives the caller the server's cooloff to act on.

### Negative
- Callers must handle `ThrottledException`. C6 in particular has to defer a target on a long
  `Retry-After` rather than treat it as a failed poll.

### Risks
- A ceiling set too low turns routine cooloffs into failures. It is configurable
  (`wfm.limits.max-retry-after`).
