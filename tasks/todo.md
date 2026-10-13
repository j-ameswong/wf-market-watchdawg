# Full-scope delivery checklist

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
