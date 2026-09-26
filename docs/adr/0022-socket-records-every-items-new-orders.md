# ADR-0022: The socket records every item's new orders

**Date**: 2026-09-26
**Status**: accepted
**Deciders**: project author, confirming decision 2 of the C5 plan

## Context

The socket's `newOrders` subscription carries every new visible order on the configured platform,
across the whole market. Only a few items are watched and polled. The feed could record just the
watched items' orders, or all of them.

A full book poll is the only source that can change or vanish an order (ADR-0008). So an order the
socket records for an item nobody polls stays live in `wfm_order` until some later book poll of that
item vanishes it, however long ago it sold.

## Decision

The socket records every new order it carries, watched or not, as `appeared` with `source=ws`. An
unpolled item's `wfm_order` rows are history, not current state: they stay live until a book poll
of that item reconciles them, and nothing reads them as current.

## Alternatives Considered

### Alternative 1: Record only watched items' orders
- **Pros**: `wfm_order` stays current for every item it holds; less write volume.
- **Cons**: An `appeared` not recorded when it happens cannot be recovered later: `/recent` holds
  only 500 orders, and books show what is listed now, not what was listed.
- **Why not**: The socket is the only free, market-wide source of new listings (SPEC 2.1), and the
  event log is what later analysis reads.

## Consequences

### Positive
- The event log holds every new listing on the platform from the day the socket runs, at no REST
  cost.

### Negative
- `wfm_order`'s live rows mean current state only for polled items. A query of current state must
  restrict itself to items with an `order_book` row recent enough to trust.
- Every new order is a market lookup and two inserts. T5 measures the volume.

### Risks
- A catalog that lags upstream skips new items' orders until the next catalog refresh.
