# Full scope delivery plan — 2026-10-13

Author decisions in SPEC's delivery agreement activate all formerly optional capabilities.
The current C5 plan remains tasks/plan.md until its checks and independent review are complete.
Each feature below is implemented, tested, independently reviewed and corrected before closure.

## C7: statistics warehouse

- Fetch both closed-trade and live-order series, hourly and daily, for watched items on a daily
  schedule, through the existing public limiter. Do not interpolate sparse upstream buckets.
- Persist decimal measures, nullable moving average, upstream id, exact market dimensions and
  crossplay. Natural identity includes section, granularity, bucket, market, side and crossplay.
- Resolve mod_rank as rank only with known-compatible catalog metadata; reject ambiguous charge
  mapping rather than collapse markets. Resolve markets before atomic per-item persistence.
- New statistics fact storage retains history indefinitely; no existing fact schema or retention
  changes. Keep storage policies in the existing repeatable migration and extend reset harness.
- Acceptance: captured payload replay is idempotent; closed/live and both crossplay populations
  coexist; decimals/nulls survive; rank/star/subtype mapping is distinct; failed fetch writes
  nothing; scheduler is disabled by the shared harness; metrics exist from startup.

## C9a: book threshold strategies

- Backward-compatible watches default to underpriced. A keyed registry selects underpriced,
  price-crossing or spread. Conditions inspect online orders in one exact market.
- Crossing supports best sell below a threshold and best buy above it. Initial qualifying book
  can alert; a continuing condition does not repeat. A nonqualifying book rearms it.
- Spread is best bid minus best ask, with absolute platinum and/or percentage-of-ask thresholds.
  Both sides must exist. Initial qualifying book alerts; clear condition rearms it.
- Persist rule state and signal evidence transactionally. Extend outbox with book-scoped evidence
  and notification rendering; retain the shared admission lock, cooldown and global ceiling.
- Acceptance: missing side/offline orders do not signal; dimensions never mix; unchanged book
  does not repeat; clear/reentry rearms; rollback leaves neither state nor signal; restart keeps
  dedup state; existing watches still work.

## C8: configurable contracts and alerts

- Version-gated riven/lich/sister weapon manifests enumerate configured targets. Priorities order
  the sweep; an explicit all-weapons option enables wider history collection. One instance,
  paced through contract-search, no auth or upstream mutations.
- Capture scrubbed live fixtures before committing DTO fields. Store only auction facts and item
  attributes; never owner profiles. Normalize riven attributes. Observe price/top-bid/closure
  changes. Search absence is not proof of closure; verify known missing entries via paced detail
  reads before recording explicit closure, and label disappearance as observed absence only.
- Configurable alerts: new/matching auction under a user price cap, with weapon, positive and
  negative riven attributes, elemental damage/ephemera filters where applicable. Fixed user
  thresholds, no guessed riven valuation. Share the global signal budget and outbox.
- Acceptance: all three item variants replay idempotently; attribute combinations are queryable;
  unchanged/changed/reappearing/closed auctions produce correct events; failed/truncated search
  never manufactures disappearance; selected priority determines sweep order; limits respected;
  rollback removes alerts with facts; notification links identify the auction.

## C9b: calibrated baselines

- Backtest volume-spike candidates against trailing closed-trade statistics for the exact market
  and crossplay, using past observations only. Split calibration and holdout time ranges, record
  data coverage and selected parameters. No interpolation in the stored data.
- Lifetime rules use observed appeared-to-vanished intervals only for polled markets. Treat these
  as observation intervals, never confirmed sales. No alert until enough calibration history.
- Record calibration metadata and refuse uncalibrated/stale/mismatched configurations. Combine
  candidate rates with other rules against the shared ceiling; report suppression explicitly.
- Acceptance: no lookahead; missing/insufficient history disables; replay is deterministic;
  holdout rate fits the shared budget; rollback and dedup hold; synthetic data tests mechanics
  but never serves as evidence for production threshold selection.

## Deployment and acceptance

- One service on homelab, persistent TimescaleDB, external topic credentials, tailnet-bound
  actuator/read access, automatic restart, explicit resource limits and backup/restore scripts.
- Verify local full build and randomized order, migration upgrades, packaged artifact, live
  socket connection/recovery and ntfy publication received by a listener under ten seconds.
- Accelerate time-dependent acceptance with injected clocks and short dev delays; never increase
  real upstream request rates. Exercise midnight, cooldown, retry caps, reconnect and recovery.
- Demonstrate restore into a separate scratch database. Document commands, health metrics,
  configuration and known data limits. Report empirically unavailable calibration separately.
