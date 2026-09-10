# ADR-0001: PC observer context with crossplay enabled

**Date**: 2026-09-09
**Status**: accepted
**Deciders**: project author (spec review)

## Context

warframe.market is segmented by platform, and a `Crossplay` setting widens a platform view to
include crossplay-enabled orders from other platforms. Covering every platform as a market in its
own right multiplies rate budget and storage by five. Measurement on 2026-09-09 established what
crossplay actually costs and returns:

| | `Crossplay: false` | `Crossplay: true` |
| --- | --- | --- |
| `/v2/orders/item/frost_prime_set` | 856 orders, all `pc` | 899 — `pc` 856, `ps4` 29, `xbox` 12, `mobile` 2 |
| Set relationship | — | strict superset; all 856 PC orders byte-identical |
| `/v2/orders/recent` | 431, all `pc` | 432, non-PC share 32/432 = 7.4% |

`user.platform` and `user.crossplay` were present on 1,331 of 1,331 sampled orders, despite
`Order.user` being flagged `optional/contextual` upstream.

## Decision

The platform context is `pc` only, with crossplay enabled. Crossplay-enabled orders from `ps4`,
`xbox` and `mobile` are in scope because a PC operator can trade with them; per-platform non-PC
markets are not.

## Alternatives Considered

### Alternative 1: PC only, crossplay off
- **Pros**: One population, no ambiguity about what an order-book series describes.
- **Cons**: Discards 7.4% of the new-order feed — orders the operator can actually trade against.
- **Why not**: It is the *lossy* option. Crossplay is a strict superset for `/v2/orders/*` and
  every order carries its own `user.platform`, so the PC-only view stays reconstructible forever
  with a `where` clause. Turning it off throws data away that cannot be recovered later.

### Alternative 2: All four platforms as first-class markets
- **Pros**: Complete coverage of the site.
- **Cons**: ~5× rate budget and storage for markets the single operator cannot trade on.
- **Why not**: No capability in the spec needs them. `switch` never appears in crossplay results
  at all — the server forces `crossplay: false` for it — so "all platforms" is not even what the
  setting offers.

## Consequences

### Positive
- Zero rate-budget cost: a header on calls already being made, and a payload field on a socket
  that spends nothing. The §2.1 arithmetic is unchanged.
- +7.4% of the new-order feed, losslessly reversible via `wfm_order.seller_platform`.

### Negative
- Every order-book series in the warehouse describes a **PC+crossplay pool**, not a PC pool.
  A query author who does not know to filter on `seller_platform` will silently get the wider
  population.

### Risks
- v1 `/items/{slug}/statistics` does **not** behave as a superset under the same setting — it
  returns a different population. That divergence is handled separately in
  [ADR-0010](0010-item-stat-market-keyed-two-series.md); do not assume one crossplay story
  across both APIs.
- Mixing the setting across channels fabricates events. See
  [ADR-0002](0002-crossplay-single-global-setting.md).
