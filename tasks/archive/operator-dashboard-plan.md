# Operator dashboard — 2026-10-13

Build and iterate on the author-requested compact dark, responsive frontend, hosted on homelab.
Apply supported changes immediately. ADR-0024 records the new configuration and HTTP boundary.

1. Add a durable versioned configuration API; reuse existing watch validation and rule execution.
   Support item/contract creation, editing, pause, deletion, priorities and filters. Reject invalid
   or conflicting edits without changing active configuration; recover saved state after restart.
2. Add bounded warehouse reads for catalog selection, exact-market statistics, service overview,
   recent signals and baseline readiness. Keep populations and series distinct; never call upstream.
3. Build Watches, Statistics and Settings screens, including usable empty/error/loading states,
   keyboard-accessible forms, charts and mobile layouts. Keep host-only settings visibly read-only.
4. Independently review each feature and fix findings. Run backend and browser checks, inspect
   screenshots, commit features/tests separately and deploy the verified jar to homelab.

## Previous delivery

The author-approved full scope is implemented. Capability plans are archived in
[archive/c5-plan.md](archive/c5-plan.md) and [archive/full-scope-plan.md](archive/full-scope-plan.md).

[Full-scope acceptance](full-scope-acceptance.md) records independent reviews, automated checks,
live delivery/recovery and backup restoration, including the limits of production evidence.
[Operations](../deploy/OPERATIONS.md) covers configuration and maintenance.

Baseline production activation is automatic only after real calibration and continuous shared
budget evidence qualify. No guessed thresholds or synthetic production history are installed.
