# ADR-0015: At-least-once notification delivery through an outbox

**Date**: 2026-09-09
**Status**: accepted
**Deciders**: project author (spec review)

## Context

A signal is produced inside the ingest transaction that justified it, but delivery is a network
call to a third party that can fail, hang or partially succeed. ntfy exposes no idempotency key,
so exactly-once delivery is not available at any price. The choice is therefore between risking a
duplicate push and risking a lost one.

## Decision

Delivery is an outbox: signals are durably queued in the same transaction as the ingest that
produced them, and sent by a separate dispatcher. Delivery is **at-least-once** — the dispatcher
marks a signal sent only after a 2xx from ntfy, so a crash between send and mark may re-deliver
one notification. It must never silently drop one.

## Alternatives Considered

### Alternative 1: Send inline during ingest
- **Pros**: No outbox table, no dispatcher, lowest latency.
- **Cons**: A slow or failing ntfy stalls or fails the ingest transaction, and a rollback after a
  successful send means a push for data that did not persist.
- **Why not**: Couples warehouse correctness to a third party's availability.

### Alternative 2: Mark sent before the POST (at-most-once)
- **Pros**: Never duplicates.
- **Cons**: A crash mid-send loses the alert with no trace.
- **Why not**: For an alerting system, a rare duplicate push is strictly preferable to a lost one.

## Consequences

### Positive
- A signal and the data that justified it commit or roll back as one unit — rolling back an ingest
  transaction leaves no signal, so there is never a push for data that did not persist.
- Failures retry to a bounded attempt count with the last error recorded, rather than vanishing.

### Negative
- A crash in the gap between a successful POST and the `notified_at` write produces one duplicate
  push on restart. Accepted.

### Risks
- Two different mechanisms are easily confused: `signal.dedup_key` prevents a rule from producing
  duplicate **signals**; it does not and cannot prevent duplicate **deliveries** of one signal.
