# ADR-0008: `vanished` is inferred only from full book polls

**Date**: 2026-09-09
**Status**: accepted
**Deciders**: project author (spec review)

## Context

Order observations arrive from three sources with different completeness guarantees. The WebSocket
`newOrders` subscription and `/v2/orders/recent` are **creates-only and partial** — they report
new orders, never removals, and never claim to enumerate the book. Only
`GET /v2/orders/item/{slug}` returns a full book for a market.

An order disappearing is the most interesting event the service can observe: it is the closest
available evidence of a sale. It is also the event most easily fabricated, because "not present in
this response" means completely different things depending on which source produced the response.

## Decision

`vanished` is inferred **only** from a full book poll, by diffing the response against last known
state. Absence from the socket or from `/v2/orders/recent` produces no event.

## Alternatives Considered

### Alternative 1: Treat absence from `/orders/recent` as a removal
- **Pros**: Removal latency drops to the 60s `/recent` cadence for recently-active orders.
- **Cons**: `/recent` returns 500 orders from the last 4h across the whole market — absence is the
  normal state for almost every order, at all times.
- **Why not**: It would mark essentially the entire book as vanished on every poll.

### Alternative 2: Timeout-based expiry — mark an order gone if unseen for N minutes
- **Pros**: No dependence on poll cadence; works for cold items.
- **Cons**: Manufactures a removal timestamp that no observation supports, on the exact event that
  order lifetimes are measured from.
- **Why not**: The warehouse would record inferred removals as if they were observed ones, with
  nothing in the row distinguishing them.

## Consequences

### Positive
- Every `vanished` row corresponds to a real observation of absence from a complete book.
- Order lifetime (appeared→vanished) is a measurable quantity, which is what the vanish-fast rule
  needs.

### Negative
- Real latency on arguably the most valuable signal: a cheap listing disappearing is only noticed
  at the next full poll of that market. This is accepted deliberately — the socket genuinely
  cannot supply removals, so the only lever is hot-tier poll cadence, and cadence is bounded by
  [ADR-0004](0004-rate-limit-discipline-is-a-hard-boundary.md).

### Risks
- If the crossplay setting differs between the socket and the polling client, the diff fabricates
  `vanished` events on ~7% of orders. Prevented structurally by
  [ADR-0002](0002-crossplay-single-global-setting.md).
- A disappearance is evidence of a sale, never proof — the order may have been cancelled or
  edited. See [ADR-0009](0009-detection-on-order-book-events.md).
