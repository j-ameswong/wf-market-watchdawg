# ADR-0002: Crossplay is one global setting across REST and WebSocket

**Date**: 2026-09-09
**Status**: accepted
**Deciders**: project author (spec review)

## Context

The two ingestion channels take opposite upstream defaults for crossplay: the REST `Crossplay`
header defaults to `false` (`docs/v2/api/overview.mdx`), while the WebSocket
`subscribe/newOrders` payload defaults to **`true`** (`docs/v2/websockets/subscriptions.mdx`).
Both channels feed one diff-based classifier, which decides whether an order `appeared` or
`vanished` by comparing a full book against known state.

Accepting either default mixes populations across channels and corrupts that classifier:

1. The socket sees a new `xbox` order → `appeared`, `source=ws`.
2. The next full book poll runs with `Crossplay: false`, so that order is absent from the response.
3. The diff classifies it **`vanished`**.

That is a false `vanished` on ~7% of ingested orders — on exactly the event treated as
evidence-of-sale, and the event order lifetimes are measured from. It produces no error and would
stay invisible until someone asked why non-PC sellers appear to sell instantly.

## Decision

Crossplay is a single global setting, applied identically to the REST `Crossplay` header and the
WebSocket subscribe payload. It is not a per-call option, and no call site may omit it or fall
back to an upstream default.

## Alternatives Considered

### Alternative 1: Per-call crossplay option
- **Pros**: Flexibility to widen or narrow individual sweeps.
- **Cons**: Any call site that forgets it silently poisons the diff.
- **Why not**: There is no use case for two populations in one warehouse, and the failure mode is
  silent data corruption rather than an error.

### Alternative 2: Set it once as a `defaultHeader` on the REST client
- **Pros**: Simple; already how the existing client works.
- **Cons**: A `defaultHeader` can be overridden per call, and it reaches only the REST channel —
  the socket still takes its own default.
- **Why not**: Does not structurally prevent the failure. Enforcement belongs in the transport
  interceptor stack, the same seam that enforces
  [ADR-0004](0004-rate-limit-discipline-is-a-hard-boundary.md).

## Consequences

### Positive
- The classifier sees one population from both channels, so `appeared`/`vanished` mean the same
  thing regardless of source.
- Makes the setting a single readable source that C5's socket subscribe can quote directly.

### Negative
- Changing the setting later reinterprets every subsequent row. For orders this is recoverable
  (`seller_platform` is on every row); for statistics it is not, which is why crossplay is part of
  `item_stat`'s identity ([ADR-0010](0010-item-stat-market-keyed-two-series.md)).

### Risks
- No automated test asserts the two channels agree, because there is no socket until C5. Mitigated
  structurally: the Kotlin defaults are removed so *no* channel can take an upstream default, and
  R5.2 carries the obligation on the socket side.
