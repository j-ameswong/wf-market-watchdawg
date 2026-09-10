# ADR-0009: Move detection runs on order-book events, not the trade series

**Date**: 2026-09-09
**Status**: accepted
**Deciders**: project author (spec review)

## Context

An early reading of the API concluded that completed sales were entirely unobservable, because
`Transaction` is produced only by `POST /v2/order/{id}/close` and is auth-gated to your own
orders. The 2026-09-09 statistics capture showed that was too strong: v1 `statistics_closed`
returns OHLC candles over genuinely closed orders — open/closed/min/max/avg/volume-weighted/median
price plus `volume` — hourly for 48h and daily for 90d, with precomputed `moving_avg` and Donchian
bounds.

So there *is* trade data, but it is aggregated and bucketed. There is still no per-trade tape and
no real-time trade event. This bounds what the alerting half of the service can be built on, and
separately bounds what any analysis on the warehouse may claim.

## Decision

Real-time move detection is defined over **order-book events** — orders appearing, changing price,
and vanishing. The aggregated trade series is used for long-term analysis and as the baseline for
volume-spike rules, never as a realtime trigger. Inferred sales are never presented as fact.

## Alternatives Considered

### Alternative 1: Trigger alerts from the closed-trade series
- **Pros**: Real traded prices, not order-book inference.
- **Cons**: Trade data lags by at least one bucket — an hour at the finest granularity.
- **Why not**: The success criterion is a push arriving "within seconds of a qualifying order
  being posted". An hourly bucket cannot meet it.

### Alternative 2: Treat order disappearance as a completed sale
- **Pros**: Would give a per-order, near-realtime pseudo-tape.
- **Cons**: Cancellations and edits are indistinguishable from sales.
- **Why not**: It would record inference as observation, permanently, with no field recording
  which it was.

## Consequences

### Positive
- Alerting latency is bounded by ingestion, not by upstream aggregation.
- Long-term analysis has real traded prices and volumes available, not merely order-book
  inference — which is what makes the volume-spike baseline rule implementable at all.

### Negative
- The warehouse cannot answer per-trade questions, ever. Any analysis built on it inherits that
  cap, and it is worth confirming the limitation is understood *before* building on it.

### Risks
- `statistics_live.volume` is a count over **open orders**, not trade volume — 2008 vs 1 for the
  same bucket on `frost_prime_set`. Conflating the two series would silently corrupt every volume
  metric in the warehouse. Prevented by
  [ADR-0010](0010-item-stat-market-keyed-two-series.md).
