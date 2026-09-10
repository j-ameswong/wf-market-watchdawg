# ADR-0006: The poll scheduler owns rate-budget compliance

**Date**: 2026-09-09
**Status**: accepted
**Deciders**: project author (spec review)

## Context

The rate limiter enforces the ceiling, but it cannot decide *what* to spend budget on — by the
time a call reaches the limiter, the decision to make it has been taken. Order-book polling is the
only consumer with an adjustable appetite: 200 hot items at 5m, 800 warm at 30m and 2,800 cold at
6h comes to 107,200 req/day (1.24 req/s), which is the overwhelming majority of the ~1.3 req/s
total. Every other consumer is fixed or negligible.

## Decision

The poll scheduler is the component responsible for keeping aggregate demand inside the C1 budget,
**by construction** rather than by luck. It holds poll targets as database rows (kind, ref, tier,
interval, next-due), backs off multiplicatively when results are unchanged, and tightens when they
change.

## Alternatives Considered

### Alternative 1: Fixed per-tier schedules, budget verified on paper
- **Pros**: Simplest possible scheduler.
- **Cons**: Any catalog growth or tier reassignment silently pushes demand over the ceiling, and
  the first symptom is a `429`.
- **Why not**: Makes the arithmetic true at design time and unverified at runtime, which is the
  opposite of what a hard boundary ([ADR-0004](0004-rate-limit-discipline-is-a-hard-boundary.md))
  requires.

### Alternative 2: Let the limiter absorb over-demand by queueing
- **Pros**: No scheduler responsibility at all; the limiter simply paces whatever arrives.
- **Cons**: Demand above the ceiling turns into unbounded queue growth and ever-staler data, with
  no signal about which targets are being starved.
- **Why not**: Moves the failure from a visible breach to an invisible backlog.

## Consequences

### Positive
- Attention follows real activity: a realtime event for an item advances that item's next-due
  time, so push informs poll priority instead of a fixed rota.
- Draining is safe under concurrency, so a second instance cannot double-poll.

### Negative
- This is the most load-bearing decision in the design — it is what makes the budget arithmetic
  hold at runtime rather than on paper. A defect here is a defect in rate-limit compliance.

### Risks
- Queue depth and oldest-overdue-target must be observable, or starvation is undetectable.
