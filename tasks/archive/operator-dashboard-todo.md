# Operator dashboard checklist

- [x] Review existing scope, APIs and configuration; resolve appearance/application preferences.
- [x] Durable configuration and runtime application, with tests and independent review.
- [x] Bounded dashboard/statistics API, with tests and independent review.
- [x] Responsive watches, statistics and settings UI, with browser tests and independent review.
- [x] Full build, screenshot inspection, focused commits, homelab deployment and documentation.

## Previous full-scope delivery

- [x] Analyze documentation and resolve scope/feature questions with the author.
- [x] Implement C5, C7, remaining C9a, C8 and evidence-gated C9b.
- [x] Independently review each feature and address findings.
- [x] Complete observability, including durable UTC-day gauges.
- [x] Exercise transaction rollback, concurrent admissions, clock boundaries and recovery.
- [x] Verify actual WebSocket signal receipt through a live ntfy listener below ten seconds.
- [x] Verify automatic live socket reconnect and gap-fill without process restart.
- [x] Verify an isolated Timescale backup restoration.
- [x] Document deployment, configurable watches, data limitations and recovery.

Build/deployment evidence and production readiness limits are in
[full-scope-acceptance.md](full-scope-acceptance.md). Original capability checks are archived.
