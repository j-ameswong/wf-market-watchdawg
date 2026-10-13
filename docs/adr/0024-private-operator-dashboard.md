# ADR-0024: Private operator dashboard and durable runtime configuration

**Date**: 2026-10-13
**Status**: accepted
**Deciders**: project author (frontend request and follow-up answers)

The operator now wants a compact dark dashboard for watches, settings and statistics on homelab.
This supersedes ADR-0011's prohibition on a UI/API and ADR-0013's startup-only configuration.

Serve static browser assets and a bounded `/api/operator` API from the existing Spring application.
Keep the single-instance, single-operator tailnet deployment. No external hosting, CDN, credentials
in the browser, or new upstream traffic from page reads. PostgreSQL remains available for SQL.
The API accepts JSON mutations with same-origin checks and a required custom header; no CORS.
Deployments must bind HTTP to loopback/tailnet, not the public Internet.

YAML bootstraps configuration until the first UI save. A versioned database document then owns item
and contract watches, the daily admission ceiling and manifest-wide contract collection. Keep every
revision for inspection/recovery. Validate catalog dimensions, contract attributes and logical topics
before saving; stale revisions return conflict rather than overwriting another tab. Publish a complete
snapshot only after commit. In-flight observations may finish using their prior selection; subsequent
evaluations and collection rounds read the new configuration. Pending outbox messages remain deliverable.
Collector cadence, observer context and credentials remain host settings in this first iteration.

Statistics always select an exact market, series, side, resolution and crossplay population. Expose
stored facts and their observation time; sparse data is not interpolated or presented as a trade tape.
Use bounded time windows and result counts. Show real loading, empty, error and stale-data states.

Ship browser-native ES modules and CSS in the jar. This keeps the existing Gradle/Nix deployment
without a second production server or frontend dependency pipeline. Browser integration tests exercise
the real UI; Spring tests cover persistence, concurrency, validation and HTTP boundaries.
