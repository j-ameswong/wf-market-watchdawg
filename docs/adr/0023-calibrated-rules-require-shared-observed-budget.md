# 0023 — Calibrated rules require shared observed budget evidence

Status: accepted (2026-10-13; product-owner decision within the full-scope delivery agreement)

Historical API statistics supply daily volume, but do not reveal the competing threshold alerts
that the service would have observed. An absent historic signal is not evidence of zero demand.

Learn volume and lifetime thresholds from earlier observations and validate them on later time
batches. Volume compares one completed day's volume with its preceding 14 contiguous days.
Lifetimes pair the latest appearance with a later disappearance in the same recorded crossplay
context, excluding old unknown-context data. Neither is proof of a sale.

Separately record prospective candidates from every rule before deduplication, cooldown, or the
shared daily ceiling. Baseline candidates are recorded while delivery remains disabled. Replay
this evidence on complete UTC days, carrying preceding dedup and cooldown state. Admit baseline
alerts only after the combined replay fits the ceiling. A process restart, changed configuration,
changed calibration threshold, or new selected market restarts coverage; all configured baseline
streams must have sufficient calibration. Tests advance observation times instead of waiting days.

This deliberately favors missing an early baseline alert over claiming unavailable calibration.
Statistics-only bootstrap cannot prove shared-budget acceptance. Production readiness remains
observable separately from statistical readiness. The live ceiling continues to apply regardless
of calibration, because future traffic need not resemble the validation period.
