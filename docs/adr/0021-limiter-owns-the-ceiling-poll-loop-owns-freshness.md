# ADR-0021: The limiter owns the request ceiling; the poll loop owns freshness

**Date**: 2026-09-25
**Status**: accepted
**Deciders**: project author, on the 2026-09-25 project review
**Supersedes**: [ADR-0006](0006-poll-scheduler-owns-budget-compliance.md)

## Context

ADR-0006 made the poll scheduler responsible for keeping demand inside the rate budget, and rejected
fixed schedules partly because over-demand would surface as a `429`. That holds only if the limiter
lets over-demand through. It does not: every request takes a paced turn, so demand above the budget
waits instead. The review that prompted this record also found a hole in that pacing when calls
queued behind a busy connection, and it is fixed in the same change.

ADR-0006 also brought in database poll targets, tiers, adaptive back-off, socket-driven promotion
and draining that is safe across instances. None of it has been needed yet. The last item is not
even sufficient on its own: the limiter is per process, so two instances polling different targets
could each stay inside their own budget and still exceed the shared upstream one.

## Decision

The limiter owns the hard ceiling: no request starts before its turn, whoever asks. The poll loop
owns freshness and priority: which items are polled, and how often. The first poll loop runs as a
single instance, on a fixed cadence over the configured watched items, and never overlaps itself.
It measures lateness and request use, and adaptive scheduling is added only when those measurements
show the fixed cadence cannot keep watched items fresh.

## Alternatives Considered

### Alternative 1: Keep ADR-0006's adaptive, database-backed scheduler
- **Pros**: Scales toward whole-catalog coverage without redesign.
- **Cons**: Tiers, back-off, promotion and cross-instance draining before any of them is shown to
  be needed. Several instances would still need a shared limiter.
- **Why not**: It builds for a coverage target before the service has delivered one alert.

### Alternative 2: Fixed cadence with no lateness measurement
- **Pros**: Simplest.
- **Cons**: When demand exceeds the budget, polls silently fall behind.
- **Why not**: ADR-0006's objection to an invisible backlog still stands, so lateness is measured.

## Consequences

### Positive
- Budget compliance holds by construction whatever the poll loop asks for, because it lives in the
  one place every request passes through.
- The poll loop is small enough to build alongside the first alert.

### Negative
- Over-demand shows up as staleness rather than being planned away. Watched items have to be few
  enough, or the interval long enough, for the cadence to hold.

### Risks
- A second instance would double the effective request rate. Running more than one needs a shared
  limiter first.
